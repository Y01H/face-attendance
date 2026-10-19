package cn.weishi.attendance;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PreDestroy;
import java.util.UUID;

import static cn.weishi.attendance.Records.*;

/**
 * 云端考勤数据存储服务。
 * <p>
 * 通过 HikariCP 连接池管理 MySQL 连接，提供员工人脸模板的拉取、注册、停用
 * 以及考勤事件的上报功能。当配置中未启用云端或 URL 为空时，所有操作将抛出
 * {@link IllegalStateException} 提示用户完成配置。
 */
@Component
public class CloudStore {

    /** HikariCP 数据源，负责底层连接池管理；未配置时为 null */
    private final HikariDataSource source;

    /** Spring JDBC 模板，用于执行 SQL 查询和更新 */
    private final JdbcTemplate jdbc;

    /** 只读事务模板，使用 REPEATABLE_READ 隔离级别，适用于拉取全量快照 */
    private final TransactionTemplate readTx;

    /** 读写事务模板，默认隔离级别，适用于注册和停用操作 */
    private final TransactionTemplate writeTx;

    /**
     * 构造方法：根据应用配置初始化云端数据库连接。
     * <p>
     * 若 {@code cloud.enabled} 为 false 或 {@code cloud.url} 为空，则跳过初始化，
     * 后续调用业务方法时会抛出异常。
     *
     * @param config 应用配置，提供数据库连接参数
     */
    public CloudStore(AppConfig config) {
        // 判断云端存储是否启用：需同时满足 enabled=true 且 url 非空
        if (!config.flag("cloud.enabled", true) || config.text("cloud.url", "").isEmpty()) {
            source = null;
            jdbc = null;
            readTx = null;
            writeTx = null;
            return;
        }

        // ---------- 配置 HikariCP 连接池 ----------
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(config.required("cloud.url"));           // MySQL JDBC 连接地址
        hc.setUsername(config.required("cloud.username"));     // 数据库用户名
        hc.setPassword(config.required("cloud.password"));     // 数据库密码
        hc.setMaximumPoolSize(3);                              // 最大连接数，考勤设备并发量小，3 足够
        hc.setMinimumIdle(0);                                  // 空闲连接为 0，节省云端资源
        hc.setConnectionTimeout(4000);                         // 获取连接超时 4 秒
        hc.setValidationTimeout(2000);                         // 连接有效性校验超时 2 秒
        hc.setInitializationFailTimeout(-1);                   // 启动时不因连接失败而抛异常，允许延迟初始化
        hc.setPoolName("CloudMysql");                          // 连接池名称，便于监控和日志排查

        source = new HikariDataSource(hc);

        // ---------- 配置 JdbcTemplate ----------
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(5);                               // 单条 SQL 查询超时 5 秒，防止慢查询阻塞

        // ---------- 配置事务模板 ----------
        DataSourceTransactionManager manager = new DataSourceTransactionManager(source);

        // 只读事务：用于 fetch() 拉取全量快照，保证一致性读
        readTx = new TransactionTemplate(manager);
        readTx.setReadOnly(true);
        readTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

        // 读写事务：用于 enroll() 和 disable()，使用默认隔离级别（通常 READ_COMMITTED）
        writeTx = new TransactionTemplate(manager);
    }

    /**
     * 判断云端存储是否已正确配置并可用。
     *
     * @return true 表示数据库连接已初始化；false 表示未启用或配置缺失
     */
    public boolean configured() {
        return jdbc != null;
    }

    /**
     * 校验数据库连接是否可用，不可用则抛出带操作指引的异常。
     */
    private void requireConnection() {
        if (jdbc == null)
            throw new IllegalStateException(
                    "云端数据库尚未配置，设置 CLOUD_DB_URL/USER/PASSWORD 并执行 sql/cloud-mysql.sql");
    }

    /**
     * 从云端拉取全量考勤快照（员工列表 + 有效人脸模板）。
     * <p>
     * 在只读事务中执行，保证员工表和模板表的数据一致性。
     * 仅返回 active=1 的员工对应的模板。
     *
     * @return 包含员工、模板及拉取时间戳的快照对象
     */
    public Snapshot fetch() {
        requireConnection();
        return readTx.execute(status -> {
            Snapshot result = new Snapshot();

            // 查询所有员工记录（包含已停用的，用于本地同步）
            result.employees = jdbc.query(
                    "SELECT employee_id,employee_name,active FROM fa_employee",
                    (r, n) -> new Employee(r.getString(1), r.getString(2), r.getBoolean(3)));

            // 查询有效员工的人脸模板（JOIN 过滤仅取 active=1 的）
            result.templates = jdbc.query(
                    "SELECT t.template_id,t.employee_id,t.model_version,t.feature " +
                    "FROM fa_face_template t JOIN fa_employee e ON t.employee_id=e.employee_id WHERE e.active=1",
                    (r, n) -> {
                        Template t = new Template();
                        t.templateId = r.getString(1);
                        t.employeeId = r.getString(2);
                        t.modelVersion = r.getString(3);
                        t.encryptedFeature = r.getBytes(4);   // 加密后的人脸特征向量
                        return t;
                    });

            result.fetchedAt = System.currentTimeMillis();     // 记录本次拉取时间，用于增量同步判断
            return result;
        });
    }

    /**
     * 注册/更新员工并添加一个人脸模板。
     * <p>
     * 若员工已存在则更新姓名并重新激活；每人最多保留 5 个模板。
     * 整个操作在单个写事务中完成，保证原子性。
     *
     * @param id      员工唯一标识
     * @param name    员工姓名
     * @param feature 加密后的人脸特征向量
     * @throws IllegalArgumentException 当该员工已有 5 个模板时抛出
     */
    public void enroll(String id, String name, byte[] feature) {
        requireConnection();
        writeTx.execute(status -> {
            // 插入或更新员工记录；若存在则更新姓名并将 active 重置为 1（重新激活）
            jdbc.update(
                    "INSERT INTO fa_employee(employee_id,employee_name,active) VALUES(?,?,1) " +
                    "ON DUPLICATE KEY UPDATE employee_name=VALUES(employee_name),active=1",
                    id, name);

            // 检查当前模板数量，限制每人最多 5 个模板以控制存储和匹配开销
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM fa_face_template WHERE employee_id=?", Integer.class, id);
            if (count != null && count >= 5) {
                throw new IllegalArgumentException("每人最多保存 5 个模板，请先删除旧模板");
            }

            // 插入新的人脸模板，使用 UUID 作为模板主键
            jdbc.update(
                    "INSERT INTO fa_face_template(template_id,employee_id,model_version,feature) VALUES(?,?,?,?)",
                    UUID.randomUUID().toString(), id, FeatureCodec.MODEL_VERSION, feature);
            return null;
        });
    }

    /**
     * 停用员工：将 active 标记为 0 并删除其所有人脸模板。
     * <p>
     * 保留员工记录（软删除）以便审计，同时清理敏感的人脸特征数据。
     *
     * @param id 员工唯一标识
     */
    public void disable(String id) {
        requireConnection();
        writeTx.execute(status -> {
            // 软删除：标记为未激活，fetch() 时不再返回其模板
            jdbc.update("UPDATE fa_employee SET active=0 WHERE employee_id=?", id);
            // 物理删除人脸模板，释放存储空间并消除隐私风险
            jdbc.update("DELETE FROM fa_face_template WHERE employee_id=?", id);
            return null;
        });
    }

    /**
     * 上报一条考勤事件到云端。
     * <p>
     * 使用 INSERT ... ON DUPLICATE KEY UPDATE 实现幂等写入，
     * 网络重试不会产生重复记录。此方法不开启显式事务（单条 SQL 本身具有原子性）。
     *
     * @param e 考勤事件对象，包含识别结果及打卡信息
     */
    public void upload(Event e) {
        requireConnection();
        // 幂等插入：若 event_id 已存在则忽略，防止重复上报产生脏数据
        jdbc.update(
                "INSERT INTO fa_attendance_event(event_id,employee_id,employee_name,device_id,occurred_at,punch_type,similarity) " +
                "VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE event_id=VALUES(event_id)",
                e.eventId, e.employeeId, e.employeeName, e.deviceId,
                e.occurredAt, e.punchType, e.similarity);
    }

    /**
     * 应用关闭时释放连接池资源。
     * <p>
     * 由 Spring 容器在 Bean 销毁前自动调用。
     */
    @PreDestroy
    public void close() {
        if (source != null) {
            source.close();    // 关闭 HikariCP 连接池，释放所有物理连接
        }
    }
}

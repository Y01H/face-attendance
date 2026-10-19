package cn.weishi.attendance;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static cn.weishi.attendance.Records.*;

/**
 * 本地数据存储层，基于 H2 嵌入式文件数据库。
 * 负责持久化考勤事件、员工缓存、人脸模板及同步状态，
 * 确保设备离线时数据不丢失，恢复网络后可补传云端/第三方。
 */
@Component
public class LocalStore {

    /** HikariCP 数据源，管理 H2 连接池 */
    private final HikariDataSource source;

    /** Spring JDBC 模板，用于执行 SQL 语句 */
    final JdbcTemplate jdbc;

    /** 编程式事务模板，保证多表操作的原子性 */
    private final TransactionTemplate tx;

    /** 应用配置，提供路径、密码、超时等参数 */
    private final AppConfig config;

    /** attendance_event 表的结果集映射器，将行数据转换为 Event 对象 */
    private final RowMapper<Event> eventMapper = (r, n) -> {
        Event e = new Event();
        e.eventId = r.getString("event_id");
        e.employeeId = r.getString("employee_id");
        e.employeeName = r.getString("employee_name");
        e.deviceId = r.getString("device_id");
        e.occurredAt = r.getString("occurred_at");
        e.punchType = r.getString("punch_type");
        e.similarity = r.getDouble("similarity");
        e.cloudStatus = r.getString("cloud_status");
        e.remoteStatus = r.getString("remote_status");
        e.retryCount = r.getInt("retry_count");
        e.lastError = r.getString("last_error");
        e.nextAttempt = r.getLong("next_attempt");
        return e;
    };

    /**
     * 构造 LocalStore：初始化连接池并建表。
     *
     * @param config 应用配置，包含数据目录路径与本地数据库密码
     * @throws Exception 数据库初始化失败时抛出
     */
    public LocalStore(AppConfig config) throws Exception {
        this.config = config;

        // 解析并创建本地数据目录
        String directory = Paths.get(config.text("data-dir", "./data")).toAbsolutePath().normalize().toString();
        Files.createDirectories(Paths.get(directory));

        // 配置 HikariCP 连接池
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:h2:file:" + directory.replace('\\', '/') + "/terminal;DB_CLOSE_ON_EXIT=FALSE");
        hc.setUsername("terminal");
        hc.setPassword(config.required("local-password"));
        hc.setMaximumPoolSize(4);
        hc.setPoolName("LocalJournal");

        source = new HikariDataSource(hc);
        jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));

        // 建表：员工缓存表
        jdbc.execute("CREATE TABLE IF NOT EXISTS employee_cache(employee_id VARCHAR(64) PRIMARY KEY, employee_name VARCHAR(100), active BOOLEAN NOT NULL)");
        // 建表：人脸模板缓存表（存储加密特征向量）
        jdbc.execute("CREATE TABLE IF NOT EXISTS template_cache(template_id VARCHAR(36) PRIMARY KEY, employee_id VARCHAR(64), model_version VARCHAR(64), feature BLOB NOT NULL)");
        // 建表：缓存元数据表（记录最近一次同步时间）
        jdbc.execute("CREATE TABLE IF NOT EXISTS cache_meta(id INT PRIMARY KEY, fetched_at BIGINT NOT NULL)");
        // 建表：考勤事件主表，记录每次打卡的完整信息及同步状态
        jdbc.execute("CREATE TABLE IF NOT EXISTS attendance_event(event_id VARCHAR(36) PRIMARY KEY,employee_id VARCHAR(64),employee_name VARCHAR(100),device_id VARCHAR(64),occurred_at VARCHAR(40),epoch_ms BIGINT,punch_type VARCHAR(20),similarity DOUBLE,cloud_status VARCHAR(20),remote_status VARCHAR(20),retry_count INT DEFAULT 0,last_error VARCHAR(500),next_attempt BIGINT DEFAULT 0)");
        // 建索引：按员工+时间查询加速
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_employee_time ON attendance_event(employee_id,epoch_ms)");
    }

    /**
     * 从本地数据库加载完整的快照（员工列表 + 人脸模板 + 同步时间戳）。
     * 在事务中执行，保证读取一致性。
     *
     * @return 当前缓存的完整快照
     */
    public Snapshot loadSnapshot() {
        return tx.execute(status -> {
            Snapshot snapshot = new Snapshot();
            // 读取员工缓存
            snapshot.employees = jdbc.query("SELECT * FROM employee_cache", (r, n) -> new Employee(r.getString(1), r.getString(2), r.getBoolean(3)));
            // 读取人脸模板缓存（含加密特征）
            snapshot.templates = jdbc.query("SELECT * FROM template_cache", (r, n) -> {
                Template t = new Template();
                t.templateId = r.getString(1);
                t.employeeId = r.getString(2);
                t.modelVersion = r.getString(3);
                t.encryptedFeature = r.getBytes(4);
                return t;
            });
            // 读取最近一次同步时间
            List<Long> times = jdbc.query("SELECT fetched_at FROM cache_meta WHERE id=1", (r, n) -> r.getLong(1));
            snapshot.fetchedAt = times.isEmpty() ? 0 : times.get(0);
            return snapshot;
        });
    }

    /**
     * 将完整快照全量写回本地数据库（先删后插）。
     * 在事务中执行，保证原子性，避免中途失败导致数据不一致。
     *
     * @param snapshot 待持久化的快照数据
     */
    public void saveSnapshot(Snapshot snapshot) {
        tx.execute(status -> {
            jdbc.update("DELETE FROM template_cache");
            jdbc.update("DELETE FROM employee_cache");
            for (Employee e : snapshot.employees)
                jdbc.update("INSERT INTO employee_cache VALUES(?,?,?)", e.employeeId, e.name, e.active);
            for (Template t : snapshot.templates)
                jdbc.update("INSERT INTO template_cache VALUES(?,?,?,?)", t.templateId, t.employeeId, t.modelVersion, t.encryptedFeature);
            // 使用 MERGE 更新缓存元数据（不存在则插入）
            jdbc.update("MERGE INTO cache_meta KEY(id) VALUES(1,?)", snapshot.fetchedAt);
            return null;
        });
    }

    /**
     * 记录一次考勤打卡事件。包含去重逻辑：同一员工在配置的时间窗口内不重复记录。
     *
     * @param employeeId 员工 ID
     * @param name       员工姓名
     * @param similarity 人脸匹配相似度
     * @return 新生成的 Event 对象；若为重复打卡则返回 null
     */
    public synchronized Event record(String employeeId, String name, double similarity) {
        long now = System.currentTimeMillis();
        return tx.execute(status -> {
            // 去重检查：同一员工在 dedup-seconds 秒窗口内是否已有记录
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM attendance_event WHERE employee_id=? AND epoch_ms>?",
                    Integer.class, employeeId, now - config.integer("face.dedup-seconds", 60) * 1000L);
            if (count != null && count > 0) return null; // 重复打卡，忽略

            // 构造新的考勤事件
            Event e = new Event();
            e.eventId = java.util.UUID.randomUUID().toString();
            e.employeeId = employeeId;
            e.employeeName = name;
            e.deviceId = config.text("device-id", "terminal-001");
            e.punchType = config.text("punch-type", "PUNCH");
            e.occurredAt = Instant.ofEpochMilli(now).toString();
            e.similarity = similarity;
            e.cloudStatus = "PENDING";   // 待上传至云端
            e.remoteStatus = "PENDING";  // 待同步至第三方系统

            // 插入事件记录
            jdbc.update("INSERT INTO attendance_event(event_id,employee_id,employee_name,device_id,occurred_at,epoch_ms,punch_type,similarity,cloud_status,remote_status) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    e.eventId, e.employeeId, e.employeeName, e.deviceId, e.occurredAt, now, e.punchType, e.similarity, e.cloudStatus, e.remoteStatus);
            return e;
        });
    }

    /**
     * 查询最近 100 条考勤事件（按时间倒序），用于 UI 展示。
     */
    public List<Event> recent() {
        return jdbc.query("SELECT * FROM attendance_event ORDER BY epoch_ms DESC LIMIT 100", eventMapper);
    }

    /**
     * 获取待上传云端的考勤事件（最多 30 条），按时间正序优先处理最早记录。
     */
    public List<Event> cloudPending() {
        return jdbc.query("SELECT * FROM attendance_event WHERE cloud_status='PENDING' ORDER BY epoch_ms LIMIT 30", eventMapper);
    }

    /**
     * 获取待同步至第三方系统的事件（需满足退避时间条件），最多 10 条。
     */
    public List<Event> remotePending() {
        return jdbc.query("SELECT * FROM attendance_event WHERE remote_status='PENDING' AND next_attempt<=? ORDER BY epoch_ms LIMIT 10", eventMapper, System.currentTimeMillis());
    }

    /**
     * 标记事件已成功上传至云端。
     *
     * @param id 事件 ID
     */
    public void cloudSent(String id) {
        jdbc.update("UPDATE attendance_event SET cloud_status='SYNCED' WHERE event_id=?", id);
    }

    /**
     * 标记事件已成功同步至第三方系统，并清除历史错误信息。
     *
     * @param id 事件 ID
     */
    public void remoteSent(String id) {
        jdbc.update("UPDATE attendance_event SET remote_status='SYNCED',last_error=NULL WHERE event_id=?", id);
    }

    /**
     * 记录第三方同步失败，递增重试次数并计算下次重试时间（指数退避）。
     * 若为永久错误或超过最大重试次数，则标记为 FAILED 不再重试。
     *
     * @param event     失败的事件
     * @param error     错误描述
     * @param permanent 是否为不可恢复的永久错误
     */
    public void remoteFailed(Event event, String error, boolean permanent) {
        int retry = event.retryCount + 1;
        // 判断最终状态：永久错误或超出重试上限则标记 FAILED
        String state = permanent || retry >= config.integer("third-party.max-retries", 12) ? "FAILED" : "PENDING";
        // 指数退避：5s * 2^(retry-1)，上限 1 小时
        long next = System.currentTimeMillis() + Math.min(3600L, 5L * (1L << Math.min(10, retry - 1))) * 1000L;
        jdbc.update("UPDATE attendance_event SET remote_status=?,retry_count=?,last_error=?,next_attempt=? WHERE event_id=?",
                state, retry, error.substring(0, Math.min(error.length(), 500)), next, event.eventId);
    }

    /**
     * 手动重试已失败的事件：重置为 PENDING 状态，清除重试计数和退避时间。
     *
     * @param id 事件 ID
     * @return 受影响的行数（0 表示事件不存在或状态非 FAILED）
     */
    public int retryFailed(String id) {
        return jdbc.update("UPDATE attendance_event SET remote_status='PENDING',retry_count=0,next_attempt=0,last_error=NULL WHERE event_id=? AND remote_status='FAILED'", id);
    }

    /**
     * 统计各类同步状态的事件数量，用于监控面板。
     *
     * @return 包含 total、cloud_pending、remote_pending、remote_failed 的映射
     */
    public Map<String, Object> counts() {
        return jdbc.queryForMap("SELECT COUNT(*) AS total, SUM(CASE WHEN cloud_status='PENDING' THEN 1 ELSE 0 END) AS cloud_pending, SUM(CASE WHEN remote_status='PENDING' THEN 1 ELSE 0 END) AS remote_pending, SUM(CASE WHEN remote_status='FAILED' THEN 1 ELSE 0 END) AS remote_failed FROM attendance_event");
    }

    /**
     * 应用关闭时释放数据源连接池。
     */
    @PreDestroy
    public void close() {
        source.close();
    }
}

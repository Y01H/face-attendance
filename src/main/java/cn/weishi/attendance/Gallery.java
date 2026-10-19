package cn.weishi.attendance;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.*;
import static cn.weishi.attendance.Records.*;

/**
 * 人脸特征图库管理器。
 * 负责本地缓存与云端人脸数据的同步，提供登记、停用、刷新及缓存可用性判断等核心操作。
 * 特征向量在持久化层以加密形式存储，运行时解密到内存中供比对使用。
 */
@Component
public class Gallery {

    /** 本地快照存储（文件/SQLite 等），用于离线容灾 */
    private final LocalStore local;
    /** 云端人脸数据服务，负责拉取全量快照及登记/停用操作 */
    private final CloudStore cloud;
    /** 特征编解码器，处理加密存储与运行时解密 */
    private final FeatureCodec codec;
    /** 应用配置，读取缓存有效期等参数 */
    private final AppConfig config;

    /** 当前内存中的人脸快照（含员工列表与特征模板），volatile 保证多线程可见性 */
    private volatile Snapshot snapshot;
    /** 云端同步状态描述，供前端展示 */
    private volatile String cloudState = "尚未同步";

    /**
     * 构造时注入各组件，并立即从本地加载上次缓存的快照，使服务启动后可离线工作。
     */
    public Gallery(LocalStore local, CloudStore cloud, FeatureCodec codec, AppConfig config) {
        this.local = local;
        this.cloud = cloud;
        this.codec = codec;
        this.config = config;
        snapshot = decode(local.loadSnapshot());
    }

    /**
     * 对快照中的模板逐一校验模型版本并解密特征向量。
     * 若模型版本不匹配则抛出异常，防止使用过期特征进行比对。
     *
     * @param input 待解码的快照对象
     * @return 解码后的同一快照（特征向量已填充到内存字段）
     */
    private Snapshot decode(Snapshot input) {
        for (Template t : input.templates) {
            if (!FeatureCodec.MODEL_VERSION.equals(t.modelVersion)) {
                throw new IllegalStateException("人脸特征模型版本不兼容，需要重新登记");
            }
            t.vector = codec.decrypt(t.encryptedFeature);
        }
        return input;
    }

    /**
     * 从云端拉取最新全量快照，解密后持久化到本地并更新内存缓存。
     * 同步成功后将状态标记为"已连接"。
     */
    public synchronized void refresh() {
        Snapshot fresh = decode(cloud.fetch());
        local.saveSnapshot(fresh);
        snapshot = fresh;
        cloudState = "已连接";
    }

    /**
     * 后台定时任务：按配置间隔（默认 30 秒）自动触发云端同步。
     * 异常时仅更新状态提示，不影响主流程运行。
     */
    @Scheduled(fixedDelayString = "${app.cloud.refresh-ms:30000}", initialDelay = 1000)
    public void backgroundRefresh() {
        try {
            refresh();
        } catch (Exception ex) {
            cloudState = "同步失败：请检查云端连接、建表及特征格式";
        }
    }

    /**
     * 登记新员工人脸特征。
     * 先将加密特征写入云端，再立即刷新本地快照以获取最新数据。
     *
     * @param id     员工编号
     * @param name   员工姓名
     * @param vector 原始浮点特征向量（方法内部会加密后存储）
     */
    public synchronized void enroll(String id, String name, float[] vector) {
        cloud.enroll(id, name, codec.encrypt(vector));
        refresh();
    }

    /**
     * 停用指定员工。
     * 先在云端标记停用，随后本地内存立即过滤该员工的特征模板并标记为 inactive，
     * 确保停用即时生效；最后尝试完整刷新以对齐云端状态，刷新失败不阻断操作。
     *
     * @param id 待停用的员工编号
     */
    public synchronized void disable(String id) {
        cloud.disable(id);

        // 本地立即构建过滤后的快照，无需等待云端同步完成
        Snapshot current = snapshot;
        Snapshot filtered = new Snapshot();
        filtered.fetchedAt = current.fetchedAt;
        for (Employee e : current.employees) {
            filtered.employees.add(new Employee(e.employeeId, e.name, e.employeeId.equals(id) ? false : e.active));
        }
        for (Template t : current.templates) {
            if (!t.employeeId.equals(id)) {
                filtered.templates.add(t);
            }
        }
        local.saveSnapshot(filtered);
        snapshot = filtered;

        // 尝试与云端完整对齐；失败时仅提示，不影响已生效的停用操作
        try {
            refresh();
        } catch (Exception ex) {
            cloudState = "停用已生效，完整缓存刷新待重试";
        }
    }

    /**
     * 判断当前本地缓存是否仍在有效期内，可用于离线识别。
     * 需同时满足：快照已加载（fetchedAt > 0）且未超过配置的 TTL（默认 24 小时）。
     *
     * @return true 表示缓存可用，false 表示需要联网重新同步
     */
    public boolean usable() {
        return snapshot.fetchedAt > 0
                && System.currentTimeMillis() - snapshot.fetchedAt <= config.integer("face.cache-ttl-hours", 24) * 3600000L;
    }

    /**
     * 获取当前内存中的人脸快照引用，供识别服务进行特征比对。
     */
    public Snapshot snapshot() {
        return snapshot;
    }

    /**
     * 汇总当前 Gallery 运行状态，用于管理页面或健康检查接口展示。
     *
     * @return 包含云端配置状态、同步描述、最后同步时间、缓存可用性、员工数及模板数的有序 Map
     */
    public Map<String, Object> state() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configured", cloud.configured());
        result.put("state", cloudState);
        result.put("lastSync", snapshot.fetchedAt);
        result.put("cacheUsable", usable());
        result.put("employees", snapshot.employees.size());
        result.put("templates", snapshot.templates.size());
        return result;
    }
}

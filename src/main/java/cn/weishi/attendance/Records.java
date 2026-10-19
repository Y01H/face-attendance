package cn.weishi.attendance;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.List;

/**
 * 考勤系统数据记录模型，包含员工、人脸模板、快照和打卡事件等核心数据结构。
 * 该类为工具类，不允许实例化。
 */
public final class Records {

    /** 私有构造方法，防止实例化 */
    private Records() {
    }

    /**
     * 员工信息
     */
    public static class Employee {
        /** 员工唯一标识 */
        public String employeeId;
        /** 员工姓名 */
        public String name;
        /** 员工是否处于活跃状态 */
        public boolean active;

        /** 无参构造方法，用于 Jackson 反序列化 */
        public Employee() {
        }

        /**
         * 全参构造方法
         *
         * @param id     员工唯一标识
         * @param name   员工姓名
         * @param active 员工是否处于活跃状态
         */
        public Employee(String id, String name, boolean active) {
            this.employeeId = id;
            this.name = name;
            this.active = active;
        }
    }

    /**
     * 人脸特征模板，用于存储员工的人脸识别数据。
     */
    public static class Template {
        /** 模板唯一标识 */
        public String templateId;
        /** 关联的员工 ID */
        public String employeeId;
        /** 人脸模型版本号，用于兼容性管理 */
        public String modelVersion;
        /** 加密后的人脸特征数据（不参与 JSON 序列化） */
        @JsonIgnore
        public byte[] encryptedFeature;
        /** 人脸特征向量（不参与 JSON 序列化） */
        @JsonIgnore
        public float[] vector;
    }

    /**
     * 数据快照，记录某一时刻从云端拉取的完整员工及模板数据。
     */
    public static class Snapshot {
        /** 快照包含的员工列表 */
        public List<Employee> employees = new ArrayList<>();
        /** 快照包含的人脸模板列表 */
        public List<Template> templates = new ArrayList<>();
        /** 快照拉取时间戳（毫秒） */
        public long fetchedAt;
    }

    /**
     * 打卡事件，记录一次人脸识别打卡的详细信息及同步状态。
     */
    public static class Event {
        /** 事件唯一标识 */
        public String eventId;
        /** 打卡员工 ID */
        public String employeeId;
        /** 打卡员工姓名 */
        public String employeeName;
        /** 产生该事件的设备 ID */
        public String deviceId;
        /** 打卡发生的时间（ISO 8601 格式） */
        public String occurredAt;
        /** 打卡类型（如：上班、下班） */
        public String punchType;
        /** 人脸识别相似度分值 */
        public double similarity;
        /** 云端同步状态 */
        public String cloudStatus;
        /** 远端服务器同步状态 */
        public String remoteStatus;
        /** 已重试次数 */
        public int retryCount;
        /** 最后一次失败的错误信息 */
        public String lastError;
        /** 下次重试的时间戳（毫秒） */
        public long nextAttempt;
    }
}

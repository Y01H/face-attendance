package cn.weishi.attendance;

import org.springframework.stereotype.Component;

/**
 * One immutable encoded frame is shared by all viewers; slow viewers skip old frames.
 * 线程安全的预览帧管理器：所有观看者共享同一帧数据，慢速观看者会自动跳过旧帧，
 * 确保始终看到最新的画面，适用于考勤摄像头的实时预览场景。
 */
@Component
public class PreviewFrames {

    /**
     * 不可变的编码帧对象，一旦创建便不可修改，可安全地在多个线程间共享。
     * 每一帧包含序号（sequence）、采集时间戳（capturedAt）和 JPEG 编码的图像数据。
     */
    public static final class EncodedFrame {
        /** 帧的全局递增序号，用于判断帧的新旧 */
        public final long sequence;
        /** 帧被摄像头采集时的时间戳（毫秒），表示画面生成的时刻 */
        public final long capturedAt;
        /** JPEG 格式的图像字节数据，即编码后的帧内容 */
        public final byte[] jpeg;

        /**
         * 私有构造器，仅允许通过外部 publish 方法创建帧实例，
         * 保证帧的不可变性。
         *
         * @param sequence   帧序号
         * @param capturedAt 采集时间戳
         * @param jpeg       JPEG 图像数据
         */
        private EncodedFrame(long sequence, long capturedAt, byte[] jpeg) {
            this.sequence = sequence;
            this.capturedAt = capturedAt;
            this.jpeg = jpeg;
        }
    }

    /** 当前最新的编码帧，所有观看者共享读取；为 null 表示尚无可用帧 */
    private EncodedFrame latest;
    /** 全局帧序号计数器，每次发布新帧时自增，确保序号单调递增 */
    private long sequence;

    /**
     * 发布一帧新的编码图像。
     * 创建不可变的 EncodedFrame 并更新 latest 指针，然后唤醒所有等待新帧的线程。
     *
     * @param capturedAt 帧的采集时间戳（毫秒）
     * @param jpeg       编码后的 JPEG 图像字节数组
     */
    public synchronized void publish(long capturedAt, byte[] jpeg) {
        latest = new EncodedFrame(++sequence, capturedAt, jpeg);
        // 唤醒所有在 awaitAfter 中阻塞等待的观看者线程
        notifyAll();
    }

    /**
     * 清除当前帧，将所有观看者的状态重置为"无可用帧"。
     * 通常在摄像头断开或系统关闭时调用。
     */
    public synchronized void clear() {
        latest = null;
        // 通知等待线程：帧已被清除，可能需要处理空帧情况
        notifyAll();
    }

    /**
     * 非阻塞地获取当前最新的编码帧。
     *
     * @return 当前的 EncodedFrame，若尚未发布过任何帧则返回 null
     */
    public synchronized EncodedFrame current() {
        return latest;
    }

    /**
     * 阻塞等待序号大于 previous 的新帧出现，支持超时机制。
     * 适用于 SSE / 长轮询等场景：客户端上次收到的帧序号为 previous，
     * 此方法会挂起当前线程直到有新帧发布或超时到达。
     * 慢速客户端会自动跳过中间所有旧帧，只拿到最新的一帧。
     *
     * @param previous  上一次获取到的帧序号（客户端记录的最后序号）
     * @param timeoutMs 最大等待时间（毫秒），<=0 表示不等待
     * @return 最新的 EncodedFrame；若超时仍未获得新帧则返回 null
     * @throws InterruptedException 等待期间线程被中断
     */
    public synchronized EncodedFrame awaitAfter(long previous, long timeoutMs) throws InterruptedException {
        // 计算绝对超时截止时间（纳秒）
        long deadline = System.nanoTime() + Math.max(0, timeoutMs) * 1000000L;

        // 循环等待，直到出现新帧（latest 不为 null 且序号大于 previous）或超时
        while (latest == null || latest.sequence <= previous) {
            // 计算剩余等待时间（纳秒）
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                // 已超时，返回 null 通知调用方无新帧可用
                return null;
            }
            // 在对象监视器上等待，至少等待 1 毫秒，唤醒后重新检查条件
            wait(Math.max(1, remaining / 1000000L));
        }

        // 返回序号大于 previous 的最新帧（慢客户端自动跳过了中间旧帧）
        return latest;
    }
}

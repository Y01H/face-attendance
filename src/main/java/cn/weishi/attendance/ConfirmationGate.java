package cn.weishi.attendance;

/**
 * 确认门：在限定时间窗口内，对重叠检测框上的同一身份进行多次确认，
 * 防止因单帧误识而触发错误的考勤打卡。
 * <p>
 * 使用方式：每帧调用 {@link #accept}，当返回值首次为 {@code true} 时，
 * 表示该身份已被连续多帧确认，可以视为有效识别结果。
 */
public class ConfirmationGate {

    /** 当前正在确认的员工 ID，null 表示尚未开始确认或已重置 */
    private String employeeId;

    /** 同一身份在时间窗口内连续被命中的次数 */
    private int count;

    /** 上一次命中时的人脸检测框 [x, y, w, h]，用于计算 IoU */
    private int[] box;

    /** 上一次命中时的时间戳（毫秒），用于判断是否超出时间窗口 */
    private long lastTime;

    /**
     * 处理一帧识别结果，判断是否达到确认阈值。
     *
     * @param result   当前帧的人脸引擎识别结果
     * @param now      当前时间戳（毫秒）
     * @param required 需要连续确认的最少次数，达到后返回 true
     * @return true 表示该身份已被确认（count >= required），可以触发考勤；
     *         false 表示尚未确认或该帧无效
     */
    public boolean accept(FaceEngine.Result result, long now, int required) {
        // 识别未通过或缺少员工 ID 时，直接重置确认状态
        if (!result.accepted || result.employeeId == null) {
            reset();
            return false;
        }

        // 判断当前帧是否与上一帧属于同一身份、在时间窗口内且检测框重叠足够
        boolean same = result.employeeId.equals(employeeId)
                && now - lastTime < 1500          // 时间窗口：1.5 秒
                && overlap(box, result.box) > 0.25; // IoU 阈值：> 25%

        // 同一身份且满足条件则累加计数，否则重新开始计数
        count = same ? count + 1 : 1;

        // 更新为当前帧的识别信息，供下一帧比较使用
        employeeId = result.employeeId;
        box = result.box.clone();   // 克隆数组，避免外部引用导致数据被篡改
        lastTime = now;

        // 达到确认次数阈值时返回 true，表示身份已确认
        return count >= required;
    }

    /**
     * 重置确认状态，清除所有已累积的计数和历史信息。
     */
    public void reset() {
        employeeId = null;
        count = 0;
        box = null;
        lastTime = 0;
    }

    /**
     * 计算两个矩形框的 IoU（Intersection over Union，交并比）。
     * <p>
     * 框格式：[x, y, width, height]，即左上角坐标 + 宽高。
     *
     * @param a 第一个矩形框，可为 null
     * @param b 第二个矩形框，可为 null
     * @return IoU 值，范围 [0, 1]；任一框为 null 时返回 0
     */
    public static double overlap(int[] a, int[] b) {
        if (a == null || b == null) return 0;

        // 计算两框在 x 轴上的重叠宽度
        int w = Math.max(0, Math.min(a[0] + a[2], b[0] + b[2]) - Math.max(a[0], b[0]));
        // 计算两框在 y 轴上的重叠高度
        int h = Math.max(0, Math.min(a[1] + a[3], b[1] + b[3]) - Math.max(a[1], b[1]));

        // 交集面积
        double inter = w * h;
        // 并集面积 = 各自面积之和 - 交集面积（去重）
        double union = a[2] * a[3] + b[2] * b[3] - inter;

        // IoU = 交集 / 并集，避免除零
        return union > 0 ? inter / union : 0;
    }
}

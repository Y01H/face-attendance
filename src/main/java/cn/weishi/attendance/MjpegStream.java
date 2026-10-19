package cn.weishi.attendance;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * MJPEG 流输出工具类。
 * 将预览帧以 multipart/x-mixed-replace 格式持续写入输出流，
 * 供浏览器端通过 &lt;img&gt; 标签实时查看摄像头预览画面。
 */
public final class MjpegStream {

    /** MJPEG 流的 HTTP Content-Type，定义多部分消息的边界标识符。 */
    public static final String CONTENT_TYPE = "multipart/x-mixed-replace;boundary=frame";

    /** 工具类，禁止实例化。 */
    private MjpegStream() {
    }

    /**
     * 将编码后的预览帧以 MJPEG 格式写入输出流。
     *
     * @param frames     预览帧数据源，提供已编码的 JPEG 帧
     * @param output     目标输出流（通常为 HTTP 响应的输出流）
     * @param durationMs 流的最大持续时间（毫秒），超时后关闭流
     * @throws IOException 写入输出流时发生的 I/O 异常
     */
    public static void write(PreviewFrames frames, OutputStream output, long durationMs) throws IOException {
        // 计算绝对截止时间（纳秒精度），用于控制流的最大持续时长
        long deadline = System.nanoTime() + durationMs * 1000000L;

        // 记录上一帧的序列号，用于请求下一帧时避免重复获取
        long previous = -1;

        try {
            // 持续读取帧直到超时或线程被中断
            while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
                // 等待获取序列号大于 previous 的下一帧，最长等待 1000ms
                PreviewFrames.EncodedFrame frame = frames.awaitAfter(previous, 1000);

                // 帧不可用或已过期（超过 1500ms 的帧视为陈旧数据），终止流
                if (frame == null || System.currentTimeMillis() - frame.capturedAt > 1500) break;

                previous = frame.sequence;

                // 构造 MIME 多部分帧头：包含边界标识、内容类型、长度及自定义元数据
                String header = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + frame.jpeg.length + "\r\nX-Frame-Sequence: " + frame.sequence + "\r\nX-Captured-At: " + frame.capturedAt + "\r\n\r\n";
                output.write(header.getBytes(StandardCharsets.US_ASCII));

                // 写入 JPEG 图像数据
                output.write(frame.jpeg);

                // 写入帧间分隔符 \r\n（CRLF）
                output.write(new byte[]{13, 10});
                output.flush();
            }

            // 写入多部分消息的终止边界，告知客户端流已结束
            output.write("--frame--\r\n".getBytes(StandardCharsets.US_ASCII));
            output.flush();
        } catch (InterruptedException ex) {
            // 恢复中断标志，让调用方感知线程被中断
            Thread.currentThread().interrupt();
        } catch (IOException ex) {
            // 浏览器关闭标签页或断开连接时写入异常属于正常情况，静默忽略
        }
    }
}

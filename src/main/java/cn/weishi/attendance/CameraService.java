package cn.weishi.attendance;

import org.opencv.core.Mat;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.Videoio;
import org.opencv.videoio.VideoWriter;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * USB 摄像头服务：负责视频帧的采集、缓冲与 JPEG 编码预览。
 * <p>
 * 采用双线程架构：
 * <ul>
 *   <li>采集线程（captureLoop）：持续从摄像头读取原始帧，维护最新帧引用；</li>
 *   <li>编码线程（encodeLoop）：按指定帧率对最新帧进行 JPEG 压缩并推送给前端预览。</li>
 * </ul>
 * 两线程通过 {@code lock} 对象实现线程安全的帧交换，避免在高频采集下阻塞。
 */
@Component
public class CameraService {

    /** 应用配置（摄像头索引、分辨率、帧率等） */
    private final AppConfig config;
    /** 编码后的预览帧存储，供 HTTP 端点读取 */
    private final PreviewFrames previewFrames;
    /** 保护 {@code latest}、{@code capturedAt}、{@code rawSequence} 的同步锁 */
    private final Object lock = new Object();
    /** 最新一帧原始图像（BGR 格式），由采集线程写入 */
    private Mat latest;
    /** 最新帧的采集时间戳（毫秒） */
    private volatile long capturedAt;
    /** 服务是否正在运行，用于控制两个线程的退出 */
    private volatile boolean running;
    /** 当前摄像头连接状态描述 */
    private volatile String state = "未启动";
    /** 原始帧自增序列号，用于判断预览线程是否有新帧可处理 */
    private long rawSequence;
    /** 采集实际帧率（帧/秒） */
    private volatile double captureFps;
    /** 预览编码帧率（帧/秒） */
    private volatile double previewFps;
    /** 最近一次 JPEG 编码耗时（毫秒） */
    private volatile double encodingMs;
    /** 驱动上报的实际帧率 */
    private volatile double driverFps;
    /** 视频采集线程 */
    private Thread thread;
    /** 预览编码线程 */
    private Thread encoderThread;

    public CameraService(AppConfig config, FaceEngine engine, PreviewFrames previewFrames) {
        this.config = config;
        this.previewFrames = previewFrames;
    }

    /**
     * Spring Boot 应用就绪后自动启动摄像头采集。
     * 若配置中 camera.enabled=false，则跳过初始化。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!config.flag("camera.enabled", true)) {
            state = "摄像头已禁用";
            return;
        }
        running = true;
        thread = new Thread(this::captureLoop, "usb-capture");
        encoderThread = new Thread(this::encodeLoop, "preview-encode");
        thread.setDaemon(true);
        encoderThread.setDaemon(true);
        thread.start();
        encoderThread.start();
    }

    /**
     * 根据配置项 camera.backend 选择 OpenCV 视频采集后端。
     * Windows 默认使用 DirectShow，Linux 默认使用 V4L2。
     */
    private int backend() {
        String type = config.text("camera.backend", "auto");
        if ("dshow".equalsIgnoreCase(type)) return Videoio.CAP_DSHOW;
        if ("msmf".equalsIgnoreCase(type)) return Videoio.CAP_MSMF;
        if ("v4l2".equalsIgnoreCase(type)) return Videoio.CAP_V4L2;
        if ("any".equalsIgnoreCase(type)) return Videoio.CAP_ANY;
        return System.getProperty("os.name").toLowerCase().contains("win") ? Videoio.CAP_DSHOW : Videoio.CAP_V4L2;
    }

    /**
     * 采集线程主循环：打开摄像头 → 持续读帧 → 帧交换 → 异常时释放并重连。
     * 每帧通过双缓冲（swap）方式存入 {@code latest}，使编码线程无需等待下一次 read。
     */
    private void captureLoop() {
        while (running) {
            VideoCapture camera = new VideoCapture();
            Mat frame = new Mat();
            long rateStart = System.nanoTime(); // 帧率统计起始时间
            int count = 0;                      // 当前统计周期内的帧计数
            try {
                state = "正在连接";
                // 按配置的索引和后端类型打开摄像头
                if (!camera.open(config.integer("camera.index", 0), backend()))
                    throw new IllegalStateException("打开失败");

                // 设置像素编码格式（如 MJPG），减少驱动端解码开销
                String fourcc = config.text("camera.fourcc", "MJPG");
                if (fourcc.length() == 4)
                    camera.set(Videoio.CAP_PROP_FOURCC, VideoWriter.fourcc(fourcc.charAt(0), fourcc.charAt(1), fourcc.charAt(2), fourcc.charAt(3)));

                // 设置目标分辨率、帧率；缓冲区大小设为 1 以降低延迟
                camera.set(Videoio.CAP_PROP_FRAME_WIDTH, config.integer("camera.width", 640));
                camera.set(Videoio.CAP_PROP_FRAME_HEIGHT, config.integer("camera.height", 480));
                camera.set(Videoio.CAP_PROP_FPS, config.integer("camera.fps", 30));
                camera.set(Videoio.CAP_PROP_BUFFERSIZE, 1);
                driverFps = camera.get(Videoio.CAP_PROP_FPS);

                // 内部读帧循环
                while (running) {
                    if (!camera.read(frame) || frame.empty()) throw new IllegalStateException("读帧失败");

                    // 双缓冲交换：当前读到的帧置入 latest，旧 latest 复用为下次读取目标，
                    // 编码线程在各自锁区间内 clone，避免频繁分配 Mat。
                    synchronized (lock) {
                        Mat previous = latest;
                        latest = frame;
                        frame = previous == null ? new Mat() : previous;
                        capturedAt = System.currentTimeMillis();
                        rawSequence++;
                    }

                    state = "已连接";
                    count++;
                    // 每 1 秒更新一次实际采集帧率
                    long now = System.nanoTime();
                    if (now - rateStart >= 1000000000L) {
                        captureFps = count * 1000000000.0 / (now - rateStart);
                        count = 0;
                        rateStart = now;
                    }
                }
            } catch (Exception ex) {
                // 采集异常（设备拔出、权限不足等）：清理资源并提示重试
                state = "摄像头不可用，3 秒后重试；检查索引、权限及占用";
                clear();
            } finally {
                camera.release();
                frame.release();
            }
            // 异常后等待 3 秒再尝试重新连接
            if (running) try {
                Thread.sleep(3000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // 线程正常退出时清理所有资源
        clear();
    }

    /**
     * 编码线程主循环：以固定帧率从 {@code latest} 取快照 → JPEG 编码 → 推送到 PreviewFrames。
     * 仅在序列号变化（即有新帧）时才执行编码，避免重复处理同一帧。
     */
    private void encodeLoop() {
        long lastSequence = -1;               // 上次处理的帧序列号
        long rateStart = System.nanoTime();   // 帧率统计起始时间
        int count = 0;                        // 当前统计周期内的编码帧计数
        // 根据配置的目标预览帧率计算每次循环的最小间隔（纳秒）
        long period = 1000000000L / Math.max(1, Math.min(60, config.integer("camera.preview-fps", 25)));

        while (running && !Thread.currentThread().isInterrupted()) {
            long started = System.nanoTime();
            try (Frame frame = snapshot()) {
                // 仅当有新帧时才编码，避免 CPU 浪费
                if (frame.sequence != lastSequence) {
                    lastSequence = frame.sequence;
                    byte[] jpeg = FaceEngine.jpeg(frame.image, config.integer("camera.jpeg-quality", 75));
                    encodingMs = (System.nanoTime() - started) / 1000000.0;
                    // 校验帧新鲜度：超过 1.5 秒的帧视为过期，不推送
                    if (System.currentTimeMillis() - frame.at <= 1500 && capturedAt > 0) {
                        previewFrames.publish(frame.at, jpeg);
                        count++;
                    }
                }
            } catch (Exception ex) {
                // snapshot 失败（摄像头刚断开）：清除旧预览，等待下一轮
                previewFrames.clear();
            }

            // 每 1 秒更新预览编码帧率
            long now = System.nanoTime();
            if (now - rateStart >= 1000000000L) {
                previewFps = count * 1000000000.0 / (now - rateStart);
                count = 0;
                rateStart = now;
            }

            // 补偿编码耗时，使整体循环周期稳定在目标帧率
            long wait = period - (System.nanoTime() - started);
            if (wait > 0) try {
                Thread.sleep(wait / 1000000L, (int) (wait % 1000000L));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * 释放当前帧引用并重置所有统计指标。
     * 在摄像头断开或线程退出时调用。
     */
    private void clear() {
        synchronized (lock) {
            if (latest != null) {
                latest.release();
                latest = null;
            }
            capturedAt = 0;
            captureFps = 0;
            previewFps = 0;
        }
        previewFrames.clear();
    }

    /**
     * 不可变帧快照：持有对 Mat 的独立引用，调用 close() 释放。
     * 实现 AutoCloseable 以支持 try-with-resources 语法。
     */
    public static class Frame implements AutoCloseable {
        /** 帧图像数据（BGR 三通道 Mat） */
        public final Mat image;
        /** 该帧的采集时间戳（毫秒） */
        public final long at;
        /** 该帧对应的序列号 */
        public final long sequence;

        Frame(Mat image, long at, long sequence) {
            this.image = image;
            this.at = at;
            this.sequence = sequence;
        }

        @Override
        public void close() {
            image.release();
        }
    }

    /**
     * 线程安全地获取当前最新帧的独立副本。
     * 若摄像头无画面或帧超过 1.5 秒未更新，抛出 IllegalStateException。
     *
     * @return 调用方负责关闭的 Frame 快照
     */
    public Frame snapshot() {
        synchronized (lock) {
            if (latest == null || System.currentTimeMillis() - capturedAt > 1500)
                throw new IllegalStateException("摄像头没有新画面");
            return new Frame(latest.clone(), capturedAt, rawSequence);
        }
    }

    /**
     * 获取当前编码好的 JPEG 预览字节流（供 HTTP 端点返回）。
     * 若预览已过期（>1.5 秒），抛出异常。
     *
     * @return JPEG 编码后的字节数组
     */
    public byte[] preview() {
        PreviewFrames.EncodedFrame frame = previewFrames.current();
        if (frame == null || System.currentTimeMillis() - frame.capturedAt > 1500)
            throw new IllegalStateException("摄像头没有新画面");
        return frame.jpeg;
    }

    /**
     * 返回摄像头服务的实时运行状态，用于监控/调试接口。
     */
    public Map<String, Object> state() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", state);
        result.put("lastFrame", capturedAt);
        result.put("index", config.integer("camera.index", 0));
        result.put("captureFps", captureFps);
        result.put("previewFps", previewFps);
        result.put("encodingMs", encodingMs);
        result.put("driverFps", driverFps);
        result.put("requestedFps", config.integer("camera.fps", 30));
        // 在锁保护下读取当前帧尺寸
        synchronized (lock) {
            result.put("width", latest == null ? 640 : latest.cols());
            result.put("height", latest == null ? 480 : latest.rows());
        }
        return result;
    }

    /**
     * 应用关闭时停止所有线程并释放预览资源。
     * 由 Spring 容器在销毁 Bean 前调用。
     */
    @PreDestroy
    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
        if (encoderThread != null) encoderThread.interrupt();
        previewFrames.clear();
    }
}

package cn.weishi.attendance;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

import static cn.weishi.attendance.Records.*;

/**
 * 人脸识别考勤核心服务。
 * 以固定间隔调度摄像头抓拍与人脸比对，识别通过后记录打卡事件，
 * 并对外提供当前识别状态快照供前端轮询。
 */
@Component
public class RecognitionService {

    /** 摄像头服务，负责抓拍视频帧 */
    private final CameraService camera;
    /** 人脸引擎，执行检测与 1:N 比对 */
    private final FaceEngine engine;
    /** 人脸特征库，缓存云端同步的员工人脸模板 */
    private final Gallery gallery;
    /** 本地持久化，用于写入打卡记录 */
    private final LocalStore local;
    /** 应用配置，提供阈值、模式等运行时参数 */
    private final AppConfig config;
    /** 确认门，要求连续多帧识别到同一人后才触发打卡，防止误识别 */
    private final ConfirmationGate gate = new ConfirmationGate();

    /** 服务总开关，false 时停止识别与打卡 */
    private volatile boolean enabled = true;
    /** 最近一次识别结果，供前端展示 */
    private volatile FaceEngine.Result last = new FaceEngine.Result();
    /** 当前状态提示消息 */
    private volatile String message = "等待摄像头";
    /** 上一次处理的帧时间戳，用于跳帧去重 */
    private volatile long lastFrame;
    /** 最近一次成功记录的打卡事件 */
    private volatile Event lastEvent;
    /** 上一次推理耗时（毫秒），用于性能监控 */
    private volatile double inferenceMs;

    /**
     * 通过构造器注入各依赖组件。
     */
    public RecognitionService(CameraService camera, FaceEngine engine, Gallery gallery, LocalStore local, AppConfig config) {
        this.camera = camera;
        this.engine = engine;
        this.gallery = gallery;
        this.local = local;
        this.config = config;
    }

    /**
     * 定时调度入口，默认每 350 ms 执行一次（可通过 app.face.interval-ms 配置）。
     * <p>
     * 处理流程：
     * <ol>
     *   <li>若服务已暂停，重置确认门并返回暂停提示；</li>
     *   <li>若人脸库不可用（未同步/过期），提示连接云端；</li>
     *   <li>抓拍一帧，跳过与上一次相同的帧；</li>
     *   <li>调用人脸引擎进行比对，记录耗时；</li>
     *   <li>纯识别模式下仅更新展示状态，不触发打卡；</li>
     *   <li>确认门通过后写入本地打卡记录，并对去重做提示。</li>
     * </ol>
     */
    @Scheduled(fixedDelayString = "${app.face.interval-ms:350}")
    public synchronized void tick() {
        // 服务暂停：清空临时状态，返回对应模式的暂停提示
        if (!enabled) {
            gate.reset();
            last = new FaceEngine.Result();
            message = config.recognitionOnly() ? "人脸识别已暂停" : "自动打卡已暂停";
            return;
        }

        // 人脸库尚未同步或已过期，无法进行有效比对
        if (!gallery.usable()) {
            gate.reset();
            last = new FaceEngine.Result();
            message = "人脸缓存尚未同步或已过期，请连接云端";
            return;
        }

        try (CameraService.Frame frame = camera.snapshot()) {
            // 跳帧保护：若摄像头返回了相同时间戳的帧则直接跳过，避免重复推理
            if (frame.at == lastFrame) return;
            lastFrame = frame.at;

            // 执行人脸检测 + 1:N 比对
            long started = System.nanoTime();
            FaceEngine.Result result = engine.analyze(frame.image, gallery.snapshot(), true);
            inferenceMs = (System.nanoTime() - started) / 1000000.0;
            last = result;
            message = result.message;

            // 纯识别模式：只展示结果，不写入打卡记录
            if (config.recognitionOnly()) {
                gate.reset();
                lastEvent = null;
                return;
            }

            // 确认门：需连续 N 帧识别到同一员工才放行，防止单帧误判
            if (gate.accept(result, frame.at, config.integer("face.confirm-frames", 3))) {
                Event event = local.record(result.employeeId, result.employeeName, result.similarity);
                if (event != null) {
                    // 打卡记录写入成功，等待后续同步至云端
                    lastEvent = event;
                    message = "已记录打卡，等待同步";
                } else {
                    // 员工在去重时间窗口内已打卡，本次忽略
                    message = "已识别，去重时间内不重复打卡";
                }
            }
        } catch (Exception ex) {
            // 摄像头读取、本地写入等异常兜底，确保定时任务不中断
            gate.reset();
            last = new FaceEngine.Result();
            message = "识别暂不可用，请检查摄像头与本地存储";
        }
    }

    /**
     * 开启/关闭识别与打卡服务。切换时重置确认门，避免残留状态影响下次启动。
     *
     * @param value true 启用，false 暂停
     */
    public synchronized void toggle(boolean value) {
        enabled = value;
        gate.reset();
    }

    /**
     * 返回当前服务状态快照，供前端轮询展示（识别结果、消息、耗时、是否纯识别模式等）。
     */
    public synchronized Map<String, Object> state() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", enabled);               // 服务是否启用
        result.put("message", message);               // 当前状态提示
        result.put("recognition", last);              // 最近一次识别结果
        result.put("lastEvent", lastEvent);           // 最近一次打卡事件
        result.put("inferenceMs", inferenceMs);       // 上次推理耗时 (ms)
        result.put("frameAt", lastFrame);             // 上次处理的帧时间戳
        result.put("recognitionOnly", config.recognitionOnly()); // 是否纯识别模式
        return result;
    }
}

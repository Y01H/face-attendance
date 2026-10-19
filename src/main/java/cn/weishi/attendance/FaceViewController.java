package cn.weishi.attendance;

// Spring MVC HTTP 响应相关类
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
// 注解：用于定义 REST 控制器和 GET 请求映射
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
// 使用 LinkedHashMap 保证 JSON 字段输出顺序稳定
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 人脸识别结果查看控制器（只读模式）。
 * <p>
 * 仅在 RECOGNITION_ONLY 模式下提供实时识别状态查询，
 * 不会触发任何考勤事件记录，适合用于调试或演示页面。
 * </p>
 */
@RestController
public class FaceViewController {

    /** 摄像头服务，负责视频流的采集与状态管理 */
    private final CameraService camera;

    /** 人脸识别服务，负责特征比对与识别结果维护 */
    private final RecognitionService recognition;

    /** 应用配置，用于判断当前运行模式（识别模式 or 打卡模式） */
    private final AppConfig config;

    /**
     * 通过构造器注入依赖，便于单元测试和 Spring 容器管理。
     *
     * @param camera      摄像头服务实例
     * @param recognition 人脸识别服务实例
     * @param config      应用配置实例
     */
    public FaceViewController(CameraService camera, RecognitionService recognition, AppConfig config) {
        this.camera = camera;
        this.recognition = recognition;
        this.config = config;
    }

    /**
     * 实时获取摄像头与人脸识别的当前状态。
     * <p>
     * 请求路径：GET /api/face/live<br>
     * 前提条件：必须处于识别模式（RECOGNITION_ONLY=true），否则抛出异常。<br>
     * 响应格式：{"camera": {...}, "recognition": {...}}<br>
     * 缓存策略：禁止浏览器及中间代理缓存，确保每次获取最新状态。
     * </p>
     *
     * @return 包含摄像头状态和识别结果的 HTTP 响应
     * @throws IllegalStateException 当前非识别模式时抛出
     */
    @GetMapping("/api/face/live")
    public ResponseEntity<Map<String, Object>> live() {
        // 安全守卫：防止在打卡模式下误用此只读接口，避免与考勤记录逻辑冲突
        if (!config.recognitionOnly()) {
            throw new IllegalStateException("当前为打卡模式，请设置 RECOGNITION_ONLY=true 后重启程序");
        }

        // 构建有序响应体，LinkedHashMap 保证 JSON 输出中 camera 在 recognition 之前
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("camera", camera.state());         // 摄像头运行状态（帧率、分辨率、是否活跃等）
        response.put("recognition", recognition.state()); // 最近一次识别结果（姓名、置信度、时间戳等）

        // noStore 确保实时性：客户端每次请求都从服务器获取最新数据，不命中本地缓存
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }
}

package cn.weishi.attendance;

import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;
import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.util.*;

import static cn.weishi.attendance.Records.*;

/**
 * 人脸识别考勤系统 REST API 控制器。
 * <p>
 * 提供以下核心功能：
 * <ul>
 *   <li>摄像头实时预览与 MJPEG 视频流</li>
 *   <li>人脸采集注册（摄像头抓拍 / 照片上传）</li>
 *   <li>人脸模板比对与识别</li>
 *   <li>员工信息管理（查询、停用）</li>
 *   <li>考勤事件查询与重试</li>
 *   <li>系统状态查询与功能开关</li>
 *   <li>CSRF 令牌获取（供前端表单提交使用）</li>
 * </ul>
 * <p>
 * 所有接口均挂载在 {@code /api} 路径下，由 Spring Security 统一进行权限与 CSRF 校验。
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    /** 摄像头服务，负责打开/关闭摄像头、抓拍帧及生成预览 JPEG */
    private final CameraService camera;

    /** 人脸引擎，封装 OpenCV/ONNX 人脸检测、特征提取与比对逻辑 */
    private final FaceEngine engine;

    /** 人脸模板库（Gallery），管理员工人脸特征向量及与云端的同步 */
    private final Gallery gallery;

    /** 识别服务，控制实时识别开关并协调摄像头帧与人脸引擎 */
    private final RecognitionService recognition;

    /** 本地事件存储，记录每次识别产生的考勤事件并支持失败重试 */
    private final LocalStore local;

    /** 云端同步服务，定期将本地数据推送到远程服务器 */
    private final SyncService sync;

    /** 预览帧缓冲区，供 MJPEG 流式接口消费最新摄像头画面 */
    private final PreviewFrames previewFrames;

    /**
     * 构造注入所有依赖服务。
     *
     * @param camera        摄像头服务实例
     * @param engine        人脸检测与识别引擎
     * @param gallery       人脸模板库
     * @param recognition   实时识别调度服务
     * @param local         本地事件存储
     * @param sync          云端同步服务
     * @param previewFrames 预览帧缓冲，用于 MJPEG 流推送
     */
    public ApiController(CameraService camera, FaceEngine engine, Gallery gallery, RecognitionService recognition, LocalStore local, SyncService sync, PreviewFrames previewFrames) {
        this.camera = camera;
        this.engine = engine;
        this.gallery = gallery;
        this.recognition = recognition;
        this.local = local;
        this.sync = sync;
        this.previewFrames = previewFrames;
    }

    // ==================== 安全相关 ====================

    /**
     * 获取当前会话的 CSRF 令牌，供前端在提交 POST/PUT/DELETE 请求时携带。
     * <p>
     * Spring Security 在请求到达此端点之前已将 {@link CsrfToken} 存入 request attribute，
     * 本方法仅将其以 JSON 形式暴露给前端。
     *
     * @param request 当前 HTTP 请求
     * @return 包含 {@code header}（请求头名称）和 {@code token}（令牌值）的 Map
     */
    @GetMapping("/csrf")
    public Map<String, String> csrf(HttpServletRequest request) {
        // 从 request 属性中提取 Spring Security 生成的 CSRF 令牌
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        Map<String, String> result = new HashMap<>();
        result.put("header", token.getHeaderName());
        result.put("token", token.getToken());
        return result;
    }

    // ==================== 系统状态 ====================

    /**
     * 查询系统各模块的当前运行状态。
     * <p>
     * 返回内容包括：摄像头连接状态、人脸模板库状态、识别引擎开关、云端同步状态、特征模型版本号。
     *
     * @return 各模块状态的聚合 Map
     */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("camera", camera.state());
        result.put("gallery", gallery.state());
        result.put("recognition", recognition.state());
        result.put("sync", sync.state());
        result.put("modelVersion", FeatureCodec.MODEL_VERSION);
        return result;
    }

    // ==================== 摄像头预览 / 视频流 ====================

    /**
     * 获取摄像头当前帧的 JPEG 静态图片（单帧快照）。
     * <p>
     * 设置 {@code Cache-Control: no-store} 防止浏览器缓存，确保每次请求获取最新画面。
     *
     * @return HTTP 200 响应，Content-Type 为 image/jpeg，Body 为 JPEG 字节数组
     */
    @GetMapping("/camera/frame.jpg")
    public ResponseEntity<byte[]> preview() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_JPEG).body(camera.preview());
    }

    /**
     * 以 MJPEG（multipart/x-mixed-replace）格式推送实时视频流。
     * <p>
     * 使用 {@link StreamingResponseBody} 实现长连接流式输出，
     * 每 55ms（约 18 fps）推送一帧 JPEG。
     * {@code X-Accel-Buffering: no} 用于禁用 Nginx 反向代理缓冲，降低延迟。
     *
     * @return HTTP 200 响应，Content-Type 为 multipart/x-mixed-replace
     */
    @GetMapping("/camera/stream.mjpg")
    public ResponseEntity<StreamingResponseBody> stream() {
        // 先触发一次预览，确保摄像头已打开且第一帧可用
        camera.preview();
        return ResponseEntity.ok().header("Content-Type", MjpegStream.CONTENT_TYPE).header("X-Accel-Buffering", "no").cacheControl(CacheControl.noStore())
                .body(output -> MjpegStream.write(previewFrames, output, 55000));
    }

    // ==================== 员工管理 ====================

    /**
     * 查询当前人脸模板库中所有员工的列表信息。
     * <p>
     * 对每位员工统计其关联的人脸模板数量，便于前端展示。
     *
     * @return 员工列表，每项包含 employeeId、name、active（是否启用）、templates（模板数）
     */
    @GetMapping("/employees")
    public List<Map<String, Object>> employees() {
        Snapshot snapshot = gallery.snapshot();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Employee e : snapshot.employees) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("employeeId", e.employeeId);
            row.put("name", e.name);
            row.put("active", e.active);
            // 统计该员工在模板库中的人脸特征数量
            row.put("templates", snapshot.templates.stream().filter(t -> t.employeeId.equals(e.employeeId)).count());
            result.add(row);
        }
        return result;
    }

    /**
     * 手动触发人脸模板库与云端的同步刷新。
     * <p>
     * 调用后 Gallery 会重新拉取云端最新的员工及人脸特征数据。
     *
     * @return 刷新后的 Gallery 状态信息
     */
    @PostMapping("/gallery/refresh")
    public Map<String, Object> refresh() {
        gallery.refresh();
        return gallery.state();
    }

    /**
     * 启用或停用实时人脸识别功能。
     * <p>
     * 停用后摄像头不再进行人脸比对，但预览画面仍然可用。
     *
     * @param enabled {@code true} 开启识别，{@code false} 关闭识别
     * @return 操作后的识别服务状态
     */
    @PostMapping("/recognition/{enabled}")
    public Map<String, Object> toggle(@PathVariable boolean enabled) {
        recognition.toggle(enabled);
        return recognition.state();
    }

    // ==================== 考勤事件 ====================

    /**
     * 查询最近产生的考勤识别事件列表。
     *
     * @return 近期事件列表（按时间倒序）
     */
    @GetMapping("/events")
    public List<Event> events() {
        return local.recent();
    }

    /**
     * 对指定事件发起云端同步重试。
     * <p>
     * 适用于事件已成功记录但因网络原因未能上传云端的情况。
     *
     * @param id 事件唯一标识（UUID）
     * @return 包含 {@code updated} 字段（固定为 1）的 Map
     */
    @PostMapping("/events/{id}/retry")
    public Map<String, Integer> retry(@PathVariable String id) {
        return Collections.singletonMap("updated", local.retryFailed(id));
    }

    /**
     * 停用指定员工并删除其所有人脸模板。
     * <p>
     * 操作为逻辑删除：员工标记为 inactive，对应模板从 Gallery 中移除。
     *
     * @param id 员工 ID
     * @return 操作结果提示信息
     */
    @DeleteMapping("/employees/{id}")
    public Map<String, String> disable(@PathVariable String id) {
        gallery.disable(id);
        return Collections.singletonMap("message", "员工已停用，人脸模板已删除");
    }

    // ==================== 人脸注册（采集） ====================

    /**
     * 通过摄像头抓拍进行人脸注册。
     * <p>
     * 流程：校验请求参数 → 从摄像头获取一帧图像 → 调用人脸引擎检测 &amp; 提取特征 → 写入模板库并同步云端。
     *
     * @param request 包含 employeeId 和 name 的注册请求体
     * @return 成功提示信息
     * @throws IllegalArgumentException 参数校验失败或人脸检测不通过时抛出
     */
    @PostMapping("/enroll/camera")
    public Map<String, String> enrollCamera(@RequestBody Enrollment request) {
        validate(request);
        // 从摄像头抓拍一帧，使用 try-with-resources 确保底层资源（Mat/Frame）及时释放
        try (CameraService.Frame frame = camera.snapshot()) {
            enroll(request, frame.image);
        }
        return Collections.singletonMap("message", "人脸模板已保存到云端");
    }

    /**
     * 通过上传照片进行人脸注册。
     * <p>
     * 接收 multipart 表单中的图片文件，解码后执行人脸检测与特征提取，
     * 成功则将模板写入 Gallery 并同步至云端。
     *
     * @param employeeId 员工唯一标识
     * @param name       员工姓名
     * @param file       上传的人脸图片（JPEG/PNG，≤ 8MB，每边 ≤ 4096px）
     * @return 成功提示信息
     * @throws Exception 图片解码或人脸检测失败时抛出
     */
    @PostMapping(value = "/enroll/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, String> enrollPhoto(@RequestParam String employeeId, @RequestParam String name, @RequestParam MultipartFile file) throws Exception {
        Enrollment request = new Enrollment();
        request.employeeId = employeeId;
        request.name = name;
        validate(request);
        // 将上传的图片字节流解码为 OpenCV Mat（BGR 三通道）
        Mat image = decode(file);
        try {
            enroll(request, image);
        } finally {
            // 确保 Mat 占用的本地内存被释放，防止内存泄漏
            image.release();
        }
        return Collections.singletonMap("message", "人脸模板已保存到云端");
    }

    // ==================== 人脸比对 / 识别 ====================

    /**
     * 对上传的单张图片执行人脸检测与模板比对。
     * <p>
     * 与实时识别不同，本接口为一次性请求，不依赖摄像头。
     * 返回检测结果及最匹配的相似度分数。
     *
     * @param file 待检测的人脸图片
     * @return 人脸引擎的分析结果（包含检测框、特征、是否通过阈值等）
     * @throws IllegalStateException    人脸模板库未同步时抛出
     * @throws Exception                图片解码失败时抛出
     */
    @PostMapping(value = "/face/check", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public FaceEngine.Result check(@RequestParam MultipartFile file) throws Exception {
        // 比对前确保模板库已加载，否则无法进行有效识别
        if (!gallery.usable()) throw new IllegalStateException("请先同步云端人脸模板");
        Mat image = decode(file);
        try {
            // 第三个参数 true 表示执行与 Gallery 中已注册模板的比对
            return engine.analyze(image, gallery.snapshot(), true);
        } finally {
            image.release();
        }
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 执行人脸注册核心流程：检测 → 提取特征 → 写入模板库。
     * <p>
     * 此方法为内部注册逻辑的复用入口，被 {@link #enrollCamera} 和 {@link #enrollPhoto} 共同调用。
     *
     * @param request 包含员工 ID 和姓名的注册请求
     * @param image   已解码的人脸图像（OpenCV Mat，BGR 格式）
     * @throws IllegalArgumentException 当人脸检测未通过（无脸 / 多脸 / 质量不达标）时抛出
     */
    private void enroll(Enrollment request, Mat image) {
        // 仅做人脸检测与特征提取，不做 Gallery 比对（第三个参数 false）
        FaceEngine.Result result = engine.analyze(image, gallery.snapshot(), false);
        if (!result.accepted) throw new IllegalArgumentException(result.message);
        // 将提取到的特征向量保存至模板库，并触发云端同步
        gallery.enroll(request.employeeId, request.name, result.feature);
    }

    /**
     * 校验人脸注册请求参数的合法性。
     * <p>
     * 规则：
     * <ul>
     *   <li>employeeId：非空，1～64 位，仅允许字母、数字、点(.)、横线(-)、下划线(_)</li>
     *   <li>name：非空，去除首尾空格后长度 ≤ 100 字符</li>
     * </ul>
     *
     * @param request 待校验的注册请求对象（name 字段会被 trim 后回写）
     * @throws IllegalArgumentException 任一校验不通过时抛出，消息为中文友好提示
     */
    private void validate(Enrollment request) {
        if (request.employeeId == null || !request.employeeId.matches("[A-Za-z0-9_.-]{1,64}"))
            throw new IllegalArgumentException("员工 ID 仅支持 1～64 位字母、数字、点、横线、下划线");
        if (request.name == null || request.name.trim().isEmpty() || request.name.length() > 100)
            throw new IllegalArgumentException("姓名不能为空，最长 100 字符");
        request.name = request.name.trim();
    }

    /**
     * 将 {@link MultipartFile} 安全地解码为 OpenCV {@link Mat} 图像。
     * <p>
     * 分两步执行：
     * <ol>
     *   <li><b>预检</b>：使用 {@code javax.imageio} 读取图片头信息，校验格式（JPEG/PNG）和尺寸（每边 ≤ 4096px）</li>
     *   <li><b>解码</b>：使用 OpenCV {@code Imgcodecs.imdecode} 将字节数组转为 BGR 三通道 Mat</li>
     * </ol>
     * 调用方负责在不再使用时调用 {@code image.release()} 释放本地内存。
     *
     * @param file 上传的图片文件
     * @return 解码后的 BGR 图像（非空）
     * @throws IllegalArgumentException 文件大小不合法、格式不支持、尺寸超限或解码失败
     * @throws Exception                读取字节流时的 IO 异常
     */
    static Mat decode(MultipartFile file) throws Exception {
        // 1. 校验文件大小：不能为空，上限 8MB
        if (file.isEmpty() || file.getSize() > 8 * 1024 * 1024)
            throw new IllegalArgumentException("图片大小应为 1 字节～8MB");

        byte[] bytes = file.getBytes();

        // 2. 预检：通过 ImageIO 探测图片格式与尺寸（仅读头部，不占大量内存）
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("仅支持可解码的 JPEG/PNG 图片");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                int w = reader.getWidth(0), h = reader.getHeight(0);
                // 限制单边最大 4096 像素，防止超大图导致 OOM
                if (w < 1 || h < 1 || w > 4096 || h > 4096)
                    throw new IllegalArgumentException("图片每边最多 4096 像素");
            } finally {
                reader.dispose();
            }
        }

        // 3. 正式解码：使用 OpenCV 将压缩字节还原为 BGR 三通道 Mat
        MatOfByte encoded = new MatOfByte(bytes);
        Mat image;
        try {
            image = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_COLOR);
        } finally {
            // MatOfByte 为临时缓冲区，解码完成后立即释放
            encoded.release();
        }

        // 4. 解码结果校验：空 Mat 表示图片损坏或格式虽合法但内容无法解析
        if (image.empty()) {
            image.release();
            throw new IllegalArgumentException("图片解码失败");
        }
        return image;
    }

    // ==================== 内部 DTO ====================

    /**
     * 人脸注册请求的 DTO（Data Transfer Object）。
     * <p>
     * 用于 {@code POST /api/enroll/camera} 接口的 JSON 请求体反序列化。
     */
    public static class Enrollment {
        /** 员工唯一标识，支持字母、数字、点、横线、下划线，长度 1～64 */
        public String employeeId;
        /** 员工姓名，去除首尾空格后不超过 100 字符 */
        public String name;
    }
}

package cn.weishi.attendance;

import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.FaceDetectorYN;
import org.opencv.objdetect.FaceRecognizerSF;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

import static cn.weishi.attendance.Records.*;

/**
 * 人脸识别引擎（Spring 单例组件）。
 * <p>
 * 基于 OpenCV 的 YuNet 人脸检测器与 SFace 人脸识别器，
 * 提供人脸检测、质量检测（模糊度/亮度/正脸度）以及 1:N 识别比对功能。
 * </p>
 */
@Component
public class FaceEngine {

    /** YuNet 人脸检测模型，负责定位图像中的人脸及关键 landmark */
    private final FaceDetectorYN detector;

    /** SFace 人脸识别模型，负责对对齐后的人脸裁剪图提取 128 维特征向量 */
    private final FaceRecognizerSF recognizer;

    /** 应用配置，包含模型路径、阈值等参数 */
    private final AppConfig config;

    /**
     * 构造并初始化人脸识别引擎。
     * <p>
     * 加载本地 OpenCV 动态库，设置并行线程数，
     * 根据配置路径加载检测/识别 ONNX 模型文件。
     * </p>
     *
     * @param config 应用配置，从中读取 face.detector、face.recognizer 等参数
     * @throws IllegalArgumentException 当模型文件不存在时抛出
     */
    public FaceEngine(AppConfig config) {
        this.config = config;
        // 加载本地 OpenCV 共享库（native）
        nu.pattern.OpenCV.loadLocally();
        // 限制 OpenCV 内部并行线程数，避免与 Web 线程池争抢 CPU
        Core.setNumThreads(Math.max(1, config.integer("face.threads", 2)));
        // 从配置读取模型路径，提供默认相对路径
        String detection = config.text("face.detector", "./models/face_detection_yunet_2023mar.onnx");
        String recognition = config.text("face.recognizer", "./models/face_recognition_sface_2021dec.onnx");
        // 校验模型文件必须存在
        if (!Files.isRegularFile(Paths.get(detection)) || !Files.isRegularFile(Paths.get(recognition)))
            throw new IllegalArgumentException("找不到模型文件，请从解压目录启动并保留 models 文件夹");
        // 创建检测器：置信度阈值 0.9，NMS 阈值 0.3，最多保留 5000 个候选框
        detector = FaceDetectorYN.create(detection, "", new Size(320, 320), 0.9f, 0.3f, 5000);
        // 创建识别器
        recognizer = FaceRecognizerSF.create(recognition, "");
    }

    /**
     * 单次分析结果的数据载体，用于向上传递检测/识别的详细信息。
     */
    public static class Result {
        /** 图像中检测到的人脸数量 */
        public int faceCount;
        /** 面向用户的提示消息 */
        public String message;
        /** 匹配到的员工工号（识别通过时填充） */
        public String employeeId;
        /** 匹配到的员工姓名（识别通过时填充） */
        public String employeeName;
        /** 最佳匹配的余弦相似度 [0,1] */
        public double similarity;
        /** 次优匹配的余弦相似度，用于计算 margin */
        public double runnerUp;
        /** 人脸在原始图像中的边界框 [x, y, width, height] */
        public int[] box;
        /** 本次分析是否通过（合格 / 识别成功） */
        public boolean accepted;
        /** 拉普拉斯方差，用于衡量图像模糊程度，值越大越清晰 */
        public double blur;
        /** 人脸区域灰度均值，范围 0~255，用于判断光照条件 */
        public double brightness;
        /** 提取的 128 维 L2 归一化特征向量（仅内部使用，不序列化到 JSON） */
        @com.fasterxml.jackson.annotation.JsonIgnore
        public float[] feature;
    }

    /**
     * 对单帧图像执行完整的人脸分析流程。
     * <p>
     * 流程：人脸检测 → 数量校验 → 尺寸校验 → 质量校验（模糊/亮度/正脸）
     * → 对齐裁剪 → 特征提取 → 可选 1:N 比对。
     * </p>
     * <p>
     * 使用 {@code synchronized} 保证 OpenCV native 资源线程安全。
     * </p>
     *
     * @param image       待分析的 BGR 图像（来自摄像头帧）
     * @param gallery     当前人脸模板库快照（含员工列表与模板向量）
     * @param requireMatch 是否执行 1:N 识别比对；false 时仅做质量检测
     * @return 分析结果，包含消息、相似度、员工信息等
     */
    public synchronized Result analyze(Mat image, Snapshot gallery, boolean requireMatch) {
        Result result = new Result();
        Mat faces = new Mat();     // 检测结果矩阵：每行代表一张人脸
        Mat aligned = new Mat();   // 对齐后的人脸裁剪图
        Mat feature = new Mat();   // 识别器输出的特征向量（OpenCV Mat 形式）
        try {
            // ===== 1. 前置校验 =====
            if (image.empty()) throw new IllegalArgumentException("空图片");

            // ===== 2. 人脸检测 =====
            detector.setInputSize(image.size());
            detector.detect(image, faces);
            result.faceCount = faces.rows();
            // 仅允许画面中出现 1 张人脸
            if (result.faceCount != 1) {
                result.message = result.faceCount == 0 ? "未检测到人脸" : "请每次仅一人进入画面";
                return result;
            }

            // ===== 3. 提取检测框与 landmark =====
            // faces 第 0 行的 15 个浮点数：[x, y, w, h, 左眼x, 左眼y, 右眼x, 右眼y, 鼻尖x, 鼻尖y, 左嘴角x, 左嘴角y, 右嘴角x, 右嘴角y, score]
            float[] info = new float[15];
            faces.get(0, 0, info);
            // 裁剪边界，防止越界
            int x = Math.max(0, (int) info[0]), y = Math.max(0, (int) info[1]);
            int width = Math.min((int) info[2], image.cols() - x), height = Math.min((int) info[3], image.rows() - y);
            result.box = new int[]{x, y, width, height};

            // 人脸过小 → 提示靠近
            if (width < config.integer("face.min-size", 80) || height < config.integer("face.min-size", 80)) {
                result.message = "请靠近摄像头";
                return result;
            }

            // ===== 4. 图像质量校验 =====
            // 裁剪人脸区域用于质量评估
            Mat crop = new Mat(image, new Rect(x, y, width, height));
            Mat gray = new Mat();       // 灰度图
            Mat laplacian = new Mat();  // 拉普拉斯算子结果
            MatOfDouble mean = new MatOfDouble();
            MatOfDouble deviation = new MatOfDouble();
            try {
                Imgproc.cvtColor(crop, gray, Imgproc.COLOR_BGR2GRAY);
                // 拉普拉斯方差 = 模糊度指标，值越小越模糊
                Imgproc.Laplacian(gray, laplacian, CvType.CV_64F);
                Core.meanStdDev(laplacian, mean, deviation);
                result.blur = Math.pow(deviation.toArray()[0], 2);
                // 灰度均值 = 亮度指标
                result.brightness = Core.mean(gray).val[0];
            } finally {
                // 显式释放本次质量计算产生的临时 Mat
                crop.release();
                gray.release();
                laplacian.release();
                mean.release();
                deviation.release();
            }
            // 模糊度过低 → 提示稳定
            if (result.blur < config.number("face.min-blur", 45)) {
                result.message = "画面模糊，请保持稳定";
                return result;
            }
            // 光照异常 → 提示调整光线
            if (result.brightness < 40 || result.brightness > 220) {
                result.message = "光照不足或过曝";
                return result;
            }
            // 正脸校验：利用双眼 landmark 判断距离与水平偏移
            double eyeDistance = Math.hypot(info[4] - info[6], info[5] - info[7]);
            if (eyeDistance < width * 0.18 || Math.abs(info[5] - info[7]) > eyeDistance * 0.5) {
                result.message = "请正对摄像头";
                return result;
            }

            // ===== 5. 人脸对齐与特征提取 =====
            Mat row = faces.row(0);
            try {
                // 根据 5 个 landmark 做仿射对齐并裁剪为标准尺寸
                recognizer.alignCrop(image, row, aligned);
            } finally {
                row.release();
            }
            // 提取 128 维特征向量
            recognizer.feature(aligned, feature);
            result.feature = new float[(int) feature.total()];
            feature.get(0, 0, result.feature);
            // L2 归一化，使后续余弦相似度等价于内积
            normalize(result.feature);

            // 若仅做质量检测则提前返回
            if (!requireMatch) {
                result.accepted = true;
                result.message = "图片质量合格";
                return result;
            }

            // ===== 6. 1:N 识别比对 =====
            // 构建活跃员工集合（过滤已停用的员工）
            Map<String, Employee> employees = new HashMap<>();
            for (Employee e : gallery.employees) if (e.active) employees.put(e.employeeId, e);

            // 对每位员工的所有模板取最大相似度（一人多张底图时取最优）
            Map<String, Double> scores = new HashMap<>();
            for (Template t : gallery.templates)
                if (employees.containsKey(t.employeeId))
                    scores.merge(t.employeeId, cosine(result.feature, t.vector), Math::max);

            // 找出最佳匹配与次优匹配（用于 margin 判断）
            double best = -1, second = -1;
            String bestId = null;
            for (Map.Entry<String, Double> item : scores.entrySet()) {
                if (item.getValue() > best) {
                    second = best;
                    best = item.getValue();
                    bestId = item.getKey();
                } else if (item.getValue() > second) second = item.getValue();
            }
            result.similarity = best;
            result.runnerUp = second;

            // 判定规则：相似度 ≥ threshold 且与次优差距 ≥ margin → 通过
            if (bestId != null && best >= config.number("face.threshold", 0.50)
                    && best - second >= config.number("face.margin", 0.08)) {
                result.employeeId = bestId;
                result.employeeName = employees.get(bestId).name;
                result.accepted = true;
                result.message = "识别通过";
            } else {
                result.message = bestId == null ? "暂无可用人脸模板" : "未知人员或匹配不明确";
            }
            return result;
        } finally {
            // 统一释放在方法中创建的所有 OpenCV Mat 资源
            faces.release();
            aligned.release();
            feature.release();
        }
    }

    /**
     * 对特征向量进行 L2 归一化（原地修改）。
     * <p>归一化后向量模长为 1，余弦相似度退化为点积。</p>
     *
     * @param feature 待归一化的浮点数组
     * @throws IllegalArgumentException 当向量模长接近零（无效特征）时抛出
     */
    public static void normalize(float[] feature) {
        double norm = 0;
        for (float f : feature) norm += f * f;
        norm = Math.sqrt(norm);
        if (norm < 1e-10) throw new IllegalArgumentException("无效零特征");
        for (int i = 0; i < feature.length; i++) feature[i] /= norm;
    }

    /**
     * 计算两个特征向量的余弦相似度。
     *
     * @param a 向量 a（已 L2 归一化时为点积）
     * @param b 向量 b
     * @return 余弦相似度 ∈ [-1, 1]；若任一向量为零则返回 -1
     * @throws IllegalArgumentException 当向量长度不一致或为 null 时抛出
     */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) throw new IllegalArgumentException("特征维数不一致");
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na > 0 && nb > 0 ? dot / Math.sqrt(na * nb) : -1;
    }

    /**
     * 将 Mat 图像编码为 JPEG 字节数组（默认质量 75）。
     *
     * @param image 待编码的 BGR 图像
     * @return JPEG 编码后的字节数组
     * @throws IllegalStateException 编码失败时抛出
     */
    public static byte[] jpeg(Mat image) {
        return jpeg(image, 75);
    }

    /**
     * 将 Mat 图像编码为 JPEG 字节数组，可指定压缩质量。
     *
     * @param image   待编码的 BGR 图像
     * @param quality JPEG 压缩质量，范围 [1, 100]，超出范围会被截断
     * @return JPEG 编码后的字节数组
     * @throws IllegalStateException 编码失败时抛出
     */
    public static byte[] jpeg(Mat image, int quality) {
        MatOfByte bytes = new MatOfByte();
        // 将质量参数限制在 1~100 之间
        MatOfInt parameters = new MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, Math.max(1, Math.min(100, quality)));
        try {
            if (!Imgcodecs.imencode(".jpg", image, bytes, parameters)) throw new IllegalStateException("图像编码失败");
            return bytes.toArray();
        } finally {
            bytes.release();
            parameters.release();
        }
    }
}

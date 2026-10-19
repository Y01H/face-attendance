package cn.weishi.attendance;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 应用配置读取工具类。
 * 封装了 Spring {@link Environment}，统一以 "app." 为前缀读取配置项，
 * 并提供类型转换、默认值回退及必填校验等能力。
 */
@Component
public class AppConfig {

    /** Spring 环境抽象，用于读取 application.yml / application.properties / 系统环境变量 */
    private final Environment env;

    /**
     * 通过构造器注入 Spring Environment。
     *
     * @param env Spring 环境对象
     */
    public AppConfig(Environment env) {
        this.env = env;
    }

    /**
     * 读取字符串类型的配置项。
     *
     * @param key      配置键（不含 "app." 前缀），例如 "face.model-path"
     * @param fallback 当配置项不存在时返回的默认值
     * @return 对应的字符串值，或默认值
     */
    public String text(String key, String fallback) {
        return env.getProperty("app." + key, fallback);
    }

    /**
     * 读取整数类型的配置项。
     *
     * @param key      配置键（不含 "app." 前缀）
     * @param fallback 当配置项不存在或无法转换时返回的默认值
     * @return 对应的整数值，或默认值
     */
    public int integer(String key, int fallback) {
        return env.getProperty("app." + key, Integer.class, fallback);
    }

    /**
     * 读取浮点数类型的配置项。
     *
     * @param key      配置键（不含 "app." 前缀）
     * @param fallback 当配置项不存在或无法转换时返回的默认值
     * @return 对应的 double 值，或默认值
     */
    public double number(String key, double fallback) {
        return env.getProperty("app." + key, Double.class, fallback);
    }

    /**
     * 读取布尔类型的配置项（开关 / 标志位）。
     *
     * @param key      配置键（不含 "app." 前缀）
     * @param fallback 当配置项不存在或无法转换时返回的默认值
     * @return 对应的布尔值，或默认值
     */
    public boolean flag(String key, boolean fallback) {
        return env.getProperty("app." + key, Boolean.class, fallback);
    }

    /**
     * 判断是否处于"仅识别"模式（不做人脸比对/注册）。
     * <p>
     * 优先级：
     * <ol>
     *   <li>配置项 {@code app.face.recognition-only}</li>
     *   <li>环境变量 {@code RECOGNITION_ONLY}（兼容旧版部署）</li>
     *   <li>默认值 {@code true}</li>
     * </ol>
     *
     * @return 若为仅识别模式返回 {@code true}，否则返回 {@code false}
     */
    public boolean recognitionOnly() {
        return env.getProperty("app.face.recognition-only", Boolean.class,
                env.getProperty("RECOGNITION_ONLY", Boolean.class, true));
    }

    /**
     * 读取必填的字符串配置项。若未配置或为空白，则抛出异常并提示用户。
     *
     * @param key 配置键（不含 "app." 前缀）
     * @return 非空字符串配置值
     * @throws IllegalArgumentException 当配置缺失时抛出，提示参见 README 和 config/env.example
     */
    public String required(String key) {
        String value = text(key, "");
        if (value.trim().isEmpty())
            throw new IllegalArgumentException("请配置 app." + key + "，参见 README 和 config/env.example");
        return value;
    }
}

package cn.weishi.attendance;

import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 人脸特征向量编解码组件。
 * <p>
 * 使用 AES-256-GCM 对 128 维人脸特征向量进行加密/解密，
 * 确保特征数据在传输和存储过程中的机密性与完整性。
 * GCM 模式的认证标签（128-bit）可检测密文是否被篡改。
 * </p>
 * <p>
 * 密文格式：[12 字节随机 Nonce] + [512 字节密文] + [16 字节 GCM 认证标签]，共 540 字节。
 * </p>
 */
@Component
public class FeatureCodec {

    /** 模型版本标识，作为 GCM 附加认证数据（AAD），防止跨模型版本误用密钥解密 */
    public static final String MODEL_VERSION = "sface-2021dec-128-v1";

    /** AES 对称密钥（256-bit），由配置中心下发 */
    private final SecretKeySpec key;

    /** 用于生成 GCM 随机 Nonce 的安全随机源 */
    private final SecureRandom random = new SecureRandom();

    /**
     * 构造编解码器实例。
     *
     * @param config 应用配置，其中 "feature-key" 为 Base64 编码的 32 字节 AES 密钥
     * @throws IllegalArgumentException 若密钥长度不是 32 字节
     */
    public FeatureCodec(AppConfig config) {
        // 从配置中读取 Base64 编码的密钥并解码为原始字节
        byte[] bytes = Base64.getDecoder().decode(config.required("feature-key"));
        // 校验密钥长度必须为 32 字节（AES-256）
        if (bytes.length != 32) throw new IllegalArgumentException("FEATURE_KEY 必须是 32 字节密钥的 Base64");
        key = new SecretKeySpec(bytes, "AES");
    }

    /**
     * 将明文人脸特征向量加密为密文字节数组。
     * <p>
     * 流程：float[] → 512 字节明文 → AES-GCM 加密 → [Nonce + 密文 + 认证标签]
     * </p>
     *
     * @param vector 128 维浮点数人脸特征向量
     * @return 加密后的字节数组（12 字节 Nonce + 528 字节密文及认证标签）
     * @throws IllegalArgumentException 若特征维度不为 128 或包含非有限浮点值
     * @throws IllegalStateException    若加密过程发生异常
     */
    public byte[] encrypt(float[] vector) {
        // 校验特征维度必须为 128
        if (vector.length != 128) throw new IllegalArgumentException("人脸特征维数应为 128");

        // 将 float 数组序列化为大端序字节缓冲区（128 × 4 = 512 字节）
        ByteBuffer plain = ByteBuffer.allocate(vector.length * 4);
        for (float value : vector) {
            // 拒绝 NaN 和 Infinity，防止数据污染
            if (!Float.isFinite(value)) throw new IllegalArgumentException("无效特征");
            plain.putFloat(value);
        }

        try {
            // 生成 12 字节随机 Nonce（每次加密使用新 Nonce，保证相同明文产生不同密文）
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);

            // 初始化 AES/GCM 加密器，认证标签长度为 128 位
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));

            // 将模型版本作为附加认证数据（AAD），绑定上下文但不参与加密
            cipher.updateAAD(MODEL_VERSION.getBytes("UTF-8"));

            // 执行加密，输出为 [密文 || 16 字节认证标签]
            byte[] encrypted = cipher.doFinal(plain.array());

            // 拼接最终密文：[12 字节 Nonce][密文 + 认证标签]，便于解密时提取 Nonce
            return ByteBuffer.allocate(12 + encrypted.length).put(nonce).put(encrypted).array();
        } catch (Exception ex) {
            throw new IllegalStateException("特征加密失败", ex);
        }
    }

    /**
     * 将密文字节数组解密还原为人脸特征向量。
     * <p>
     * 流程：[Nonce + 密文 + 认证标签] → AES-GCM 解密 → 512 字节明文 → float[128]
     * </p>
     *
     * @param encrypted 加密特征字节数组，长度必须为 540（12 Nonce + 512 密文 + 16 认证标签）
     * @return 解密后的 128 维浮点数人脸特征向量
     * @throws IllegalArgumentException 若密文长度不合法或特征值非有限
     * @throws IllegalStateException    若解密失败（密钥不匹配或密文被篡改）
     */
    public float[] decrypt(byte[] encrypted) {
        try {
            // 校验密文长度：12(Nonce) + 512(密文) + 16(GCM认证标签) = 540
            if (encrypted == null || encrypted.length != 12 + 512 + 16)
                throw new IllegalArgumentException("无效加密特征长度");

            // 从密文前 12 字节提取 Nonce，初始化 AES/GCM 解密器
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, encrypted, 0, 12));

            // AAD 必须与加密时一致，否则认证失败
            cipher.updateAAD(MODEL_VERSION.getBytes("UTF-8"));

            // 解密第 12 字节之后的全部内容（密文 + 认证标签）
            ByteBuffer buffer = ByteBuffer.wrap(cipher.doFinal(encrypted, 12, encrypted.length - 12));

            // 将 512 字节明文反序列化为 128 维浮点数组
            float[] vector = new float[128];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = buffer.getFloat();
                // 二次校验：解密结果必须是有限浮点数
                if (!Float.isFinite(vector[i])) throw new IllegalArgumentException("无效特征");
            }
            return vector;
        } catch (Exception ex) {
            // 常见原因：密钥不一致、密文被篡改、AAD 版本不匹配
            throw new IllegalStateException("特征解密失败，请检查各终端 FEATURE_KEY 是否一致", ex);
        }
    }
}

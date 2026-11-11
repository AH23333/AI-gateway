package com.ai.gateway.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 摘要工具（SHA-256）
 *
 * 用途（选型书 3.1 哈希化改造）：
 * - RefreshToken 落库前哈希（数据库泄露时攻击者拿不到可用 Token）；
 * - API Key 落库前哈希（修 P8：明文只在创建时返回一次）。
 *
 * 为什么不用 BCrypt 哈希这些凭证？
 * BCrypt 是"慢哈希"，服务密码这种低频校验场景；
 * API Key / Token 每次请求都要校验，慢哈希会放大网关延迟，
 * 且这些凭证本身是 128 位以上高熵随机串，暴力字典攻击无意义 —— SHA-256 足够。
 */
public final class DigestUtil {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private DigestUtil() {
    }

    /** SHA-256 → 64 位小写十六进制字符串 */
    public static String sha256Hex(String input) {
        if (input == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(HEX[(b >> 4) & 0x0F]);
                sb.append(HEX[b & 0x0F]);
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // JDK 规范保证 SHA-256 存在，理论不可达
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}

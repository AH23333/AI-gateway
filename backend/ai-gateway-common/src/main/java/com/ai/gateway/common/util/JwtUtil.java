package com.ai.gateway.common.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * JWT 令牌工具类（jjwt 0.12.x 重写版）
 *
 * 与 0.9.1 的 API 差异（升栈必改点）：
 * - Jwts.parser().setSigningKey(...)  → Jwts.parser().verifyWith(key).build()
 * - SignatureAlgorithm + 字符串密钥   → Keys.hmacShaKeyFor(bytes)（密钥 >= 32 字节）
 * - setClaims/setExpiration           → claims()/expiration（新 builder 风格）
 *
 * 双 Token 策略（基线保留）：
 * AccessToken（默认 30 分钟，可配）— 业务接口鉴权；
 * RefreshToken（默认 7 天，可配）— 无感刷新 AccessToken。
 * 登出时 AccessToken 加入 Redis 黑名单（JWT 无状态无法吊销，只能黑名单拦截）。
 *
 * ⚠ 数字声明类型陷阱（v2 自增 ID 后必须处理）：
 * jjwt 反序列化时把小整数解析成 Integer 而不是 Long，
 * claims.get("userId", Long.class) 会直接 ClassCastException。
 * 统一经 getUserIdClaim() 以 Number.longValue() 读取。
 */
public final class JwtUtil {

    /** 签名密钥（>= 32 字节，由 JwtInitializer 从配置注入） */
    private static volatile SecretKey KEY =
            Keys.hmacShaKeyFor("ai-gateway-default-secret-key-CHANGE-ME!!".getBytes(StandardCharsets.UTF_8));

    /** AccessToken 有效期（毫秒），默认 30 分钟 */
    private static volatile long accessExpireMillis = 30 * 60 * 1000L;

    /** RefreshToken 有效期（毫秒），默认 7 天 */
    private static volatile long refreshExpireMillis = 7 * 24 * 60 * 60 * 1000L;

    private JwtUtil() {
    }

    /** 应用启动时注入密钥与有效期（见 JwtInitializer），密钥不足 32 字节直接快速失败 */
    public static void init(String secret, long accessExpireMinutes, long refreshExpireDays) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("jwt.secret 必须至少 32 字节（HS256 要求）");
        }
        JwtUtil.KEY = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        if (accessExpireMinutes > 0) {
            JwtUtil.accessExpireMillis = accessExpireMinutes * 60 * 1000L;
        }
        if (refreshExpireDays > 0) {
            JwtUtil.refreshExpireMillis = refreshExpireDays * 24 * 60 * 60 * 1000L;
        }
    }

    /**
     * 创建 AccessToken
     * 载荷：userId / username / role / type="access"，
     * access 模块过滤器解析后注入 X-User-Id 等请求头。
     */
    public static String createAccessToken(Long userId, String username, String role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("username", username);
        claims.put("role", role != null ? role : "USER");
        claims.put("type", "access");
        return Jwts.builder()
                .claims(claims)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessExpireMillis))
                .signWith(KEY)
                .compact();
    }

    /**
     * 创建 RefreshToken
     * 载荷只含 userId + type 标识（最小化敏感信息），jti 用于唯一标识。
     */
    public static String createRefreshToken(Long userId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("type", "refresh");
        claims.put("jti", UUID.randomUUID().toString());
        return Jwts.builder()
                .claims(claims)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + refreshExpireMillis))
                .signWith(KEY)
                .compact();
    }

    /** 解析并验签 Token，返回载荷；签名不符/过期抛 JwtException 子类 */
    public static Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(KEY)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 安全读取 userId 声明（Integer/Long 兼容）。
     * 陷阱见类注释：自增小 ID 会被 jjwt 反序列化为 Integer。
     */
    public static Long getUserIdClaim(Claims claims) {
        Object value = claims.get("userId");
        if (value instanceof Number number) {
            return number.longValue();
        }
        return null;
    }

    /** AccessToken 总有效期（毫秒） */
    public static long getAccessExpire() {
        return accessExpireMillis;
    }

    /**
     * 管理端热更 AccessToken 有效期（分钟，系统设置中心调用）
     * 即时对下一次签发生效；值来自 t_system_config（DB 覆盖 yml）。
     */
    public static void updateAccessExpireMinutes(long accessExpireMinutes) {
        if (accessExpireMinutes > 0) {
            JwtUtil.accessExpireMillis = accessExpireMinutes * 60 * 1000L;
        }
    }

    /**
     * 计算 Token 剩余有效期（毫秒），用于设置 Redis 黑名单 TTL：
     * Token 只剩 5 分钟就过期，黑名单也只需存 5 分钟，不浪费 Redis 内存。
     * 解析失败（过期/非法）返回 0，黑名单不写入。
     */
    public static long getRemainingExpire(String token) {
        try {
            Claims claims = parseToken(token);
            long remaining = claims.getExpiration().getTime() - System.currentTimeMillis();
            return Math.max(remaining, 0);
        } catch (JwtException e) {
            return 0;
        }
    }
}

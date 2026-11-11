package com.ai.gateway.access.filter;

import com.ai.gateway.access.config.AccessProperties;
import com.ai.gateway.access.support.AccessErrorWriter;
import com.ai.gateway.access.support.HeaderInjectRequestWrapper;
import com.ai.gateway.common.constant.RedisKeyConstant;
import com.ai.gateway.common.constant.SystemConfigKey;
import com.ai.gateway.common.holder.SystemConfigHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.io.IOException;
import java.util.Collections;

/**
 * 固定窗口限流过滤器（原网关 RedisRateLimiterFilter 的 Servlet 化移植）
 *
 * 限流算法：Redis Lua 固定窗口计数器（原子操作，并发无竞态）
 *   key   = rate:limit:{userId 或 IP}:{path}
 *   窗口  = access.rate-window-sencods（默认 60s）
 *   阈值  = access.rate-limit（默认 100 次/窗口）
 *
 * 限流粒度：每用户（或 IP）+ 每路径独立计数 ——
 * 刷爆 /v1/chat/completions 不影响该用户查 /api/user/chat/logs。
 *
 * 为什么未登录按 IP？
 * 所有匿名请求共享一个桶的话，一个恶意 IP 就能耗尽全站配额。
 *
 * 为什么用 Lua 而不是 Java 先 INCR 再判断？
 * Lua 在 Redis 内原子执行，避免并发下两请求同时 INCR 后都判通过的竞态。
 */
@Slf4j
public class RateLimitFilter extends AbstractHttpFilter {

    /**
     * 限流 Lua 脚本（固定窗口计数器）
     * KEYS[1]=限流键  ARGV[1]=阈值  ARGV[2]=窗口秒数
     * 首次计数（current==1）时设置过期；超阈值返回 0（拒绝），否则返回 1（放行）
     */
    private static final String LUA_SCRIPT =
            "local key = KEYS[1] " +
            "local limit = tonumber(ARGV[1]) " +
            "local window = tonumber(ARGV[2]) " +
            "local current = redis.call('INCR', key) " +
            "if current == 1 then " +
            "    redis.call('EXPIRE', key, window) " +
            "end " +
            "if current > limit then " +
            "    return 0 " +
            "end " +
            "return 1";

    /** 预编译脚本（内容不变编译一次；Long 对应返回值 0/1） */
    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT =
            new DefaultRedisScript<>(LUA_SCRIPT, Long.class);

    private final AccessProperties properties;
    private final StringRedisTemplate redisTemplate;

    public RateLimitFilter(AccessProperties properties, StringRedisTemplate redisTemplate) {
        this.properties = properties;
        this.redisTemplate = redisTemplate;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // 1. 限流标识：已登录（JwtAuthFilter 注入 X-User-Id）按用户，否则按 IP
        String userId = request.getHeader(HeaderInjectRequestWrapper.HEADER_USER_ID);
        String identity = userId != null ? userId : getClientIp(request);

        // 2. 限流键：rate:limit:{身份}:{路径}
        String key = RedisKeyConstant.RATE_LIMIT + identity + ":" + request.getRequestURI();

        // 3. 阈值/窗口实时读系统设置中心（DB 覆盖 yml，管理端在线改免重启；缺行回退 access.* 配置）
        int rateLimit = SystemConfigHolder.getInt(
                SystemConfigKey.ACCESS_RATE_LIMIT, properties.getRateLimit());
        int rateWindowSeconds = SystemConfigHolder.getInt(
                SystemConfigKey.ACCESS_RATE_WINDOW_SECONDS, properties.getRateWindowSeconds());

        // 4. 原子执行 Lua（INCR + 首次 EXPIRE + 阈值判断）
        Long result = redisTemplate.execute(RATE_LIMIT_SCRIPT,
                Collections.singletonList(key),
                String.valueOf(rateLimit),
                String.valueOf(rateWindowSeconds));

        // 5. 超阈值 → 429（双协议写出：/v1 面 OpenAI error，其余 Result）
        if (result != null && result == 0) {
            log.warn("限流触发: key={}", key);
            AccessErrorWriter.write(request, response, 429, "请求过于频繁，请稍后重试");
            return;
        }

        chain.doFilter(request, response);
    }

    /** 客户端真实 IP（本机直连场景取 remoteAddr 即可；前置 Nginx 时读 X-Forwarded-For） */
    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // X-Forwarded-For 可能串联多级：client, proxy1, proxy2 —— 取第一个
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}

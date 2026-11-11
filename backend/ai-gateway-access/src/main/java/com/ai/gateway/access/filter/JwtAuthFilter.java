package com.ai.gateway.access.filter;

import com.ai.gateway.access.config.AccessProperties;
import com.ai.gateway.access.support.AccessErrorWriter;
import com.ai.gateway.access.support.HeaderInjectRequestWrapper;
import com.ai.gateway.auth.service.ApiKeyService;
import com.ai.gateway.common.constant.BizCodeEnum;
import com.ai.gateway.common.constant.CommonStatusEnum;
import com.ai.gateway.common.constant.RedisKeyConstant;
import com.ai.gateway.common.pojo.ApiKey;
import com.ai.gateway.common.pojo.User;
import com.ai.gateway.common.util.JwtUtil;
import com.ai.gateway.auth.mapper.UserMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.AntPathMatcher;

import java.io.IOException;
import java.util.List;

/**
 * 认证过滤器 —— 入口第二道防线（原网关 JwtAuthFilter 的 Servlet 化移植 + API Key 扩展）
 *
 * 完整流程：
 * 1. 白名单检查     → /auth/login 等路径直接放行；
 * 2. 提取凭证       → Authorization: Bearer {JWT 或 sk-xxx}；
 * 3a. JWT 路径      → Redis 黑名单 → 验签/过期 → type=access 校验 → 解析身份；
 * 3b. API Key 路径  → 哈希检索启用态 Key（auth 域 ApiKeyService）→ 用户状态校验；
 * 4. 角色校验       → /admin/** 仅 ADMIN；
 * 5. 身份注入       → X-User-Id / X-Username / X-User-Role（HeaderInjectRequestWrapper，
 *                     客户端伪造的同名头被覆盖，控制器只信过滤器）。
 *
 * 为什么 API Key 直连要查用户状态？
 * 用户被禁用后其 Key 也必须失效 —— 凭证有效性 = Key 有效性 ∧ 账号有效性。
 */
@Slf4j
public class JwtAuthFilter extends AbstractHttpFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String API_KEY_PREFIX = "sk-";
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final AccessProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final ApiKeyService apiKeyService;
    private final UserMapper userMapper;

    public JwtAuthFilter(AccessProperties properties, StringRedisTemplate redisTemplate,
                         ApiKeyService apiKeyService, UserMapper userMapper) {
        this.properties = properties;
        this.redisTemplate = redisTemplate;
        this.apiKeyService = apiKeyService;
        this.userMapper = userMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();

        // ====== 1. 白名单放行（Ant 风格：/auth/** 匹配 /auth/login 等） ======
        if (isWhitelisted(path)) {
            chain.doFilter(request, response);
            return;
        }

        // ====== 2. 提取凭证 ======
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            log.warn("缺少凭证: path={}", path);
            AccessErrorWriter.write(request, response,
                    BizCodeEnum.UNAUTHORIZED.getCode(), BizCodeEnum.UNAUTHORIZED.getDesc());
            return;
        }
        String credential = authHeader.substring(BEARER_PREFIX.length()).trim();

        // ====== 3. 双路径分流：API Key 直连 / JWT 会话 ======
        User user;
        if (credential.startsWith(API_KEY_PREFIX)) {
            user = authenticateByApiKey(credential);
            if (user == null) {
                log.warn("API Key 无效: path={}", path);
                AccessErrorWriter.write(request, response,
                        BizCodeEnum.UNAUTHORIZED.getCode(), "API Key 无效或已吊销");
                return;
            }
        } else {
            user = authenticateByJwt(request, response, credential);
            if (user == null) {
                // 具体失败原因已在 authenticateByJwt 内写入响应
                return;
            }
        }

        // ====== 4. 角色校验：/admin/** 仅 ADMIN ======
        if (path.startsWith("/admin/") && !"ADMIN".equals(user.getRole())) {
            log.warn("非管理员访问管理接口: userId={}, role={}, path={}",
                    user.getId(), user.getRole(), path);
            AccessErrorWriter.write(request, response,
                    BizCodeEnum.FORBIDDEN.getCode(), "无权限，仅管理员可访问");
            return;
        }

        // ====== 5. 身份注入并放行 ======
        chain.doFilter(new HeaderInjectRequestWrapper(
                request, user.getId(), user.getUsername(), user.getRole()), response);
    }

    /**
     * JWT 认证路径（前端/平台用户）
     *
     * @return 认证成功返回用户；失败返回 null 并已写错误响应
     */
    private User authenticateByJwt(HttpServletRequest request, HttpServletResponse response,
                                   String token) throws IOException {
        // 黑名单：登出/刷新后的旧 AccessToken（TTL = 剩余有效期，自动过期）
        String blacklistKey = RedisKeyConstant.TOKEN_BLACKLIST + token;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(blacklistKey))) {
            log.warn("Token 已列入黑名单");
            AccessErrorWriter.write(request, response,
                    BizCodeEnum.UNAUTHORIZED.getCode(), "Token 已失效，请重新登录");
            return null;
        }
        try {
            Claims claims = JwtUtil.parseToken(token);

            // RefreshToken 只能走 /auth/refresh，不能访问业务接口
            if (!"access".equals(claims.get("type"))) {
                log.warn("非 AccessToken 用于业务鉴权: type={}", claims.get("type"));
                AccessErrorWriter.write(request, response,
                        BizCodeEnum.UNAUTHORIZED.getCode(), "请使用 AccessToken");
                return null;
            }
            Long userId = JwtUtil.getUserIdClaim(claims);
            if (userId == null) {
                AccessErrorWriter.write(request, response,
                        BizCodeEnum.UNAUTHORIZED.getCode(), "Token 无效");
                return null;
            }
            User user = userMapper.selectById(userId);
            if (user == null) {
                AccessErrorWriter.write(request, response,
                        BizCodeEnum.USER_NOT_FOUND.getCode(), BizCodeEnum.USER_NOT_FOUND.getDesc());
                return null;
            }
            // 账号禁用即时生效（JWT 无状态，此处补一道数据库状态校验）
            if (user.getStatus() == null
                    || user.getStatus() == CommonStatusEnum.DISABLED.getCode()) {
                AccessErrorWriter.write(request, response,
                        BizCodeEnum.ACCOUNT_DISABLED.getCode(), BizCodeEnum.ACCOUNT_DISABLED.getDesc());
                return null;
            }
            return user;
        } catch (ExpiredJwtException e) {
            log.info("Token 已过期");
            AccessErrorWriter.write(request, response,
                    BizCodeEnum.UNAUTHORIZED.getCode(), "Token 已过期，请重新登录");
            return null;
        } catch (Exception e) {
            log.warn("Token 无效: {}", e.getMessage());
            // 畸形/验签失败 token：必须写错误响应，否则调用方拿到空 200
            AccessErrorWriter.write(request, response,
                    BizCodeEnum.UNAUTHORIZED.getCode(), "Token 无效，请重新登录");
            return null;
        }
    }

    /**
     * API Key 认证路径（agent / LangChain / OpenAI SDK 直连场景）
     *
     * 凭证有效性 = Key 启用 ∧ 账号启用；
     * Key 哈希检索（auth 域），明文永不落库（选型书 3.1 修 P8）。
     *
     * @return 认证成功返回用户；失败返回 null
     */
    private User authenticateByApiKey(String plainKey) {
        ApiKey apiKey = apiKeyService.findByHash(plainKey);
        if (apiKey == null) {
            return null;
        }
        User user = userMapper.selectById(apiKey.getUserId());
        if (user == null
                || user.getStatus() == null
                || user.getStatus() == CommonStatusEnum.DISABLED.getCode()) {
            return null;
        }
        return user;
    }

    /** Ant 风格白名单匹配（/auth/** → /auth/login、/auth/register…） */
    private boolean isWhitelisted(String path) {
        List<String> whitelist = properties.getWhitelist();
        if (whitelist == null) {
            return false;
        }
        for (String pattern : whitelist) {
            if (PATH_MATCHER.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }
}

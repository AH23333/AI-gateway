package com.ai.gateway.access.config;

import com.ai.gateway.access.filter.JwtAuthFilter;
import com.ai.gateway.access.filter.RateLimitFilter;
import com.ai.gateway.access.filter.TraceIdFilter;
import com.ai.gateway.auth.mapper.UserMapper;
import com.ai.gateway.auth.service.ApiKeyService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 过滤器链装配（顺序即语义，1 → 2 → 3）：
 *
 *   ① TraceIdFilter   最先执行 —— 后续所有日志都带 traceId；
 *   ② JwtAuthFilter   认证在限流之前 —— 限流需要 X-User-Id 区分用户；
 *   ③ RateLimitFilter 最后执行 —— 只对"已认证的请求"计数，未认证的已被 ② 拦截。
 *
 * 为什么用 FilterRegistrationBean 而不是 @Component + @Order？
 * 显式注册可以精确控制 URL 匹配与派发类型（仅 REQUEST），
 * 且过滤器类保持无注解 —— 不被 Boot 自动注册，避免"同一过滤器进两次链"。
 */
@Configuration
public class AccessFilterConfig {

    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilterRegistration() {
        FilterRegistrationBean<TraceIdFilter> registration =
                new FilterRegistrationBean<>(new TraceIdFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(1);
        registration.setName("traceIdFilter");
        return registration;
    }

    @Bean
    public FilterRegistrationBean<JwtAuthFilter> jwtAuthFilterRegistration(
            AccessProperties properties, StringRedisTemplate redisTemplate,
            ApiKeyService apiKeyService, UserMapper userMapper) {
        FilterRegistrationBean<JwtAuthFilter> registration = new FilterRegistrationBean<>(
                new JwtAuthFilter(properties, redisTemplate, apiKeyService, userMapper));
        registration.addUrlPatterns("/*");
        registration.setOrder(2);
        registration.setName("jwtAuthFilter");
        return registration;
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(
            AccessProperties properties, StringRedisTemplate redisTemplate) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(
                new RateLimitFilter(properties, redisTemplate));
        registration.addUrlPatterns("/*");
        registration.setOrder(3);
        registration.setName("rateLimitFilter");
        return registration;
    }
}

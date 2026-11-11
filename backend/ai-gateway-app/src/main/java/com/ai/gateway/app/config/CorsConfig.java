package com.ai.gateway.app.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS 配置 —— 前端（React/Vue dev server）跨域直连本后端
 *
 * 放开口径：
 * - 允许携带 Authorization 头（JWT / sk- 两条认证路径都依赖它）；
 * - 全方法放开（GET/POST/PUT/DELETE/OPTIONS）；
 * - 预检结果缓存 1 小时，减少 OPTIONS 往返。
 *
 * 安全口径：allowedOriginPatterns 与 allowCredentials 必须同时精确，
 * 生产把前端域名写进环境变量，禁止 * + credentials 的组合（浏览器也会拒绝）。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("${CORS_ORIGINS:*}")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                // 前端要读 X-Trace-Id（排障）和 X-Routing-Decision（路由可视化）
                .exposedHeaders("X-Trace-Id", "X-Routing-Decision")
                .allowCredentials(true)
                .maxAge(3600);
    }
}

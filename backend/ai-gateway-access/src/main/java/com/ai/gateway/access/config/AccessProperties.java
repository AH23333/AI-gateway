package com.ai.gateway.access.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * access 模块配置项（application.yml 中 access.* 前缀）
 *
 * 全部带默认值：零配置即可启动；生产用环境变量覆盖。
 */
@Data
@ConfigurationProperties(prefix = "access")
public class AccessProperties {

    /**
     * 认证白名单（Ant 风格路径）：
     * 登录/注册/接口文档/健康检查不需要 Token，其余全部鉴权。
     */
    private java.util.List<String> whitelist = java.util.List.of(
            "/auth/**",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/doc.html",
            "/webjars/**",
            "/actuator/**",
            "/settings/**",
            "/error",
            "/favicon.ico");

    /** 固定窗口限流：每用户（或 IP）每路径窗口内最大请求数 */
    private int rateLimit = 100;

    /** 固定窗口大小（秒） */
    private int rateWindowSeconds = 60;
}

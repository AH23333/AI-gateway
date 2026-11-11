package com.ai.gateway.app.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 文档配置（springdoc，替代原 knife4j）
 *
 * 统一挂 Bearer 认证方案：
 * swagger-ui 右上角 Authorize 填入 JWT 或 sk- Key，
 * 后续所有调试请求自动带 Authorization: Bearer {token}，
 * 与 access 模块 JwtAuthFilter 的两条认证路径完全一致。
 */
@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME_NAME = "BearerAuth";

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("AI Gateway 智能路由平台 API")
                        .description("模块化单体后端（Java 21 + Spring Boot 3.2）。"
                                + "双协议：/v1/** 为 OpenAI 兼容协议（供 LangChain/OpenAI SDK 直连），"
                                + "其余为管理/自助接口（Result 统一响应体）。")
                        .version("2.0.0"))
                .components(new Components().addSecuritySchemes(SECURITY_SCHEME_NAME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT 或 sk- API Key")
                                .description("登录获取 AccessToken，或直接填入 API Key（sk- 开头）")))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME));
    }
}

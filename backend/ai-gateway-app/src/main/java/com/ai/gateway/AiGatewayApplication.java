package com.ai.gateway;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AI Gateway 模块化单体 —— 唯一启动入口
 *
 * 包位置有意放在根包 com.ai.gateway：
 * @SpringBootApplication 默认扫描"入口类所在包"及子包，
 * 放根包才能一次扫到六个模块（common/auth/billing/router/admin/access）
 * 的全部 @Component/@Service/@Configuration。
 *
 * 模块组装关系（父 POM ai-gateway-app 的依赖即全部模块）：
 *   请求 → access(过滤:traceId→认证→限流) → admin/router 控制器 → billing/router 服务 → MySQL/Redis/OpenAI 兼容上游
 *
 * 拆分微服务的升级路径（选型书第八章）：
 * 模块边界 = 未来服务边界，直接按模块拆出，业务代码零改动。
 */
@SpringBootApplication
@EnableScheduling
@MapperScan({
        "com.ai.gateway.auth.mapper",
        "com.ai.gateway.billing.mapper",
        "com.ai.gateway.router.mapper",
        "com.ai.gateway.admin.mapper"
})
@ConfigurationPropertiesScan("com.ai.gateway")
public class AiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiGatewayApplication.class, args);
    }
}

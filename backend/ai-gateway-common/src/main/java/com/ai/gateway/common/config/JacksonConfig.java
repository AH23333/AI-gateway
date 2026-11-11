package com.ai.gateway.common.config;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/**
 * Jackson 全局配置
 *
 * Long → String 序列化：
 * JS Number 是 IEEE-754 双精度，安全整数上限 2^53-1，
 * 雪花 ID（19 位）直接以数字返回前端会精度丢失（尾数变 0）。
 * 全局将 Long/long 序列化为字符串，前端按字符串处理即可。
 * （v2 业务主键已改自增，不超 2^53，但 traceId 相关字段仍有大数，规则统一保留。）
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longToStringCustomizer() {
        return builder -> {
            builder.serializerByType(Long.class, ToStringSerializer.instance);
            builder.serializerByType(Long.TYPE, ToStringSerializer.instance);
        };
    }
}

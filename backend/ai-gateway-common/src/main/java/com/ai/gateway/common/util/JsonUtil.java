package com.ai.gateway.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Jackson JSON 工具类
 *
 * 为什么用静态方法 + @Component 注入？
 * static 字段无法直接 @Autowired，采用 setter 注入 + 静态变量桥接：
 * Spring 创建 Bean 时自动调用 setObjectMapper()（复用 Boot 自动配置、
 * 已含日期格式/时区定制的 ObjectMapper），静态方法经桥接调用。
 *
 * 替代 FastJSON 的原因：FastJSON 存在高危反序列化漏洞历史。
 */
@Component
public class JsonUtil {

    private static ObjectMapper objectMapper;

    @Autowired
    public void setObjectMapper(ObjectMapper objectMapper) {
        JsonUtil.objectMapper = objectMapper;
    }

    /** 对象 → JSON 字符串 */
    public static String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON序列化失败", e);
        }
    }

    /** JSON 字符串 → 对象 */
    public static <T> T parseObject(String json, Class<T> clazz) {
        try {
            return objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON反序列化失败", e);
        }
    }

    /** JSON 字符串 → JsonNode（按路径解析，如 node.path("usage").path("total_tokens")） */
    public static JsonNode parseTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON解析失败", e);
        }
    }
}

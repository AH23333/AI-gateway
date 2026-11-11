package com.ai.gateway.router.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.util.List;

/**
 * OpenAI 兼容上游响应体（v2：Ollama 原生 NDJSON → OpenAI 兼容协议）
 *
 * 一个 DTO 同时覆盖两种形态：
 * - 非流式：POST /chat/completions 的完整 JSON（choices[0].message 终态）；
 * - 流式：SSE 的单个 data: {...} 帧（choices[0].delta 增量）。
 *
 * 兼容上游（均实现 OpenAI /v1/chat/completions 协议）：
 * - 智谱 GLM   https://open.bigmodel.cn/api/paas/v4/chat/completions
 * - DeepSeek   https://api.deepseek.com/v1/chat/completions
 * - 本地 Ollama http://localhost:11434/v1/chat/completions（OpenAI 兼容模式）
 *
 * 思考型模型扩展（GLM glm-4.5+ / DeepSeek-R1 同构）：
 * message/delta.reasoning_content 为推理过程增量，不计入应答 content、
 * 不入会话存储与计费 —— 网关仅原样透传给支持该字段的客户端。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class UpstreamChatResponse {

    /** 上游 completion id（如 2026110222481203f43f8573074ac0） */
    private String id;

    /** 实际应答模型编码 */
    private String model;

    /** chat.completion / chat.completion.chunk */
    private String object;

    private List<Choice> choices;

    /** 用量（GLM/DeepSeek 在终态帧携带；OpenAI 流式默认不带需估算兜底） */
    private Usage usage;

    /** 上游错误体（非 200 时 {"error":{"message","code","type"}}） */
    private UpstreamError error;

    // ==================== 便捷访问 ====================

    /** 取 choices[0]（上游协议保证单候选；缺失返回 null） */
    public Choice firstChoice() {
        return choices == null || choices.isEmpty() ? null : choices.get(0);
    }

    /** 非流式应答文本（choices[0].message.content，缺失返回 null） */
    public String messageContent() {
        Choice choice = firstChoice();
        if (choice == null || choice.getMessage() == null) {
            return null;
        }
        return choice.getMessage().getContent();
    }

    /** 终态结束原因（非流式取 message 帧，流式取最后一个含 finish_reason 的帧） */
    public String firstFinishReason() {
        Choice choice = firstChoice();
        return choice == null ? null : choice.getFinishReason();
    }

    /** 用量：输入 token（缺失返回 null，调用方估算兜底） */
    public Long usagePromptTokens() {
        return usage == null ? null : usage.getPromptTokens();
    }

    /** 用量：输出 token（缺失返回 null，调用方估算兜底） */
    public Long usageCompletionTokens() {
        return usage == null ? null : usage.getCompletionTokens();
    }

    /**
     * 候选（非流式 message 终态 / 流式 delta 增量，二选一）
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Choice {

        /** 候选序号（网关单候选恒 0） */
        private Integer index;

        /** 非流式终态消息 */
        private Message message;

        /** 流式增量 */
        private Delta delta;

        /** 结束原因：stop / length / tool_calls / sensitive / ... */
        @JsonProperty("finish_reason")
        private String finishReason;
    }

    /**
     * 非流式终态消息（OpenAI message 结构）
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Message {

        private String role;

        /** 应答正文（不含推理过程） */
        private String content;

        /** 工具调用（OpenAI 格式：[{id,type,function:{name,arguments}}]） */
        @JsonProperty("tool_calls")
        private JsonNode toolCalls;
    }

    /**
     * 流式增量（OpenAI delta 结构 + 思考模型扩展）
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Delta {

        private String role;

        /** 应答正文增量 */
        private String content;

        /** 思考型模型的推理增量（GLM glm-4.5+ 等，透传不入库） */
        @JsonProperty("reasoning_content")
        private String reasoningContent;

        /** 工具调用增量（OpenAI 分片协议；GLM 通常单帧给全量） */
        @JsonProperty("tool_calls")
        private JsonNode toolCalls;
    }

    /**
     * 用量（OpenAI usage 结构）
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Usage {

        @JsonProperty("prompt_tokens")
        private Long promptTokens;

        @JsonProperty("completion_tokens")
        private Long completionTokens;

        @JsonProperty("total_tokens")
        private Long totalTokens;
    }

    /**
     * 上游错误体（OpenAI error 结构）
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class UpstreamError {

        private String message;

        /** GLM 为字符串数字（"400"/"1210"...），OpenAI 为错误码字符串 */
        private String code;

        private String type;
    }
}

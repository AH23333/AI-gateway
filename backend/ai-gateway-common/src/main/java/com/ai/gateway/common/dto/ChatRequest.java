package com.ai.gateway.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.util.List;

/**
 * AI 对话请求 DTO —— OpenAI /v1/chat/completions 协议（v2，FR-14 扩展）
 *
 * v2 关键变更（1.md §7.1 + FR-14）：
 * 1. model 允许为空：不传 → 智能路由（FR-01~05）；传 → 基线行为（指定模型）；
 * 2. 新增透传字段：tools / tool_choice / response_format / temperature /
 *    top_p / max_tokens / stop —— 原样透传至 OpenAI 兼容上游，不因
 *    白名单校验丢弃；这是 LangChain/LangGraph agent 应用接入的地基；
 * 3. Message 扩展 tool_calls / tool_call_id / name：兼容 agent 多轮工具调用
 *    会话回放（assistant 历史含 tool_calls、tool 角色回传结果）。
 *
 * 透传字段使用 JsonNode 而非强类型 POJO 的理由：
 * OpenAI 的 tools/tool_choice schema 面向未来演进，强类型建模会人为缩小
 * 兼容面；网关的职责是"不解析、只转发"，JsonNode 保留报文原貌。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatRequest {

    /** 模型编码（可选）：为空走智能路由，如 "qwen2.5:3b" */
    private String model;

    /** 对话消息列表（必填） */
    private List<Message> messages;

    /** 是否流式返回（SSE），默认 false；为 true 时返回 OpenAI chat.completion.chunk 流 */
    private Boolean stream;

    // ==================== FR-14 透传字段（可选） ====================

    /** 工具定义列表（OpenAI tools 格式，原样透传） */
    private JsonNode tools;

    /** 工具选择策略（"auto"/"none"/"required"/具体工具对象，原样透传） */
    private JsonNode toolChoice;

    /** 响应格式（{type:"json_object"} → 上游等价开启 JSON 模式） */
    @JsonProperty("response_format")
    private JsonNode responseFormat;

    /** 采样温度（0~2，透传至上游 options.temperature） */
    private Double temperature;

    /** nucleus 采样参数（透传至上游 options.top_p） */
    @JsonProperty("top_p")
    private Double topP;

    /** 最大生成 token 数（透传至上游 options.num_predict） */
    @JsonProperty("max_tokens")
    private Integer maxTokens;

    /** 停止序列（字符串或数组，透传至上游 options.stop） */
    private JsonNode stop;

    /**
     * 单条消息 —— OpenAI 协议扩展版
     *
     * role: "user" | "assistant" | "system" | "tool"
     * content: 消息文本（tool 消息即工具执行结果）
     * tool_calls: assistant 历史中的工具调用记录（agent 多轮回放用）
     * tool_call_id: tool 角色消息对应的调用 ID
     */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Message {

        /** 角色 */
        private String role;

        /** 消息内容 */
        private String content;

        /** assistant 消息携带的工具调用记录（原样透传，JsonNode 保留 schema） */
        @JsonProperty("tool_calls")
        private JsonNode toolCalls;

        /** tool 角色消息关联的调用 ID */
        @JsonProperty("tool_call_id")
        private String toolCallId;

        /** 消息署名（部分客户端会带，透传保底） */
        private String name;
    }
}

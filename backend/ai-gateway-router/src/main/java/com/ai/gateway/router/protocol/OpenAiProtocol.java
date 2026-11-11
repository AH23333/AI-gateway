package com.ai.gateway.router.protocol;

import com.ai.gateway.common.dto.ChatRequest;
import com.ai.gateway.common.util.IdGen;
import com.ai.gateway.common.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 协议层（v2：南向北向同为 OpenAI 协议，翻译退化为"白名单透传"）
 *
 * 职责（纯函数，无状态）：
 * 1. 请求构造：入站 /v1/chat/completions body → 上游 OpenAI 兼容 body
 *    （白名单字段透传：messages/tools/tool_choice/response_format/采样参数，
 *    网关自有字段如 model 改写为目标模型编码，model="auto" 在路由层已消费）；
 * 2. 应答构造：上游终态/增量 → OpenAI chat.completion / chunk 帧序列
 *    （id 换成网关的 chatcmpl-{traceId}，思考型 reasoning_content 透传）；
 * 3. 工具调用规范化：上游 tool_calls 补全 id/index/type（缺失时兜底生成）。
 *
 * 为什么手动拼 Map 而不是建响应 POJO？
 * OpenAI 响应字段按存在与否变化（tool_calls 有无、usage、reasoning_content 等），
 * Map + LinkedHashMap 保持字段顺序可控，也避免为每种组合建实体类。
 */
public final class OpenAiProtocol {

    private OpenAiProtocol() {
    }

    // ==================== ID / 时间 ====================

    /** OpenAI 风格 completion id：chatcmpl-{traceId} */
    public static String completionId(String traceId) {
        return "chatcmpl-" + traceId;
    }

    /** Unix 秒级时间戳（OpenAI created 字段） */
    public static long created() {
        return System.currentTimeMillis() / 1000;
    }

    // ==================== 请求构造：入站 → 上游（白名单透传）====================

    /**
     * 入站 OpenAI 请求 → 上游 OpenAI 兼容请求
     *
     * 映射要点：
     * - model 强制改写为目标模型编码（路由决策产物，客户端的 "auto"/缺省已消费）；
     * - content null → ""（部分上游对 null 容错差）；
     * - 白名单外的字段（user/n/presence_penalty/logprobs 等）不透传：
     *   网关只保证 FR-14 声明的字段面，防未知字段引发上游 4xx；
     * - stream 由调用方决定（网关内部统一管理流式语义）。
     */
    public static Map<String, Object> toUpstreamRequest(ChatRequest request, String modelCode,
                                                        boolean stream) {
        List<Map<String, Object>> messages = new ArrayList<>();
        if (request.getMessages() != null) {
            for (ChatRequest.Message msg : request.getMessages()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("role", msg.getRole());
                m.put("content", msg.getContent() == null ? "" : msg.getContent());
                if (msg.getName() != null) {
                    m.put("name", msg.getName());
                }
                if (msg.getToolCalls() != null && !msg.getToolCalls().isNull()) {
                    m.put("tool_calls", msg.getToolCalls());
                }
                if (msg.getToolCallId() != null) {
                    m.put("tool_call_id", msg.getToolCallId());
                }
                messages.add(m);
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelCode);
        body.put("messages", messages);
        body.put("stream", stream);
        if (request.getTemperature() != null) {
            body.put("temperature", request.getTemperature());
        }
        if (request.getTopP() != null) {
            body.put("top_p", request.getTopP());
        }
        if (request.getMaxTokens() != null && request.getMaxTokens() > 0) {
            body.put("max_tokens", request.getMaxTokens());
        }
        if (request.getStop() != null && !request.getStop().isNull()) {
            // stop 为字符串或字符串数组，OpenAI 兼容上游原生支持，原样透传
            body.put("stop", request.getStop());
        }
        if (request.getTools() != null && !request.getTools().isNull()) {
            body.put("tools", request.getTools());
        }
        if (request.getToolChoice() != null && !request.getToolChoice().isNull()) {
            body.put("tool_choice", request.getToolChoice());
        }
        if (request.getResponseFormat() != null && !request.getResponseFormat().isNull()) {
            // {type:"json_object"|"json_schema"} —— GLM/DeepSeek/OpenAI 同构，原样透传
            body.put("response_format", request.getResponseFormat());
        }
        return body;
    }

    // ==================== 应答构造：上游 → 出站（非流式）====================

    /**
     * 上游终态 → OpenAI chat.completion 完整响应
     *
     * @param content        应答文本（不含思考过程）
     * @param toolCallsOpenAi 已规范化的 OpenAI tool_calls（无工具调用传 null）
     * @param finishReason   stop / tool_calls / length
     */
    public static Map<String, Object> chatCompletion(String traceId, String modelCode, String content,
                                                      List<Map<String, Object>> toolCallsOpenAi,
                                                      String finishReason,
                                                      long promptTokens, long completionTokens) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", content == null ? "" : content);
        if (toolCallsOpenAi != null && !toolCallsOpenAi.isEmpty()) {
            message.put("tool_calls", toolCallsOpenAi);
        }

        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", finishReason);
        choice.put("logprobs", null);

        Map<String, Object> usage = usage(promptTokens, completionTokens);

        Map<String, Object> body = baseBody(completionId(traceId), modelCode, "chat.completion");
        body.put("choices", List.of(choice));
        body.put("usage", usage);
        return body;
    }

    // ==================== 应答构造：上游 → 出站（流式 chunk）====================

    /** 首帧：delta = {role:"assistant", content:""} */
    public static Map<String, Object> roleChunk(String traceId, String modelCode) {
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("role", "assistant");
        delta.put("content", "");
        return chunkBody(traceId, modelCode, delta, null);
    }

    /** 内容帧：delta = {content:"片段"} */
    public static Map<String, Object> contentChunk(String traceId, String modelCode, String piece) {
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("content", piece);
        return chunkBody(traceId, modelCode, delta, null);
    }

    /**
     * 思考帧：delta = {reasoning_content:"片段"}（OpenAI 语义外扩展）
     *
     * 思考型上游（GLM glm-4.5+ 等）的推理增量原样透传：支持该字段的客户端
     * （Cherry Studio / 智谱 SDK / LangChain 自定义解析）可渲染思考过程，
     * 标准 OpenAI SDK 会忽略未知 delta 字段不受影响；推理文本不入库、不计费。
     */
    public static Map<String, Object> reasoningChunk(String traceId, String modelCode, String piece) {
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("reasoning_content", piece);
        return chunkBody(traceId, modelCode, delta, null);
    }

    /** 工具调用帧：delta = {tool_calls:[...]}（上游单帧全量，OpenAI 端可整帧接收） */
    public static Map<String, Object> toolCallsChunk(String traceId, String modelCode,
                                                      List<Map<String, Object>> toolCallsOpenAi) {
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("tool_calls", toolCallsOpenAi);
        return chunkBody(traceId, modelCode, delta, null);
    }

    /** 收尾帧：空 delta + finish_reason */
    public static Map<String, Object> finishChunk(String traceId, String modelCode, String finishReason) {
        Map<String, Object> delta = new LinkedHashMap<>();
        return chunkBody(traceId, modelCode, delta, finishReason);
    }

    /** 流中错误帧（OpenAI 语义外扩展：error 事件 + data 前缀发送） */
    public static Map<String, Object> errorEvent(String message, String code) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", message);
        error.put("type", "api_error");
        error.put("code", code);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        return body;
    }

    // ==================== 工具调用规范化 ====================

    /**
     * 上游 tool_calls → OpenAI tool_calls 规范化
     *
     * 上游已是 OpenAI 结构（[{id?,index?,type?,function:{name,arguments:"..."}}]），
     * 本方法只做兜底：id 缺失生成、index 缺失按序补、type 缺失补 "function"、
     * arguments 统一为 JSON 字符串（对象则序列化）。
     */
    public static List<Map<String, Object>> mapUpstreamToolCalls(JsonNode toolCalls) {
        if (toolCalls == null || !toolCalls.isArray() || toolCalls.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> result = new ArrayList<>();
        int seq = 0;
        for (JsonNode tc : toolCalls) {
            JsonNode fn = tc.path("function");
            if (fn.isMissingNode()) {
                continue;
            }
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", fn.path("name").asText(""));
            JsonNode args = fn.path("arguments");
            if (args.isTextual()) {
                function.put("arguments", args.asText());
            } else if (args.isMissingNode() || args.isNull()) {
                function.put("arguments", "{}");
            } else {
                function.put("arguments", JsonUtil.toJson(args));
            }

            Map<String, Object> openAiTc = new LinkedHashMap<>();
            JsonNode upstreamIndex = tc.path("index");
            openAiTc.put("index", upstreamIndex.isNumber() ? upstreamIndex.asInt() : seq);
            String id = tc.path("id").asText("");
            openAiTc.put("id", id.isBlank() ? "call_" + IdGen.nextIdStr() : id);
            openAiTc.put("type", "function");
            openAiTc.put("function", function);
            result.add(openAiTc);
            seq++;
        }
        return result.isEmpty() ? null : result;
    }

    // ==================== 结束原因映射 ====================

    /**
     * 上游 finish_reason → OpenAI finish_reason
     *
     * 优先级：有工具调用 → tool_calls；length → length（含思考耗尽 max_tokens）；
     * 其余（stop / sensitive / 其他厂商扩展值）→ stop。
     */
    public static String mapFinishReason(String upstreamFinishReason, boolean hasToolCalls) {
        if (hasToolCalls) {
            return "tool_calls";
        }
        if ("length".equals(upstreamFinishReason)) {
            return "length";
        }
        return "stop";
    }

    // ==================== 内部构造 ====================

    private static Map<String, Object> baseBody(String id, String modelCode, String object) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("object", object);
        body.put("created", created());
        body.put("model", modelCode);
        return body;
    }

    private static Map<String, Object> chunkBody(String traceId, String modelCode,
                                                 Map<String, Object> delta, String finishReason) {
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", finishReason);
        Map<String, Object> body = baseBody(completionId(traceId), modelCode, "chat.completion.chunk");
        body.put("choices", List.of(choice));
        return body;
    }

    private static Map<String, Object> usage(long promptTokens, long completionTokens) {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("prompt_tokens", promptTokens);
        usage.put("completion_tokens", completionTokens);
        usage.put("total_tokens", promptTokens + completionTokens);
        return usage;
    }
}

package com.ai.gateway.router.client;

import com.ai.gateway.common.pojo.ModelInfo;
import com.ai.gateway.common.util.JsonUtil;
import com.ai.gateway.router.config.RouterProperties;
import com.ai.gateway.router.dto.UpstreamChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OpenAI 兼容上游客户端（v2 重写：Ollama /api/chat → OpenAI /chat/completions）
 *
 * 为什么上游统一为 OpenAI 兼容协议？
 * 1. 一个客户端对接全部主流推理服务：智谱 GLM / DeepSeek / 通义兼容模式 /
 *    本地 Ollama（/v1 兼容模式）/ vLLM —— 换上游只改 t_model 的 endpoint +
 *    api_key 两列，代码零改动（这是"模型网关"的核心价值）；
 * 2. 南北两侧同为 OpenAI 协议后，tools/tool_choice/response_format 等扩展
 *    字段从"格式翻译"退化为"白名单透传"，FR-14 的映射面大幅收窄；
 * 3. 原生 Ollama 协议仍是 Ollama 的 /v1 兼容端点的子集，无能力损失。
 *
 * 为什么不用 WebClient/RestTemplate/RestClient？
 * 1. 流式 SSE 需要"逐帧到达逐帧消费"—— HttpClient 的 ofInputStream
 *    配合 BufferedReader 是标准 JDK 写法，无额外依赖、行为完全可控；
 * 2. 取消语义：流中断/客户端断连时，从外部 close() InputStream，
 *    阻塞中的读线程立即抛 IOException 返回 —— 这是 SseEmitter 场景
 *    唯一可靠的打断手段（WebClient 的 Flux cancel 在虚拟线程栈下调试成本高）。
 *
 * 超时矩阵：
 * - 连接：client 级 connectTimeout（默认 3s，上游不在线快速失败）；
 * - 非流式：request 级 timeout 覆盖完整响应（默认 180s）；
 * - 流式：request 级 timeout 只约束"响应头到达"（不能覆盖 body，否则长流被杀），
 *   body 的空闲/总时长由 ChatService 的看门狗 + SseEmitter 超时兜底。
 */
@Slf4j
@Component
public class OpenAiCompatClient {

    /** SSE 数据帧前缀（OpenAI 协议：data: {...}） */
    private static final String SSE_DATA_PREFIX = "data:";

    /** SSE 流终止标记 */
    private static final String SSE_DONE = "[DONE]";

    private final RouterProperties properties;
    private final HttpClient httpClient;

    public OpenAiCompatClient(RouterProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .build();
    }

    // ==================== 非流式 ====================

    /**
     * 非流式对话（阻塞至响应完成）
     *
     * @param request OpenAI 协议请求体（OpenAiProtocol.toUpstreamRequest 产物）
     * @param model   目标模型（endpoint = 完整 URL；apiKey 非空时携带 Bearer 鉴权）
     * @throws UpstreamCallException 上游非 200 / 超时 / 连接失败 / 响应解析失败
     */
    public UpstreamChatResponse chat(Map<String, Object> request, ModelInfo model) {
        HttpRequest httpRequest = baseRequest(request, model)
                .timeout(Duration.ofMillis(properties.getCallTimeoutMs()))
                .build();
        try {
            HttpResponse<String> response =
                    httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new UpstreamCallException("上游 HTTP " + response.statusCode()
                        + ": " + truncate(response.body()), response.statusCode());
            }
            UpstreamChatResponse parsed =
                    JsonUtil.parseObject(response.body(), UpstreamChatResponse.class);
            if (parsed.getError() != null) {
                throw new UpstreamCallException("上游错误[" + parsed.getError().getCode() + "]: "
                        + parsed.getError().getMessage(), 500);
            }
            return parsed;
        } catch (HttpTimeoutException e) {
            throw new UpstreamCallException(
                    "上游调用超时(" + properties.getCallTimeoutMs() + "ms)", 504);
        } catch (ConnectException e) {
            throw new UpstreamCallException("上游连接失败: " + model.getEndpoint(), 502);
        } catch (IOException e) {
            throw new UpstreamCallException("上游 IO 异常: " + e.getMessage(), 502);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamCallException("上游调用被中断", 500);
        }
    }

    // ==================== 流式 ====================

    /**
     * 流式对话（SSE data 帧逐个回调）
     *
     * 调用方约定（ChatService）：
     * - 在独立线程（虚拟线程）中调用本方法，本方法阻塞直至流结束；
     * - bodyRef 用于外部取消：调用方在超时/断连时 close 该 InputStream，
     *   阻塞的读线程抛 IOException 且 isCancelled()=true → 静默退出；
     * - callback.onDone 携带终态帧（finish_reason + usage），EOF 但未见
     *   data: [DONE] 视为异常终止。
     *
     * @param bodyRef 响应体引用（取消句柄，可为 null = 不支持取消）
     */
    public void streamChat(Map<String, Object> request, ModelInfo model,
                           StreamCallback callback, AtomicReference<InputStream> bodyRef) {
        HttpRequest httpRequest = baseRequest(request, model)
                // 仅约束响应头：body 流式时长由调用方看门狗管理
                .timeout(Duration.ofMillis(properties.getCallTimeoutMs()))
                .build();
        try {
            HttpResponse<InputStream> response =
                    httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                String err = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                callback.onError(new UpstreamCallException("上游 HTTP " + response.statusCode()
                        + ": " + truncate(err), response.statusCode()));
                return;
            }
            if (bodyRef != null) {
                bodyRef.set(response.body());
            }
            // 终态帧：含 finish_reason 或 usage 的帧（GLM/DeepSeek 二者同帧）
            UpstreamChatResponse terminal = null;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // 外部取消：不再消费任何残余数据
                    if (callback.isCancelled()) {
                        log.info("上游流已被取消, 停止消费");
                        return;
                    }
                    // SSE 协议：空行=帧分隔，: 开头=注释帧（代理保活），event:=事件名（忽略）
                    if (line.isBlank() || line.startsWith(":") || !line.startsWith(SSE_DATA_PREFIX)) {
                        continue;
                    }
                    String data = line.substring(SSE_DATA_PREFIX.length()).trim();
                    if (data.isEmpty()) {
                        continue;
                    }
                    if (SSE_DONE.equals(data)) {
                        callback.onDone(terminal);
                        return;
                    }
                    UpstreamChatResponse chunk = JsonUtil.parseObject(data, UpstreamChatResponse.class);
                    if (chunk.getError() != null) {
                        callback.onError(new UpstreamCallException("上游错误["
                                + chunk.getError().getCode() + "]: "
                                + chunk.getError().getMessage(), 500));
                        return;
                    }
                    if (chunk.getChoices() != null || chunk.getUsage() != null) {
                        if (chunk.firstFinishReason() != null || chunk.getUsage() != null) {
                            terminal = chunk;
                        }
                        callback.onChunk(chunk);
                    }
                }
                // EOF 但未收到 data: [DONE]：上游异常终止
                callback.onError(new UpstreamCallException("上游流意外终止（未见 [DONE]）", 502));
            }
        } catch (IOException e) {
            // 外部 close() 打断的读异常：取消路径，静默退出
            if (callback.isCancelled()) {
                log.info("上游流读被打断（取消路径）");
                return;
            }
            callback.onError(new UpstreamCallException("上游流式 IO 异常: " + e.getMessage(), 502));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            callback.onError(new UpstreamCallException("上游流式调用被中断", 500));
        }
    }

    /** 流式回调契约 */
    public interface StreamCallback {

        /** 每个数据帧（content/reasoning_content/tool_calls 增量，终态帧也经此投递） */
        void onChunk(UpstreamChatResponse chunk);

        /** 正常结束（终态帧：含 finish_reason/usage；上游不回用量时可为 null） */
        void onDone(UpstreamChatResponse terminalChunk);

        /** 异常结束（连接/超时/协议错误） */
        void onError(Exception e);

        /** 外部取消标记（超时/客户端断连时置位） */
        boolean isCancelled();
    }

    // ==================== 内部构造 ====================

    /**
     * 请求骨架：POST {model.endpoint}，api_key 非空时携带 Bearer 鉴权
     *
     * 鉴权设计：上游凭证随模型行存储（t_model.api_key）而非全局配置 ——
     * 不同模型可指向不同厂商（GLM/DeepSeek/本地 Ollama 各持各的 Key，
     * 本地无鉴权上游留空即可），这是多厂商接入的前提。
     */
    private HttpRequest.Builder baseRequest(Map<String, Object> request, ModelInfo model) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(model.getEndpoint()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        JsonUtil.toJson(request), StandardCharsets.UTF_8));
        if (model.getApiKey() != null && !model.getApiKey().isBlank()) {
            builder.header("Authorization", "Bearer " + model.getApiKey().trim());
        }
        return builder;
    }

    /** 上游调用异常（携带近似 HTTP 状态便于日志分类） */
    public static class UpstreamCallException extends RuntimeException {

        private final int status;

        public UpstreamCallException(String message, int status) {
            super(message);
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}

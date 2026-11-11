package com.ai.gateway.router.controller;

import com.ai.gateway.common.dto.ChatRequest;
import com.ai.gateway.router.dto.ChatHeaders;
import com.ai.gateway.router.metrics.MetricsRegistry;
import com.ai.gateway.router.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OpenAI 兼容对话接口（FR-14：LangChain/LangGraph/OpenAI SDK 可直连）
 *
 * 路由：POST /v1/chat/completions（基线路径保留）
 *
 * 认证（access 模块 JwtAuthFilter 完成，此处只收结果）：
 * - Authorization: Bearer {JWT}（前端/平台用户）
 * - Authorization: Bearer sk-xxx（API Key 直连，agent 场景）
 * 两条路径都注入 X-User-Id，本控制器无感。
 *
 * 请求级路由约束头（1.md §7.1）：
 * - X-Cost-Budget / X-Latency-SLA / X-Agent-Task-Id（均可选）
 *
 * 响应：
 * - 非流式：application/json，OpenAI chat.completion 结构
 * - 流式：text/event-stream，OpenAI chat.completion.chunk 流 + [DONE]
 * - 附加响应头 X-Routing-Decision：路由摘要（manual/auto:模型编码[→fallback:备用编码]）
 */
@Tag(name = "OpenAI 兼容对话")
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final MetricsRegistry metricsRegistry;

    @Operation(summary = "对话补全（OpenAI 协议兼容，stream=true 走 SSE 流式）")
    @PostMapping(value = "/chat/completions")
    public Object chatCompletions(
            @Parameter(description = "用户 ID（网关过滤器注入，客户端勿传）")
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @Parameter(description = "请求级成本预算（元）")
            @RequestHeader(value = "X-Cost-Budget", required = false) String costBudget,
            @Parameter(description = "请求级延迟 SLA（毫秒）")
            @RequestHeader(value = "X-Latency-SLA", required = false) String latencySla,
            @Parameter(description = "Agent 任务归因 ID")
            @RequestHeader(value = "X-Agent-Task-Id", required = false) String agentTaskId,
            jakarta.servlet.http.HttpServletResponse response,
            @RequestBody ChatRequest request) {

        ChatHeaders headers = new ChatHeaders(costBudget, latencySla, agentTaskId);

        // 观测埋点（v2）：流式/非流式共用入口计数，出口路径各自归位
        metricsRegistry.beginCall();

        // 流式：SSE。SseEmitter 必须裸返回（Spring 异步专用返回值处理器）——
        // 包进 ResponseEntity 会落入消息转换器路径，报
        // "No converter for SseEmitter with preset Content-Type text/event-stream"；
        // 决策头改走 HttpServletResponse 原生 setHeader（异步首帧提交时一并生效）。
        if (Boolean.TRUE.equals(request.getStream())) {
            ChatService.StreamOutcome outcome;
            try {
                outcome = chatService.streamChat(request, headers, userId);
            } catch (RuntimeException e) {
                // streamChat 对校验/预扣失败同步抛出（emitter 尚未创建），
                // 终止回调无从注册，必须在此手动归位在途计数
                metricsRegistry.endCall();
                throw e;
            }
            response.setHeader("X-Routing-Decision", outcome.routingDecisionHeader());

            // SSE 生命周期与 HTTP 线程解耦：在途/活跃流只能靠 emitter 终止回调归位。
            // Spring 同一 emitter 的 onCompletion/onError/onTimeout 均支持追加多回调
            // （与 ChatService 内部回调共存），且 completeWithError 之后完成回调仍会执行，
            // 用 CAS 保证 endStream/endCall 恰好执行一次，避免重复扣减。
            metricsRegistry.beginStream();
            SseEmitter emitter = outcome.emitter();
            AtomicBoolean ended = new AtomicBoolean(false);
            Runnable endStream = () -> {
                if (ended.compareAndSet(false, true)) {
                    metricsRegistry.endStream();
                    metricsRegistry.endCall();
                }
            };
            emitter.onCompletion(endStream);
            emitter.onError(e -> endStream.run());
            emitter.onTimeout(endStream);
            return emitter;
        }

        // 非流式：OpenAI chat.completion JSON
        try {
            ChatService.ChatOutcome outcome = chatService.chat(request, headers, userId);
            Map<String, Object> body = outcome.body();
            return ResponseEntity.ok()
                    .header("X-Routing-Decision", outcome.routingDecisionHeader())
                    .body(body);
        } finally {
            metricsRegistry.endCall();
        }
    }
}

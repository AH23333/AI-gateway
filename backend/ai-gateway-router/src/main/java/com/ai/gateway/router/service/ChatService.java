package com.ai.gateway.router.service;

import com.ai.gateway.auth.service.ApiKeyService;
import com.ai.gateway.billing.dto.SettleContext;
import com.ai.gateway.billing.event.SettleEvent;
import com.ai.gateway.billing.service.QuotaService;
import com.ai.gateway.common.constant.BizCodeEnum;
import com.ai.gateway.common.dto.ChatRequest;
import com.ai.gateway.common.exception.BizException;
import com.ai.gateway.common.pojo.ChatMessage;
import com.ai.gateway.common.pojo.InvokeLogV2;
import com.ai.gateway.common.pojo.ModelInfo;
import com.ai.gateway.common.pojo.ModelPrice;
import com.ai.gateway.common.pojo.QuotaReservation;
import com.ai.gateway.common.pojo.RoutingConfig;
import com.ai.gateway.common.util.TokenEstimator;
import com.ai.gateway.common.util.TraceIdUtil;
import com.ai.gateway.router.client.OpenAiCompatClient;
import com.ai.gateway.router.config.RouterProperties;
import com.ai.gateway.router.dto.ChatHeaders;
import com.ai.gateway.router.dto.UpstreamChatResponse;
import com.ai.gateway.router.protocol.OpenAiProtocol;
import com.ai.gateway.router.util.CostCalculator;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 对话编排服务 —— /v1/chat/completions 主链路（v2 核心）
 *
 * 编排主线（每次调用完整走一遍，阻塞/流式共享前半段）：
 *
 *   校验 → API Key 前置 → 模型解析（手动指定 / 智能路由 / 默认）
 *       → 预扣 reserve → 上游调用（阻塞或 SSE 流式，含降级）
 *       → 结算 settle（单事务）→ 发布 SettleEvent（统计/日志旁路）
 *       → 应答翻译为 OpenAI 协议返回
 *
 * 流式实现要点（Java 21 虚拟线程 + SseEmitter）：
 * - 校验/路由/预扣在请求线程同步完成 —— 参数错误能在 HTTP 响应里
 *   返回正确的 OpenAI error JSON + 状态码（流一旦开始就无法再改状态码）；
 * - 上游消费在虚拟线程执行（每条流一个，阻塞读不占用平台线程）；
 * - 心跳：15s 注释帧保活（代理/网关不断连）；
 * - 看门狗：60s 无上游数据 → 主动中断（防挂死连接占资源）；
 * - 取消：SseEmitter 超时/客户端断连 → 关闭上游 InputStream，
 *   阻塞读立即抛错返回 —— 唯一可靠的打断手段；
 * - 降级：首帧前失败 → 无缝切备用模型；首帧后失败 → 不可切换，错误帧收尾。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    // ==================== 校验常量 ====================
    private static final int MAX_MESSAGES = 30;
    private static final int MAX_CONTENT_CHARS = 8_000;
    private static final Set<String> VALID_ROLES = Set.of("user", "assistant", "system", "tool");

    private final ApiKeyService apiKeyService;
    private final QuotaService quotaService;
    private final ModelConfigService modelConfigService;
    private final RoutingEngine routingEngine;
    private final OpenAiCompatClient openAiCompatClient;
    private final PayloadStore payloadStore;
    private final ApplicationEventPublisher eventPublisher;
    private final RouterProperties properties;

    /** 流式执行器：每条流一个虚拟线程（Java 21，阻塞读不占平台线程） */
    private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /** 心跳/看门狗调度器（守护线程，不阻塞 JVM 退出） */
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "stream-watchdog");
        t.setDaemon(true);
        return t;
    });

    // ==================== 阻塞式对话 ====================

    /**
     * 非流式对话：返回 OpenAI chat.completion 完整 JSON
     */
    public ChatOutcome chat(ChatRequest request, ChatHeaders headers, Long userId) {
        validate(request);
        requireApiKey(userId);
        String traceId = TraceIdUtil.getOrCreate();
        ParsedHeaders h = parseHeaders(headers);

        long estInput = TokenEstimator.estimateInput(request.getMessages());
        ResolvedModel resolved = resolveModel(request, estInput, h);
        long estTotal = TokenEstimator.estimateTotal(estInput);

        QuotaReservation reservation = quotaService.reserve(traceId, userId, estTotal);
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> upstreamReq = OpenAiProtocol.toUpstreamRequest(
                    request, resolved.model().getModelCode(), false);
            UpstreamChatResponse response;
            ModelInfo answeredModel = resolved.model();
            boolean fallbackUsed = false;
            try {
                response = openAiCompatClient.chat(upstreamReq, resolved.model());
            } catch (OpenAiCompatClient.UpstreamCallException e) {
                ModelInfo fallback = resolveFallback(resolved.model());
                if (fallback == null) {
                    throw e;
                }
                log.warn("模型 {} 调用失败, 降级 {}: {}", resolved.model().getModelCode(),
                        fallback.getModelCode(), e.getMessage());
                response = openAiCompatClient.chat(OpenAiProtocol.toUpstreamRequest(
                        request, fallback.getModelCode(), false), fallback);
                answeredModel = fallback;
                fallbackUsed = true;
            }
            long latency = System.currentTimeMillis() - start;

            Map<String, Object> body = finishBlocking(traceId, userId, reservation, request,
                    resolved, answeredModel, response, h, latency, fallbackUsed, estInput);
            String decisionHeader = buildDecisionHeader(resolved, answeredModel, fallbackUsed);
            return new ChatOutcome(body, decisionHeader);
        } catch (OpenAiCompatClient.UpstreamCallException e) {
            // 全部尝试失败：关闭预扣（成功结算 actual=0，释放预扣水位），记录失败日志
            long latency = System.currentTimeMillis() - start;
            settleFailure(traceId, userId, reservation, resolved.model().getModelCode(), h,
                    latency, buildChatMessages(traceId, userId, request, null));
            throw BizException.of(BizCodeEnum.MODEL_CALL_FAILED, e.getMessage());
        }
    }

    /** 阻塞式成功收尾：用量/计价 → 采样 → 结算 → 事件 → OpenAI 协议应答 */
    private Map<String, Object> finishBlocking(String traceId, Long userId,
                                               QuotaReservation reservation, ChatRequest request,
                                               ResolvedModel resolved, ModelInfo answeredModel,
                                               UpstreamChatResponse response, ParsedHeaders h,
                                               long latency, boolean fallbackUsed, long estInput) {
        UpstreamChatResponse.Choice choice = response.firstChoice();
        UpstreamChatResponse.Message msg = choice == null ? null : choice.getMessage();
        String content = msg == null || msg.getContent() == null ? "" : msg.getContent();
        List<Map<String, Object>> toolCalls =
                OpenAiProtocol.mapUpstreamToolCalls(msg == null ? null : msg.getToolCalls());
        boolean hasToolCalls = toolCalls != null;
        String finishReason = OpenAiProtocol.mapFinishReason(
                choice == null ? null : choice.getFinishReason(), hasToolCalls);

        // 用量：上游计数优先，缺失回退估算（OpenAI 流式/部分兼容上游不返回计数）
        long promptTokens = response.usagePromptTokens() != null
                ? response.usagePromptTokens() : estInput;
        long completionTokens = response.usageCompletionTokens() != null
                ? response.usageCompletionTokens() : TokenEstimator.estimateText(content);
        ModelPrice price = modelConfigService.currentPrice(answeredModel.getModelCode());
        if (price == null) {
            log.warn("模型未配置生效价格, 本次按 0 元计费: {}", answeredModel.getModelCode());
        }
        BigDecimal cost = CostCalculator.cost(price, promptTokens, completionTokens);

        String payloadRef = payloadStore.store(traceId, request,
                Map.of("content", content, "finishReason", finishReason));
        List<ChatMessage> chatMessages = buildChatMessages(traceId, userId, request, content);

        SettleContext ctx = SettleContext.builder()
                .reservationId(reservation.getId())
                .userId(userId)
                .traceId(traceId)
                .modelCode(answeredModel.getModelCode())
                .agentTaskId(h.agentTaskId())
                .success(true)
                .fallbackUsed(fallbackUsed)
                .interrupted(false)
                .routingDecision(resolved.routingDecision())
                .payloadRef(payloadRef)
                .priceVersion(price == null ? null : price.getVersion())
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .totalTokens(promptTokens + completionTokens)
                .costAmount(cost)
                .latencyMs(latency)
                .ttftMs(null)
                .chatMessages(chatMessages)
                .build();
        InvokeLogV2 logEntity = quotaService.settle(ctx);
        publishSettleEvent(logEntity, ctx, qualityProxy(true, false, content));
        return OpenAiProtocol.chatCompletion(traceId, answeredModel.getModelCode(),
                content, toolCalls, finishReason, promptTokens, completionTokens);
    }

    // ==================== 流式对话 ====================

    /**
     * 流式对话：返回 SseEmitter（OpenAI chat.completion.chunk 流 + [DONE]）
     *
     * 同步段（请求线程）：校验/路由/预扣 —— 错误以 OpenAI error JSON + 状态码返回；
     * 异步段（虚拟线程）：上游消费 + 分片下发 + 心跳看门狗 + 结算。
     */
    public StreamOutcome streamChat(ChatRequest request, ChatHeaders headers, Long userId) {
        validate(request);
        requireApiKey(userId);
        String traceId = TraceIdUtil.getOrCreate();
        ParsedHeaders h = parseHeaders(headers);

        long estInput = TokenEstimator.estimateInput(request.getMessages());
        ResolvedModel resolved = resolveModel(request, estInput, h);
        long estTotal = TokenEstimator.estimateTotal(estInput);
        QuotaReservation reservation = quotaService.reserve(traceId, userId, estTotal);

        SseEmitter emitter = new SseEmitter(properties.getStreamTimeoutMs());
        StreamContext ctx = new StreamContext(traceId, userId, request, h, resolved,
                reservation, estInput, emitter);
        emitter.onTimeout(() -> {
            log.warn("SSE 总超时({}ms), 中断: traceId={}", properties.getStreamTimeoutMs(), traceId);
            ctx.cancel();
        });
        emitter.onError(t -> {
            // 客户端断连等异步错误：标记取消，让消费循环退出
            log.info("SSE 异步错误({}): traceId={}", t.getMessage(), traceId);
            ctx.cancel();
        });

        streamExecutor.submit(() -> runStream(ctx));
        String decisionHeader = buildDecisionHeader(resolved, ctx.answeredModel, ctx.fallbackUsed);
        return new StreamOutcome(emitter, decisionHeader);
    }

    /** 流式主循环（虚拟线程内） */
    private void runStream(StreamContext ctx) {
        ScheduledFuture<?> watchdog = scheduler.scheduleAtFixedRate(
                () -> watchdogTick(ctx),
                properties.getHeartbeatIntervalMs(), properties.getHeartbeatIntervalMs(),
                TimeUnit.MILLISECONDS);
        try {
            attemptStream(ctx, ctx.resolved.model());
            // 首帧前失败 → 无缝降级备用模型（客户端尚未收到任何内容）
            if (ctx.error != null && !ctx.firstChunkSent) {
                ModelInfo fallback = resolveFallback(ctx.resolved.model());
                if (fallback != null) {
                    log.warn("流式模型 {} 失败, 降级 {}: {}", ctx.resolved.model().getModelCode(),
                            fallback.getModelCode(), ctx.error.getMessage());
                    ctx.error = null;
                    ctx.finalChunk = null;
                    ctx.bodyRef = new AtomicReference<>();
                    attemptStream(ctx, fallback);
                    if (ctx.finalChunk != null) {
                        ctx.answeredModel = fallback;
                        ctx.fallbackUsed = true;
                    }
                }
            }
            settleStream(ctx);
        } catch (Exception e) {
            // 兜底：任何未预期异常也要保证预扣被关闭 + 发射器终结
            log.error("流式主循环异常: traceId={}", ctx.traceId, e);
            ctx.error = e;
            try {
                quotaService.settle(failureContext(ctx, 0, 0));
            } catch (Exception settleEx) {
                log.error("流式兜底结算失败(等待对账回滚): traceId={}", ctx.traceId, settleEx);
            }
            completeQuietly(ctx);
        } finally {
            watchdog.cancel(false);
        }
    }

    /** 消费一次上游流（回调式逐帧处理，OpenAiCompatClient 阻塞直至结束/取消） */
    private void attemptStream(StreamContext ctx, ModelInfo target) {
        Map<String, Object> upstreamReq = OpenAiProtocol.toUpstreamRequest(
                ctx.request, target.getModelCode(), true);
        openAiCompatClient.streamChat(upstreamReq, target, new OpenAiCompatClient.StreamCallback() {

            @Override
            public void onChunk(UpstreamChatResponse chunk) {
                ctx.lastActivity.set(System.currentTimeMillis());
                if (ctx.cancelled.get()) {
                    return;
                }
                UpstreamChatResponse.Choice choice = chunk.firstChoice();
                if (choice == null) {
                    return;
                }
                UpstreamChatResponse.Delta delta = choice.getDelta();
                if (delta == null) {
                    return;
                }
                String reasoning = delta.getReasoningContent();
                String piece = delta.getContent();
                boolean hasToolCalls = delta.getToolCalls() != null && delta.getToolCalls().isArray()
                        && !delta.getToolCalls().isEmpty();
                boolean nothing = (reasoning == null || reasoning.isEmpty())
                        && (piece == null || piece.isEmpty()) && !hasToolCalls;
                if (nothing) {
                    return;
                }
                try {
                    if (!ctx.firstChunkSent) {
                        ctx.firstChunkSent = true;
                        ctx.ttftMs = System.currentTimeMillis() - ctx.startMs;
                        ctx.send(OpenAiProtocol.roleChunk(ctx.traceId, target.getModelCode()));
                    }
                    // 思考增量：透传不入库（不入 content / 会话存储 / 计费）
                    if (reasoning != null && !reasoning.isEmpty()) {
                        ctx.send(OpenAiProtocol.reasoningChunk(ctx.traceId, target.getModelCode(), reasoning));
                    }
                    if (piece != null && !piece.isEmpty()) {
                        ctx.content.append(piece);
                        ctx.send(OpenAiProtocol.contentChunk(ctx.traceId, target.getModelCode(), piece));
                    }
                    if (hasToolCalls) {
                        ctx.toolCalls = delta.getToolCalls();
                        ctx.send(OpenAiProtocol.toolCallsChunk(ctx.traceId, target.getModelCode(),
                                OpenAiProtocol.mapUpstreamToolCalls(delta.getToolCalls())));
                    }
                } catch (IOException | IllegalStateException e) {
                    // 客户端已断开 / 发射器已关闭：视为取消，停止消费
                    log.info("SSE 发送失败, 视为客户端断连: traceId={}, err={}", ctx.traceId, e.getMessage());
                    ctx.cancelled.set(true);
                }
            }

            @Override
            public void onDone(UpstreamChatResponse terminalChunk) {
                ctx.finalChunk = terminalChunk;
            }

            @Override
            public void onError(Exception e) {
                ctx.error = e;
            }

            @Override
            public boolean isCancelled() {
                return ctx.cancelled.get();
            }
        }, ctx.bodyRef);
    }

    /** 心跳 + 空闲看门狗（单 tick 逻辑，异常不外抛） */
    private void watchdogTick(StreamContext ctx) {
        if (ctx.completed.get() || ctx.cancelled.get()) {
            return;
        }
        try {
            long idle = System.currentTimeMillis() - ctx.lastActivity.get();
            if (idle > properties.getStreamIdleTimeoutMs()) {
                log.warn("流式空闲 {}ms 超阈值 {}ms, 主动中断: traceId={}",
                        idle, properties.getStreamIdleTimeoutMs(), ctx.traceId);
                ctx.cancel();
                return;
            }
            ctx.emitter.send(SseEmitter.event().comment("keep-alive"));
        } catch (Exception e) {
            // 发射器已关闭（完成/超时/断连）：心跳停止，无需处理
        }
    }

    /** 流式结算：区分 成功 / 中断 / 失败 三态，保证预扣必被关闭 */
    private void settleStream(StreamContext ctx) {
        boolean success = ctx.finalChunk != null && ctx.error == null;
        boolean interrupted = ctx.cancelled.get();
        if (success) {
            String content = ctx.content.toString();
            List<Map<String, Object>> toolCalls = OpenAiProtocol.mapUpstreamToolCalls(ctx.toolCalls);
            String finishReason = OpenAiProtocol.mapFinishReason(
                    ctx.finalChunk.firstFinishReason(), toolCalls != null);
            // 用量：上游终态帧携带则用之（GLM/DeepSeek 均回传），否则估算兜底
            long promptTokens = ctx.finalChunk.usagePromptTokens() != null
                    ? ctx.finalChunk.usagePromptTokens() : ctx.estInput;
            long completionTokens = ctx.finalChunk.usageCompletionTokens() != null
                    ? ctx.finalChunk.usageCompletionTokens() : TokenEstimator.estimateText(content);
            ModelPrice price = modelConfigService.currentPrice(ctx.answeredModel.getModelCode());
            if (price == null) {
                log.warn("模型未配置生效价格, 本次按 0 元计费: {}", ctx.answeredModel.getModelCode());
            }
            BigDecimal cost = CostCalculator.cost(price, promptTokens, completionTokens);
            String payloadRef = payloadStore.store(ctx.traceId, ctx.request,
                    Map.of("content", content, "finishReason", finishReason));
            List<ChatMessage> chatMessages = buildChatMessages(ctx.traceId, ctx.userId, ctx.request, content);

            SettleContext settleCtx = SettleContext.builder()
                    .reservationId(ctx.reservation.getId())
                    .userId(ctx.userId)
                    .traceId(ctx.traceId)
                    .modelCode(ctx.answeredModel.getModelCode())
                    .agentTaskId(ctx.headers.agentTaskId())
                    .success(true)
                    .fallbackUsed(ctx.fallbackUsed)
                    .interrupted(interrupted)
                    .routingDecision(ctx.resolved.routingDecision())
                    .payloadRef(payloadRef)
                    .priceVersion(price == null ? null : price.getVersion())
                    .promptTokens(promptTokens)
                    .completionTokens(completionTokens)
                    .totalTokens(promptTokens + completionTokens)
                    .costAmount(cost)
                    .latencyMs(System.currentTimeMillis() - ctx.startMs)
                    .ttftMs(ctx.ttftMs)
                    .chatMessages(chatMessages)
                    .build();
            InvokeLogV2 logEntity = quotaService.settle(settleCtx);
            publishSettleEvent(logEntity, settleCtx, qualityProxy(true, interrupted, content));

            // 客户端仍在：发送收尾帧 + [DONE]
            if (!interrupted) {
                try {
                    ctx.send(OpenAiProtocol.finishChunk(ctx.traceId, ctx.answeredModel.getModelCode(), finishReason));
                    ctx.send("[DONE]");
                    ctx.emitter.complete();
                    ctx.completed.set(true);
                } catch (IOException | IllegalStateException e) {
                    log.info("SSE 收尾帧发送失败(客户端已断连): traceId={}", ctx.traceId);
                    ctx.cancelled.set(true);
                }
            }
        } else {
            // 失败/中断：不收费（tokens=0/cost=0），但必须关闭预扣并留痕
            SettleContext settleCtx = failureContext(ctx, ctx.estInput,
                    TokenEstimator.estimateText(ctx.content.toString()));
            InvokeLogV2 logEntity = quotaService.settle(settleCtx);
            publishSettleEvent(logEntity, settleCtx,
                    qualityProxy(false, interrupted, ctx.content.toString()));
            if (!interrupted && ctx.error != null) {
                // 首帧前失败且无降级可用：错误帧 + [DONE] 收尾（OpenAI 语义外扩展，见 README）
                try {
                    ctx.send(OpenAiProtocol.errorEvent(
                            "流式生成失败: " + ctx.error.getMessage(), "stream_error"));
                    ctx.send("[DONE]");
                    ctx.emitter.complete();
                    ctx.completed.set(true);
                } catch (IOException | IllegalStateException ignore) {
                    ctx.cancelled.set(true);
                }
            } else {
                completeQuietly(ctx);
            }
            log.warn("流式失败收尾: traceId={}, interrupted={}, error={}",
                    ctx.traceId, interrupted, ctx.error == null ? "无" : ctx.error.getMessage());
        }
    }

    /** 失败结算上下文（tokens/cost 全 0：失败不收费，预扣全量释放） */
    private SettleContext failureContext(StreamContext ctx, long promptTokens, long completionTokens) {
        return SettleContext.builder()
                .reservationId(ctx.reservation.getId())
                .userId(ctx.userId)
                .traceId(ctx.traceId)
                .modelCode(ctx.resolved.model().getModelCode())
                .agentTaskId(ctx.headers.agentTaskId())
                .success(false)
                .fallbackUsed(ctx.fallbackUsed)
                .interrupted(ctx.cancelled.get())
                .routingDecision(ctx.resolved.routingDecision())
                .payloadRef(null)
                .priceVersion(null)
                .promptTokens(0)
                .completionTokens(0)
                .totalTokens(0)
                .costAmount(BigDecimal.ZERO)
                .latencyMs(System.currentTimeMillis() - ctx.startMs)
                .ttftMs(ctx.ttftMs)
                .chatMessages(buildChatMessages(ctx.traceId, ctx.userId, ctx.request, null))
                .build();
    }

    /** 阻塞式失败结算（复用同一套语义） */
    private void settleFailure(String traceId, Long userId, QuotaReservation reservation,
                               String modelCode, ParsedHeaders h, long latency,
                               List<ChatMessage> chatMessages) {
        try {
            SettleContext ctx = SettleContext.builder()
                    .reservationId(reservation.getId())
                    .userId(userId)
                    .traceId(traceId)
                    .modelCode(modelCode)
                    .agentTaskId(h.agentTaskId())
                    .success(false)
                    .fallbackUsed(false)
                    .interrupted(false)
                    .routingDecision(null)
                    .promptTokens(0)
                    .completionTokens(0)
                    .totalTokens(0)
                    .costAmount(BigDecimal.ZERO)
                    .latencyMs(latency)
                    .ttftMs(null)
                    .chatMessages(chatMessages)
                    .build();
            InvokeLogV2 logEntity = quotaService.settle(ctx);
            publishSettleEvent(logEntity, ctx, qualityProxy(false, false, null));
        } catch (Exception e) {
            log.error("失败结算异常(等待对账回滚): traceId={}", traceId, e);
        }
    }

    // ==================== 模型解析 / 降级 ====================

    /** 模型解析结果：目标模型 + 路由决策 JSON + 模式（manual/auto/default） */
    private record ResolvedModel(ModelInfo model, String routingDecision, String mode) {
    }

    /**
     * 模型解析（1.md §7.1 契约）：
     * - model 为具体编码   → 手动指定（manual）；
     * - model = "auto"     → 强制智能路由（auto），无视路由开关（SDK 无法感知开关时的逃生舱）；
     * - model 缺省         → 路由开启走智能路由，关闭走第一个启用模型兜底（default）。
     */
    private ResolvedModel resolveModel(ChatRequest request, long estInput, ParsedHeaders h) {
        String requested = request.getModel() == null ? null : request.getModel().trim();
        boolean forcedAuto = "auto".equalsIgnoreCase(requested);
        if (requested != null && !requested.isBlank() && !forcedAuto) {
            return new ResolvedModel(
                    modelConfigService.getEnabledModel(requested), null, "manual");
        }
        RoutingConfig config = modelConfigService.getRoutingConfig();
        if (forcedAuto || (config.getEnabled() != null && config.getEnabled() == 1)) {
            RoutingEngine.RouteResult route = routingEngine.select(
                    request, estInput, h.costBudget(), h.latencySlaMs());
            return new ResolvedModel(route.getSelected(), route.getDecisionJson(), "auto");
        }
        // 路由关闭且未指定模型：第一个启用模型兜底（基线"默认模型"行为）
        List<ModelInfo> models = modelConfigService.listEnabledModels();
        if (models.isEmpty()) {
            throw BizException.of(BizCodeEnum.ROUTE_NO_CANDIDATE);
        }
        return new ResolvedModel(models.get(0), null, "default");
    }

    /** 降级模型解析：未配置/指向自己/不可用 → null（调用方走失败路径） */
    private ModelInfo resolveFallback(ModelInfo model) {
        if (model.getFallbackCode() == null || model.getFallbackCode().isBlank()
                || model.getFallbackCode().equals(model.getModelCode())) {
            return null;
        }
        try {
            return modelConfigService.getEnabledModel(model.getFallbackCode());
        } catch (BizException e) {
            log.warn("备用模型不可用({}): {}", model.getFallbackCode(), e.getMessage());
            return null;
        }
    }

    /**
     * 响应头 X-Routing-Decision 摘要：{mode}:{model} 或 {mode}:{model}->fallback:{fallback}
     * 注意：HTTP 头只允许 ASCII，箭头必须用 "->"（"→" 会被 Tomcat 静默截断成乱码）。
     */
    private String buildDecisionHeader(ResolvedModel resolved, ModelInfo answered, boolean fallbackUsed) {
        String base = resolved.mode() + ":" + resolved.model().getModelCode();
        if (fallbackUsed && answered != null) {
            return base + "->fallback:" + answered.getModelCode();
        }
        return base;
    }

    // ==================== 校验 / 头解析 ====================

    /** 请求校验（基线规则保留 + FR-14 扩展 tool 角色/工具调用消息） */
    private void validate(ChatRequest request) {
        if (request == null || request.getMessages() == null || request.getMessages().isEmpty()) {
            throw BizException.of(BizCodeEnum.MESSAGES_REQUIRED);
        }
        if (request.getMessages().size() > MAX_MESSAGES) {
            throw BizException.of(BizCodeEnum.TOO_MANY_MESSAGES, MAX_MESSAGES);
        }
        for (ChatRequest.Message msg : request.getMessages()) {
            if (msg.getRole() == null || !VALID_ROLES.contains(msg.getRole())) {
                throw BizException.of(BizCodeEnum.INVALID_ROLE, String.valueOf(msg.getRole()));
            }
            boolean hasToolCalls = msg.getToolCalls() != null && msg.getToolCalls().isArray()
                    && !msg.getToolCalls().isEmpty();
            if (msg.getContent() == null || msg.getContent().isBlank()) {
                // assistant 历史可只含 tool_calls；其余角色内容必填
                boolean assistantWithToolCalls = "assistant".equals(msg.getRole()) && hasToolCalls;
                if (!assistantWithToolCalls) {
                    throw BizException.of(BizCodeEnum.MESSAGE_CONTENT_REQUIRED);
                }
            }
            if (msg.getContent() != null && msg.getContent().length() > MAX_CONTENT_CHARS) {
                throw BizException.of(BizCodeEnum.MESSAGE_TOO_LONG, MAX_CONTENT_CHARS);
            }
        }
    }

    /** API Key 前置校验（基线规则保留：未建 Key 先建 Key） */
    private void requireApiKey(Long userId) {
        if (userId == null) {
            throw BizException.of(BizCodeEnum.USER_CONTEXT_MISSING);
        }
        if (!apiKeyService.hasActiveApiKey(userId)) {
            throw BizException.of(BizCodeEnum.API_KEY_REQUIRED_FOR_CHAT);
        }
    }

    /** 解析后的请求头 */
    private record ParsedHeaders(BigDecimal costBudget, Long latencySlaMs, String agentTaskId) {
    }

    /**
     * 头解析（OQ-10：非法值忽略 + 告警，不拒绝请求）
     */
    private ParsedHeaders parseHeaders(ChatHeaders headers) {
        BigDecimal budget = null;
        if (headers.costBudget() != null && !headers.costBudget().isBlank()) {
            try {
                BigDecimal parsed = new BigDecimal(headers.costBudget().trim());
                if (parsed.signum() > 0) {
                    budget = parsed;
                } else {
                    log.warn("X-Cost-Budget 非法({}), 忽略预算约束", headers.costBudget());
                }
            } catch (NumberFormatException e) {
                log.warn("X-Cost-Budget 非法({}), 忽略预算约束", headers.costBudget());
            }
        }
        Long sla = null;
        if (headers.latencySlaMs() != null && !headers.latencySlaMs().isBlank()) {
            try {
                long parsed = Long.parseLong(headers.latencySlaMs().trim());
                if (parsed > 0) {
                    sla = parsed;
                } else {
                    log.warn("X-Latency-SLA 非法({}), 忽略 SLA 约束", headers.latencySlaMs());
                }
            } catch (NumberFormatException e) {
                log.warn("X-Latency-SLA 非法({}), 忽略 SLA 约束", headers.latencySlaMs());
            }
        }
        String agentTaskId = TraceIdUtil.sanitize(headers.agentTaskId());
        return new ParsedHeaders(budget, sla, agentTaskId);
    }

    // ==================== 公共辅助 ====================

    /** D5 会话内容快照：逐条输入消息 + assistant 应答（失败调用无应答行） */
    private List<ChatMessage> buildChatMessages(String traceId, Long userId,
                                                ChatRequest request, String assistantContent) {
        List<ChatMessage> result = new ArrayList<>();
        if (request.getMessages() != null) {
            for (ChatRequest.Message msg : request.getMessages()) {
                ChatMessage cm = new ChatMessage();
                cm.setTraceId(traceId);
                cm.setUserId(userId);
                cm.setRole(msg.getRole());
                cm.setContent(msg.getContent() == null ? "" : msg.getContent());
                result.add(cm);
            }
        }
        if (assistantContent != null) {
            ChatMessage am = new ChatMessage();
            am.setTraceId(traceId);
            am.setUserId(userId);
            am.setRole("assistant");
            am.setContent(assistantContent);
            result.add(am);
        }
        return result;
    }

    /**
     * 质量代理分（OQ-03 启发式，[0,100]）
     *
     * 口径：完成且有内容按长度分级（60/75/85）；失败/中断 30；空响应 40。
     * 升级路径（选型书第八章）：接入用户反馈（点赞/点踩）或 LLM-as-judge
     * 评估后，本方法是唯一需要替换的质量打分入口。
     */
    private Integer qualityProxy(boolean success, boolean interrupted, String content) {
        if (!success || interrupted) {
            return 30;
        }
        if (content == null || content.isBlank()) {
            return 40;
        }
        int len = content.length();
        if (len < 50) {
            return 60;
        }
        if (len < 500) {
            return 75;
        }
        return 85;
    }

    /** 结算事件发布（幂等命中 logEntity=null 时跳过） */
    private void publishSettleEvent(InvokeLogV2 logEntity, SettleContext ctx, Integer qualityScore) {
        if (logEntity == null) {
            return;
        }
        eventPublisher.publishEvent(new SettleEvent(logEntity, ctx.getChatMessages(), qualityScore));
    }

    /** 阻塞式应答包装（body + 路由摘要头） */
    public record ChatOutcome(Map<String, Object> body, String routingDecisionHeader) {
    }

    /** 流式应答包装（emitter + 路由摘要头） */
    public record StreamOutcome(SseEmitter emitter, String routingDecisionHeader) {
    }

    private void completeQuietly(StreamContext ctx) {
        try {
            ctx.emitter.complete();
            ctx.completed.set(true);
        } catch (Exception ignore) {
            // 已完成/已超时：无需处理
        }
    }

    /** 停机：看门狗立即停，在途流给 10s 收尾后强制中断 */
    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
        streamExecutor.shutdown();
        try {
            if (!streamExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                streamExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            streamExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 流式上下文：一次流式调用的全部可变状态（单虚拟线程写，看门狗线程只读原子量）
     */
    private static final class StreamContext {

        final String traceId;
        final Long userId;
        final ChatRequest request;
        final ParsedHeaders headers;
        final ResolvedModel resolved;
        final QuotaReservation reservation;
        final long estInput;
        final SseEmitter emitter;
        final long startMs = System.currentTimeMillis();

        /** 取消标记（超时/断连/看门狗中断共用） */
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        /** 发射器已终结（心跳停止依据） */
        final AtomicBoolean completed = new AtomicBoolean(false);
        /** 最近一次上游活动时间（空闲看门狗依据） */
        final AtomicLong lastActivity = new AtomicLong(System.currentTimeMillis());
        /** 上游响应体引用（取消句柄：外部 close 打断阻塞读） */
        volatile AtomicReference<InputStream> bodyRef = new AtomicReference<>();

        // ---- 以下状态仅消费线程写 ----
        final StringBuilder content = new StringBuilder();
        boolean firstChunkSent = false;
        Long ttftMs = null;
        com.fasterxml.jackson.databind.JsonNode toolCalls = null;
        UpstreamChatResponse finalChunk = null;
        Exception error = null;
        ModelInfo answeredModel;
        boolean fallbackUsed = false;

        StreamContext(String traceId, Long userId, ChatRequest request, ParsedHeaders headers,
                      ResolvedModel resolved, QuotaReservation reservation, long estInput,
                      SseEmitter emitter) {
            this.traceId = traceId;
            this.userId = userId;
            this.request = request;
            this.headers = headers;
            this.resolved = resolved;
            this.reservation = reservation;
            this.estInput = estInput;
            this.emitter = emitter;
            this.answeredModel = resolved.model();
        }

        /** 取消：置标记 + 关闭上游响应体（阻塞读立即抛错返回） */
        void cancel() {
            cancelled.set(true);
            InputStream in = bodyRef.get();
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignore) {
                    // 关闭失败不影响取消语义
                }
            }
        }

        /** 发送一帧 JSON（data 前缀） */
        void send(Object openAiFrame) throws IOException {
            emitter.send(SseEmitter.event().data(com.ai.gateway.common.util.JsonUtil.toJson(openAiFrame)));
        }

        /** 发送裸帧（[DONE]） */
        void send(String raw) throws IOException {
            emitter.send(SseEmitter.event().data(raw));
        }
    }
}

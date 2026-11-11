package com.ai.gateway.billing.event;

import com.ai.gateway.common.pojo.ChatMessage;
import com.ai.gateway.common.pojo.InvokeLogV2;

import java.util.List;

/**
 * 结算完成事件（进程内异步旁路的统一挂点，v2 新增）
 *
 * 发布时机：QuotaService.settle 事务提交之后（由 router 的 ChatService
 * 在拿到结算结果后发布）—— 监听器拿到的日志实体必然已持久化。
 *
 * 监听方（均为弱一致旁路，失败不影响账务）：
 * - billing.ChatLogListener   → D5 会话消息 + 日汇总入批量写队列；
 * - router.RouteStatsListener → Redis 路由统计窗口/健康熔断更新。
 *
 * 为什么不用 @TransactionalEventListener(AFTER_COMMIT)？
 * 事件在事务提交后才发布，监听器立即执行即可；
 * AFTER_COMMIT 主要解决"事务内发布、提交前消费"的时序问题，
 * 本项目发布点已在提交后，普通 @EventListener 语义最简。
 *
 * qualityScore：质量代理分（OQ-03，router 按启发式计算），
 * 由事件显式携带而不是让路由统计回读模型内容 —— 域间无回读耦合。
 */
public record SettleEvent(
        /** 已持久化的调用日志实体（含自增 id） */
        InvokeLogV2 log,
        /** D5 会话内容快照（已随结算写入计划，此处供批量层异步落库） */
        List<ChatMessage> chatMessages,
        /** 质量代理分 [0,100]（OQ-03 启发式，可空） */
        Integer qualityScore) {
}

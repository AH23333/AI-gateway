package com.ai.gateway.billing.listener;

import com.ai.gateway.billing.event.SettleEvent;
import com.ai.gateway.billing.writer.BatchLogWriter;
import com.ai.gateway.common.pojo.BillingDaily;
import com.ai.gateway.common.pojo.ChatMessage;
import com.ai.gateway.common.pojo.InvokeLogV2;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 会话日志监听器 —— SettleEvent → 批量写队列（D5/D 汇总旁路）
 *
 * 职责（全部弱一致，失败不触碰账务）：
 * 1. D5 会话内容入队：settle 上下文里的消息快照补齐 traceId/userId 后
 *    交给 BatchLogWriter 攒批写 t_chat_message；
 * 2. 日汇总增量入队：仅成功调用计入计费汇总（失败调用成本为 0，
 *    其明细仍在 t_invoke_log_v2 可查）。
 *
 * statDate 取监听时刻（结算后毫秒级），午夜边界误差可忽略；
 * 若未来要求严格对齐调用时刻，可由 SettleEvent 携带结算时间。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatLogListener {

    private final BatchLogWriter batchLogWriter;

    @EventListener
    public void onSettle(SettleEvent event) {
        InvokeLogV2 invokeLog = event.log();

        // 1. D5 会话内容 → 批量队列
        if (event.chatMessages() != null && !event.chatMessages().isEmpty()) {
            for (ChatMessage message : event.chatMessages()) {
                if (message.getTraceId() == null) {
                    message.setTraceId(invokeLog.getTraceId());
                }
                if (message.getUserId() == null) {
                    message.setUserId(invokeLog.getUserId());
                }
                if (!batchLogWriter.enqueueMessage(message)) {
                    log.warn("会话消息队列已满, 丢弃: traceId={}, role={}",
                            invokeLog.getTraceId(), message.getRole());
                }
            }
        }

        // 2. 日汇总增量（仅成功调用）
        if (invokeLog.getSuccess() != null && invokeLog.getSuccess() == 1) {
            BillingDaily delta = new BillingDaily();
            delta.setUserId(invokeLog.getUserId());
            delta.setStatDate(LocalDate.now());
            delta.setModelCode(invokeLog.getModelCode());
            delta.setCallCount(1);
            delta.setTotalPromptTokens(nvl(invokeLog.getPromptTokens()));
            delta.setTotalCompletionTokens(nvl(invokeLog.getCompletionTokens()));
            delta.setTotalTokens(nvl(invokeLog.getTotalTokens()));
            delta.setTotalCost(invokeLog.getCostAmount() == null ? BigDecimal.ZERO : invokeLog.getCostAmount());
            if (!batchLogWriter.enqueueBilling(delta)) {
                log.warn("日汇总队列已满, 丢弃增量: userId={}, model={}",
                        invokeLog.getUserId(), invokeLog.getModelCode());
            }
        }
    }

    private long nvl(Long value) {
        return value == null ? 0L : value;
    }

    private long nvl(Integer value) {
        return value == null ? 0L : value;
    }
}

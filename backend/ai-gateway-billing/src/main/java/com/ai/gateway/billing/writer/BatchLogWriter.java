package com.ai.gateway.billing.writer;

import com.ai.gateway.billing.mapper.BillingDailyMapper;
import com.ai.gateway.billing.mapper.ChatMessageMapper;
import com.ai.gateway.common.pojo.BillingDaily;
import com.ai.gateway.common.pojo.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 批量日志写入器 —— 内存队列 → 攒批写库（v2 新增，选型书 3.3/3.5）
 *
 * 为什么需要它？
 * settle 是同步强一致路径，日志明细（D5 会话消息、日汇总）若也同步写，
 * 高频对话时 t_chat_message/t_billing_daily 的行锁会成为链路瓶颈；
 * 改为"内存队列 + 500ms/500 条攒批"后，写库次数下降 1~2 个数量级。
 *
 * 可靠性边界（有界取舍，选型书 3.5）：
 * - 队列有界（2×10000）：拒绝服务等极端场景丢明细日志但保住 JVM；
 * - 进程崩溃丢队列残留：账务（t_user/t_invoke_log_v2）不受影响，
 *   明细可从窄表重建 —— 日志属于"允许最终一致"的旁路；
 * - 写库失败重试一轮后丢弃并记日志，防止毒消息堵塞队列。
 *
 * 停机语义：@PreDestroy 兜底 flush，正常停机不丢尾批。
 */
@Slf4j
@Component
public class BatchLogWriter {

    /** 队列容量：会话消息 + 日汇总各 1 万条 */
    private static final int QUEUE_CAPACITY = 10_000;

    /** 单批最大条数（SQL 参数量控制在安全范围） */
    private static final int BATCH_SIZE = 500;

    /** 写库失败后的重试轮数 */
    private static final int MAX_RETRY_ROUNDS = 1;

    private final LinkedBlockingQueue<ChatMessage> messageQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final LinkedBlockingQueue<BillingDaily> billingQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);

    private final ChatMessageMapper chatMessageMapper;
    private final BillingDailyMapper billingDailyMapper;

    /** 攒批窗口（毫秒），与 flush 定时周期一致 */
    @Value("${router.log-flush-interval-ms:500}")
    private long flushIntervalMs;

    public BatchLogWriter(ChatMessageMapper chatMessageMapper, BillingDailyMapper billingDailyMapper,
                          MeterRegistry meterRegistry) {
        this.chatMessageMapper = chatMessageMapper;
        this.billingDailyMapper = billingDailyMapper;
        // Prometheus 桥：队列水位以标准 gauge 暴露（告警参考阈值：>80% 容量）
        Gauge.builder("gateway.batch.message.queue.size", messageQueue, LinkedBlockingQueue::size)
                .description("会话消息批量写队列水位")
                .register(meterRegistry);
        Gauge.builder("gateway.batch.billing.queue.size", billingQueue, LinkedBlockingQueue::size)
                .description("日汇总批量写队列水位")
                .register(meterRegistry);
    }

    /** 会话消息入队（offer 不阻塞：队列满时返回 false，由调用方记日志丢弃） */
    public boolean enqueueMessage(ChatMessage message) {
        return messageQueue.offer(message);
    }

    /** 日汇总增量入队 */
    public boolean enqueueBilling(BillingDaily delta) {
        return billingQueue.offer(delta);
    }

    // ==================== 观测域暴露（/admin/metrics/runtime 读取） ====================

    /** 会话消息队列当前水位 */
    public int messageQueueSize() {
        return messageQueue.size();
    }

    /** 日汇总队列当前水位 */
    public int billingQueueSize() {
        return billingQueue.size();
    }

    /** 有界队列容量（水位告警参考阈值：>80%） */
    public int queueCapacity() {
        return QUEUE_CAPACITY;
    }

    /** 攒批窗口毫秒数 */
    public long flushIntervalMs() {
        return flushIntervalMs;
    }

    /** 定时攒批落库：每 500ms 一轮（可在配置中调大） */
    @Scheduled(fixedDelayString = "${router.log-flush-interval-ms:500}")
    public void flush() {
        flushMessages();
        flushBilling();
    }

    /** 停机前把队列残留全部落库 */
    @PreDestroy
    public void shutdown() {
        flush();
        log.info("批量日志写入器停机 flush 完成: 残留消息={}, 残留汇总={}", messageQueue.size(), billingQueue.size());
    }

    private void flushMessages() {
        if (messageQueue.isEmpty()) {
            return;
        }
        List<ChatMessage> batch = new ArrayList<>(BATCH_SIZE);
        messageQueue.drainTo(batch, BATCH_SIZE);
        int dropped = 0;
        while (!batch.isEmpty()) {
            boolean ok = false;
            for (int round = 0; round <= MAX_RETRY_ROUNDS && !ok; round++) {
                try {
                    chatMessageMapper.insertBatch(batch);
                    ok = true;
                } catch (Exception e) {
                    log.error("会话消息批量落库失败(round {}): size={}", round, batch.size(), e);
                }
            }
            if (!ok) {
                dropped += batch.size();
            }
            batch.clear();
            messageQueue.drainTo(batch, BATCH_SIZE);
        }
        if (dropped > 0) {
            log.error("会话消息批量落库最终失败, 丢弃 {} 条（账务不受影响, 可从 t_invoke_log_v2 审计）", dropped);
        }
    }

    private void flushBilling() {
        if (billingQueue.isEmpty()) {
            return;
        }
        // 同批内按 (userId, modelCode) 预合并：减少 upsert 冲突行数，且天然去重
        Map<String, BillingDaily> merged = new LinkedHashMap<>();
        List<BillingDaily> batch = new ArrayList<>(BATCH_SIZE);
        billingQueue.drainTo(batch, BATCH_SIZE);
        while (!batch.isEmpty()) {
            for (BillingDaily delta : batch) {
                String key = delta.getUserId() + ":" + delta.getModelCode();
                merged.merge(key, delta, this::mergeDelta);
            }
            batch.clear();
            billingQueue.drainTo(batch, BATCH_SIZE);
        }
        if (merged.isEmpty()) {
            return;
        }
        boolean ok = false;
        for (int round = 0; round <= MAX_RETRY_ROUNDS && !ok; round++) {
            try {
                billingDailyMapper.upsertBatch(new ArrayList<>(merged.values()));
                ok = true;
            } catch (Exception e) {
                log.error("日汇总批量落库失败(round {}): size={}", round, merged.size(), e);
            }
        }
        if (!ok) {
            log.error("日汇总批量落库最终失败, 丢弃 {} 条增量", merged.size());
        }
    }

    /** 同键增量合并（statDate 同为当天） */
    private BillingDaily mergeDelta(BillingDaily a, BillingDaily b) {
        a.setCallCount(a.getCallCount() + b.getCallCount());
        a.setTotalPromptTokens(nvl(a.getTotalPromptTokens()) + nvl(b.getTotalPromptTokens()));
        a.setTotalCompletionTokens(nvl(a.getTotalCompletionTokens()) + nvl(b.getTotalCompletionTokens()));
        a.setTotalTokens(nvl(a.getTotalTokens()) + nvl(b.getTotalTokens()));
        a.setTotalCost(a.getTotalCost().add(b.getTotalCost()));
        return a;
    }

    private long nvl(Long value) {
        return value == null ? 0L : value;
    }
}

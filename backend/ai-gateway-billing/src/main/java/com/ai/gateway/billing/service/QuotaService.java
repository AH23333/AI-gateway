package com.ai.gateway.billing.service;

import com.ai.gateway.billing.dto.SettleContext;
import com.ai.gateway.billing.mapper.InvokeLogV2Mapper;
import com.ai.gateway.billing.mapper.QuotaReservationMapper;
import com.ai.gateway.billing.mapper.QuotaUserMapper;
import com.ai.gateway.common.constant.BizCodeEnum;
import com.ai.gateway.common.exception.BizException;
import com.ai.gateway.common.pojo.InvokeLogV2;
import com.ai.gateway.common.pojo.QuotaReservation;
import com.ai.gateway.common.pojo.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 配额服务 —— 两段式计费核心（v2 重写，选型书 5.1/5.2）
 *
 * ┌─ reserve（同步、事务）──────────────────────────────┐
 * │ 1. t_user 条件更新上浮预扣水位（防超扣闸门）            │
 * │ 2. 插入预扣流水（status=0）                           │
 * └──────────────────────────────────────────────────┘
 *                      ↓ 模型调用（30s~180s，不在任何事务内）
 * ┌─ settle（同步、单事务）──────────────────────────────┐
 * │ 1. 流水 status 0→1 抢占（rows=0 = 幂等命中，直接返回）  │
 * │ 2. t_user 实际用量入账 + 预扣水位释放                   │
 * │ 3. t_invoke_log_v2 明细落库                           │
 * └──────────────────────────────────────────────────┘
 *                      ↓ 事务提交后
 *          router 发布 SettleEvent → 日志/统计异步旁路
 *
 * 崩溃语义（选型书 5.1）：
 * - reserve 后崩溃：流水残留 status=0，ReservationReconciler 在
 *   容忍窗口（5 分钟）后回滚水位（status 0→2）—— 预扣不会永久占额；
 * - settle 中崩溃：单事务回滚，流水回到 status=0，同样被对账兜底；
 *   客户端重试（同 traceId）时 settle 的 status 抢占保证只入账一次。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuotaService {

    private final QuotaUserMapper quotaUserMapper;
    private final QuotaReservationMapper reservationMapper;
    private final InvokeLogV2Mapper invokeLogMapper;

    /**
     * 预扣（第一阶段）
     *
     * @param traceId   全链路追踪 ID（预扣凭证）
     * @param userId    用户 ID
     * @param estTokens 预扣估算（est_input + est_output）
     * @return 预扣流水（含自增 id，settle 凭证）
     * @throws BizException 配额不足（5001/5002）/ 账号禁用 / 用户不存在
     */
    @Transactional
    public QuotaReservation reserve(String traceId, Long userId, long estTokens) {
        // 预扣至少 1：零/负估算会让条件更新失去闸门意义
        long est = Math.max(estTokens, 1L);

        int rows = quotaUserMapper.reserve(userId, est);
        if (rows == 0) {
            throw buildQuotaException(userId, est);
        }

        QuotaReservation reservation = new QuotaReservation();
        reservation.setTraceId(traceId);
        reservation.setUserId(userId);
        reservation.setEstTokens((int) est);
        reservation.setStatus(0);
        reservation.setGmtCreate(LocalDateTime.now());
        reservationMapper.insert(reservation);

        log.info("预扣成功: traceId={}, userId={}, est={} tokens", traceId, userId, est);
        return reservation;
    }

    /**
     * 结算（第二阶段，单事务）
     *
     * 幂等实现（选型书 5.2）：流水 status 0→1 的条件更新是唯一抢占点，
     * rows=0 说明同一 traceId 已结算过（客户端重试）或已被对账回滚
     * （迟到结算），此时直接返回 null，调用方不得再发布事件。
     *
     * @param ctx 结算上下文（router 组装）
     * @return 已持久化的调用日志（事件发布用）；null = 幂等命中，无需处理
     */
    @Transactional
    public InvokeLogV2 settle(SettleContext ctx) {
        // 1. 抢占流水：status 0→1
        int closed = reservationMapper.closeForSettle(ctx.getReservationId(), ctx.getTotalTokens());
        if (closed == 0) {
            log.warn("预扣流水已被结算或回滚，跳过本次入账: reservationId={}, traceId={}",
                    ctx.getReservationId(), ctx.getTraceId());
            return null;
        }

        // 2. 实际用量入账 + 预扣水位释放（同一事务）
        QuotaReservation reservation = reservationMapper.selectById(ctx.getReservationId());
        long est = reservation != null && reservation.getEstTokens() != null
                ? reservation.getEstTokens() : ctx.getTotalTokens();
        quotaUserMapper.settleUsage(ctx.getUserId(), ctx.getTotalTokens(), est);

        // 3. 调用明细落库
        InvokeLogV2 invokeLog = buildInvokeLog(ctx);
        invokeLogMapper.insert(invokeLog);

        log.info("结算完成: traceId={}, model={}, tokens={}, cost={}, latency={}ms, success={}",
                ctx.getTraceId(), ctx.getModelCode(), ctx.getTotalTokens(),
                ctx.getCostAmount(), ctx.getLatencyMs(), ctx.isSuccess());
        return invokeLog;
    }

    /** 预扣失败时细分原因（第二次查询仅发生在失败路径，量小可接受） */
    private BizException buildQuotaException(Long userId, long est) {
        User user = quotaUserMapper.findById(userId);
        if (user == null) {
            return BizException.of(BizCodeEnum.USER_NOT_FOUND);
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            return BizException.of(BizCodeEnum.ACCOUNT_DISABLED);
        }
        long dailyFree = nvl(user.getDailyQuota()) - nvl(user.getUsedDaily()) - nvl(user.getReservedDaily());
        long monthlyFree = nvl(user.getMonthlyQuota()) - nvl(user.getUsedMonthly()) - nvl(user.getReservedMonthly());
        log.info("预扣失败: userId={}, est={}, 日剩余={}, 月剩余={}", userId, est, dailyFree, monthlyFree);
        if (est > dailyFree || est > monthlyFree) {
            return BizException.of(BizCodeEnum.QUOTA_INSUFFICIENT_PRECHECK);
        }
        // 条件更新失败但两种剩余都够：并发窗口内被其他请求抢占，按通用口径返回
        return BizException.of(BizCodeEnum.QUOTA_EXCEEDED);
    }

    private InvokeLogV2 buildInvokeLog(SettleContext ctx) {
        InvokeLogV2 l = new InvokeLogV2();
        l.setTraceId(ctx.getTraceId());
        l.setUserId(ctx.getUserId());
        l.setModelCode(ctx.getModelCode());
        l.setPromptTokens((int) ctx.getPromptTokens());
        l.setCompletionTokens((int) ctx.getCompletionTokens());
        l.setTotalTokens((int) ctx.getTotalTokens());
        l.setCostAmount(ctx.getCostAmount());
        l.setLatencyMs((int) Math.min(ctx.getLatencyMs(), Integer.MAX_VALUE));
        l.setTtftMs(ctx.getTtftMs() == null ? null : (int) Math.min(ctx.getTtftMs(), Integer.MAX_VALUE));
        l.setSuccess(ctx.isSuccess() ? 1 : 0);
        l.setFallbackUsed(ctx.isFallbackUsed() ? 1 : 0);
        l.setInterrupted(ctx.isInterrupted() ? 1 : 0);
        l.setRoutingDecision(ctx.getRoutingDecision());
        l.setAgentTaskId(ctx.getAgentTaskId());
        l.setPayloadRef(ctx.getPayloadRef());
        l.setPriceVersion(ctx.getPriceVersion());
        return l;
    }

    private long nvl(Long value) {
        return value == null ? 0L : value;
    }
}

package com.ai.gateway.billing.task;

import com.ai.gateway.billing.mapper.QuotaReservationMapper;
import com.ai.gateway.billing.mapper.QuotaUserMapper;
import com.ai.gateway.common.pojo.QuotaReservation;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 预扣对账定时任务（v2 新增，选型书 5.1 兜底闭环）
 *
 * 解决的问题：进程在"reserve 之后、settle 之前"被 kill -9 /
 * 断电，预扣水位（t_user.reserved_*）成为永久泄漏的"幽灵占额"，
 * 用户可用配额被无谓侵蚀。
 *
 * 对账口径：
 * - 容忍窗口 5 分钟 >> 模型调用超时上限（180s），窗口内不误伤在途请求；
 * - 逐笔条件回滚（status 0→2 抢占），与 settle 的并发竞争由行锁裁决；
 * - 回滚成功才释放水位（rows=1），已被 settle 抢先结算的（rows=0）跳过。
 *
 * 单实例假设：单体架构下仅一个调度实例；拆分多实例时需引入
 * 分布式锁（如 Redis SETNX）保证同一时刻只有一个对账者（选型书第八章）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationReconciler {

    /** 容忍窗口（分钟）：调用超时 180s + 网络重试余量 */
    private static final int TOLERANCE_MINUTES = 5;

    /** 单轮对账最大处理笔数（防止积压时单轮耗时过长） */
    private static final int BATCH_LIMIT = 1000;

    private final QuotaReservationMapper reservationMapper;
    private final QuotaUserMapper quotaUserMapper;

    /** 每 60 秒扫描一轮过期预扣 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void reconcile() {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(TOLERANCE_MINUTES);
        List<QuotaReservation> expired = reservationMapper.selectList(
                new LambdaQueryWrapper<QuotaReservation>()
                        .eq(QuotaReservation::getStatus, 0)
                        .lt(QuotaReservation::getGmtCreate, deadline)
                        .last("LIMIT " + BATCH_LIMIT));
        if (expired.isEmpty()) {
            return;
        }

        int rolledBack = 0;
        for (QuotaReservation reservation : expired) {
            int closed = reservationMapper.closeExpired(reservation.getId());
            if (closed == 1) {
                long est = reservation.getEstTokens() == null ? 0L : reservation.getEstTokens();
                quotaUserMapper.release(reservation.getUserId(), est);
                rolledBack++;
                log.warn("对账回滚过期预扣: reservationId={}, traceId={}, userId={}, 释放 est={} tokens",
                        reservation.getId(), reservation.getTraceId(), reservation.getUserId(), est);
            }
            // closed == 0：settle 恰好抢先结算，无需处理
        }
        log.warn("本轮对账完成: 扫描 {} 笔, 回滚 {} 笔（容忍窗口 {} 分钟）",
                expired.size(), rolledBack, TOLERANCE_MINUTES);
    }
}

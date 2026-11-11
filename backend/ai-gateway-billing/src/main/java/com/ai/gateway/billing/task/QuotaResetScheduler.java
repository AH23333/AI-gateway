package com.ai.gateway.billing.task;

import com.ai.gateway.billing.mapper.QuotaUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 配额周期重置定时任务（基线行为保留，v2 移植）
 *
 * 每日 00:00 重置 used_daily，每月 1 日 00:00 重置 used_monthly。
 *
 * 与两段式的交互：
 * - 只清"已结算用量"，不动"预扣水位"—— 在途预扣由各自流水
 *   settle/对账自然收敛，避免重置与释放叠加造成水位错乱；
 * - 全表 UPDATE 在用户量级 <1e4 时毫秒级完成，无需分批。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuotaResetScheduler {

    private final QuotaUserMapper quotaUserMapper;

    /** 每日 00:00:00 重置当日用量 */
    @Scheduled(cron = "0 0 0 * * ?")
    public void resetDaily() {
        int rows = quotaUserMapper.resetDaily();
        log.info("每日配额重置完成, 影响用户数: {}", rows);
    }

    /** 每月 1 日 00:00:00 重置当月用量 */
    @Scheduled(cron = "0 0 0 1 * ?")
    public void resetMonthly() {
        int rows = quotaUserMapper.resetMonthly();
        log.info("每月配额重置完成, 影响用户数: {}", rows);
    }
}

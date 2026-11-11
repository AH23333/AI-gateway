package com.ai.gateway.router.listener;

import com.ai.gateway.billing.event.SettleEvent;
import com.ai.gateway.common.pojo.InvokeLogV2;
import com.ai.gateway.router.service.RouteStatsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 路由统计监听器 —— SettleEvent → Redis 统计窗口（FR-03/FR-04 数据源）
 *
 * 每次结算（含失败/中断）都更新对应模型的统计窗口：
 * 请求/错误窗口驱动健康熔断，延迟窗口驱动 P90 预测，质量 Hash 驱动打分。
 *
 * 已知取舍：降级调用的"主模型失败"不单独入账（日志只记录最终应答模型），
 * 主模型持续降级时错误率不增长 —— 现阶段以 fallback_used 标记观察，
 * 需要时在 SettleEvent 中补充"主模型失败事件"字段即可（监听器天然可扩展）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RouteStatsListener {

    private final RouteStatsService routeStatsService;

    @EventListener
    public void onSettle(SettleEvent event) {
        InvokeLogV2 logEntity = event.log();
        try {
            long latencyMs = logEntity.getLatencyMs() == null ? 0L : logEntity.getLatencyMs();
            boolean success = logEntity.getSuccess() != null && logEntity.getSuccess() == 1;
            routeStatsService.recordInvocation(
                    logEntity.getModelCode(), logEntity.getTraceId(), success, latencyMs,
                    event.qualityScore());
        } catch (Exception e) {
            // 统计旁路失败不影响账务，但要留痕
            log.error("路由统计更新失败: traceId={}, model={}",
                    logEntity.getTraceId(), logEntity.getModelCode(), e);
        }
    }
}

package com.ai.gateway.router.metrics;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 进程内运行时指标注册表 —— 观测域（v2 新增）
 *
 * 定位：管理端"系统监控"页的数据源（/admin/metrics/runtime 读取）。
 * 采集 ChatController 单一入口的请求生命周期：
 *   beginCall（请求进入） → endCall（非流式 finally / 流式 emitter 终止）
 *   beginStream/endStream（SSE 流真正开始/结束，与 HTTP 请求返回时机解耦）
 *
 * 设计要点：
 * - 60 秒逐秒环形窗口：slot = epochSecond % 60，写前校验槽位归属，
 *   过期槽位自然被新秒覆盖，无需后台清理线程；
 * - 记点频率 = 每请求 2~3 次，synchronized 足够（竞争可忽略），
 *   不引入 LongAdder 等更重结构；
 * - 指标属于"尽力而为"旁路：只增计数不抛错，绝不影响主链路；
 * - 进程内数据，重启清零 —— 持久化趋势由 t_billing_daily /
 *   t_invoke_log_v2 承担，两者职责不重叠。
 */
@Component
public class MetricsRegistry {

    /** 逐秒窗口长度（秒） */
    private static final int WINDOW = 60;

    private final long bootTimeMs = System.currentTimeMillis();

    /** 环形窗口：counts[slot] 与 secondOf[slot] 配对，secondOf 记录槽位当前归属的 epoch 秒 */
    private final long[] counts = new long[WINDOW];
    private final long[] secondOf = new long[WINDOW];

    private long totalCalls;
    private long inFlight;
    private long activeStreams;

    /**
     * @param meterRegistry Micrometer 注册表（actuator 自动装配）：
     *                      进程内窗口以 Prometheus 标准指标同步暴露（/actuator/prometheus）；
     *                      Grafana 走 Micrometer 体系，管理页走本类快照，两条观测路径互不干扰。
     */
    public MetricsRegistry(MeterRegistry meterRegistry) {
        FunctionCounter.builder("gateway.calls.total", this, MetricsRegistry::getTotalCalls)
                .description("网关累计调用数")
                .register(meterRegistry);
        Gauge.builder("gateway.calls.inflight", this, MetricsRegistry::getInFlight)
                .description("在途请求数（beginCall 未归位）")
                .register(meterRegistry);
        Gauge.builder("gateway.streams.active", this, MetricsRegistry::getActiveStreams)
                .description("活跃 SSE 流数")
                .register(meterRegistry);
    }

    /** 请求进入（流式/非流式共用）：总数、在途 +1，秒窗口记点 */
    public synchronized void beginCall() {
        totalCalls++;
        inFlight++;
        long now = System.currentTimeMillis() / 1000;
        int slot = (int) (now % WINDOW);
        if (secondOf[slot] != now) {
            secondOf[slot] = now;
            counts[slot] = 0;
        }
        counts[slot]++;
    }

    /** 请求结束：非流式走 finally；流式走 emitter 终止回调（调用方保证恰好一次） */
    public synchronized void endCall() {
        inFlight--;
    }

    /** SSE 流真正开始（emitter 已创建并交还控制器） */
    public synchronized void beginStream() {
        activeStreams++;
    }

    /** SSE 流结束（完成/出错/超时，调用方用 CAS 保证恰好一次） */
    public synchronized void endStream() {
        activeStreams--;
    }

    public synchronized long getTotalCalls() {
        return totalCalls;
    }

    public synchronized long getInFlight() {
        return inFlight;
    }

    public synchronized long getActiveStreams() {
        return activeStreams;
    }

    public long getBootTimeMs() {
        return bootTimeMs;
    }

    /**
     * 60 秒逐秒快照（旧 → 新，空槽补 0）。
     * 返回 record 携带派生量：60s/10s 汇总，避免调用方重复遍历。
     */
    public synchronized SecondWindow snapshot() {
        long now = System.currentTimeMillis() / 1000;
        List<Long> perSecond = new ArrayList<>(WINDOW);
        long sum60 = 0;
        long sum10 = 0;
        // i 从 60 递减到 1：sec = now - i + 1，覆盖 [now-59, now] 共 60 个完整秒
        for (int i = WINDOW; i >= 1; i--) {
            long sec = now - i + 1;
            int slot = (int) (sec % WINDOW);
            long c = secondOf[slot] == sec ? counts[slot] : 0;
            perSecond.add(c);
            sum60 += c;
            if (i <= 10) {
                sum10 += c;
            }
        }
        return new SecondWindow(perSecond, sum60, sum10);
    }

    /** 逐秒窗口快照（观测 DTO，仅 admin 模块消费） */
    public record SecondWindow(List<Long> perSecond, long total60, long total10) {
    }
}

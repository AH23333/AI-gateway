package com.ai.gateway.admin.controller;

import com.ai.gateway.admin.mapper.HealthProbeMapper;
import com.ai.gateway.billing.writer.BatchLogWriter;
import com.ai.gateway.common.dto.Result;
import com.ai.gateway.router.metrics.MetricsRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网关运行时指标（观测域，v2 新增）
 *
 * 路由：GET /admin/metrics/runtime —— 前端"系统监控"页 5s 轮询。
 *
 * 数据来源全部进程内/探针，零 DB 聚合查询：
 * - 调用指标：router 模块 MetricsRegistry（ChatController 生命周期埋点）
 * - 批量写队列水位：billing 模块 BatchLogWriter 有界队列 size 暴露
 * - JVM/线程：JDK ManagementFactory（MXBean，无额外依赖）
 * - Redis/DB 探活：PING / SELECT 1 + 耗时测量（down 不抛错，降级标注）
 *
 * 模型健康分不在本接口重复实现：监控页复用 /admin/routing/analysis。
 */
@Tag(name = "系统监控（观测域）")
@RestController
@RequestMapping("/admin/metrics")
@RequiredArgsConstructor
public class RuntimeMetricsController {

    private final MetricsRegistry metricsRegistry;
    private final BatchLogWriter batchLogWriter;
    private final StringRedisTemplate redisTemplate;
    private final HealthProbeMapper healthProbeMapper;

    @Operation(summary = "网关运行时指标总览（调用/JVM/线程/队列水位/依赖探活）")
    @GetMapping("/runtime")
    public Result<Map<String, Object>> runtime() {
        Map<String, Object> body = new LinkedHashMap<>();

        // ==================== 基本信息 ====================
        body.put("version", "2.0.0");
        body.put("bootTimeMs", metricsRegistry.getBootTimeMs());
        body.put("uptimeSeconds", (System.currentTimeMillis() - metricsRegistry.getBootTimeMs()) / 1000);

        // ==================== 调用指标（60s 逐秒窗口） ====================
        MetricsRegistry.SecondWindow win = metricsRegistry.snapshot();
        Map<String, Object> calls = new LinkedHashMap<>();
        calls.put("totalCalls", metricsRegistry.getTotalCalls());
        calls.put("inFlight", metricsRegistry.getInFlight());
        calls.put("activeStreams", metricsRegistry.getActiveStreams());
        calls.put("perSecond", win.perSecond());
        calls.put("qps60s", round2(win.total60() / 60.0));
        calls.put("qps10s", round2(win.total10() / 10.0));
        body.put("calls", calls);

        // ==================== JVM 内存 ====================
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memory.getHeapMemoryUsage();
        MemoryUsage nonHeap = memory.getNonHeapMemoryUsage();
        Map<String, Object> jvm = new LinkedHashMap<>();
        jvm.put("heapUsed", heap.getUsed());
        jvm.put("heapCommitted", heap.getCommitted());
        jvm.put("heapMax", heap.getMax());
        jvm.put("nonHeapUsed", nonHeap.getUsed());
        jvm.put("nonHeapCommitted", nonHeap.getCommitted());
        body.put("jvm", jvm);

        // ==================== 线程 ====================
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        Map<String, Object> thread = new LinkedHashMap<>();
        thread.put("threadCount", threads.getThreadCount());
        thread.put("peakThreadCount", threads.getPeakThreadCount());
        thread.put("daemonThreadCount", threads.getDaemonThreadCount());
        thread.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        body.put("threads", thread);

        // ==================== 批量写队列水位 ====================
        // 水位持续接近容量 = 下游 MySQL 写入跟不上（观测页告警阈值参考：>80%）
        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("messageQueueSize", batchLogWriter.messageQueueSize());
        batch.put("billingQueueSize", batchLogWriter.billingQueueSize());
        batch.put("queueCapacity", batchLogWriter.queueCapacity());
        batch.put("flushIntervalMs", batchLogWriter.flushIntervalMs());
        body.put("batchWriter", batch);

        // ==================== 依赖探活（异常不传染，降级标注） ====================
        body.put("redis", probeRedis());
        body.put("db", probeDb());

        return Result.ok(body);
    }

    /** Redis PING + 耗时；down 时返回状态而非抛错（监控页展示红点） */
    private Map<String, Object> probeRedis() {
        Map<String, Object> redis = new LinkedHashMap<>();
        long start = System.nanoTime();
        try {
            String pong = redisTemplate.execute((RedisCallback<String>) conn -> conn.ping());
            long costMs = (System.nanoTime() - start) / 1_000_000;
            redis.put("status", "PONG".equalsIgnoreCase(pong) ? "up" : "degraded");
            redis.put("latencyMs", costMs);
        } catch (Exception e) {
            redis.put("status", "down");
            redis.put("latencyMs", -1);
        }
        return redis;
    }

    /** MySQL SELECT 1 + 耗时 */
    private Map<String, Object> probeDb() {
        Map<String, Object> db = new LinkedHashMap<>();
        long start = System.nanoTime();
        try {
            int one = healthProbeMapper.dbPing();
            long costMs = (System.nanoTime() - start) / 1_000_000;
            db.put("status", one == 1 ? "up" : "degraded");
            db.put("latencyMs", costMs);
        } catch (Exception e) {
            db.put("status", "down");
            db.put("latencyMs", -1);
        }
        return db;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}

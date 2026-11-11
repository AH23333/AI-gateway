package com.ai.gateway.router.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MetricsRegistry 单元测试：计数正确性 + 并发安全 + Micrometer 桥接。
 * 观测旁路的底线：并发高压下计数不丢、在途不泄漏（恰好归零）。
 */
class MetricsRegistryTest {

    @Test
    @DisplayName("并发 begin/end 配对称：总数不丢，在途恰好归零")
    void beginEndCallSymmetryUnderConcurrency() throws Exception {
        MetricsRegistry registry = new MetricsRegistry(new SimpleMeterRegistry());
        int threads = 10;
        int ops = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.execute(() -> {
                ready.countDown();
                try {
                    // 所有线程就位后再开跑，最大化 begin/end 交错
                    ready.await();
                    for (int i = 0; i < ops; i++) {
                        registry.beginCall();
                        registry.endCall();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        assertThat(registry.getTotalCalls()).isEqualTo((long) threads * ops);
        assertThat(registry.getInFlight()).isZero();
    }

    @Test
    @DisplayName("流式计数：beginStream/endStream 与在途请求解耦")
    void streamCountersMirror() {
        MetricsRegistry registry = new MetricsRegistry(new SimpleMeterRegistry());
        registry.beginCall();
        registry.beginStream();
        assertThat(registry.getInFlight()).isEqualTo(1);
        assertThat(registry.getActiveStreams()).isEqualTo(1);
        registry.endStream();
        registry.endCall();
        assertThat(registry.getActiveStreams()).isZero();
        assertThat(registry.getInFlight()).isZero();
    }

    @Test
    @DisplayName("60 秒逐秒窗口：快照覆盖全部已记录调用")
    void snapshotCoversRecordedCalls() {
        MetricsRegistry registry = new MetricsRegistry(new SimpleMeterRegistry());
        registry.beginCall();
        registry.beginCall();
        MetricsRegistry.SecondWindow win = registry.snapshot();
        assertThat(win.perSecond()).hasSize(60);
        assertThat(win.total60()).isEqualTo(2);
        registry.endCall();
        registry.endCall();
        assertThat(registry.getInFlight()).isZero();
    }

    @Test
    @DisplayName("Micrometer 桥接：三个指标实时反映注册表状态")
    void micrometerBridgeExposesGauges() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        MetricsRegistry registry = new MetricsRegistry(meters);
        registry.beginCall();
        registry.beginStream();
        assertThat(meters.get("gateway.calls.total").functionCounter().count()).isEqualTo(1.0);
        assertThat(meters.get("gateway.calls.inflight").gauge().value()).isEqualTo(1.0);
        assertThat(meters.get("gateway.streams.active").gauge().value()).isEqualTo(1.0);
        registry.endStream();
        registry.endCall();
        assertThat(meters.get("gateway.calls.inflight").gauge().value()).isZero();
        assertThat(meters.get("gateway.streams.active").gauge().value()).isZero();
    }
}

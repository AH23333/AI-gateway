package com.ai.gateway.router.service;

import com.ai.gateway.common.constant.RedisKeyConstant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 路由统计服务 —— Redis 窗口统计 + 健康熔断（1.md §8.2，FR-03/FR-04）
 *
 * 数据结构（key 见 RedisKeyConstant，全部 7 天 TTL，模型下架自动过期）：
 * - 延迟窗口  ZSet  route:latency:{code}   score=耗时ms member=traceId（最近 500 条）
 * - 质量分    Hash  route:quality:{code}   scoreTotal/samples → 均值
 * - 请求窗口  ZSet  route:reqwin:{code}    score=时间戳 member=traceId（5 分钟滑动）
 * - 错误窗口  ZSet  route:errwin:{code}    同上，仅失败请求
 * - 黑名单    String route:health:{code}   存在即"冷却拉黑中"，值=原因，TTL=冷却时长
 *
 * 健康熔断口径（自算而非注册中心，选型书第四章）：
 * 5 分钟窗口内请求 ≥ 5 且错误率 ≥ 50% → 拉黑 60 秒
 * （单实例上游：模型加载/限流/欠费会连续失败，60s 冷却足够跨过瞬时故障期）。
 *
 * ZSet member=traceId 的幂等性：重试/重复投递同 traceId 只覆盖 score，
 * 不产生重复样本（NFR-03）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RouteStatsService {

    // ==================== 熔断参数 ====================
    private static final long WINDOW_MS = 5 * 60 * 1000L;      // 滑动窗口 5 分钟
    private static final double ERROR_RATE_THRESHOLD = 0.5;    // 错误率阈值 50%
    private static final long MIN_REQUESTS_FOR_HEALTH = 5;     // 熔断最小样本数
    private static final long BLACKLIST_COOLDOWN_SECONDS = 60; // 拉黑冷却时长
    private static final long LATENCY_WINDOW_MAX = 500;        // 延迟样本上限
    private static final long STATS_TTL_DAYS = 7;              // 统计 key 统一 TTL

    private final StringRedisTemplate redisTemplate;

    /**
     * 记录一次调用结果（SettleEvent 旁路触发，失败不影响主链路）
     *
     * @param qualityScore 质量代理分 [0,100]（OQ-03 启发式），失败调用不记质量
     */
    public void recordInvocation(String modelCode, String traceId,
                                 boolean success, long latencyMs, Integer qualityScore) {
        long now = System.currentTimeMillis();
        String reqKey = RedisKeyConstant.routeReqWindow(modelCode);

        // 1. 请求窗口（成功失败都算请求数）
        redisTemplate.opsForZSet().add(reqKey, traceId, now);
        redisTemplate.opsForZSet().removeRangeByScore(reqKey, 0, now - WINDOW_MS);
        redisTemplate.expire(reqKey, STATS_TTL_DAYS, TimeUnit.DAYS);

        if (success) {
            // 2. 延迟窗口（失败调用的耗时不具参考性，不入样本）
            if (latencyMs > 0) {
                String latencyKey = RedisKeyConstant.routeLatency(modelCode);
                redisTemplate.opsForZSet().add(latencyKey, traceId, latencyMs);
                // 只保留最近 500 条（按插入序，老的先淘汰）
                redisTemplate.opsForZSet().removeRange(latencyKey, 0, -LATENCY_WINDOW_MAX - 1);
                redisTemplate.expire(latencyKey, STATS_TTL_DAYS, TimeUnit.DAYS);
            }
            // 3. 质量分累加
            if (qualityScore != null) {
                String qualityKey = RedisKeyConstant.routeQuality(modelCode);
                redisTemplate.opsForHash().increment(qualityKey, "samples", 1);
                redisTemplate.opsForHash().increment(qualityKey, "scoreTotal", qualityScore);
                redisTemplate.expire(qualityKey, STATS_TTL_DAYS, TimeUnit.DAYS);
            }
        } else {
            // 4. 错误窗口 + 熔断检查
            String errKey = RedisKeyConstant.routeErrWindow(modelCode);
            redisTemplate.opsForZSet().add(errKey, traceId, now);
            redisTemplate.opsForZSet().removeRangeByScore(errKey, 0, now - WINDOW_MS);
            redisTemplate.expire(errKey, STATS_TTL_DAYS, TimeUnit.DAYS);
            checkCircuitBreaker(modelCode, reqKey, errKey, now);
        }
    }

    /** 是否处于冷却拉黑中（RoutingEngine 健康过滤调用） */
    public boolean isBlacklisted(String modelCode) {
        return Boolean.TRUE.equals(
                redisTemplate.hasKey(RedisKeyConstant.routeHealth(modelCode)));
    }

    /** 拉黑原因（未拉黑返回 null，管理端路由分析展示用） */
    public String blacklistReason(String modelCode) {
        return redisTemplate.opsForValue().get(RedisKeyConstant.routeHealth(modelCode));
    }

    /** 健康分 = 1 - 窗口错误率（样本不足返回 null，调用方回退"视为健康"） */
    public Double healthScore(String modelCode) {
        long now = System.currentTimeMillis();
        Long req = redisTemplate.opsForZSet().count(
                RedisKeyConstant.routeReqWindow(modelCode), now - WINDOW_MS, now);
        Long err = redisTemplate.opsForZSet().count(
                RedisKeyConstant.routeErrWindow(modelCode), now - WINDOW_MS, now);
        if (req == null || req < MIN_REQUESTS_FOR_HEALTH) {
            return null;
        }
        return 1.0 - (err == null ? 0 : err) / (double) req;
    }

    /**
     * P90 延迟（毫秒）：延迟窗口按 rank 取 90 分位
     *
     * @return 样本为空返回 null（调用方回退 t_model.baseline_latency_ms）
     */
    public Long p90LatencyMs(String modelCode) {
        String key = RedisKeyConstant.routeLatency(modelCode);
        Long size = redisTemplate.opsForZSet().zCard(key);
        if (size == null || size == 0) {
            return null;
        }
        // P90 rank：ceil(0.9 * n) - 1，ZSet 按 score 升序
        long rank = Math.max(0, (long) Math.ceil(size * 0.9) - 1);
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redisTemplate.opsForZSet().rangeWithScores(key, rank, rank);
        if (tuples == null || tuples.isEmpty()) {
            return null;
        }
        Double score = tuples.iterator().next().getScore();
        return score == null ? null : score.longValue();
    }

    /**
     * 平均质量分 [0,100]
     *
     * @return 样本不足（< 冷启动阈值）返回 null，调用方回退 baseline_quality
     */
    public Double avgQuality(String modelCode) {
        Object samples = redisTemplate.opsForHash()
                .get(RedisKeyConstant.routeQuality(modelCode), "samples");
        Object total = redisTemplate.opsForHash()
                .get(RedisKeyConstant.routeQuality(modelCode), "scoreTotal");
        if (samples == null || total == null) {
            return null;
        }
        long n = Long.parseLong(samples.toString());
        if (n <= 0) {
            return null;
        }
        return Double.parseDouble(total.toString()) / n;
    }

    /** 质量样本数（冷启动判定：样本 < 20 用 baseline，1.md §6.2） */
    public long qualitySamples(String modelCode) {
        Object samples = redisTemplate.opsForHash()
                .get(RedisKeyConstant.routeQuality(modelCode), "samples");
        return samples == null ? 0 : Long.parseLong(samples.toString());
    }

    /** 熔断判定：窗口内样本足够且错误率超阈值 → 拉黑并写冷却 TTL */
    private void checkCircuitBreaker(String modelCode, String reqKey, String errKey, long now) {
        Long req = redisTemplate.opsForZSet().count(reqKey, now - WINDOW_MS, now);
        Long err = redisTemplate.opsForZSet().count(errKey, now - WINDOW_MS, now);
        if (req == null || req < MIN_REQUESTS_FOR_HEALTH) {
            return;
        }
        double errorRate = (err == null ? 0 : err) / (double) req;
        if (errorRate >= ERROR_RATE_THRESHOLD) {
            String healthKey = RedisKeyConstant.routeHealth(modelCode);
            redisTemplate.opsForValue().set(healthKey,
                    String.format("错误率 %.0f%%（%d/%d），冷却 %ds",
                            errorRate * 100, err, req, BLACKLIST_COOLDOWN_SECONDS),
                    BLACKLIST_COOLDOWN_SECONDS, TimeUnit.SECONDS);
            log.warn("模型熔断拉黑: model={}, 5分钟窗口错误率={}, 冷却 {}s",
                    modelCode, String.format("%.0f%%", errorRate * 100), BLACKLIST_COOLDOWN_SECONDS);
        }
    }
}

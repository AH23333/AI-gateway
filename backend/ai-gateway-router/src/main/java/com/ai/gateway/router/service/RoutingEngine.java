package com.ai.gateway.router.service;

import com.ai.gateway.common.constant.BizCodeEnum;
import com.ai.gateway.common.dto.ChatRequest;
import com.ai.gateway.common.exception.BizException;
import com.ai.gateway.common.pojo.ModelInfo;
import com.ai.gateway.common.pojo.ModelPrice;
import com.ai.gateway.common.pojo.RoutingConfig;
import com.ai.gateway.common.util.JsonUtil;
import com.ai.gateway.common.util.TokenEstimator;
import com.ai.gateway.router.util.CostCalculator;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能路由引擎 —— 过滤链 + 加权打分（v2 核心，FR-01~05 / FR-12，1.md §6）
 *
 * 决策流程（每步可解释、全量记录进 routing_decision）：
 *
 *   候选池（启用态模型）
 *     → [TIER]   复杂度分池：simple→cheap / complex→strong / balanced→全池
 *                （tools 请求一律视为 complex —— agent 工具任务是强模型场景）
 *     → [HEALTH] 剔除熔断黑名单中的模型（清空则回退全池：可用性优先）
 *     → [BUDGET] X-Cost-Budget 内的模型（严格：清空即 8001 报错）
 *     → [SLA]    P90 预测 ≤ X-Latency-SLA 的模型（严格：清空即 8001 报错）
 *     → [SCORE]  score = w_q × normQuality − w_c × normCost，取最大
 *
 * 冷启动（1.md §6.2）：质量样本 < 20 → 回退 t_model.baseline_quality；
 * 延迟样本为空 → 回退 t_model.baseline_latency_ms。
 *
 * 归一化：min-max 到 [0,1]；单候选/全等时 quality=1、cost=0（无害默认）。
 *
 * 平手裁决：分数相同 → P90 延迟低者优先 → id 小者优先（结果稳定可复现）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoutingEngine {

    /** 复杂度分池阈值（token） */
    private static final long SIMPLE_MAX_INPUT = 500L;
    private static final long COMPLEX_MIN_INPUT = 2_000L;

    /** 质量分冷启动样本阈值 */
    private static final long QUALITY_COLD_START_SAMPLES = 20;

    /** 质量分兜底（模型未配置 baseline 且无样本时） */
    private static final double QUALITY_FALLBACK = 60.0;

    /** 延迟兜底（毫秒） */
    private static final long LATENCY_FALLBACK_MS = 30_000L;

    private final ModelConfigService modelConfigService;
    private final RouteStatsService routeStatsService;

    /**
     * 路由结果（供 ChatService 编排 + 管理端决策分析）
     */
    @Data
    public static class RouteResult {
        /** 选中的模型 */
        private ModelInfo selected;
        /** 决策全量 JSON（落 t_invoke_log_v2.routing_decision，管理端回放） */
        private String decisionJson;
        /** 决策摘要（响应头 X-Routing-Decision） */
        private String summary;
        /** 复杂度分级：simple / complex / balanced */
        private String complexity;
    }

    /** 单候选打分明细（决策 JSON 的 candidates 数组元素） */
    private record CandidateScore(
            String modelCode, double score, double quality, double cost,
            long predictedLatencyMs, long qualitySamples, boolean chosen) {
    }

    /**
     * 执行路由决策
     *
     * @param request      原始请求（tools 参与 complex 判定）
     * @param estInput     输入 token 估算（复杂度分池 + 成本预估用）
     * @param costBudget   请求级成本预算（元，null = 不约束）
     * @param latencySlaMs 请求级延迟 SLA（毫秒，null = 不约束）
     */
    public RouteResult select(ChatRequest request, long estInput,
                              BigDecimal costBudget, Long latencySlaMs) {
        List<ModelInfo> pool = modelConfigService.listEnabledModels();
        if (pool.isEmpty()) {
            throw BizException.of(BizCodeEnum.ROUTE_NO_CANDIDATE);
        }
        RoutingConfig config = modelConfigService.getRoutingConfig();
        double wq = normalizeWeightQuality(config);
        double wc = 1.0 - wq;

        // ---------- 1. 复杂度分池 ----------
        String complexity = classifyComplexity(request, estInput);
        List<ModelInfo> tierFiltered = filterByTier(pool, complexity);

        // ---------- 2. 健康过滤（清空回退全池：可用性优先） ----------
        List<ModelInfo> healthFiltered = filterByHealth(tierFiltered);
        // lambda 捕获用的不可变快照（healthFiltered 下方回退时会重新赋值）
        final List<ModelInfo> healthySnapshot = List.copyOf(healthFiltered);
        List<String> blacklisted = tierFiltered.stream()
                .filter(m -> !healthySnapshot.contains(m))
                .map(ModelInfo::getModelCode).toList();
        if (healthFiltered.isEmpty()) {
            log.warn("健康过滤清空候选, 回退全池（可用性优先）: 原黑名单={}", blacklisted);
            healthFiltered = tierFiltered;
        }

        // ---------- 3. 预算过滤（严格） ----------
        long estOutput = TokenEstimator.estimateOutput(estInput);
        List<ModelInfo> budgetFiltered = filterByBudget(healthFiltered, estInput, estOutput, costBudget);
        if (budgetFiltered.isEmpty()) {
            throw BizException.of(BizCodeEnum.ROUTE_NO_CANDIDATE);
        }

        // ---------- 4. SLA 过滤（严格） ----------
        List<ModelInfo> slaFiltered = filterBySla(budgetFiltered, latencySlaMs);
        if (slaFiltered.isEmpty()) {
            throw BizException.of(BizCodeEnum.ROUTE_NO_CANDIDATE);
        }

        // ---------- 5. 加权打分 ----------
        ModelInfo selected = null;
        List<CandidateScore> scores = new ArrayList<>();
        double bestScore = Double.NEGATIVE_INFINITY;
        long bestLatency = Long.MAX_VALUE;
        for (ModelInfo model : slaFiltered) {
            double quality = qualityOf(model);
            BigDecimal cost = estimateCost(model, estInput, estOutput);
            long predictedLatency = predictedLatencyOf(model);
            scores.add(new CandidateScore(model.getModelCode(), 0, quality,
                    cost.doubleValue(), predictedLatency,
                    routeStatsService.qualitySamples(model.getModelCode()), false));
            // score 在归一化后统一回填
        }
        // 归一化打分（在候选集内 min-max，避免全局量纲问题）
        double minQ = scores.stream().mapToDouble(CandidateScore::quality).min().orElse(0);
        double maxQ = scores.stream().mapToDouble(CandidateScore::quality).max().orElse(0);
        double minC = scores.stream().mapToDouble(CandidateScore::cost).min().orElse(0);
        double maxC = scores.stream().mapToDouble(CandidateScore::cost).max().orElse(0);
        int bestIndex = 0;
        for (int i = 0; i < scores.size(); i++) {
            CandidateScore cs = scores.get(i);
            double normQ = maxQ > minQ ? (cs.quality() - minQ) / (maxQ - minQ) : 1.0;
            double normC = maxC > minC ? (cs.cost() - minC) / (maxC - minC) : 0.0;
            double score = wq * normQ - wc * normC;
            scores.set(i, new CandidateScore(cs.modelCode(), score, cs.quality(), cs.cost(),
                    cs.predictedLatencyMs(), cs.qualitySamples(), false));
            if (score > bestScore + 1e-9
                    || (Math.abs(score - bestScore) <= 1e-9
                        && scores.get(bestIndex).predictedLatencyMs() > cs.predictedLatencyMs())) {
                bestScore = score;
                bestIndex = i;
                selected = slaFiltered.get(i);
                bestLatency = cs.predictedLatencyMs();
            }
        }
        // 标记选中候选
        CandidateScore chosen = scores.get(bestIndex);
        scores.set(bestIndex, new CandidateScore(chosen.modelCode(), chosen.score(),
                chosen.quality(), chosen.cost(), chosen.predictedLatencyMs(),
                chosen.qualitySamples(), true));

        // ---------- 6. 决策记录 ----------
        RouteResult result = new RouteResult();
        result.setSelected(selected);
        result.setComplexity(complexity);
        result.setSummary("auto:" + selected.getModelCode());
        result.setDecisionJson(buildDecisionJson(complexity, config, wq, wc,
                blacklisted, costBudget, latencySlaMs, scores));
        log.info("路由决策: {} → {}（复杂度={}, 候选={}, score={})",
                result.getSummary(), selected.getModelCode(), complexity, scores.size(), bestLatency);
        return result;
    }

    // ==================== 过滤器 ====================

    /** 复杂度分级：tools 请求 → complex；短输入 → simple；长输入 → complex；其余 balanced */
    private String classifyComplexity(ChatRequest request, long estInput) {
        if (request.getTools() != null && !request.getTools().isNull()) {
            return "complex";
        }
        if (estInput <= SIMPLE_MAX_INPUT) {
            return "simple";
        }
        if (estInput >= COMPLEX_MIN_INPUT) {
            return "complex";
        }
        return "balanced";
    }

    /** 池过滤：simple→cheap 池 / complex→strong 池 / balanced→全池；池空回退全池 */
    private List<ModelInfo> filterByTier(List<ModelInfo> pool, String complexity) {
        String tier = switch (complexity) {
            case "simple" -> "cheap";
            case "complex" -> "strong";
            default -> null; // balanced：全池
        };
        if (tier == null) {
            return pool;
        }
        List<ModelInfo> filtered = pool.stream()
                .filter(m -> tier.equals(m.getModelTier()))
                .toList();
        if (filtered.isEmpty()) {
            log.warn("复杂度 {} 对应池 {} 为空, 回退全池", complexity, tier);
            return pool;
        }
        return filtered;
    }

    /** 健康过滤：剔除熔断拉黑中的模型 */
    private List<ModelInfo> filterByHealth(List<ModelInfo> candidates) {
        return candidates.stream()
                .filter(m -> !routeStatsService.isBlacklisted(m.getModelCode()))
                .toList();
    }

    /** 预算过滤：预估费用 ≤ X-Cost-Budget */
    private List<ModelInfo> filterByBudget(List<ModelInfo> candidates, long estInput,
                                           long estOutput, BigDecimal costBudget) {
        if (costBudget == null) {
            return candidates;
        }
        return candidates.stream()
                .filter(m -> estimateCost(m, estInput, estOutput).compareTo(costBudget) <= 0)
                .toList();
    }

    /** SLA 过滤：P90 预测 ≤ X-Latency-SLA */
    private List<ModelInfo> filterBySla(List<ModelInfo> candidates, Long latencySlaMs) {
        if (latencySlaMs == null) {
            return candidates;
        }
        return candidates.stream()
                .filter(m -> predictedLatencyOf(m) <= latencySlaMs)
                .toList();
    }

    // ==================== 特征值 ====================

    /** 质量分：样本 ≥ 20 取统计均值，否则回退 baseline（再兜底 60） */
    private double qualityOf(ModelInfo model) {
        if (routeStatsService.qualitySamples(model.getModelCode()) >= QUALITY_COLD_START_SAMPLES) {
            Double avg = routeStatsService.avgQuality(model.getModelCode());
            if (avg != null) {
                return avg;
            }
        }
        if (model.getBaselineQuality() != null) {
            return model.getBaselineQuality();
        }
        return QUALITY_FALLBACK;
    }

    /** 预估费用（元） */
    private BigDecimal estimateCost(ModelInfo model, long estInput, long estOutput) {
        return CostCalculator.cost(modelConfigService.currentPrice(model.getModelCode()),
                estInput, estOutput);
    }

    /** 延迟预测：P90 统计 → baseline → 30s 兜底 */
    private long predictedLatencyOf(ModelInfo model) {
        Long p90 = routeStatsService.p90LatencyMs(model.getModelCode());
        if (p90 != null) {
            return p90;
        }
        if (model.getBaselineLatencyMs() != null) {
            return model.getBaselineLatencyMs();
        }
        return LATENCY_FALLBACK_MS;
    }

    /** 权重合法性兜底：w_q 非法或 w_q+w_c≠1 时回退默认 0.6/0.4 */
    private double normalizeWeightQuality(RoutingConfig config) {
        Double wq = config.getWeightQuality();
        Double wc = config.getWeightCost();
        if (wq == null || wc == null || wq < 0 || wc < 0
                || Math.abs(wq + wc - 1.0) > 1e-6) {
            return 0.6;
        }
        return wq;
    }

    /** 决策全量 JSON（管理端路由分析回放的数据源） */
    private String buildDecisionJson(String complexity, RoutingConfig config,
                                     double wq, double wc, List<String> blacklisted,
                                     BigDecimal costBudget, Long latencySlaMs,
                                     List<CandidateScore> scores) {
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("complexity", complexity);
        Map<String, Object> weights = new LinkedHashMap<>();
        weights.put("quality", round2(wq));
        weights.put("cost", round2(wc));
        decision.put("weights", weights);
        if (!blacklisted.isEmpty()) {
            decision.put("healthBlacklisted", blacklisted);
        }
        if (costBudget != null) {
            decision.put("budgetYuan", costBudget);
        }
        if (latencySlaMs != null) {
            decision.put("latencySlaMs", latencySlaMs);
        }
        List<Map<String, Object>> candidateList = new ArrayList<>();
        for (CandidateScore cs : scores) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("model", cs.modelCode());
            c.put("score", round4(cs.score()));
            c.put("quality", round2(cs.quality()));
            c.put("costYuan", round4(cs.cost()));
            c.put("predictedLatencyMs", cs.predictedLatencyMs());
            c.put("qualitySamples", cs.qualitySamples());
            c.put("chosen", cs.chosen());
            candidateList.add(c);
        }
        decision.put("candidates", candidateList);
        return JsonUtil.toJson(decision);
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}

package com.ai.gateway.router.service;

import com.ai.gateway.common.dto.ChatRequest;
import com.ai.gateway.common.exception.BizException;
import com.ai.gateway.common.pojo.ModelInfo;
import com.ai.gateway.common.pojo.ModelPrice;
import com.ai.gateway.common.pojo.RoutingConfig;
import com.ai.gateway.common.util.JsonUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RoutingEngine 单元测试：过滤链 + 加权打分的决策矩阵。
 *
 * Mock 边界：ModelConfigService（候选池/配置/价格）与 RouteStatsService（健康/统计窗口），
 * 只验证引擎纯逻辑，不触 Spring 上下文 / Redis / MySQL——路由决策是网关正确性的核心，
 * 必须可在无环境条件下回归。
 */
class RoutingEngineTest {

    private ModelConfigService modelConfigService;
    private RouteStatsService routeStatsService;
    private RoutingEngine engine;

    private ModelInfo cheap;
    private ModelInfo strong;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // JsonUtil 的 ObjectMapper 由 Spring @Autowired 静态桥接注入；纯单测无上下文，手工搭桥
        new JsonUtil().setObjectMapper(new ObjectMapper());

        modelConfigService = mock(ModelConfigService.class);
        routeStatsService = mock(RouteStatsService.class);
        engine = new RoutingEngine(modelConfigService, routeStatsService);

        cheap = model("glm-4-flash", "cheap", 80, 3000);
        strong = model("glm-4-plus", "strong", 92, 8000);

        when(modelConfigService.listEnabledModels()).thenReturn(List.of(cheap, strong));
        when(modelConfigService.getRoutingConfig()).thenReturn(config(0.6, 0.4));
        // 冷启动基线：无熔断、无统计样本（qualitySamples=0 → 回退 baseline；p90=null → 回退 baseline）
        when(routeStatsService.isBlacklisted(anyString())).thenReturn(false);
        when(routeStatsService.qualitySamples(anyString())).thenReturn(0L);
        when(routeStatsService.p90LatencyMs(anyString())).thenReturn(null);
    }

    // ==================== 复杂度分池 ====================

    @Test
    @DisplayName("tools 请求一律 complex（即使输入只有 100 token）→ strong 池")
    void toolsAlwaysComplex() {
        ChatRequest request = new ChatRequest();
        request.setTools(objectMapper.createArrayNode().addObject());
        RoutingEngine.RouteResult result = engine.select(request, 100, null, null);
        assertThat(result.getComplexity()).isEqualTo("complex");
        assertThat(result.getSelected().getModelCode()).isEqualTo("glm-4-plus");
        assertThat(result.getSummary()).isEqualTo("auto:glm-4-plus");
    }

    @Test
    @DisplayName("短输入（≤500 token）→ simple → cheap 池")
    void shortInputSimple() {
        RoutingEngine.RouteResult result = engine.select(new ChatRequest(), 100, null, null);
        assertThat(result.getComplexity()).isEqualTo("simple");
        assertThat(result.getSelected().getModelCode()).isEqualTo("glm-4-flash");
    }

    @Test
    @DisplayName("长输入（≥2000 token）→ complex → strong 池")
    void longInputComplex() {
        RoutingEngine.RouteResult result = engine.select(new ChatRequest(), 3_000, null, null);
        assertThat(result.getComplexity()).isEqualTo("complex");
        assertThat(result.getSelected().getModelCode()).isEqualTo("glm-4-plus");
    }

    @Test
    @DisplayName("中间输入 → balanced 全池：两者未定价（cost=0）时质量权重主导 → strong")
    void balancedPrefersQualityByWeight() {
        RoutingEngine.RouteResult result = engine.select(new ChatRequest(), 1_000, null, null);
        assertThat(result.getComplexity()).isEqualTo("balanced");
        assertThat(result.getSelected().getModelCode()).isEqualTo("glm-4-plus");
    }

    // ==================== 健康 / 可用性 ====================

    @Test
    @DisplayName("健康过滤清空候选时回退原池（fail-open，可用性优先）")
    void healthFilterFallsBackWhenAllBlacklisted() {
        when(routeStatsService.isBlacklisted(anyString())).thenReturn(true);
        ChatRequest request = new ChatRequest();
        request.setTools(objectMapper.createArrayNode()); // complex → strong 池
        RoutingEngine.RouteResult result = engine.select(request, 100, null, null);
        // strong 被拉黑但池清空后回退 → 仍选出 strong，且决策 JSON 记录黑名单
        assertThat(result.getSelected().getModelCode()).isEqualTo("glm-4-plus");
        assertThat(result.getDecisionJson()).contains("healthBlacklisted");
    }

    // ==================== 预算 / SLA（严格，不回退） ====================

    @Test
    @DisplayName("预算过滤：超预算模型被剔除，未定价（0 元）模型可过")
    void budgetFilterIsStrict() {
        when(modelConfigService.currentPrice("glm-4-plus")).thenReturn(price(0.05, 0.05));
        // balanced 全池；预算 0.000001 元 → strong（≥0.05 元）剔除，cheap（未定价=0 元）入选
        RoutingEngine.RouteResult result = engine.select(new ChatRequest(), 1_000,
                new BigDecimal("0.000001"), null);
        assertThat(result.getSelected().getModelCode()).isEqualTo("glm-4-flash");
    }

    @Test
    @DisplayName("预算过滤清空候选 → 严格报错（不回退）")
    void budgetEmptyThrows() {
        assertThatThrownBy(() -> engine.select(new ChatRequest(), 1_000, new BigDecimal("-1"), null))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("SLA 过滤：P90 预测（冷启动回退 baseline）超限的模型被剔除")
    void slaFilterIsStrict() {
        // cheap baseline 3000ms ≤ 5000；strong baseline 8000ms > 5000 → balanced 下只留 cheap
        RoutingEngine.RouteResult result = engine.select(new ChatRequest(), 1_000, null, 5_000L);
        assertThat(result.getSelected().getModelCode()).isEqualTo("glm-4-flash");
    }

    @Test
    @DisplayName("SLA 过滤清空候选 → 严格报错（不回退）")
    void slaEmptyThrows() {
        assertThatThrownBy(() -> engine.select(new ChatRequest(), 1_000, null, 1L))
                .isInstanceOf(BizException.class);
    }

    // ==================== 特征值 / 兜底 ====================

    @Test
    @DisplayName("统计样本 ≥20 时质量分取实测均值而非 baseline")
    void qualitySamplesOverrideBaseline() {
        when(routeStatsService.qualitySamples("glm-4-plus")).thenReturn(25L);
        when(routeStatsService.avgQuality("glm-4-plus")).thenReturn(50.0); // 实测 50 < baseline 92
        RoutingEngine.RouteResult result = engine.select(new ChatRequest(), 1_000, null, null);
        // strong 实测质量 50 < cheap baseline 80 → balanced 下选 cheap
        assertThat(result.getSelected().getModelCode()).isEqualTo("glm-4-flash");
    }

    @Test
    @DisplayName("权重非法（null）时回退默认 0.6/0.4，决策 JSON 可追溯")
    void weightFallbackToDefault() {
        when(modelConfigService.getRoutingConfig()).thenReturn(config(null, null));
        RoutingEngine.RouteResult result = engine.select(new ChatRequest(), 1_000, null, null);
        assertThat(result.getDecisionJson()).contains("\"quality\":0.6").contains("\"cost\":0.4");
    }

    @Test
    @DisplayName("候选池为空 → 报错（网关无模型可路由）")
    void emptyPoolThrows() {
        when(modelConfigService.listEnabledModels()).thenReturn(List.of());
        assertThatThrownBy(() -> engine.select(new ChatRequest(), 1_000, null, null))
                .isInstanceOf(BizException.class);
    }

    // ==================== 测试夹具 ====================

    private ModelInfo model(String code, String tier, int baselineQuality, int baselineLatencyMs) {
        ModelInfo m = new ModelInfo();
        m.setModelCode(code);
        m.setModelTier(tier);
        m.setBaselineQuality(baselineQuality);
        m.setBaselineLatencyMs(baselineLatencyMs);
        return m;
    }

    private RoutingConfig config(Double wq, Double wc) {
        RoutingConfig c = new RoutingConfig();
        c.setEnabled(1);
        c.setWeightQuality(wq);
        c.setWeightCost(wc);
        return c;
    }

    private ModelPrice price(double in, double out) {
        ModelPrice p = new ModelPrice();
        p.setPriceInput(BigDecimal.valueOf(in));
        p.setPriceOutput(BigDecimal.valueOf(out));
        return p;
    }
}

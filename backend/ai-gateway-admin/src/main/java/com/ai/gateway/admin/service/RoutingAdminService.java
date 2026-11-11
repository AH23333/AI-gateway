package com.ai.gateway.admin.service;

import com.ai.gateway.admin.mapper.InvokeStatsMapper;
import com.ai.gateway.common.constant.BizCodeEnum;
import com.ai.gateway.common.exception.BizException;
import com.ai.gateway.common.pojo.InvokeLogV2;
import com.ai.gateway.common.pojo.ModelInfo;
import com.ai.gateway.common.pojo.ModelPrice;
import com.ai.gateway.common.pojo.RoutingConfig;
import com.ai.gateway.common.dto.PageResult;
import com.ai.gateway.router.mapper.RoutingConfigMapper;
import com.ai.gateway.router.service.ModelConfigService;
import com.ai.gateway.router.service.RouteStatsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 路由管理服务 —— 开关/权重热更新 + 路由分析 + 决策回放（FR-11/FR-05 管理面）
 *
 * 热更新闭环：改 t_routing_config → ModelConfigService.invalidateRoutingConfig()
 * → 下一笔请求读新配置 —— 无需重启，60s TTL 只作兜底。
 *
 * 路由分析（/admin/routing/analysis）：逐模型聚合实时状态
 * （质量样本/均值、P90 延迟、健康分、熔断原因、生效价），
 * 数据源全部自算（Redis 统计 + t_model 元数据），不依赖注册中心。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoutingAdminService {

    private final RoutingConfigMapper routingConfigMapper;
    private final ModelConfigService modelConfigService;
    private final RouteStatsService routeStatsService;
    private final InvokeStatsMapper invokeStatsMapper;

    /** 当前路由配置（缓存读取） */
    public RoutingConfig getConfig() {
        return modelConfigService.getRoutingConfig();
    }

    /** 路由总开关（FR-11） */
    @Transactional
    public void updateSwitch(Integer enabled) {
        if (enabled == null || (enabled != 0 && enabled != 1)) {
            throw BizException.of(BizCodeEnum.BAD_REQUEST);
        }
        RoutingConfig config = requireConfigRow();
        config.setEnabled(enabled);
        config.setGmtModified(LocalDateTime.now());
        routingConfigMapper.updateById(config);
        modelConfigService.invalidateRoutingConfig();
        log.info("路由开关切换: enabled={}", enabled);
    }

    /** 打分权重更新（FR-05，w_q + w_c = 1 强校验） */
    @Transactional
    public void updateWeights(Double weightQuality, Double weightCost) {
        if (weightQuality == null || weightCost == null
                || weightQuality < 0 || weightCost < 0
                || Math.abs(weightQuality + weightCost - 1.0) > 1e-6) {
            throw BizException.of(BizCodeEnum.BAD_REQUEST);
        }
        RoutingConfig config = requireConfigRow();
        config.setWeightQuality(weightQuality);
        config.setWeightCost(weightCost);
        config.setGmtModified(LocalDateTime.now());
        routingConfigMapper.updateById(config);
        modelConfigService.invalidateRoutingConfig();
        log.info("路由权重更新: w_q={}, w_c={}", weightQuality, weightCost);
    }

    /** 逐模型实时路由分析（启用模型全量） */
    public List<Map<String, Object>> analysis() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ModelInfo model : modelConfigService.listEnabledModels()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("modelCode", model.getModelCode());
            item.put("modelName", model.getModelName());
            item.put("modelTier", model.getModelTier());
            item.put("fallbackCode", model.getFallbackCode());
            item.put("baselineQuality", model.getBaselineQuality());
            item.put("baselineLatencyMs", model.getBaselineLatencyMs());
            item.put("qualitySamples", routeStatsService.qualitySamples(model.getModelCode()));
            item.put("avgQuality", routeStatsService.avgQuality(model.getModelCode()));
            item.put("p90LatencyMs", routeStatsService.p90LatencyMs(model.getModelCode()));
            item.put("healthScore", routeStatsService.healthScore(model.getModelCode()));
            boolean blacklisted = routeStatsService.isBlacklisted(model.getModelCode());
            item.put("blacklisted", blacklisted);
            if (blacklisted) {
                item.put("blacklistReason", routeStatsService.blacklistReason(model.getModelCode()));
            }
            ModelPrice price = modelConfigService.currentPrice(model.getModelCode());
            item.put("priceInput", price == null ? null : price.getPriceInput());
            item.put("priceOutput", price == null ? null : price.getPriceOutput());
            item.put("priceVersion", price == null ? null : price.getVersion());
            result.add(item);
        }
        return result;
    }

    /** 智能路由决策日志分页（routing_decision 非空，可解释性回放） */
    public PageResult<InvokeLogV2> decisions(int page, int size) {
        long total = invokeStatsMapper.countRoutingDecisions();
        int offset = (page - 1) * size;
        List<InvokeLogV2> records =
                invokeStatsMapper.pageRoutingDecisions(offset, size);
        return PageResult.of(records, total, page, size);
    }

    /** 配置行必须存在（缺失说明 seed 未执行） */
    private RoutingConfig requireConfigRow() {
        RoutingConfig config = routingConfigMapper.selectById(1L);
        if (config == null) {
            throw BizException.of(BizCodeEnum.INTERNAL_ERROR);
        }
        return config;
    }
}

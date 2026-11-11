package com.ai.gateway.router.service;

import com.ai.gateway.common.constant.BizCodeEnum;
import com.ai.gateway.common.constant.CommonStatusEnum;
import com.ai.gateway.common.exception.BizException;
import com.ai.gateway.common.pojo.ModelInfo;
import com.ai.gateway.common.pojo.ModelPrice;
import com.ai.gateway.common.pojo.RoutingConfig;
import com.ai.gateway.router.mapper.ModelInfoMapper;
import com.ai.gateway.router.mapper.ModelPriceMapper;
import com.ai.gateway.router.mapper.RoutingConfigMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 模型配置服务 —— 模型/价格/路由配置的统一读取口（Caffeine 60s 本地缓存）
 *
 * 为什么加缓存？
 * 一次对话链路要读：候选模型列表 + 每模型价格 + 路由配置 ≈ 3~5 条 SQL，
 * 高频对话下数据库 QPS 与对话 QPS 同阶 —— 缓存后降至 1 QPS/60s。
 *
 * 一致性策略（单体下的最简正确解）：
 * - 60s TTL 兜底过期；
 * - 管理端更新（admin 模块）调用本类 invalidate* 方法主动失效 ——
 *   "改库 + 清缓存"在单 JVM 内天然有序；多实例部署时升级为配置中心
 *   广播失效（选型书第八章触发条件之一）。
 *
 * 缓存 key 设计：Object key + 手动区分（MODELS/PRICE:{code}/CONFIG），
 * 避免多泛型 Cache 字段冗余。
 */
@Slf4j
@Service
public class ModelConfigService {

    private static final String KEY_MODELS = "MODELS";
    private static final String KEY_CONFIG = "ROUTING_CONFIG";
    private static final String KEY_PRICE_PREFIX = "PRICE:";

    private final ModelInfoMapper modelInfoMapper;
    private final ModelPriceMapper modelPriceMapper;
    private final RoutingConfigMapper routingConfigMapper;

    /** 统一缓存：模型列表 / 路由配置 / 单模型价格，60s 过期 */
    private final Cache<String, Object> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))
            .maximumSize(1_000)
            .build();

    public ModelConfigService(ModelInfoMapper modelInfoMapper,
                              ModelPriceMapper modelPriceMapper,
                              RoutingConfigMapper routingConfigMapper) {
        this.modelInfoMapper = modelInfoMapper;
        this.modelPriceMapper = modelPriceMapper;
        this.routingConfigMapper = routingConfigMapper;
    }

    /** 全部启用态模型（id 升序，路由候选池） */
    public List<ModelInfo> listEnabledModels() {
        Object cached = cache.get(KEY_MODELS, k -> modelInfoMapper.selectList(
                new LambdaQueryWrapper<ModelInfo>()
                        .eq(ModelInfo::getStatus, CommonStatusEnum.ENABLED.getCode())
                        .orderByAsc(ModelInfo::getId)));
        if (cached instanceof List<?> list) {
            @SuppressWarnings("unchecked")
            List<ModelInfo> models = (List<ModelInfo>) list;
            return models;
        }
        return List.of();
    }

    /** 按编码查启用态模型（不存在/已禁用抛业务异常，手动指定路径用） */
    public ModelInfo getEnabledModel(String modelCode) {
        return listEnabledModels().stream()
                .filter(m -> m.getModelCode().equals(modelCode))
                .findFirst()
                .orElseThrow(() -> BizException.of(BizCodeEnum.MODEL_NOT_FOUND, modelCode));
    }

    /** 模型编码是否存在（任意状态，管理端 CRUD 校验用） */
    public boolean existsByCode(String modelCode) {
        return modelInfoMapper.selectCount(new LambdaQueryWrapper<ModelInfo>()
                .eq(ModelInfo::getModelCode, modelCode)) > 0;
    }

    /** 当前生效价格（未配置返回 null —— 未定价模型按 0 元计费并告警） */
    public ModelPrice currentPrice(String modelCode) {
        String key = KEY_PRICE_PREFIX + modelCode;
        Object cached = cache.get(key, k -> {
            ModelPrice price = modelPriceMapper.selectEffective(modelCode);
            // 未定价缓存为哨兵对象，防止缓存穿透（每次对话都打库）
            return price != null ? price : NIL_PRICE;
        });
        if (cached == NIL_PRICE) {
            return null;
        }
        return cached instanceof ModelPrice price ? price : null;
    }

    /** 路由配置（单行表缺失时返回默认值：路由关闭 + 默认权重，保证系统可启动） */
    public RoutingConfig getRoutingConfig() {
        Object cached = cache.get(KEY_CONFIG, k -> {
            RoutingConfig config = routingConfigMapper.selectById(1L);
            return config != null ? config : defaultConfig();
        });
        if (cached instanceof RoutingConfig config) {
            return config;
        }
        return defaultConfig();
    }

    // ==================== 管理端主动失效入口 ====================

    /** 模型增删改/调池后失效（admin 模块调用） */
    public void invalidateModels() {
        cache.invalidate(KEY_MODELS);
        log.info("模型列表缓存已失效");
    }

    /** 价格变更后失效对应模型（admin 模块调用） */
    public void invalidatePrice(String modelCode) {
        cache.invalidate(KEY_PRICE_PREFIX + modelCode);
        log.info("价格缓存已失效: model={}", modelCode);
    }

    /** 路由开关/权重变更后失效（admin 模块调用） */
    public void invalidateRoutingConfig() {
        cache.invalidate(KEY_CONFIG);
        log.info("路由配置缓存已失效");
    }

    private RoutingConfig defaultConfig() {
        RoutingConfig config = new RoutingConfig();
        config.setId(1L);
        config.setEnabled(0);
        config.setWeightQuality(0.6);
        config.setWeightCost(0.4);
        config.setGmtModified(LocalDateTime.now());
        return config;
    }

    /** 未定价哨兵（与 null 区分，防缓存穿透） */
    private static final ModelPrice NIL_PRICE = new ModelPrice();
}

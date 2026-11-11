package com.ai.gateway.admin.service;

import com.ai.gateway.admin.dto.ModelUpsertRequest;
import com.ai.gateway.admin.dto.PriceUpsertRequest;
import com.ai.gateway.admin.mapper.InvokeStatsMapper;
import com.ai.gateway.common.constant.BizCodeEnum;
import com.ai.gateway.common.exception.BizException;
import com.ai.gateway.common.pojo.ModelInfo;
import com.ai.gateway.common.pojo.ModelPrice;
import com.ai.gateway.router.mapper.ModelInfoMapper;
import com.ai.gateway.router.mapper.ModelPriceMapper;
import com.ai.gateway.router.service.ModelConfigService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 模型管理服务 —— 模型 CRUD + 价格版本管理（v2 新增：价格版本化）
 *
 * 缓存一致性（关键路径）：
 * 任何模型/价格变更后必须调用 ModelConfigService.invalidate*()，
 * 否则路由引擎最长 60s 内仍按旧元数据决策 —— 单体下"改库 + 清缓存"
 * 天然有序，这是选型书第四章模块化单体的一致性红利。
 *
 * 删除保护：
 * - 模型有调用日志 → MODEL_DELETE_BLOCKED（历史账单按编码引用）；
 * - 模型被其他模型引用为备用 → 同样阻止（防降级链断裂）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelAdminService {

    /** 合法模型池等级 */
    private static final Set<String> VALID_TIERS = Set.of("cheap", "strong", "universal");

    private final ModelInfoMapper modelInfoMapper;
    private final ModelPriceMapper modelPriceMapper;
    private final InvokeStatsMapper invokeStatsMapper;
    private final ModelConfigService modelConfigService;

    /** 全量模型列表（含禁用，管理端展示） */
    public List<ModelInfo> listModels() {
        return modelInfoMapper.selectList(
                new LambdaQueryWrapper<ModelInfo>().orderByAsc(ModelInfo::getId));
    }

    /** 新增模型 */
    @Transactional
    public ModelInfo createModel(ModelUpsertRequest req) {
        requireText(req.getModelCode(), "modelCode");
        requireText(req.getEndpoint(), "endpoint");
        if (modelConfigService.existsByCode(req.getModelCode().trim())) {
            throw BizException.of(BizCodeEnum.MODEL_CODE_DUPLICATE);
        }
        validateTier(req.getModelTier());
        validateFallback(req.getFallbackCode(), req.getModelCode().trim());

        ModelInfo model = new ModelInfo();
        model.setModelCode(req.getModelCode().trim());
        model.setModelName(textOr(req.getModelName(), req.getModelCode().trim()));
        model.setEndpoint(req.getEndpoint().trim());
        model.setModelTier(textOr(req.getModelTier(), "universal"));
        model.setFallbackCode(req.getFallbackCode());
        model.setBaselineQuality(req.getBaselineQuality() != null ? req.getBaselineQuality() : 60);
        model.setBaselineLatencyMs(req.getBaselineLatencyMs() != null ? req.getBaselineLatencyMs() : 30_000);
        model.setStatus(req.getStatus() != null ? req.getStatus() : 1);
        model.setGmtCreate(LocalDateTime.now());
        model.setGmtModified(LocalDateTime.now());
        modelInfoMapper.insert(model);
        modelConfigService.invalidateModels();
        log.info("模型创建: code={}, tier={}", model.getModelCode(), model.getModelTier());
        return model;
    }

    /** 编辑模型（modelCode 可改但强烈不建议——历史日志按编码引用） */
    @Transactional
    public ModelInfo updateModel(Long id, ModelUpsertRequest req) {
        ModelInfo model = requireModel(id);
        if (req.getModelCode() != null && !req.getModelCode().isBlank()
                && !req.getModelCode().trim().equals(model.getModelCode())) {
            if (modelConfigService.existsByCode(req.getModelCode().trim())) {
                throw BizException.of(BizCodeEnum.MODEL_CODE_DUPLICATE);
            }
            log.warn("模型编码变更(历史日志仍按旧编码): {} → {}", model.getModelCode(), req.getModelCode());
            model.setModelCode(req.getModelCode().trim());
        }
        if (req.getModelName() != null && !req.getModelName().isBlank()) {
            model.setModelName(req.getModelName());
        }
        if (req.getEndpoint() != null && !req.getEndpoint().isBlank()) {
            model.setEndpoint(req.getEndpoint().trim());
        }
        if (req.getApiKey() != null && !req.getApiKey().isBlank()) {
            model.setApiKey(req.getApiKey().trim());
        }
        if (req.getModelTier() != null) {
            validateTier(req.getModelTier());
            model.setModelTier(req.getModelTier());
        }
        if (req.getFallbackCode() != null) {
            validateFallback(req.getFallbackCode(), model.getModelCode());
            model.setFallbackCode(req.getFallbackCode().isBlank() ? null : req.getFallbackCode());
        }
        if (req.getBaselineQuality() != null) {
            model.setBaselineQuality(req.getBaselineQuality());
        }
        if (req.getBaselineLatencyMs() != null) {
            model.setBaselineLatencyMs(req.getBaselineLatencyMs());
        }
        if (req.getStatus() != null) {
            if (req.getStatus() != 0 && req.getStatus() != 1) {
                throw BizException.of(BizCodeEnum.BAD_REQUEST);
            }
            model.setStatus(req.getStatus());
        }
        model.setGmtModified(LocalDateTime.now());
        modelInfoMapper.updateById(model);
        modelConfigService.invalidateModels();
        log.info("模型更新: code={}", model.getModelCode());
        return model;
    }

    /** 删除模型（有日志/被引用时拒绝，只能禁用） */
    @Transactional
    public void deleteModel(Long id) {
        ModelInfo model = requireModel(id);
        if (invokeStatsMapper.countByModelCode(model.getModelCode()) > 0) {
            throw BizException.of(BizCodeEnum.MODEL_DELETE_BLOCKED);
        }
        Long referencedAsFallback = modelInfoMapper.selectCount(
                new LambdaQueryWrapper<ModelInfo>().eq(ModelInfo::getFallbackCode, model.getModelCode()));
        if (referencedAsFallback != null && referencedAsFallback > 0) {
            throw BizException.of(BizCodeEnum.MODEL_DELETE_BLOCKED);
        }
        modelInfoMapper.deleteById(id);
        modelConfigService.invalidateModels();
        log.info("模型删除: code={}", model.getModelCode());
    }

    /** 价格版本历史（新版本在前） */
    public List<ModelPrice> priceHistory(Long modelId) {
        ModelInfo model = requireModel(modelId);
        return modelPriceMapper.selectList(new LambdaQueryWrapper<ModelPrice>()
                .eq(ModelPrice::getModelCode, model.getModelCode())
                .orderByDesc(ModelPrice::getVersion));
    }

    /** 新增价格版本（version+1，立即生效） */
    @Transactional
    public ModelPrice addPrice(Long modelId, PriceUpsertRequest req) {
        ModelInfo model = requireModel(modelId);
        if (req.getPriceInput() == null || req.getPriceOutput() == null
                || req.getPriceInput().signum() <= 0 || req.getPriceOutput().signum() <= 0) {
            throw BizException.of(BizCodeEnum.BAD_REQUEST);
        }
        ModelPrice latest = modelPriceMapper.selectOne(new LambdaQueryWrapper<ModelPrice>()
                .eq(ModelPrice::getModelCode, model.getModelCode())
                .orderByDesc(ModelPrice::getVersion)
                .last("LIMIT 1"));
        int nextVersion = latest == null || latest.getVersion() == null ? 1 : latest.getVersion() + 1;

        ModelPrice price = new ModelPrice();
        price.setModelCode(model.getModelCode());
        price.setVersion(nextVersion);
        price.setPriceInput(req.getPriceInput());
        price.setPriceOutput(req.getPriceOutput());
        price.setEffectiveTime(LocalDateTime.now());
        price.setStatus(1);
        price.setGmtCreate(LocalDateTime.now());
        modelPriceMapper.insert(price);
        // 关键：立即失效价格缓存，下一笔调用按新价计费
        modelConfigService.invalidatePrice(model.getModelCode());
        log.info("价格版本新增: model={}, version={}, in={}, out={}",
                model.getModelCode(), nextVersion, req.getPriceInput(), req.getPriceOutput());
        return price;
    }

    // ==================== 内部 ====================

    private ModelInfo requireModel(Long id) {
        ModelInfo model = modelInfoMapper.selectById(id);
        if (model == null) {
            throw BizException.of(BizCodeEnum.MODEL_NOT_FOUND, String.valueOf(id));
        }
        return model;
    }

    private void validateTier(String tier) {
        if (tier != null && !VALID_TIERS.contains(tier)) {
            throw BizException.of(BizCodeEnum.BAD_REQUEST);
        }
    }

    private void validateFallback(String fallbackCode, String selfCode) {
        if (fallbackCode == null || fallbackCode.isBlank()) {
            return;
        }
        if (fallbackCode.equals(selfCode)) {
            throw BizException.of(BizCodeEnum.BAD_REQUEST);
        }
        if (!modelConfigService.existsByCode(fallbackCode)) {
            throw BizException.of(BizCodeEnum.FALLBACK_MODEL_NOT_FOUND, fallbackCode);
        }
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw BizException.of(BizCodeEnum.BAD_REQUEST);
        }
    }

    private String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

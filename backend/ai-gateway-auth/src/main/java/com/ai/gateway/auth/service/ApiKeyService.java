package com.ai.gateway.auth.service;

import com.ai.gateway.auth.mapper.ApiKeyMapper;
import com.ai.gateway.common.constant.BizCodeEnum;
import com.ai.gateway.common.constant.CommonStatusEnum;
import com.ai.gateway.common.dto.ApiKeyVO;
import com.ai.gateway.common.exception.BizException;
import com.ai.gateway.common.pojo.ApiKey;
import com.ai.gateway.common.util.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * API Key 服务（v2：从 admin 域划入凭证域，哈希化存储）
 *
 * 安全规则（选型书 3.1，修 P8）：
 * - 落库只存 SHA-256 哈希 + 前 8 位明文前缀；
 * - 明文仅在创建响应里返回一次；
 * - 鉴权路径：access 模块把请求头的 key 哈希后走 findByHash 精确匹配。
 *
 * 生成格式：sk-{32 位随机十六进制}，前缀取前 8 位（含 "sk-"）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private final ApiKeyMapper apiKeyMapper;

    /** 创建 API Key：明文只返回这一次 */
    @Transactional
    public ApiKeyVO create(Long userId, String keyName) {
        String plainKey = "sk-" + UUID.randomUUID().toString().replace("-", "");
        String prefix = plainKey.substring(0, Math.min(8, plainKey.length()));

        ApiKey apiKey = new ApiKey();
        apiKey.setUserId(userId);
        apiKey.setKeyHash(DigestUtil.sha256Hex(plainKey));
        apiKey.setKeyPrefix(prefix);
        apiKey.setKeyName(keyName == null || keyName.isBlank() ? "default" : keyName);
        apiKey.setStatus(CommonStatusEnum.ENABLED.getCode());
        apiKey.setGmtCreate(LocalDateTime.now());
        apiKey.setGmtExpire(LocalDateTime.now().plusYears(1));
        apiKeyMapper.insert(apiKey);

        log.info("API Key 创建: userId={}, keyPrefix={}", userId, prefix);
        return toVO(apiKey, plainKey);
    }

    /** 按 key 哈希精确查找启用态 Key（access 模块 API Key 鉴权路径） */
    public ApiKey findByHash(String plainKey) {
        if (plainKey == null || plainKey.isBlank()) {
            return null;
        }
        return apiKeyMapper.selectOne(new LambdaQueryWrapper<ApiKey>()
                .eq(ApiKey::getKeyHash, DigestUtil.sha256Hex(plainKey))
                .eq(ApiKey::getStatus, CommonStatusEnum.ENABLED.getCode())
                .last("LIMIT 1"));
    }

    /** 用户是否存在启用态 API Key（ChatService 前置校验，基线规则保留） */
    public boolean hasActiveApiKey(Long userId) {
        return apiKeyMapper.selectCount(new LambdaQueryWrapper<ApiKey>()
                .eq(ApiKey::getUserId, userId)
                .eq(ApiKey::getStatus, CommonStatusEnum.ENABLED.getCode())) > 0;
    }

    /** 获取用户第一个启用态 API Key（供计费归因取 apiKeyId） */
    public ApiKey getFirstActive(Long userId) {
        return apiKeyMapper.selectOne(new LambdaQueryWrapper<ApiKey>()
                .eq(ApiKey::getUserId, userId)
                .eq(ApiKey::getStatus, CommonStatusEnum.ENABLED.getCode())
                .orderByAsc(ApiKey::getId)
                .last("LIMIT 1"));
    }

    /** 用户自己的 Key 列表（永不返回明文/哈希） */
    public List<ApiKeyVO> listByUser(Long userId) {
        List<ApiKey> keys = apiKeyMapper.selectList(new LambdaQueryWrapper<ApiKey>()
                .eq(ApiKey::getUserId, userId)
                .orderByDesc(ApiKey::getGmtCreate));
        return keys.stream().map(k -> toVO(k, null)).toList();
    }

    /** 吊销用户自己的 Key（校验所有权，防越权吊销他人 Key） */
    @Transactional
    public void revokeByOwner(Long id, Long userId) {
        ApiKey apiKey = requireOwned(id, userId);
        apiKey.setStatus(CommonStatusEnum.DISABLED.getCode());
        apiKeyMapper.updateById(apiKey);
    }

    /** 管理端吊销任意 Key */
    @Transactional
    public void revokeByAdmin(Long id) {
        ApiKey apiKey = apiKeyMapper.selectById(id);
        if (apiKey == null) {
            throw BizException.of(BizCodeEnum.API_KEY_NOT_FOUND);
        }
        apiKey.setStatus(CommonStatusEnum.DISABLED.getCode());
        apiKeyMapper.updateById(apiKey);
    }

    /** 管理端全量 Key 分页（脱敏 VO） */
    public Page<ApiKeyVO> pageAll(Integer page, Integer size) {
        Page<ApiKey> result = apiKeyMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<ApiKey>().orderByDesc(ApiKey::getGmtCreate));
        Page<ApiKeyVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(result.getRecords().stream().map(k -> toVO(k, null)).toList());
        return voPage;
    }

    private ApiKey requireOwned(Long id, Long userId) {
        ApiKey apiKey = apiKeyMapper.selectById(id);
        if (apiKey == null) {
            throw BizException.of(BizCodeEnum.API_KEY_NOT_FOUND);
        }
        if (!apiKey.getUserId().equals(userId)) {
            throw BizException.of(BizCodeEnum.API_KEY_FORBIDDEN);
        }
        return apiKey;
    }

    /** 实体 → VO；fullKey 仅创建时传入，其余场景恒 null */
    private ApiKeyVO toVO(ApiKey apiKey, String fullKey) {
        return ApiKeyVO.builder()
                .id(apiKey.getId())
                .userId(apiKey.getUserId())
                .keyName(apiKey.getKeyName())
                .keyPrefix(apiKey.getKeyPrefix())
                .fullKey(fullKey)
                .status(apiKey.getStatus())
                .gmtCreate(apiKey.getGmtCreate())
                .gmtExpire(apiKey.getGmtExpire())
                .build();
    }
}

package com.ai.gateway.admin.dto;

import lombok.Data;

/**
 * 模型新增/编辑请求（管理端）
 *
 * modelCode 创建后可改但不建议（历史日志按编码引用）；
 * modelTier：cheap（便宜池）/ strong（强模型池）/ universal（全池通用）。
 */
@Data
public class ModelUpsertRequest {

    /** 模型编码（如 qwen2.5:3b） */
    private String modelCode;

    /** 展示名称 */
    private String modelName;

    /** 上游端点 URL（OpenAI 兼容 /chat/completions 完整地址） */
    private String endpoint;

    /**
     * 上游鉴权 API Key（Bearer）
     * 云端厂商必填；本地无鉴权上游留空。编辑时传空 = 保持原值不覆盖。
     */
    private String apiKey;

    /** 模型池等级 */
    private String modelTier;

    /** 备用模型编码 */
    private String fallbackCode;

    /** 冷启动基准质量分 [0,100] */
    private Integer baselineQuality;

    /** 冷启动基准延迟（毫秒） */
    private Integer baselineLatencyMs;

    /** 状态：0=禁用, 1=启用 */
    private Integer status;
}

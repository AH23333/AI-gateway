package com.ai.gateway.common.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模型实体 —— 对应 t_model 表（v2：由 t_model_config 演进）
 *
 * v2 关键变更（1.md §8.1）：
 * - 表更名 t_model_config → t_model（选型书 D3 元数据域）；
 * - 新增 model_tier（cheap/strong/universal）：模型池等级，复杂度匹配的
 *   候选池依据（FR-12），管理端可调整；
 * - 新增 baseline_quality / baseline_latency_ms：冷启动回退静态值
 *   （Redis 统计样本 < 20 时使用，1.md §6.2）；
 * - 价格剥离到 t_model_price（价格版本化，选型书 3.7）。
 */
@Data
@TableName("t_model")
public class ModelInfo {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 模型编码，唯一（如 qwen2.5:3b），请求 model 字段的取值 */
    private String modelCode;

    /** 模型展示名称（如 Qwen2.5-3B） */
    private String modelName;

    /**
     * 上游推理端点完整 URL（OpenAI 兼容 /chat/completions）
     * 如 https://open.bigmodel.cn/api/paas/v4/chat/completions
     */
    private String endpoint;

    /**
     * 上游鉴权 API Key（Authorization: Bearer 携带）
     * 云端厂商（GLM/DeepSeek 等）必填；本地无鉴权上游（Ollama/vLLM）留空。
     * 按模型行存储而非全局配置：不同模型可指向不同厂商、各持各的 Key。
     */
    private String apiKey;

    /**
     * 模型池等级：cheap=便宜池 / strong=强模型池 / universal=全池通用
     * 复杂度匹配规则（1.md §6.2）：simple→cheap，complex→strong，balanced→全池
     */
    private String modelTier;

    /** 备用模型编码：主模型不可用时自动降级（基线行为保留） */
    private String fallbackCode;

    /** 冷启动基准质量分 [0,100]：统计样本不足时打分回退用，管理端可改 */
    private Integer baselineQuality;

    /** 冷启动基准延迟（毫秒）：统计样本不足时 SLA 过滤/预测回退用 */
    private Integer baselineLatencyMs;

    /** 状态：0=禁用, 1=启用 */
    private Integer status;

    private LocalDateTime gmtCreate;

    private LocalDateTime gmtModified;
}

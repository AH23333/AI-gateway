package com.ai.gateway.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * API Key 视图对象（auth 模块对外输出专用）
 *
 * 安全规则（选型书 3.1，修 P8）：
 * 1. t_api_key 表只存 SHA-256 哈希，明文只在"创建那一次"返回；
 * 2. 列表/详情永远只返回前 8 位前缀（keyPrefix），服务端不可逆推明文。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiKeyVO {

    /** 主键 */
    private Long id;

    /** 关联用户 ID */
    private Long userId;

    /** 密钥名称（备注） */
    private String keyName;

    /** 明文前缀（sk-xxxx 形式的前 8 位，用于列表辨识） */
    private String keyPrefix;

    /**
     * 完整明文密钥：仅创建接口返回一次，其余场景恒为 null。
     * 前端提示用户"关闭弹窗后无法再次查看"。
     */
    private String fullKey;

    /** 状态：0=禁用, 1=启用 */
    private Integer status;

    private LocalDateTime gmtCreate;

    /** 过期时间 */
    private LocalDateTime gmtExpire;
}

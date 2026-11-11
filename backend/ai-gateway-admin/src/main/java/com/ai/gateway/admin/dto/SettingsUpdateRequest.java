package com.ai.gateway.admin.dto;

import lombok.Data;

/**
 * 设置项更新请求（PUT /admin/settings）
 *
 * 单键单值：不同键校验规则不同（长度/区间），逐键保存比整表提交更清晰。
 */
@Data
public class SettingsUpdateRequest {

    /** 配置键（SystemConfigKey 已知键之一） */
    private String key;

    /** 配置值（文本，服务端按键校验并归一化） */
    private String value;
}

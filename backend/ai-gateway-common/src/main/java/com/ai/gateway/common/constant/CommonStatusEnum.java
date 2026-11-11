package com.ai.gateway.common.constant;

/**
 * 通用状态枚举
 *
 * 用于数据库字段（如 status、fallback_used 等布尔语义列）
 * 0=禁用/否，1=启用/是
 */
public enum CommonStatusEnum {

    DISABLED(0, "禁用"),
    ENABLED(1, "启用");

    private final int code;
    private final String desc;

    CommonStatusEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }
}

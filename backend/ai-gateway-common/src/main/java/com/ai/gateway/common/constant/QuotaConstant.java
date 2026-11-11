package com.ai.gateway.common.constant;

/**
 * 用户配额常量
 *
 * 注册新用户的默认配额（Token 数）。
 * 单位说明：配额按"实际消耗的模型 Token"计量（prompt + completion），
 * 由 billing 模块两段式（reserve → settle）保证不超扣。
 */
public class QuotaConstant {

    /** 默认每日配额：100 万 Token */
    public static final long DEFAULT_DAILY_QUOTA = 1_000_000L;

    /** 默认每月配额：1000 万 Token */
    public static final long DEFAULT_MONTHLY_QUOTA = 10_000_000L;

    private QuotaConstant() {
    }
}

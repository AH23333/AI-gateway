package com.ai.gateway.common.constant;

/**
 * 限流常量
 *
 * access 模块 Redis Lua 固定窗口限流使用（原网关限流策略原样保留）。
 */
public class RateLimitConstant {

    /** 默认每个用户每窗口的最大请求数 */
    public static final String DEFAULT_LIMIT = "100";

    /** 默认固定窗口大小（秒） */
    public static final String DEFAULT_WINDOW_SECONDS = "60";

    private RateLimitConstant() {
    }
}

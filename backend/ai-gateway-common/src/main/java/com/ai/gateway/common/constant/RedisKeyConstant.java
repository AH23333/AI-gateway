package com.ai.gateway.common.constant;

/**
 * Redis Key 统一登记处
 *
 * 为什么集中管理？
 * Redis Key 是跨模块共享的全局命名空间，散落在各处会造成
 * 命名冲突与"不知道谁在用"的问题。所有 key 前缀在此登记、加注释。
 *
 * TTL 策略（NFR-08）：统计类 key 统一 7 天过期，模型下架后自动过期。
 */
public final class RedisKeyConstant {

    // ==================== 认证域（auth/access）====================

    /** AccessToken 黑名单：登出/刷新时写入，TTL = Token 剩余有效期 */
    public static final String TOKEN_BLACKLIST = "token:blacklist:";

    /** RefreshToken 快速校验缓存（可选优化路径） */
    public static final String REFRESH_TOKEN = "refresh:token:";

    // ==================== 限流域（access）====================

    /** 固定窗口限流计数：rate:limit:{userId或IP}:{path} */
    public static final String RATE_LIMIT = "rate:limit:";

    // ==================== 智能路由统计域（router，1.md §8.2）====================

    /** 延迟窗口 ZSet：score=耗时 ms，member=traceId（重复投递同 member 只覆盖，天然幂等，NFR-03） */
    public static final String ROUTE_LATENCY = "route:latency:";

    /** 质量分 Hash：字段 scoreTotal/samples/errors，聚合质量分 = scoreTotal / samples */
    public static final String ROUTE_QUALITY = "route:quality:";

    /** 健康分/黑名单 String：存在即视为"冷却拉黑中"，值为拉黑原因（错误率超阈值） */
    public static final String ROUTE_HEALTH = "route:health:";

    /** 请求事件窗口 ZSet：score=时间戳，member=traceId，用于滑动窗口错误率合成 */
    public static final String ROUTE_REQ_WINDOW = "route:reqwin:";

    /** 错误事件窗口 ZSet：score=时间戳，member=traceId */
    public static final String ROUTE_ERR_WINDOW = "route:errwin:";

    /** D6 报文采样节流标记：每分钟首请求采样（SET NX EX 65s） */
    public static final String PAYLOAD_SAMPLE = "route:payload-sample:";

    private RedisKeyConstant() {
    }

    /** 拼接带模型编码的路由统计 key */
    public static String routeLatency(String modelCode) {
        return ROUTE_LATENCY + modelCode;
    }

    public static String routeQuality(String modelCode) {
        return ROUTE_QUALITY + modelCode;
    }

    public static String routeHealth(String modelCode) {
        return ROUTE_HEALTH + modelCode;
    }

    public static String routeReqWindow(String modelCode) {
        return ROUTE_REQ_WINDOW + modelCode;
    }

    public static String routeErrWindow(String modelCode) {
        return ROUTE_ERR_WINDOW + modelCode;
    }
}

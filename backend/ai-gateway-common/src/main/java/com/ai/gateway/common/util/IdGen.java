package com.ai.gateway.common.util;

import cn.hutool.core.util.IdUtil;

/**
 * 雪花 ID 生成器（v2：本地实现，替代百度 UidGenerator）
 *
 * v2 变更（选型书 3.8）：
 * - 删除 UidGenerator 与 WORKER_NODE 表（消除 DB 分配与 JDK 21 兼容风险）；
 * - 业务表主键全部改为数据库自增；
 * - 本工具只服务 traceId 场景（全链路追踪/预扣凭证/幂等键）。
 *
 * workerId 说明：单机部署用默认 1 即可；多实例时用 -Dapp.worker-id=N 区分，
 * 避免不同实例生成相同 ID（升级触发条件见选型书第八章）。
 */
public final class IdGen {

    private static final cn.hutool.core.lang.Snowflake SNOWFLAKE =
            IdUtil.getSnowflake(getLongProperty("app.worker-id", 1L), 1L);

    private IdGen() {
    }

    /** 生成 long 类型 ID */
    public static long nextId() {
        return SNOWFLAKE.nextId();
    }

    /** 生成 String 类型 ID（traceId 使用，纯数字字符串，≤19 位） */
    public static String nextIdStr() {
        return SNOWFLAKE.nextIdStr();
    }

    private static long getLongProperty(String key, long defaultValue) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}

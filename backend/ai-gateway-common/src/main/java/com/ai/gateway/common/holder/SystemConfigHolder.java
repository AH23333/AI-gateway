package com.ai.gateway.common.holder;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 系统设置进程内持有器（v2.4 系统设置中心）
 *
 * 为什么放 common 且静态？
 * access（限流）与 auth（JWT）都要读设置，但依赖方向上它们不能依赖 admin
 * （admin → common/auth/billing/router，access 只有 common）——
 * 零依赖的静态持有器是唯一的共享位置。
 *
 * 数据流（单向）：
 *   t_system_config --启动/管理端修改时--> SystemConfigService.reloadHolder() --> 本类
 *   auth / access / 公开设置接口只读本类，DB 缺行时回退 yml 默认值。
 *
 * 单实例语义：改库即刷新，天然一致；
 * 多实例升级为配置中心广播失效（同 RoutingConfig 缓存口径，选型书第八章）。
 */
public final class SystemConfigHolder {

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private SystemConfigHolder() {
    }

    /** 全量替换（SystemConfigService 装载/刷新用） */
    public static void replaceAll(Map<String, String> configs) {
        CACHE.clear();
        if (configs != null) {
            CACHE.putAll(configs);
        }
    }

    /** 读字符串；缺行/空值回退默认 */
    public static String get(String key, String defaultValue) {
        String value = CACHE.get(key);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }

    /** 读 int；缺行/空值/非数字回退默认（脏值不阻断请求链路） */
    public static int getInt(String key, int defaultValue) {
        String value = CACHE.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** 当前条目数（启动日志用） */
    public static int size() {
        return CACHE.size();
    }
}

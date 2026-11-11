package com.ai.gateway.common.constant;

import java.util.List;

/**
 * 系统设置键常量（v2.4 系统设置中心，1Panel 对标项 1）
 *
 * 键即 t_system_config.config_key，点分层级；
 * 值统一文本存储（config_value VARCHAR），读取方经 SystemConfigHolder 按需转型。
 *
 * 新增设置项三步：此登记键 → SystemConfigService 补描述/校验/默认值 → 设置页加分组。
 */
public final class SystemConfigKey {

    /** 站点名称（登录页品牌区与浏览器标题） */
    public static final String SITE_NAME = "site.name";

    /** 站点公告（登录页横幅，空串不展示） */
    public static final String SITE_ANNOUNCEMENT = "site.announcement";

    /** AccessToken 有效期（分钟，5-1440；改后下次登录签发生效） */
    public static final String JWT_ACCESS_EXPIRE_MINUTES = "jwt.access-expire-minutes";

    /** 固定窗口限流阈值（每用户/IP/每路径/窗口内最大请求数，1-100000） */
    public static final String ACCESS_RATE_LIMIT = "access.rate-limit";

    /** 限流窗口大小（秒，10-3600） */
    public static final String ACCESS_RATE_WINDOW_SECONDS = "access.rate-window-seconds";

    /** 全部已知键（SystemConfigService 启动自检补行用） */
    public static final List<String> ALL_KEYS = List.of(
            SITE_NAME,
            SITE_ANNOUNCEMENT,
            JWT_ACCESS_EXPIRE_MINUTES,
            ACCESS_RATE_LIMIT,
            ACCESS_RATE_WINDOW_SECONDS);

    private SystemConfigKey() {
    }
}

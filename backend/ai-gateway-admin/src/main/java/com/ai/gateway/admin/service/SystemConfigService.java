package com.ai.gateway.admin.service;

import com.ai.gateway.admin.mapper.SystemConfigMapper;
import com.ai.gateway.common.constant.BizCodeEnum;
import com.ai.gateway.common.constant.SystemConfigKey;
import com.ai.gateway.common.exception.BizException;
import com.ai.gateway.common.holder.SystemConfigHolder;
import com.ai.gateway.common.pojo.SystemConfig;
import com.ai.gateway.common.util.JwtUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统设置服务（v2.4 系统设置中心，1Panel 对标项 1）
 *
 * 职责：
 * - 启动自检：已知键缺行自动补种（默认值取 yml，description 以代码定义为准）；
 * - 装载：t_system_config → SystemConfigHolder（进程内缓存，access/auth 共读）；
 * - 更新：逐键校验归一化 → 落库 → 立即刷新 Holder（免重启生效）；
 *   JWT 有效期变更同时热更 JwtUtil（下次签发即按新值）。
 *
 * description 归代码管（改文案不用动 DB）、config_value 归管理端管 ——
 * 启动时 description 漂移自动回写，值永不覆盖。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemConfigService {

    private final SystemConfigMapper systemConfigMapper;

    @Value("${jwt.access-expire-minutes:30}")
    private long jwtAccessExpireMinutes;

    @Value("${access.rate-limit:100}")
    private int rateLimit;

    @Value("${access.rate-window-seconds:60}")
    private int rateWindowSeconds;

    @PostConstruct
    public void init() {
        ensureRows();
        reloadHolder();
        applyJwtExpire();
        log.info("系统设置中心就绪: {} 项 (jwt={}min, rateLimit={}/{})s",
                SystemConfigHolder.size(), jwtAccessExpireMinutes, rateLimit, rateWindowSeconds);
    }

    /** 全量设置项（设置页） */
    public List<SystemConfig> listAll() {
        return systemConfigMapper.selectList(new LambdaQueryWrapper<SystemConfig>()
                .orderByAsc(SystemConfig::getConfigKey));
    }

    /** 更新单个设置项：校验 → 落库 → 刷新缓存（即时生效） */
    public SystemConfig update(String key, String value, Long updatedBy) {
        String normalized = normalizeAndValidate(key, value);
        SystemConfig existing = systemConfigMapper.selectById(key);
        if (existing == null) {
            throw BizException.of(BizCodeEnum.BAD_REQUEST);
        }
        existing.setConfigValue(normalized);
        existing.setUpdatedBy(updatedBy);
        existing.setGmtModified(LocalDateTime.now());
        systemConfigMapper.updateById(existing);

        reloadHolder();
        applyJwtExpire();
        log.info("系统设置更新: key={}, value={}, updatedBy={}", key, normalized, updatedBy);
        return systemConfigMapper.selectById(key);
    }

    // ==================== 内部 ====================

    /** 启动自检：已知键缺行补种（默认值=yml 当前值）；description 漂移回写 */
    private void ensureRows() {
        for (String key : SystemConfigKey.ALL_KEYS) {
            String description = descriptionOf(key);
            SystemConfig existing = systemConfigMapper.selectById(key);
            if (existing == null) {
                SystemConfig config = new SystemConfig();
                config.setConfigKey(key);
                config.setConfigValue(defaultValueOf(key));
                config.setDescription(description);
                config.setGmtModified(LocalDateTime.now());
                systemConfigMapper.insert(config);
            } else if (!description.equals(existing.getDescription())) {
                existing.setDescription(description);
                systemConfigMapper.updateById(existing);
            }
        }
    }

    /** 全量装载进进程内持有器 */
    private void reloadHolder() {
        Map<String, String> snapshot = new HashMap<>();
        for (SystemConfig config : systemConfigMapper.selectList(null)) {
            snapshot.put(config.getConfigKey(), config.getConfigValue());
        }
        SystemConfigHolder.replaceAll(snapshot);
    }

    /** DB 里的 JWT 有效期应用到 JwtUtil（DB 覆盖 yml；只在设置行存在且合法时生效） */
    private void applyJwtExpire() {
        int minutes = SystemConfigHolder.getInt(SystemConfigKey.JWT_ACCESS_EXPIRE_MINUTES, 0);
        if (minutes > 0) {
            JwtUtil.updateAccessExpireMinutes(minutes);
        }
    }

    /** 键的默认值（缺行补种用；与 yml 保持一致，seed.sql 同口径） */
    private String defaultValueOf(String key) {
        switch (key) {
            case SystemConfigKey.JWT_ACCESS_EXPIRE_MINUTES:
                return String.valueOf(jwtAccessExpireMinutes);
            case SystemConfigKey.ACCESS_RATE_LIMIT:
                return String.valueOf(rateLimit);
            case SystemConfigKey.ACCESS_RATE_WINDOW_SECONDS:
                return String.valueOf(rateWindowSeconds);
            case SystemConfigKey.SITE_NAME:
                return "AI Gateway";
            case SystemConfigKey.SITE_ANNOUNCEMENT:
                return "";
            default:
                return "";
        }
    }

    /** 键的说明文案（代码定义为准，启动时同步 DB；与 seed.sql 同口径） */
    private String descriptionOf(String key) {
        switch (key) {
            case SystemConfigKey.SITE_NAME:
                return "站点名称（登录页与浏览器标题）";
            case SystemConfigKey.SITE_ANNOUNCEMENT:
                return "站点公告（登录页横幅，留空不展示）";
            case SystemConfigKey.JWT_ACCESS_EXPIRE_MINUTES:
                return "AccessToken 有效期（分钟，5-1440，改后下次登录生效）";
            case SystemConfigKey.ACCESS_RATE_LIMIT:
                return "限流阈值（每用户/IP/路径/窗口，1-100000）";
            case SystemConfigKey.ACCESS_RATE_WINDOW_SECONDS:
                return "限流窗口（秒，10-3600）";
            default:
                return "";
        }
    }

    /** 逐键校验并归一化；未知键直接拒绝 */
    private String normalizeAndValidate(String key, String rawValue) {
        String value = rawValue == null ? "" : rawValue.trim();
        switch (key) {
            case SystemConfigKey.SITE_NAME: {
                if (value.isEmpty() || value.length() > 50) {
                    throw BizException.of(BizCodeEnum.BAD_REQUEST);
                }
                return value;
            }
            case SystemConfigKey.SITE_ANNOUNCEMENT: {
                if (value.length() > 500) {
                    throw BizException.of(BizCodeEnum.BAD_REQUEST);
                }
                return value;
            }
            case SystemConfigKey.JWT_ACCESS_EXPIRE_MINUTES:
                return String.valueOf(requireInt(value, 5, 1440));
            case SystemConfigKey.ACCESS_RATE_LIMIT:
                return String.valueOf(requireInt(value, 1, 100000));
            case SystemConfigKey.ACCESS_RATE_WINDOW_SECONDS:
                return String.valueOf(requireInt(value, 10, 3600));
            default:
                throw BizException.of(BizCodeEnum.BAD_REQUEST);
        }
    }

    /** 整数区间校验（trim 后解析） */
    private int requireInt(String value, int min, int max) {
        int parsed;
        try {
            parsed = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw BizException.of(BizCodeEnum.BAD_REQUEST);
        }
        if (parsed < min || parsed > max) {
            throw BizException.of(BizCodeEnum.BAD_REQUEST);
        }
        return parsed;
    }
}

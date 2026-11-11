package com.ai.gateway.admin.controller;

import com.ai.gateway.common.constant.SystemConfigKey;
import com.ai.gateway.common.dto.Result;
import com.ai.gateway.common.holder.SystemConfigHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 公开站点设置（v2.4 系统设置中心）
 *
 * 免认证（JwtAuthFilter 白名单 /settings/**）：登录页要在登录前展示
 * 站点名与公告 —— 只暴露这两个非敏感键，其余设置项不可见。
 * 读进程内 Holder，无 DB 压力。
 */
@Tag(name = "公开设置")
@RestController
@RequestMapping("/settings")
public class PublicSettingsController {

    @Operation(summary = "公开站点设置（登录页站点名/公告，免认证）")
    @GetMapping("/public")
    public Result<Map<String, String>> publicSettings() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("siteName", SystemConfigHolder.get(SystemConfigKey.SITE_NAME, "AI Gateway"));
        result.put("announcement", SystemConfigHolder.get(SystemConfigKey.SITE_ANNOUNCEMENT, ""));
        return Result.ok(result);
    }
}

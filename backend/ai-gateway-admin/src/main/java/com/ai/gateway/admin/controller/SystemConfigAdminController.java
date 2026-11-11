package com.ai.gateway.admin.controller;

import com.ai.gateway.admin.annotation.Audited;
import com.ai.gateway.admin.dto.SettingsUpdateRequest;
import com.ai.gateway.admin.service.SystemConfigService;
import com.ai.gateway.common.pojo.SystemConfig;
import com.ai.gateway.common.dto.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 系统设置管理接口（v2.4 系统设置中心，ADMIN 角色，access 层 /admin/** 守卫）
 *
 * 单键单值提交（PUT body 带 key）：不同键校验规则不同，逐键保存。
 */
@Tag(name = "管理端-系统设置")
@RestController
@RequestMapping("/admin/settings")
@RequiredArgsConstructor
public class SystemConfigAdminController {

    private final SystemConfigService systemConfigService;

    @Operation(summary = "全部设置项（含说明/更新人/更新时间）")
    @GetMapping
    public Result<List<SystemConfig>> list() {
        return Result.ok(systemConfigService.listAll());
    }

    @Operation(summary = "更新设置项（即时生效，免重启）")
    @Audited(action = "SETTING_UPDATE", desc = "更新系统设置")
    @PutMapping
    public Result<SystemConfig> update(
            @RequestBody SettingsUpdateRequest request,
            @RequestHeader(value = "X-User-Id", required = false) Long updatedBy) {
        return Result.ok(systemConfigService.update(request.getKey(), request.getValue(), updatedBy));
    }
}

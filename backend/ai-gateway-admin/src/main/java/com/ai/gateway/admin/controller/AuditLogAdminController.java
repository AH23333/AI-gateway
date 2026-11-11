package com.ai.gateway.admin.controller;

import com.ai.gateway.admin.service.AuditLogService;
import com.ai.gateway.common.constant.PageConstant;
import com.ai.gateway.common.dto.PageResult;
import com.ai.gateway.common.dto.Result;
import com.ai.gateway.common.pojo.AuditLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 操作审计查询接口（v2.5 操作审计，ADMIN 角色，access 层 /admin/** 守卫）
 *
 * 记录由 AuditAspect 旁路写入，本接口只读检索（who/when/what/结果四要素）。
 */
@Tag(name = "管理端-操作审计")
@RestController
@RequestMapping("/admin/audit-logs")
@RequiredArgsConstructor
public class AuditLogAdminController {

    private final AuditLogService auditLogService;

    @Operation(summary = "审计日志分页（用户/动作/结果/时间范围过滤）")
    @GetMapping
    public Result<PageResult<AuditLog>> page(
            @Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = PageConstant.DEFAULT_PAGE) int page,
            @Parameter(description = "每页大小") @RequestParam(defaultValue = PageConstant.DEFAULT_SIZE) int size,
            @Parameter(description = "操作人用户名（精确）") @RequestParam(required = false) String username,
            @Parameter(description = "动作编码（如 SETTING_UPDATE）") @RequestParam(required = false) String action,
            @Parameter(description = "结果：1 成功 0 失败") @RequestParam(required = false) Integer success,
            @Parameter(description = "起始时间（含），yyyy-MM-dd HH:mm:ss")
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @Parameter(description = "截止时间（含），yyyy-MM-dd HH:mm:ss")
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {
        return Result.ok(auditLogService.page(page, size, username, action, success, startTime, endTime));
    }
}

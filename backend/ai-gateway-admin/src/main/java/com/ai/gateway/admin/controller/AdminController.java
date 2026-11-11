package com.ai.gateway.admin.controller;

import com.ai.gateway.admin.dto.QuotaUpdateRequest;
import com.ai.gateway.admin.service.AdminService;
import com.ai.gateway.auth.service.ApiKeyService;
import com.ai.gateway.common.constant.PageConstant;
import com.ai.gateway.common.dto.ApiKeyVO;
import com.ai.gateway.common.dto.PageResult;
import com.ai.gateway.common.dto.Result;
import com.ai.gateway.common.pojo.InvokeLogV2;
import com.ai.gateway.common.pojo.User;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理端接口 —— 用户管理 / 看板 / API Key 管辖（ADMIN 角色，access 层守卫）
 *
 * 路径契约保留基线：/admin/users、/admin/dashboard、/admin/apikeys。
 */
@Tag(name = "管理端-用户与看板")
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;
    private final ApiKeyService apiKeyService;

    @Operation(summary = "用户分页（username 模糊检索）")
    @GetMapping("/users")
    public Result<PageResult<User>> pageUsers(
            @Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = PageConstant.DEFAULT_PAGE) int page,
            @Parameter(description = "每页大小") @RequestParam(defaultValue = PageConstant.DEFAULT_SIZE) int size,
            @RequestParam(required = false) String keyword) {
        Page<User> result = adminService.pageUsers(page, size, keyword);
        return Result.ok(PageResult.of(result.getRecords(), result.getTotal(),
                (int) result.getCurrent(), (int) result.getSize()));
    }

    @Operation(summary = "调整用户配额（单边可调）")
    @PutMapping("/users/{id}/quota")
    public Result<Void> adjustQuota(@PathVariable Long id, @RequestBody QuotaUpdateRequest request) {
        adminService.adjustQuota(id, request.getDailyQuota(), request.getMonthlyQuota());
        return Result.ok();
    }

    @Operation(summary = "启用/禁用账号（最后一名管理员不可禁用）")
    @PutMapping("/users/{id}/status")
    public Result<Void> toggleStatus(@PathVariable Long id, @RequestParam Integer status) {
        adminService.toggleStatus(id, status);
        return Result.ok();
    }

    @Operation(summary = "变更角色（最后一名管理员不可降级）")
    @PutMapping("/users/{id}/role")
    public Result<Void> changeRole(@PathVariable Long id, @RequestParam String role) {
        adminService.changeRole(id, role);
        return Result.ok();
    }

    @Operation(summary = "全局看板（今日实时/模型分布/趋势）")
    @GetMapping("/dashboard")
    public Result<Map<String, Object>> dashboard() {
        return Result.ok(adminService.dashboard());
    }

    @Operation(summary = "全量调用日志分页（管理端用量页）")
    @GetMapping("/usage")
    public Result<PageResult<InvokeLogV2>> pageUsage(
            @RequestParam(defaultValue = PageConstant.DEFAULT_PAGE) int page,
            @RequestParam(defaultValue = PageConstant.DEFAULT_SIZE) int size) {
        return Result.ok(adminService.usage(page, size));
    }

    @Operation(summary = "全量 API Key 分页（脱敏）")
    @GetMapping("/apikeys")
    public Result<PageResult<ApiKeyVO>> pageApiKeys(
            @RequestParam(defaultValue = PageConstant.DEFAULT_PAGE) int page,
            @RequestParam(defaultValue = PageConstant.DEFAULT_SIZE) int size) {
        Page<ApiKeyVO> result = apiKeyService.pageAll(page, size);
        return Result.ok(PageResult.of(result.getRecords(), result.getTotal(),
                (int) result.getCurrent(), (int) result.getSize()));
    }

    @Operation(summary = "吊销任意 API Key")
    @DeleteMapping("/apikeys/{id}")
    public Result<Void> revokeApiKey(@PathVariable Long id) {
        apiKeyService.revokeByAdmin(id);
        return Result.ok();
    }
}

package com.ai.gateway.admin.controller;

import com.ai.gateway.admin.dto.SelfRequests.PasswordUpdateRequest;
import com.ai.gateway.admin.dto.SelfRequests.ApiKeyCreateRequest;
import com.ai.gateway.admin.service.UserSelfService;
import com.ai.gateway.auth.service.ApiKeyService;
import com.ai.gateway.common.dto.ApiKeyVO;
import com.ai.gateway.common.dto.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 用户自助接口 —— 个人信息/改密/用量/我的 Key（登录即可，非 ADMIN）
 *
 * userId 由 access 模块 JwtAuthFilter 从 JWT 注入（X-User-Id），客户端不可伪造。
 */
@Tag(name = "用户自助")
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserSelfController {

    private final UserSelfService userSelfService;
    private final ApiKeyService apiKeyService;

    @Operation(summary = "个人信息 + 配额水位 + 今日/本月用量")
    @GetMapping("/info")
    public Result<Map<String, Object>> info(
            @Parameter(description = "用户 ID（网关过滤器注入）")
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        return Result.ok(userSelfService.info(userId));
    }

    @Operation(summary = "修改密码（原密码校验）")
    @PutMapping("/password")
    public Result<Void> changePassword(
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestBody PasswordUpdateRequest request) {
        userSelfService.changePassword(userId, request.getOldPassword(), request.getNewPassword());
        return Result.ok();
    }

    @Operation(summary = "我的 API Key 列表（脱敏）")
    @GetMapping("/apikeys")
    public Result<List<ApiKeyVO>> myApiKeys(
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        return Result.ok(userSelfService.myApiKeys(userId));
    }

    @Operation(summary = "创建 API Key（明文仅本次返回）")
    @PostMapping("/apikeys")
    public Result<ApiKeyVO> createApiKey(
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestBody(required = false) ApiKeyCreateRequest request) {
        String keyName = request == null ? null : request.getKeyName();
        return Result.ok(userSelfService.createApiKey(userId, keyName));
    }

    @Operation(summary = "吊销我的 API Key")
    @DeleteMapping("/apikeys/{id}")
    public Result<Void> revokeApiKey(
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @PathVariable Long id) {
        userSelfService.revokeApiKey(userId, id);
        return Result.ok();
    }

    @Operation(summary = "对话可用模型列表（启用态，前端选择器）")
    @GetMapping("/models")
    public Result<List<Map<String, String>>> models() {
        return Result.ok(userSelfService.enabledModels());
    }
}

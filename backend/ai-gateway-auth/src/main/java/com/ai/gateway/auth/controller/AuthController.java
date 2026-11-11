package com.ai.gateway.auth.controller;

import com.ai.gateway.auth.service.AuthService;
import com.ai.gateway.common.dto.LoginRequest;
import com.ai.gateway.common.dto.RegisterRequest;
import com.ai.gateway.common.dto.Result;
import com.ai.gateway.common.dto.TokenResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 认证接口 Controller
 *
 * 路由：/auth/**（access 模块白名单放行，无需 Token）
 * 基线路径契约保留：/auth/register /auth/login /auth/refresh /auth/logout。
 */
@Tag(name = "认证接口")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "用户注册")
    @PostMapping("/register")
    public Result<TokenResponse> register(@RequestBody RegisterRequest request) {
        return Result.ok(authService.register(request));
    }

    @Operation(summary = "用户登录")
    @PostMapping("/login")
    public Result<TokenResponse> login(@RequestBody LoginRequest request) {
        return Result.ok(authService.login(request));
    }

    /**
     * 刷新 Token
     *
     * 前端在 AccessToken 过期前主动调用，用 RefreshToken 换新双 Token。
     * 同时传入旧 AccessToken，服务端将其加入黑名单，防止刷新后继续使用。
     */
    @Operation(summary = "刷新Token")
    @PostMapping("/refresh")
    public Result<TokenResponse> refresh(@RequestHeader(value = "Authorization", required = false) String auth,
                                         @RequestBody Map<String, String> body) {
        String accessToken = "";
        if (auth != null && auth.startsWith("Bearer ")) {
            accessToken = auth.substring("Bearer ".length());
        }
        String refreshToken = body != null ? body.get("refreshToken") : null;
        return Result.ok(authService.refresh(accessToken, refreshToken));
    }

    /**
     * 登出：AccessToken 入黑名单 + RefreshToken 标记失效
     */
    @Operation(summary = "登出")
    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = "Authorization", required = false) String auth,
                               @RequestBody(required = false) Map<String, String> body) {
        String accessToken = "";
        if (auth != null && auth.startsWith("Bearer ")) {
            accessToken = auth.substring("Bearer ".length());
        }
        String refreshToken = body != null ? body.get("refreshToken") : null;
        authService.logout(accessToken, refreshToken);
        return Result.ok();
    }
}

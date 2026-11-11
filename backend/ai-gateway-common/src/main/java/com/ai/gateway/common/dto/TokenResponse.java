package com.ai.gateway.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Token 响应 DTO
 *
 * 登录/注册/刷新后返回
 * expiresIn 单位：秒，前端用于倒计时自动刷新
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TokenResponse {

    /** 访问令牌（30 分钟，业务接口鉴权用） */
    private String accessToken;

    /** 刷新令牌（7 天，仅 /auth/refresh 使用） */
    private String refreshToken;

    /** AccessToken 有效期（秒） */
    private long expiresIn;
}

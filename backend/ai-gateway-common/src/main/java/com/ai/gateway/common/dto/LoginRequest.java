package com.ai.gateway.common.dto;

import lombok.Data;

/**
 * 登录请求 DTO
 */
@Data
public class LoginRequest {

    /** 用户名 */
    private String username;

    /** 密码（明文仅在 HTTPS/本地传输，服务端只存 BCrypt） */
    private String password;
}

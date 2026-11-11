package com.ai.gateway.common.dto;

import lombok.Data;

/**
 * 注册请求 DTO
 */
@Data
public class RegisterRequest {

    /** 用户名（唯一） */
    private String username;

    /** 密码（BCrypt 加密后落库） */
    private String password;

    /** 邮箱（可选） */
    private String email;
}

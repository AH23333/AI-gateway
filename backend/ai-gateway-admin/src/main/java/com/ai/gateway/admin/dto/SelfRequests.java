package com.ai.gateway.admin.dto;

import lombok.Data;

/**
 * 用户自助请求 DTO 集合
 */
public final class SelfRequests {

    private SelfRequests() {
    }

    /** 修改密码请求 */
    @Data
    public static class PasswordUpdateRequest {

        /** 原密码（BCrypt 校验） */
        private String oldPassword;

        /** 新密码（≥6 位） */
        private String newPassword;
    }

    /** 创建 API Key 请求 */
    @Data
    public static class ApiKeyCreateRequest {

        /** Key 备注名（可空，默认 default） */
        private String keyName;
    }
}

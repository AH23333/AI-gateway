package com.ai.gateway.common.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * RefreshToken 实体 —— 对应 t_refresh_token 表（v2：哈希化改造）
 *
 * v2 关键变更（选型书 3.1）：token 列只存 SHA-256 哈希（token_hash），
 * 明文 JWT 仅在签发时返回客户端一次。
 * 理由：数据库泄露时攻击者拿不到可用 RefreshToken；
 * 校验方式 = SHA-256(传入明文) 与 token_hash 精确匹配。
 *
 * 安全机制：
 * 1. 每次刷新生成新 Token，旧 Token 立即标记禁用（防重用攻击）；
 * 2. 登出时标记禁用；
 * 3. 到期时间由数据库字段控制，与 JWT exp 一致。
 */
@Data
@TableName("t_refresh_token")
public class RefreshToken {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联用户 ID */
    private Long userId;

    /** RefreshToken 的 SHA-256 哈希（64 位十六进制），明文不落库 */
    private String tokenHash;

    /** 过期时间（与 JWT exp 一致） */
    private LocalDateTime expireTime;

    /** 状态：0=禁用, 1=启用 */
    private Integer status;

    private LocalDateTime gmtCreate;
}

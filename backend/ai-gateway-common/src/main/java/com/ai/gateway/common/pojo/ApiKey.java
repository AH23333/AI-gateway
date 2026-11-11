package com.ai.gateway.common.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * API Key 实体 —— 对应 t_api_key 表（v2：哈希化存储，修 P8）
 *
 * v2 关键变更（选型书 3.1）：
 * - key_hash：存 SHA-256(完整明文) 的十六进制，鉴权时哈希后精确匹配；
 * - key_prefix：明文前 8 位（含 "sk-"），列表展示用；
 * - 明文不再落库 —— 创建接口一次性返回，之后任何人都无法再取回。
 *
 * 用途：第三方客户端 / OpenAI SDK（api_key 形式）调用 /v1/chat/completions。
 */
@Data
@TableName("t_api_key")
public class ApiKey {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联用户 ID */
    private Long userId;

    /** 完整明文的 SHA-256 哈希（64 位十六进制） */
    private String keyHash;

    /** 明文前 8 位，如 "sk-9f2a"（列表辨识用） */
    private String keyPrefix;

    /** 密钥名称（备注） */
    private String keyName;

    /** 状态：0=禁用, 1=启用 */
    private Integer status;

    private LocalDateTime gmtCreate;

    /** 过期时间 */
    private LocalDateTime gmtExpire;
}

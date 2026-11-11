package com.ai.gateway.common.pojo;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话消息实体 —— 对应 t_chat_message 表（v2 新增，选型书 3.3 D5）
 *
 * 定位：会话内容落库表（trace_id 关联 t_invoke_log_v2 窄表），
 * 检索用 ngram FULLTEXT（中文分词由 ngram 内建 2-gram 覆盖）。
 *
 * 为什么不用分区？
 * MySQL 8.0 分区表不支持 FULLTEXT 索引（官方限制），二选一时
 * 保留检索能力（本项目消息量级毫秒级可查），保留策略改为定期清理。
 */
@Data
@TableName("t_chat_message")
public class ChatMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联调用 traceId（t_invoke_log_v2.trace_id） */
    private String traceId;

    /** 用户 ID */
    private Long userId;

    /** 角色：user / assistant / system / tool */
    private String role;

    /** 消息内容 */
    private String content;

    /** 创建时间（DB 默认值填充） */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime gmtCreate;
}

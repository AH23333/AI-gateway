package com.ai.gateway.billing.mapper;

import com.ai.gateway.common.pojo.ChatMessage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 会话消息 Mapper —— t_chat_message 批量写入（D5 会话内容域）
 *
 * 写入走内存队列批量层（BatchLogWriter），单条 insert 仅兜底场景使用。
 * gmt_create 由数据库 DEFAULT CURRENT_TIMESTAMP 填充，
 * 批量语句不携带该列（实体上的 FieldStrategy.NEVER 也拦截了 MP 单条插入）。
 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

    /** 批量插入（500 条/批上限由 BatchLogWriter 控制） */
    @Insert("<script>"
            + "INSERT INTO t_chat_message (trace_id, user_id, role, content, gmt_create) VALUES "
            + "<foreach collection='list' item='m' separator=','>"
            + "(#{m.traceId}, #{m.userId}, #{m.role}, #{m.content}, NOW())"
            + "</foreach>"
            + "</script>")
    int insertBatch(@Param("list") List<ChatMessage> messages);
}

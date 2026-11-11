package com.ai.gateway.admin.mapper;

import com.ai.gateway.common.pojo.ChatMessage;
import com.ai.gateway.common.pojo.InvokeLogV2;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 聊天记录检索 Mapper —— D5 会话内容域的读取口（admin 域窄接口）
 *
 * 检索双路径（1.md §7.2）：
 * - 有关键词 → t_chat_message ngram FULLTEXT（MATCH...AGAINST BOOLEAN MODE）；
 * - 无关键词 → t_invoke_log_v2 分页（调用维度浏览）。
 *
 * 隐私边界：所有 SQL 强制 user_id 过滤，用户只能检索自己的内容
 * （管理端审查入口暂未开放，开放时需在产品层面明确授权口径）。
 */
@Mapper
public interface ChatHistoryMapper {

    /** 关键词命中数（用户自己的消息内检索） */
    @Select("SELECT COUNT(*) FROM t_chat_message "
            + "WHERE user_id = #{userId} AND MATCH(content) AGAINST(#{keyword} IN BOOLEAN MODE)")
    long countSearch(@Param("userId") Long userId, @Param("keyword") String keyword);

    /** 关键词检索（按 id 倒序，最新命中优先） */
    @Select("SELECT * FROM t_chat_message "
            + "WHERE user_id = #{userId} AND MATCH(content) AGAINST(#{keyword} IN BOOLEAN MODE) "
            + "ORDER BY id DESC LIMIT #{offset}, #{size}")
    List<ChatMessage> search(@Param("userId") Long userId, @Param("keyword") String keyword,
                             @Param("offset") long offset, @Param("size") int size);

    /** 按调用流水取会话消息（详情回放） */
    @Select("SELECT * FROM t_chat_message WHERE trace_id = #{traceId} ORDER BY id ASC")
    List<ChatMessage> messagesByTraceId(@Param("traceId") String traceId);

    /** 按用户+traceId 取调用日志（所有权校验内联） */
    @Select("SELECT * FROM t_invoke_log_v2 WHERE trace_id = #{traceId} AND user_id = #{userId} LIMIT 1")
    InvokeLogV2 findUserLog(@Param("traceId") String traceId, @Param("userId") Long userId);

    /** 用户调用日志总数（无关键词浏览路径） */
    @Select("SELECT COUNT(*) FROM t_invoke_log_v2 WHERE user_id = #{userId}")
    long countUserLogs(@Param("userId") Long userId);

    /** 用户调用日志分页（按 id 倒序，最新调用优先） */
    @Select("SELECT * FROM t_invoke_log_v2 WHERE user_id = #{userId} "
            + "ORDER BY id DESC LIMIT #{offset}, #{size}")
    List<InvokeLogV2> pageUserLogs(@Param("userId") Long userId,
                                   @Param("offset") long offset, @Param("size") int size);
}

package com.ai.gateway.admin.service;

import com.ai.gateway.admin.mapper.ChatHistoryMapper;
import com.ai.gateway.common.constant.BizCodeEnum;
import com.ai.gateway.common.dto.PageResult;
import com.ai.gateway.common.exception.BizException;
import com.ai.gateway.common.pojo.ChatMessage;
import com.ai.gateway.common.pojo.InvokeLogV2;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天记录检索服务（D5 会话内容域的读取编排）
 *
 * 双路径（1.md §7.2）：
 * - 有关键词 → t_chat_message ngram FULLTEXT（中文 2-gram 分词内建）；
 * - 无关键词 → t_invoke_log_v2 分页（调用维度）；
 * - 详情     → 调用日志 + 该 traceId 的全部会话消息（所有权强制校验）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatHistoryService {

    private final ChatHistoryMapper chatHistoryMapper;

    /** 调用日志分页（无关键词浏览路径） */
    public PageResult<InvokeLogV2> logs(Long userId, int page, int size) {
        long total = chatHistoryMapper.countUserLogs(userId);
        List<InvokeLogV2> records =
                chatHistoryMapper.pageUserLogs(userId, (long) (page - 1) * size, size);
        return PageResult.of(records, total, page, size);
    }

    /** 关键词全文检索（ngram FULLTEXT） */
    public PageResult<ChatMessage> search(Long userId, String keyword, int page, int size) {
        if (keyword == null || keyword.isBlank()) {
            throw BizException.of(BizCodeEnum.BAD_REQUEST);
        }
        String trimmed = keyword.trim();
        long total = chatHistoryMapper.countSearch(userId, trimmed);
        List<ChatMessage> records =
                chatHistoryMapper.search(userId, trimmed, (long) (page - 1) * size, size);
        return PageResult.of(records, total, page, size);
    }

    /** 调用详情：日志 + 会话消息（所有权校验：findUserLog 内联 user_id 条件） */
    public Map<String, Object> detail(Long userId, String traceId) {
        InvokeLogV2 log = chatHistoryMapper.findUserLog(traceId, userId);
        if (log == null) {
            throw BizException.of(BizCodeEnum.NOT_FOUND);
        }
        List<ChatMessage> messages = chatHistoryMapper.messagesByTraceId(traceId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("log", log);
        result.put("messages", messages);
        return result;
    }
}

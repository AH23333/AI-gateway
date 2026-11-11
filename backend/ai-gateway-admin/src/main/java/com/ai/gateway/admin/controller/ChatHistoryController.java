package com.ai.gateway.admin.controller;

import com.ai.gateway.admin.service.ChatHistoryService;
import com.ai.gateway.common.dto.PageResult;
import com.ai.gateway.common.dto.Result;
import com.ai.gateway.common.pojo.ChatMessage;
import com.ai.gateway.common.pojo.InvokeLogV2;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 用户聊天记录接口 —— 调用日志浏览 / 关键词检索 / 详情回放（登录即可）
 *
 * userId 由 access 模块注入；服务层所有 SQL 强制 user_id 过滤（隐私边界）。
 */
@Tag(name = "用户聊天记录")
@RestController
@RequestMapping("/api/user/chat")
@RequiredArgsConstructor
public class ChatHistoryController {

    private final ChatHistoryService chatHistoryService;

    @Operation(summary = "我的调用日志分页（无关键词浏览）")
    @GetMapping("/logs")
    public Result<PageResult<InvokeLogV2>> logs(
            @Parameter(description = "用户 ID（网关过滤器注入）")
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(chatHistoryService.logs(userId, page, size));
    }

    @Operation(summary = "会话内容关键词检索（ngram 全文索引）")
    @GetMapping("/search")
    public Result<PageResult<ChatMessage>> search(
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @Parameter(description = "检索关键词（必填）") @RequestParam String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(chatHistoryService.search(userId, keyword, page, size));
    }

    @Operation(summary = "调用详情：日志 + 该次调用全部会话消息")
    @GetMapping("/logs/{traceId}")
    public Result<Map<String, Object>> detail(
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @PathVariable String traceId) {
        return Result.ok(chatHistoryService.detail(userId, traceId));
    }
}

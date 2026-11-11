package com.ai.gateway.access.support;

import com.ai.gateway.common.dto.Result;
import com.ai.gateway.common.util.JsonUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 过滤器层错误响应写出器（双协议，与 GlobalExceptionHandler 口径一致）
 *
 * 为什么过滤器需要单独的写出口？
 * 过滤器异常不经过 @RestControllerAdvice（那只在 MVC 层生效），
 * 必须自己把错误写回响应流；口径若不一致，前端/OpenAI SDK 会看到两种错误格式。
 *
 * 双协议约定（全系统统一）：
 * - /v1/**  → OpenAI error JSON + 语义化 HTTP 状态码（SDK/LangChain 可识别）；
 * - 其余    → HTTP 200 + Result{code,msg}（前端按 code 分支，单一契约）。
 */
public final class AccessErrorWriter {

    private AccessErrorWriter() {
    }

    /**
     * 写出错误响应
     *
     * @param bizCode 业务码（BizCodeEnum 的 code，用于双协议分流与 OpenAI code 字段）
     * @param message 错误信息
     */
    public static void write(HttpServletRequest request, HttpServletResponse response,
                             int bizCode, String message) throws IOException {
        // 已提交的响应无法重写（如流式已吐出首块后过滤器才失败），只记日志放弃写入
        if (response.isCommitted()) {
            return;
        }
        boolean openAiProtocol = request.getRequestURI().startsWith("/v1/");
        response.reset();
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        if (openAiProtocol) {
            response.setStatus(toHttpStatus(bizCode));
            response.getWriter().write(JsonUtil.toJson(openAiError(message, String.valueOf(bizCode))));
        } else {
            // 非 OpenAI 协议面：HTTP 200 + 业务码（与 GlobalExceptionHandler 一致）
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write(JsonUtil.toJson(Result.fail(bizCode, message)));
        }
    }

    /** 业务码 → HTTP 状态码映射（OpenAI 协议面，与 GlobalExceptionHandler.toHttpStatus 同口径） */
    private static int toHttpStatus(int code) {
        return switch (code) {
            case 401, 10401 -> HttpServletResponse.SC_UNAUTHORIZED;
            case 403, 10403, 4403 -> HttpServletResponse.SC_FORBIDDEN;
            case 429, 3429, 5001, 5002 -> 429;
            case 400, 4001, 4003, 6001, 6002, 6003, 6004, 6005, 6006, 6007, 6008 -> 400;
            case 404, 2001, 3001 -> HttpServletResponse.SC_NOT_FOUND;
            default -> HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        };
    }

    /** OpenAI 风格错误体：{"error":{"message":...,"type":...,"code":...}} */
    private static Map<String, Object> openAiError(String message, String code) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", message);
        error.put("type", "invalid_request_error");
        error.put("code", code);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        return body;
    }
}

package com.ai.gateway.common.exception;

import com.ai.gateway.common.constant.BizCodeEnum;
import com.ai.gateway.common.dto.Result;
import com.ai.gateway.common.util.TraceIdUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理器（v2：双协议分流）
 *
 * /v1/** 是 OpenAI 协议面：异常必须翻译成 OpenAI error JSON + 语义化 HTTP 状态码，
 * 否则 OpenAI SDK / LangChain 无法识别（FR-14 验收依赖）；
 * 其余路径走基线 Result 契约（HTTP 200 + code/msg，前端按 code 分支）。
 *
 * 分层：
 * 1. BizException → 业务异常（预期内），warn 级日志；
 * 2. Exception    → 系统异常（预期外），error 级 + 完整堆栈，不暴露内部细节。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：按请求路径分流为 OpenAI error 或 Result */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Object> handleBizException(BizException e) {
        log.warn("业务异常: code={}, msg={}", e.getCode(), e.getMessage());
        if (isOpenAiProtocolRequest()) {
            return ResponseEntity.status(toHttpStatus(e.getCode()))
                    .body(openAiError(e.getMessage(), String.valueOf(e.getCode())));
        }
        return ResponseEntity.ok(Result.fail(e.getCode(), e.getMessage()));
    }

    /** 系统异常：兜底，打印完整堆栈，返回通用错误信息 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleException(Exception e) {
        log.error("系统异常, traceId={}", TraceIdUtil.current(), e);
        if (isOpenAiProtocolRequest()) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(openAiError("The server had an error while processing your request: "
                            + e.getMessage(), "internal_error"));
        }
        return ResponseEntity.ok(Result.fail("系统内部错误"));
    }

    /** 判断当前请求是否走 OpenAI 协议（/v1/**） */
    private boolean isOpenAiProtocolRequest() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return false;
        }
        return attrs.getRequest().getRequestURI().startsWith("/v1/");
    }

    /**
     * 业务码 → HTTP 状态码映射（OpenAI 协议面）：
     * 10401→401；10403/403→403；429/3429/5001/5002（限流与配额）→429；
     * 4xx 段→400；其余→500。
     */
    private HttpStatus toHttpStatus(int code) {
        return switch (code) {
            case 401, 10401 -> HttpStatus.UNAUTHORIZED;
            case 403, 10403, 4403 -> HttpStatus.FORBIDDEN;
            case 429, 3429, 5001, 5002 -> HttpStatus.TOO_MANY_REQUESTS;
            case 400, 4001, 4003, 6001, 6002, 6003, 6004, 6005, 6006, 6007, 6008 -> HttpStatus.BAD_REQUEST;
            case 404, 2001, 3001 -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    /** 构造 OpenAI 风格错误体：{"error":{"message":...,"type":...,"code":...}} */
    private Map<String, Object> openAiError(String message, String code) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", message);
        error.put("type", "invalid_request_error");
        error.put("code", code);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        return body;
    }
}

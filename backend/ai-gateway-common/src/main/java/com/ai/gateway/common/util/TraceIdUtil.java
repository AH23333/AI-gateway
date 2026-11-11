package com.ai.gateway.common.util;

import org.slf4j.MDC;

import java.util.regex.Pattern;

/**
 * TraceId 工具（全链路追踪，基线正确起点保留并统一化）
 *
 * v2 统一约定：
 * - access 模块 TraceIdFilter 在请求入口生成/接收 traceId 放入 MDC（key=traceId）
 *   并写入响应头 X-Trace-Id；
 * - 业务代码（ChatService/billing）统一通过本工具从 MDC 取，
 *   作为预扣凭证（t_quota_reservation.trace_id）与调用日志幂等键
 *   （t_invoke_log_v2.trace_id）——一次请求一个 ID 贯穿全链路；
 * - 客户端可自带 X-Trace-Id（agent 生态对接），仅接受 [A-Za-z0-9_-]{1,64}，
 *   非法字符一律拒绝重新生成，防止日志注入与超长攻击。
 */
public final class TraceIdUtil {

    /** MDC 键名（日志 pattern 通过 %X{traceId} 输出） */
    public static final String MDC_KEY = "traceId";

    /** 客户端自带 traceId 的合法格式 */
    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private TraceIdUtil() {
    }

    /** 获取当前请求 traceId；缺失时生成新 ID 并写回 MDC（兜底路径） */
    public static String getOrCreate() {
        String traceId = MDC.get(MDC_KEY);
        if (traceId == null || traceId.isBlank()) {
            traceId = IdGen.nextIdStr();
            MDC.put(MDC_KEY, traceId);
        }
        return traceId;
    }

    /** 当前 MDC 中的 traceId（可能为 null，只读场景用） */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    /** 校验客户端自带 traceId 是否合法（非法返回 null，由调用方重新生成） */
    public static String sanitize(String candidate) {
        if (candidate == null) {
            return null;
        }
        String trimmed = candidate.trim();
        return VALID.matcher(trimmed).matches() ? trimmed : null;
    }
}

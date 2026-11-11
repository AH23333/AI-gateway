package com.ai.gateway.access.filter;

import com.ai.gateway.common.util.IdGen;
import com.ai.gateway.common.util.TraceIdUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;

import java.io.IOException;

/**
 * 全链路追踪过滤器（第一道，优先级最高）
 *
 * 职责：
 * 1. 接收或生成 traceId 放入 MDC —— 日志 pattern 的 %X{traceId} 立即可用；
 * 2. 写回响应头 X-Trace-Id —— 客户端/运维凭它反查一次请求的全部日志；
 * 3. 请求结束清理 MDC —— Tomcat 线程池复用，不清理会串号（A 请求的 traceId 泄漏到 B）。
 *
 * traceId 下游贯穿（v2 统一约定）：
 * 预扣凭证 trace_id → 调用日志幂等键 → t_chat_message 关联 → 全链路日志。
 *
 * 客户端可自带 X-Trace-Id（agent 生态对接），仅接受 [A-Za-z0-9_-]{1,64}，
 * 非法一律重新生成（防日志注入，见 TraceIdUtil 类注释）。
 */
public class TraceIdFilter extends AbstractHttpFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            // 优先信任合法的客户端自带 traceId，否则生成雪花 ID
            String incoming = TraceIdUtil.sanitize(request.getHeader("X-Trace-Id"));
            String traceId = incoming != null ? incoming : IdGen.nextIdStr();
            MDC.put(TraceIdUtil.MDC_KEY, traceId);
            response.setHeader("X-Trace-Id", traceId);
            chain.doFilter(request, response);
        } finally {
            // 线程池复用，必须清理，否则下一个请求会带着旧 traceId
            MDC.remove(TraceIdUtil.MDC_KEY);
        }
    }
}

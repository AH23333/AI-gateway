package com.ai.gateway.admin.aspect;

import com.ai.gateway.admin.annotation.Audited;
import com.ai.gateway.admin.mapper.AuditLogMapper;
import com.ai.gateway.common.exception.BizException;
import com.ai.gateway.common.pojo.AuditLog;
import com.ai.gateway.common.util.JsonUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 操作审计切面（v2.5 操作审计，1Panel 对标项 2）
 *
 * 拦截所有标注 @Audited 的管理端写操作，环绕记录四要素：
 *   who    —— access 过滤器注入的 X-User-Id / X-Username（客户端伪造无效）；
 *   when   —— created_at（DB 默认当前时间）；
 *   what   —— action/desc（注解声明）+ HTTP 方法 + URI（含路径变量即操作对象）+ 参数摘要；
 *   结果   —— success(1/0) + error_msg + cost_ms + ip。
 *
 * 设计约束：
 * 1. 审计落库失败只记 error 日志，绝不向上抛（审计是旁路，业务是主路）；
 * 2. 参数摘要脱敏（password/secret/token/apikey/credential 值替换为 ***）+ 截断 1000 字符；
 * 3. 业务异常（BizException）也记为失败审计后原样重抛，交 GlobalExceptionHandler 统一返回。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditAspect {

    /** access 层 HeaderInjectRequestWrapper 注入的身份头（admin 不依赖 access，此处用同名常量） */
    private static final String HEADER_USER_ID = "X-User-Id";
    private static final String HEADER_USERNAME = "X-Username";

    /** 参数摘要长度上限（对齐 t_audit_log.params VARCHAR(1000)） */
    private static final int PARAMS_MAX_LENGTH = 1000;

    /** 失败原因长度上限（对齐 t_audit_log.error_msg VARCHAR(500)） */
    private static final int ERROR_MSG_MAX_LENGTH = 500;

    /** 敏感字段脱敏：字段名含这些关键词（忽略大小写）→ 值替换为 ***（"key" 不在列，保留配置键可读性） */
    private static final Pattern SENSITIVE_FIELD = Pattern.compile(
            "(\"[^\"]*(?i:password|secret|token|apikey|credential)[^\"]*\"\\s*:\\s*)\"[^\"]*\"");

    /** 序列化时跳过的参数类型（Servlet 对象/文件/绑定结果等非业务参数） */
    private static final List<Class<?>> EXCLUDED_ARG_TYPES = List.of(
            HttpServletRequest.class, HttpServletResponse.class, MultipartFile.class);

    private final AuditLogMapper auditLogMapper;

    @Around("@annotation(audited)")
    public Object around(ProceedingJoinPoint pjp, Audited audited) throws Throwable {
        long start = System.currentTimeMillis();
        boolean success = true;
        String errorMsg = null;
        try {
            return pjp.proceed();
        } catch (BizException e) {
            success = false;
            errorMsg = e.getMessage();
            throw e;
        } catch (Throwable t) {
            success = false;
            errorMsg = t.getMessage();
            throw t;
        } finally {
            saveAuditLog(pjp, audited, success, errorMsg, System.currentTimeMillis() - start);
        }
    }

    /** 组装并落库一条审计记录（任何异常自吞，只记日志） */
    private void saveAuditLog(ProceedingJoinPoint pjp, Audited audited,
                              boolean success, String errorMsg, long costMs) {
        try {
            HttpServletRequest request = currentRequest();
            AuditLog record = new AuditLog();
            if (request != null) {
                record.setUserId(parseLong(request.getHeader(HEADER_USER_ID)));
                record.setUsername(request.getHeader(HEADER_USERNAME));
                record.setHttpMethod(request.getMethod());
                record.setUri(request.getRequestURI());
                record.setParams(buildParams(request, pjp.getArgs()));
                record.setIp(resolveIp(request));
            }
            record.setAction(audited.action());
            record.setActionDesc(audited.desc());
            record.setSuccess(success ? 1 : 0);
            record.setErrorMsg(truncate(errorMsg, ERROR_MSG_MAX_LENGTH));
            record.setCostMs(costMs);
            auditLogMapper.insert(record);
        } catch (Exception e) {
            // 审计旁路失败不影响业务（如库表缺失/字段超长），error 级留痕排查
            log.error("审计落库失败（不影响业务）: action={}, uri={}, err={}",
                    audited.action(), pjp.getSignature().toShortString(), e.getMessage());
        }
    }

    /** 参数摘要 = query string + 业务参数 JSON（脱敏截断） */
    private String buildParams(HttpServletRequest request, Object[] args) {
        StringBuilder sb = new StringBuilder();
        String query = request.getQueryString();
        if (query != null && !query.isBlank()) {
            sb.append("q: ").append(query);
        }
        String bodyJson = serializeArgs(args);
        if (bodyJson != null && !bodyJson.isBlank() && !"[]".equals(bodyJson)) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append("body: ").append(bodyJson);
        }
        return sb.length() == 0 ? null : truncate(sb.toString(), PARAMS_MAX_LENGTH);
    }

    /** 序列化业务参数为 JSON 数组（跳过 Servlet/文件参数，脱敏） */
    private String serializeArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        List<Object> businessArgs = new ArrayList<>();
        for (Object arg : args) {
            if (arg == null) {
                continue;
            }
            boolean excluded = false;
            for (Class<?> type : EXCLUDED_ARG_TYPES) {
                if (type.isInstance(arg)) {
                    excluded = true;
                    break;
                }
            }
            if (!excluded) {
                businessArgs.add(arg);
            }
        }
        if (businessArgs.isEmpty()) {
            return null;
        }
        try {
            return SENSITIVE_FIELD.matcher(JsonUtil.toJson(businessArgs)).replaceAll("$1\"***\"");
        } catch (Exception e) {
            // 参数可能不可序列化（无 getter 等），降级为类型名列表
            return businessArgs.stream()
                    .map(a -> a.getClass().getSimpleName())
                    .reduce((a, b) -> a + "," + b)
                    .orElse(null);
        }
    }

    /** 客户端 IP：反代场景取 X-Forwarded-For 首跳，直连取 remoteAddr */
    private String resolveIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return comma > 0 ? forwarded.substring(0, comma).trim() : forwarded.trim();
        }
        return request.getRemoteAddr();
    }

    private HttpServletRequest currentRequest() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attrs != null ? attrs.getRequest() : null;
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}

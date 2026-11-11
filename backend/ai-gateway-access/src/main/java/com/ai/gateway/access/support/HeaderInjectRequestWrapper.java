package com.ai.gateway.access.support;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 请求头包装器 —— 把认证过滤器解析出的身份信息"注入"下游请求
 *
 * 单体内没有跨进程转发，"注入"的含义是让 @RequestHeader("X-User-Id")
 * 能从包装后的 Request 中读到 —— 控制器对身份来源无感（与基线网关语义等价）。
 *
 * 安全边界：X-User-Id / X-Username / X-User-Role 三个头在此覆盖，
 * 客户端伪造的同名头到不了控制器（过滤器先于一切 MVC 代码执行）。
 */
public class HeaderInjectRequestWrapper extends HttpServletRequestWrapper {

    /** 身份三件套：下游只允许从这三个头读身份 */
    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_USERNAME = "X-Username";
    public static final String HEADER_USER_ROLE = "X-User-Role";

    /** 会被覆盖的安全头（防外部伪造） */
    private static final Set<String> SECURITY_HEADERS =
            Set.of(HEADER_USER_ID, HEADER_USERNAME, HEADER_USER_ROLE);

    private final Map<String, String> injectedHeaders = new HashMap<>();

    public HeaderInjectRequestWrapper(HttpServletRequest request, Long userId,
                                      String username, String role) {
        super(request);
        injectedHeaders.put(HEADER_USER_ID, String.valueOf(userId));
        injectedHeaders.put(HEADER_USERNAME, username != null ? username : "");
        injectedHeaders.put(HEADER_USER_ROLE, role != null ? role : "USER");
    }

    @Override
    public String getHeader(String name) {
        if (SECURITY_HEADERS.contains(name) && injectedHeaders.containsKey(name)) {
            return injectedHeaders.get(name);
        }
        return super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        if (SECURITY_HEADERS.contains(name) && injectedHeaders.containsKey(name)) {
            return Collections.enumeration(java.util.List.of(injectedHeaders.get(name)));
        }
        return super.getHeaders(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        Map<String, String> merged = new HashMap<>();
        Enumeration<String> original = super.getHeaderNames();
        while (original.hasMoreElements()) {
            String name = original.nextElement();
            if (!SECURITY_HEADERS.contains(name)) {
                merged.put(name, super.getHeader(name));
            }
        }
        merged.putAll(injectedHeaders);
        return Collections.enumeration(merged.keySet());
    }
}

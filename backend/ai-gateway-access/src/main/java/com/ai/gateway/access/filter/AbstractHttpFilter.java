package com.ai.gateway.access.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * HTTP 过滤器基类 —— 只放行 HTTP 请求，非 HTTP（罕见）直接透传
 *
 * 等价于 Spring Security 的 OncePerRequestFilter 中"类型窄化"那一半；
 * 自制的原因：不引入 spring-security-web（整个 BOM 只用到了 crypto 一个包）。
 *
 * 单次执行保证由 FilterRegistrationBean 的默认 dispatcher 类型保证
 * （仅 REQUEST，不含 FORWARD/INCLUDE/ERROR，不会重复过滤）。
 */
public abstract class AbstractHttpFilter implements Filter {

    @Override
    public final void doFilter(ServletRequest request, ServletResponse response,
                               FilterChain chain) throws ServletException, IOException {
        if (request instanceof HttpServletRequest httpRequest
                && response instanceof HttpServletResponse httpResponse) {
            doFilterInternal(httpRequest, httpResponse, chain);
        } else {
            chain.doFilter(request, response);
        }
    }

    /** 子类实现：只处理 HTTP 请求 */
    protected abstract void doFilterInternal(HttpServletRequest request,
                                             HttpServletResponse response,
                                             FilterChain chain) throws ServletException, IOException;
}

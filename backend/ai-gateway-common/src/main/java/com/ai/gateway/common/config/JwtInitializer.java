package com.ai.gateway.common.config;

import com.ai.gateway.common.util.JwtUtil;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * JWT 初始化器：把配置文件的密钥/有效期注入 JwtUtil 静态工具
 *
 * 为什么不把 JwtUtil 做成 Bean？
 * 过滤器与工具类大量以静态方式使用（基线习惯保留），
 * 静态桥接（@PostConstruct 注入）是最小改动方案。
 * 密钥用占位符 ${JWT_SECRET:...}（修 P7：凭据不硬编码进仓库）。
 */
@Slf4j
@Component
public class JwtInitializer {

    @Value("${jwt.secret}")
    private String secret;

    /** AccessToken 有效期（分钟） */
    @Value("${jwt.access-expire-minutes:30}")
    private long accessExpireMinutes;

    /** RefreshToken 有效期（天） */
    @Value("${jwt.refresh-expire-days:7}")
    private long refreshExpireDays;

    @PostConstruct
    public void init() {
        JwtUtil.init(secret, accessExpireMinutes, refreshExpireDays);
        log.info("JwtUtil 初始化完成: accessExpire={}min, refreshExpire={}d",
                accessExpireMinutes, refreshExpireDays);
    }
}

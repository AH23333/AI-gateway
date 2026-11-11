package com.ai.gateway.auth.service;

import com.ai.gateway.auth.mapper.RefreshTokenMapper;
import com.ai.gateway.auth.mapper.UserMapper;
import com.ai.gateway.common.constant.BizCodeEnum;
import com.ai.gateway.common.constant.CommonStatusEnum;
import com.ai.gateway.common.constant.QuotaConstant;
import com.ai.gateway.common.constant.RedisKeyConstant;
import com.ai.gateway.common.dto.LoginRequest;
import com.ai.gateway.common.dto.RegisterRequest;
import com.ai.gateway.common.dto.TokenResponse;
import com.ai.gateway.common.exception.BizException;
import com.ai.gateway.common.pojo.RefreshToken;
import com.ai.gateway.common.pojo.User;
import com.ai.gateway.common.util.DigestUtil;
import com.ai.gateway.common.util.JwtUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 认证服务 —— 注册、登录、刷新、登出（v2 移植 + 哈希化升级）
 *
 * 认证流程（基线机制保留）：
 * 注册 → 生成双 Token → 返回
 * 登录 → 校验密码 → 生成双 Token → 返回
 * 刷新 → 校验 RefreshToken → 吊销旧 Token → 新双 Token（防重用攻击）
 * 登出 → AccessToken 入 Redis 黑名单（TTL=剩余有效期）+ RefreshToken 标记失效
 *
 * v2 变更：
 * - RefreshToken 落库只存 SHA-256 哈希（选型书 3.1）；
 * - 主键自增（雪花 UidGenerator 已删除）；
 * - jjwt 0.12 API（见 JwtUtil）。
 *
 * 为什么用 BCrypt？
 * 慢哈希算法，暴力破解成本极高；每次加密随机生成 salt，相同密码产生不同密文。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final RefreshTokenMapper refreshTokenMapper;
    private final StringRedisTemplate redisTemplate;

    /** BCrypt 慢哈希（无状态实例，线程安全） */
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * 用户注册
     *
     * 步骤：唯一性检查 → BCrypt 加密密码 → 分配默认配额 → 生成双 Token。
     */
    @Transactional
    public TokenResponse register(RegisterRequest request) {
        if (request.getUsername() == null || request.getUsername().isBlank()
                || request.getPassword() == null || request.getPassword().isBlank()) {
            throw BizException.of(BizCodeEnum.BAD_REQUEST);
        }
        // 检查用户名唯一性
        User exist = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, request.getUsername()));
        if (exist != null) {
            throw BizException.of(BizCodeEnum.USERNAME_ALREADY_EXISTS);
        }

        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setEmail(request.getEmail());
        user.setDailyQuota(QuotaConstant.DEFAULT_DAILY_QUOTA);
        user.setMonthlyQuota(QuotaConstant.DEFAULT_MONTHLY_QUOTA);
        user.setUsedDaily(0L);
        user.setUsedMonthly(0L);
        user.setReservedDaily(0L);
        user.setReservedMonthly(0L);
        user.setRole("USER");
        user.setStatus(CommonStatusEnum.ENABLED.getCode());
        user.setGmtCreate(LocalDateTime.now());
        user.setGmtModified(LocalDateTime.now());
        userMapper.insert(user);

        return generateTokens(user);
    }

    /**
     * 用户登录
     *
     * 安全口径：
     * - 用户不存在与密码错误返回同一文案（防用户枚举攻击）；
     * - BCrypt 验密（恒定语义）；账号禁用单独提示。
     */
    public TokenResponse login(LoginRequest request) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, request.getUsername()));
        if (user == null) {
            log.warn("登录失败-用户不存在: username=[{}]", request.getUsername());
            throw BizException.of(BizCodeEnum.USERNAME_OR_PASSWORD_ERROR);
        }
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            log.warn("登录失败-密码错误: username=[{}]", request.getUsername());
            throw BizException.of(BizCodeEnum.USERNAME_OR_PASSWORD_ERROR);
        }
        if (user.getStatus() == CommonStatusEnum.DISABLED.getCode()) {
            throw BizException.of(BizCodeEnum.ACCOUNT_DISABLED);
        }
        return generateTokens(user);
    }

    /**
     * 刷新 Token
     *
     * 为什么查数据库哈希而不是只验 JWT 签名？
     * JWT 无状态无法吊销；数据库存哈希 + status 支持"登出即失效""刷新即轮换"。
     *
     * 步骤：
     * 1. SHA-256(传入明文) 匹配 token_hash 且 status=1、未过期；
     * 2. 吊销旧 RefreshToken（防重用攻击）；
     * 3. 旧 AccessToken 加入 Redis 黑名单（TTL=剩余有效期）——
     *    刷新是"换锁"而非"多配一把钥匙"，旧 Access 不吊销则被盗用窗口仍有 30 分钟；
     * 4. 签发新双 Token。
     */
    @Transactional
    public TokenResponse refresh(String accessToken, String refreshTokenValue) {
        if (refreshTokenValue == null || refreshTokenValue.isBlank()) {
            throw BizException.of(BizCodeEnum.REFRESH_TOKEN_INVALID);
        }
        RefreshToken stored = refreshTokenMapper.selectOne(
                new LambdaQueryWrapper<RefreshToken>()
                        .eq(RefreshToken::getTokenHash, DigestUtil.sha256Hex(refreshTokenValue))
                        .eq(RefreshToken::getStatus, CommonStatusEnum.ENABLED.getCode())
                        .last("LIMIT 1"));
        if (stored == null || stored.getExpireTime().isBefore(LocalDateTime.now())) {
            throw BizException.of(BizCodeEnum.REFRESH_TOKEN_INVALID);
        }

        // 吊销旧 RefreshToken，防止重用
        stored.setStatus(CommonStatusEnum.DISABLED.getCode());
        refreshTokenMapper.updateById(stored);

        blacklistAccessToken(accessToken);

        User user = userMapper.selectById(stored.getUserId());
        if (user == null) {
            throw BizException.of(BizCodeEnum.USER_NOT_FOUND);
        }
        return generateTokens(user);
    }

    /**
     * 登出（双保险）
     *
     * AccessToken → Redis 黑名单（TTL = 剩余有效期，到期自动清理）；
     * RefreshToken → 数据库标记禁用，防止后续刷新。
     */
    public void logout(String accessToken, String refreshTokenValue) {
        blacklistAccessToken(accessToken);
        if (refreshTokenValue != null && !refreshTokenValue.isBlank()) {
            RefreshToken stored = refreshTokenMapper.selectOne(
                    new LambdaQueryWrapper<RefreshToken>()
                            .eq(RefreshToken::getTokenHash, DigestUtil.sha256Hex(refreshTokenValue))
                            .last("LIMIT 1"));
            if (stored != null) {
                stored.setStatus(CommonStatusEnum.DISABLED.getCode());
                refreshTokenMapper.updateById(stored);
            }
        }
    }

    /** AccessToken 加入 Redis 黑名单；TTL 用剩余有效期而非总有效期，节省内存 */
    private void blacklistAccessToken(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return;
        }
        String blacklistKey = RedisKeyConstant.TOKEN_BLACKLIST + accessToken;
        long remaining = JwtUtil.getRemainingExpire(accessToken);
        if (remaining > 0) {
            redisTemplate.opsForValue().set(blacklistKey, "1", remaining, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 生成双 Token + 持久化 RefreshToken 哈希
     *
     * 每次签发吊销该用户全部旧 RefreshToken（保证至多一条有效，基线行为保留）。
     * RefreshToken 落库的是 SHA-256 哈希（v2，选型书 3.1）。
     */
    private TokenResponse generateTokens(User user) {
        String accessToken = JwtUtil.createAccessToken(user.getId(), user.getUsername(), user.getRole());
        String refreshTokenValue = JwtUtil.createRefreshToken(user.getId());

        // 禁用该用户所有旧的 RefreshToken
        refreshTokenMapper.revokeAllByUserId(user.getId());

        // 哈希持久化
        RefreshToken rt = new RefreshToken();
        rt.setUserId(user.getId());
        rt.setTokenHash(DigestUtil.sha256Hex(refreshTokenValue));
        rt.setExpireTime(LocalDateTime.now().plusDays(7));
        rt.setStatus(CommonStatusEnum.ENABLED.getCode());
        rt.setGmtCreate(LocalDateTime.now());
        refreshTokenMapper.insert(rt);

        return new TokenResponse(accessToken, refreshTokenValue, JwtUtil.getAccessExpire() / 1000);
    }
}

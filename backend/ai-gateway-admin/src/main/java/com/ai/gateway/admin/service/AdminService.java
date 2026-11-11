package com.ai.gateway.admin.service;

import com.ai.gateway.admin.mapper.InvokeStatsMapper;
import com.ai.gateway.auth.mapper.UserMapper;
import com.ai.gateway.common.constant.BizCodeEnum;
import com.ai.gateway.common.exception.BizException;
import com.ai.gateway.common.dto.PageResult;
import com.ai.gateway.common.pojo.InvokeLogV2;
import com.ai.gateway.common.pojo.ModelInfo;
import com.ai.gateway.common.pojo.User;
import com.ai.gateway.router.mapper.ModelInfoMapper;
import com.ai.gateway.router.service.ModelConfigService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理服务 —— 用户管理 + 全局看板（v2 移植 + 看板数据源切换）
 *
 * 用户管理规则（基线语义保留）：
 * - 最后一名管理员不可禁用/降级（LAST_ADMIN_CANNOT_DEGRADE 守卫）；
 * - 配额调整即时生效（预扣条件更新在下一笔请求即按新配额判断）；
 * - 禁用账号：既有 AccessToken 在剩余有效期内仍有效（JWT 无状态的原生窗口，
 *   课程口径可接受；生产需配合黑名单即时踢出）。
 *
 * 看板数据源（v2 变化）：
 * - 实时统计（今日）→ t_invoke_log_v2 聚合（原 TokenUsageMapper 汇总表查询替换）；
 * - 趋势（近7天）→ t_billing_daily 预聚合直查。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminService {

    private final UserMapper userMapper;
    private final ModelInfoMapper modelInfoMapper;
    private final ModelConfigService modelConfigService;
    private final InvokeStatsMapper invokeStatsMapper;

    // ==================== 用户管理 ====================

    /** 用户分页（username 模糊检索） */
    public Page<User> pageUsers(int page, int size, String keyword) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(User::getUsername, keyword.trim());
        }
        wrapper.orderByDesc(User::getId);
        return userMapper.selectPage(new Page<>(page, size), wrapper);
    }

    /** 配额调整（单边可调，>0 校验） */
    @Transactional
    public void adjustQuota(Long userId, Long dailyQuota, Long monthlyQuota) {
        User user = requireUser(userId);
        if (dailyQuota != null) {
            if (dailyQuota <= 0) {
                throw BizException.of(BizCodeEnum.BAD_REQUEST);
            }
            user.setDailyQuota(dailyQuota);
        }
        if (monthlyQuota != null) {
            if (monthlyQuota <= 0) {
                throw BizException.of(BizCodeEnum.BAD_REQUEST);
            }
            user.setMonthlyQuota(monthlyQuota);
        }
        user.setGmtModified(java.time.LocalDateTime.now());
        userMapper.updateById(user);
        log.info("管理端调整配额: userId={}, dailyQuota={}, monthlyQuota={}",
                userId, user.getDailyQuota(), user.getMonthlyQuota());
    }

    /** 启用/禁用账号（禁用最后一名管理员被拒绝） */
    @Transactional
    public void toggleStatus(Long userId, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw BizException.of(BizCodeEnum.BAD_REQUEST);
        }
        User user = requireUser(userId);
        if (status == 0 && "ADMIN".equals(user.getRole())) {
            ensureNotLastAdmin();
        }
        user.setStatus(status);
        user.setGmtModified(java.time.LocalDateTime.now());
        userMapper.updateById(user);
        log.info("管理端变更账号状态: userId={}, status={}", userId, status);
    }

    /** 角色变更（降级最后一名管理员被拒绝） */
    @Transactional
    public void changeRole(Long userId, String role) {
        if (role == null || !("ADMIN".equals(role) || "USER".equals(role))) {
            throw BizException.of(BizCodeEnum.BAD_REQUEST);
        }
        User user = requireUser(userId);
        if ("USER".equals(role) && "ADMIN".equals(user.getRole())) {
            ensureNotLastAdmin();
        }
        user.setRole(role);
        user.setGmtModified(java.time.LocalDateTime.now());
        userMapper.updateById(user);
        log.info("管理端变更角色: userId={}, role={}", userId, role);
    }

    // ==================== 全局看板 ====================

    /**
     * 全量调用日志分页（管理端用量页：跨用户浏览所有调用明细）
     */
    public PageResult<InvokeLogV2> usage(int page, int size) {
        long total = invokeStatsMapper.countAllLogs();
        List<InvokeLogV2> records = invokeStatsMapper.pageAllLogs((long) (page - 1) * size, size);
        return PageResult.of(records, total, page, size);
    }

    /**
     * 管理端看板聚合
     *
     * 结构：今日实时（调用/成功率/Token/费用）+ 近7天模型分布 + 近7天趋势
     * + 用户数/启用模型数/路由开关状态。
     */
    public Map<String, Object> dashboard() {
        Map<String, Object> today = orEmpty(invokeStatsMapper.todayStats());
        List<Map<String, Object>> modelDistribution = invokeStatsMapper.modelDistribution7d();
        List<Map<String, Object>> trend = invokeStatsMapper.weeklyTrend();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("today", withDerived(today));
        result.put("modelDistribution7d", modelDistribution);
        result.put("trend7d", trend);
        result.put("totalUsers", userMapper.selectCount(null));
        result.put("activeModels", modelInfoMapper.selectCount(
                new LambdaQueryWrapper<ModelInfo>().eq(ModelInfo::getStatus, 1)));
        result.put("routingEnabled", modelConfigService.getRoutingConfig().getEnabled());
        return result;
    }

    // ==================== 内部 ====================

    private User requireUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.of(BizCodeEnum.USER_NOT_FOUND);
        }
        return user;
    }

    /** 最后一名管理员守卫（基线规则保留） */
    private void ensureNotLastAdmin() {
        Long adminCount = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getRole, "ADMIN")
                .eq(User::getStatus, 1));
        if (adminCount != null && adminCount <= 1) {
            throw BizException.of(BizCodeEnum.LAST_ADMIN_CANNOT_DEGRADE);
        }
    }

    /** 衍生字段：失败调用数/成功率（前端免二次计算） */
    private Map<String, Object> withDerived(Map<String, Object> today) {
        Map<String, Object> enriched = new LinkedHashMap<>(today);
        long calls = toLong(today.get("calls"));
        long successCalls = toLong(today.get("successCalls"));
        enriched.put("failedCalls", calls - successCalls);
        enriched.put("successRate", calls > 0 ? (double) successCalls / calls : 1.0);
        return enriched;
    }

    private Map<String, Object> orEmpty(Map<String, Object> map) {
        return map == null ? new LinkedHashMap<>() : map;
    }

    private long toLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}

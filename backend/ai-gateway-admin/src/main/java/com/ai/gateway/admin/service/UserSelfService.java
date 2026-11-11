package com.ai.gateway.admin.service;

import com.ai.gateway.admin.mapper.ChatHistoryMapper;
import com.ai.gateway.admin.mapper.InvokeStatsMapper;
import com.ai.gateway.auth.mapper.UserMapper;
import com.ai.gateway.auth.service.ApiKeyService;
import com.ai.gateway.common.constant.BizCodeEnum;
import com.ai.gateway.common.dto.ApiKeyVO;
import com.ai.gateway.common.dto.PageResult;
import com.ai.gateway.common.exception.BizException;
import com.ai.gateway.common.pojo.ChatMessage;
import com.ai.gateway.common.pojo.InvokeLogV2;
import com.ai.gateway.common.pojo.ModelInfo;
import com.ai.gateway.common.pojo.User;
import com.ai.gateway.router.mapper.ModelInfoMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户自助服务 —— 个人信息/改密/用量/Key 管理（基线语义保留 + 数据源切至 v2 表）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserSelfService {

    /** BCrypt（与 AuthService 同一算法口径，注册/改密/校验一致） */
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private final UserMapper userMapper;
    private final ApiKeyService apiKeyService;
    private final InvokeStatsMapper invokeStatsMapper;
    private final ChatHistoryMapper chatHistoryMapper;
    private final ModelInfoMapper modelInfoMapper;

    /** 个人信息 + 配额水位 + 今日/本月用量 */
    public Map<String, Object> info(Long userId) {
        User user = requireUser(userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", user.getId());
        result.put("username", user.getUsername());
        result.put("email", user.getEmail());
        result.put("role", user.getRole());
        result.put("status", user.getStatus());
        result.put("dailyQuota", user.getDailyQuota());
        result.put("monthlyQuota", user.getMonthlyQuota());
        result.put("usedDaily", user.getUsedDaily());
        result.put("usedMonthly", user.getUsedMonthly());
        result.put("reservedDaily", user.getReservedDaily());
        result.put("reservedMonthly", user.getReservedMonthly());
        result.put("gmtCreate", user.getGmtCreate());
        result.put("todayUsage", invokeStatsMapper.userTodayStats(userId));
        result.put("monthUsage", invokeStatsMapper.userMonthStats(userId));
        return result;
    }

    /** 修改密码（原密码校验 + 最小长度约束） */
    @Transactional
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        User user = requireUser(userId);
        if (oldPassword == null || newPassword == null
                || !passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw BizException.of(BizCodeEnum.USERNAME_OR_PASSWORD_ERROR);
        }
        if (newPassword.length() < 6) {
            throw BizException.of(BizCodeEnum.BAD_REQUEST);
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setGmtModified(LocalDateTime.now());
        userMapper.updateById(user);
        log.info("用户改密: userId={}", userId);
    }

    /** 我的 Key 列表 */
    public List<ApiKeyVO> myApiKeys(Long userId) {
        return apiKeyService.listByUser(userId);
    }

    /** 创建 Key（明文仅本次返回） */
    public ApiKeyVO createApiKey(Long userId, String keyName) {
        return apiKeyService.create(userId, keyName);
    }

    /** 吊销我的 Key（所有权校验内联） */
    @Transactional
    public void revokeApiKey(Long userId, Long apiKeyId) {
        apiKeyService.revokeByOwner(apiKeyId, userId);
    }

    /** 我的调用日志分页 */
    public PageResult<InvokeLogV2> myLogs(Long userId, int page, int size) {
        long total = chatHistoryMapper.countUserLogs(userId);
        List<InvokeLogV2> records =
                chatHistoryMapper.pageUserLogs(userId, (long) (page - 1) * size, size);
        return PageResult.of(records, total, page, size);
    }

    /**
     * 对话可用模型列表（启用态，前端模型选择器数据源）
     *
     * 返回精简键（name/displayName）而非完整实体：
     * endpoint/apiKey 等上游敏感配置不暴露给普通登录用户。
     */
    public List<Map<String, String>> enabledModels() {
        List<ModelInfo> models = modelInfoMapper.selectList(new LambdaQueryWrapper<ModelInfo>()
                .eq(ModelInfo::getStatus, 1)
                .orderByAsc(ModelInfo::getId));
        List<Map<String, String>> result = new ArrayList<>(models.size());
        for (ModelInfo model : models) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("name", model.getModelCode());
            item.put("displayName", model.getModelName());
            result.add(item);
        }
        return result;
    }

    private User requireUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.of(BizCodeEnum.USER_NOT_FOUND);
        }
        return user;
    }
}

package com.ai.gateway.admin.service;

import com.ai.gateway.admin.mapper.AuditLogMapper;
import com.ai.gateway.common.dto.PageResult;
import com.ai.gateway.common.pojo.AuditLog;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 操作审计查询服务（v2.5 操作审计，1Panel 对标项 2）
 *
 * 只读查询：审计记录由 AuditAspect 异步旁路写入，本服务仅供管理端审计页检索。
 */
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogMapper auditLogMapper;

    /**
     * 审计日志分页检索
     *
     * @param page     页码（从 1 开始）
     * @param size     每页大小
     * @param username 操作人（精确，空忽略）
     * @param action   动作编码（精确，空忽略）
     * @param success  结果（1 成功 0 失败，空忽略）
     * @param start    起始时间（含，空忽略）
     * @param end      截止时间（含，空忽略）
     */
    public PageResult<AuditLog> page(int page, int size, String username, String action,
                                     Integer success, LocalDateTime start, LocalDateTime end) {
        LambdaQueryWrapper<AuditLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(username != null && !username.isBlank(), AuditLog::getUsername, trimToNull(username))
                .eq(action != null && !action.isBlank(), AuditLog::getAction, trimToNull(action))
                .eq(success != null, AuditLog::getSuccess, success)
                .ge(start != null, AuditLog::getCreatedAt, start)
                .le(end != null, AuditLog::getCreatedAt, end)
                .orderByDesc(AuditLog::getId);
        Page<AuditLog> result = auditLogMapper.selectPage(new Page<>(page, size), wrapper);
        return PageResult.of(result.getRecords(), result.getTotal(),
                (int) result.getCurrent(), (int) result.getSize());
    }

    private String trimToNull(String value) {
        return value == null ? null : value.trim();
    }
}

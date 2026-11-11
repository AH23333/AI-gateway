package com.ai.gateway.admin.mapper;

import com.ai.gateway.common.pojo.AuditLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 操作审计 Mapper（v2.5 操作审计）
 *
 * 双重注册约定：启动类 @MapperScan 已扫 admin.mapper 包 + 接口加 @Mapper。
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}

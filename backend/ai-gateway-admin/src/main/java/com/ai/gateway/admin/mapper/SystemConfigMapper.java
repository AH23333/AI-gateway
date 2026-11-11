package com.ai.gateway.admin.mapper;

import com.ai.gateway.common.pojo.SystemConfig;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 系统设置 Mapper（v2.4 系统设置中心）
 *
 * 双重注册约定：启动类 @MapperScan 已扫 admin.mapper 包 + 接口加 @Mapper。
 */
@Mapper
public interface SystemConfigMapper extends BaseMapper<SystemConfig> {
}

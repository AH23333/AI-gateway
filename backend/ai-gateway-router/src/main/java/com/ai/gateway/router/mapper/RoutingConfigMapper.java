package com.ai.gateway.router.mapper;

import com.ai.gateway.common.pojo.RoutingConfig;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 路由配置 Mapper（t_routing_config 单行表，id=1）
 *
 * FR-11 总开关 + FR-05 权重：管理端 PUT 更新 → ModelConfigService 缓存失效 → 60s 内全量生效。
 */
@Mapper
public interface RoutingConfigMapper extends BaseMapper<RoutingConfig> {
}

package com.ai.gateway.router.mapper;

import com.ai.gateway.common.pojo.ModelInfo;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 模型表 Mapper（t_model 元数据域）
 *
 * admin 模块通过本 Mapper 做模型 CRUD + 更新后调用 ModelConfigService 失效缓存；
 * router 模块只读（走 Caffeine 缓存）。
 */
@Mapper
public interface ModelInfoMapper extends BaseMapper<ModelInfo> {
}

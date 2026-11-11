package com.ai.gateway.auth.mapper;

import com.ai.gateway.common.pojo.ApiKey;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * API Key 表 Mapper（v2：key_hash 查询）
 */
@Mapper
public interface ApiKeyMapper extends BaseMapper<ApiKey> {
}

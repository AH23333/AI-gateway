package com.ai.gateway.admin.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 存活探针（观测域）：SELECT 1 验证数据源连通性。
 *
 * 独立 mapper 而非挂在业务 mapper 上：
 * - 语义清晰（探活 ≠ 业务查询）；
 * - 探活 SQL 频率高（监控页 5s 轮询），避免与业务统计互相干扰定位。
 */
@Mapper
public interface HealthProbeMapper {

    /** 数据源探活：返回 1 即连通 */
    @Select("SELECT 1")
    int dbPing();
}

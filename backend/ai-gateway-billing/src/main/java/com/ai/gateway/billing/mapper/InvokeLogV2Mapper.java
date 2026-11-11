package com.ai.gateway.billing.mapper;

import com.ai.gateway.common.pojo.InvokeLogV2;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 调用日志 Mapper —— t_invoke_log_v2 分区窄表
 *
 * 写入路径：settle 事务内同步单条 insert（账务强一致部分）；
 * 读取路径：管理端统计走 admin 模块自己的 InvokeStatsMapper（窄接口按域拆分）。
 */
@Mapper
public interface InvokeLogV2Mapper extends BaseMapper<InvokeLogV2> {
}

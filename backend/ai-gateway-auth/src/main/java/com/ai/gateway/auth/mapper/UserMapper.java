package com.ai.gateway.auth.mapper;

import com.ai.gateway.common.pojo.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户表 Mapper（auth 域基础 CRUD）
 *
 * 说明：t_user 被多个模块访问，但各模块只暴露自己所需的窄接口——
 * auth 域只做凭证相关的基础读写；
 * billing 域的 QuotaUserMapper 承载配额原子 SQL（reserve/settle）；
 * admin 域的 AdminUserMapper 承载管理端列表/配额调整。
 * 表仍然只有一张，接口按域拆分是为了拆分微服务时边界清晰。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}

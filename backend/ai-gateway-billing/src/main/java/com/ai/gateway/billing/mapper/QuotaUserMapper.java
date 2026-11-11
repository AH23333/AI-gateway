package com.ai.gateway.billing.mapper;

import com.ai.gateway.common.pojo.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 配额账户 Mapper —— t_user 上的配额原子操作（v2 核心，选型书 5.1）
 *
 * 为什么不用 MyBatis-Plus 的实体更新而手写 SQL？
 * 两段式计费的防超扣依赖"读取判断与写入"必须是同一条原子语句：
 *   reserve：WHERE 里同时判断 used + reserved + est <= quota，
 *   并发请求在 t_user 行锁上串行化，任何一方失败都拿不到行锁后的更新资格；
 *   若拆成"先 SELECT 再 UPDATE"，两个并发都能通过检查 → 超扣（基线缺陷）。
 *
 * GREATEST(..., 0) 兜底：
 * settle/reconcile 释放预扣水位时，若水位已被异常清零（人工修数等），
 * 不允许出现负数污染后续判断。
 */
@Mapper
public interface QuotaUserMapper {

    /**
     * 预扣水位上浮（reserve 阶段）
     *
     * 条件更新的原子性即防超扣闸门：
     * 日/月配额同时判断（used + reserved + est 双边约束），
     * status=1 同时校验账号启用（禁用账号立即失去调用资格）。
     *
     * @return 1=预扣成功；0=配额不足/账号禁用/用户不存在（由调用方细分原因）
     */
    @Update("UPDATE t_user SET "
            + "reserved_daily = reserved_daily + #{est}, "
            + "reserved_monthly = reserved_monthly + #{est} "
            + "WHERE id = #{userId} AND status = 1 "
            + "AND used_daily + reserved_daily + #{est} <= daily_quota "
            + "AND used_monthly + reserved_monthly + #{est} <= monthly_quota")
    int reserve(@Param("userId") Long userId, @Param("est") long est);

    /**
     * 结算（settle 阶段）：实际用量入账 + 预扣水位释放
     *
     * actual 允许大于 est（估算偏低时不拒绝已完成的调用，诚实入账），
     * 超出部分计入下一轮 reserve 的判断 —— 配额闸门在下一次请求收口。
     */
    @Update("UPDATE t_user SET "
            + "used_daily = used_daily + #{actual}, "
            + "used_monthly = used_monthly + #{actual}, "
            + "reserved_daily = GREATEST(reserved_daily - #{est}, 0), "
            + "reserved_monthly = GREATEST(reserved_monthly - #{est}, 0) "
            + "WHERE id = #{userId}")
    int settleUsage(@Param("userId") Long userId,
                    @Param("actual") long actual,
                    @Param("est") long est);

    /**
     * 释放预扣水位（reconcile 回滚阶段）：只减水位，不动用量
     */
    @Update("UPDATE t_user SET "
            + "reserved_daily = GREATEST(reserved_daily - #{est}, 0), "
            + "reserved_monthly = GREATEST(reserved_monthly - #{est}, 0) "
            + "WHERE id = #{userId}")
    int release(@Param("userId") Long userId, @Param("est") long est);

    /** 每日 00:00 重置当日已结算用量（预扣水位不动，由各自流水自然收敛） */
    @Update("UPDATE t_user SET used_daily = 0, gmt_modified = NOW()")
    int resetDaily();

    /** 每月 1 日 00:00 重置当月已结算用量 */
    @Update("UPDATE t_user SET used_monthly = 0, gmt_modified = NOW()")
    int resetMonthly();

    /** 配额上下文查询（reserve 失败后的原因细分用） */
    @Select("SELECT * FROM t_user WHERE id = #{userId}")
    User findById(@Param("userId") Long userId);
}

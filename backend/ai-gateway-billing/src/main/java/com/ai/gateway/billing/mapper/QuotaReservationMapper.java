package com.ai.gateway.billing.mapper;

import com.ai.gateway.common.pojo.QuotaReservation;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 配额预扣流水 Mapper（v2 新增）
 *
 * 两处条件更新的 status=0 前置是幂等与互斥的锚点：
 * - settle：status 0→1，回填实际用量与结算时间；
 * - reconcile：status 0→2，回填回滚时间；
 * 二者并发竞争同一行时，数据库行锁保证只有一个 UPDATE 的 rows=1，
 * 后到者 rows=0 直接跳过 —— 无双结算、无双回滚（选型书 5.1/5.2）。
 */
@Mapper
public interface QuotaReservationMapper extends BaseMapper<QuotaReservation> {

    /** settle 抢占：status 0→1，rows=0 表示已被结算或已回滚（幂等命中） */
    @Update("UPDATE t_quota_reservation SET status = 1, actual_tokens = #{actualTokens}, "
            + "settled_time = NOW() WHERE id = #{id} AND status = 0")
    int closeForSettle(@Param("id") Long id, @Param("actualTokens") long actualTokens);

    /** reconcile 回滚抢占：status 0→2，rows=0 表示已被 settle 抢先结算 */
    @Update("UPDATE t_quota_reservation SET status = 2, rollback_time = NOW() "
            + "WHERE id = #{id} AND status = 0")
    int closeExpired(@Param("id") Long id);
}

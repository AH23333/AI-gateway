package com.ai.gateway.billing.mapper;

import com.ai.gateway.common.pojo.BillingDaily;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 每日计费汇总 Mapper —— INSERT ... ON DUPLICATE KEY UPDATE 原子累加
 *
 * uk(stat_date, user_id, model_code) 命中即转为 UPDATE 累加，
 * 并发 upsert 由数据库唯一键 + 行锁保证不丢不重；
 * 批量层（BatchLogWriter）已按 (userId, modelCode) 预合并，
 * 同一批内不会出现同键多行导致 VALUES 覆盖的边界问题。
 */
@Mapper
public interface BillingDailyMapper extends BaseMapper<BillingDaily> {

    /** 批量原子累加（含首次插入），每批 ≤500 条 */
    @Insert("<script>"
            + "INSERT INTO t_billing_daily "
            + "(user_id, stat_date, model_code, call_count, total_prompt_tokens, "
            + "total_completion_tokens, total_tokens, total_cost, gmt_create, gmt_modified) VALUES "
            + "<foreach collection='list' item='b' separator=','>"
            + "(#{b.userId}, #{b.statDate}, #{b.modelCode}, #{b.callCount}, #{b.totalPromptTokens}, "
            + "#{b.totalCompletionTokens}, #{b.totalTokens}, #{b.totalCost}, NOW(), NOW())"
            + "</foreach>"
            + "ON DUPLICATE KEY UPDATE "
            + "call_count = call_count + VALUES(call_count), "
            + "total_prompt_tokens = total_prompt_tokens + VALUES(total_prompt_tokens), "
            + "total_completion_tokens = total_completion_tokens + VALUES(total_completion_tokens), "
            + "total_tokens = total_tokens + VALUES(total_tokens), "
            + "total_cost = total_cost + VALUES(total_cost), "
            + "gmt_modified = NOW()"
            + "</script>")
    int upsertBatch(@Param("list") List<BillingDaily> deltas);
}

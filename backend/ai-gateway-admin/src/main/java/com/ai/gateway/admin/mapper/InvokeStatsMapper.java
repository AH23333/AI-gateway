package com.ai.gateway.admin.mapper;

import com.ai.gateway.common.pojo.InvokeLogV2;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 调用统计 Mapper —— 管理端看板聚合（t_invoke_log_v2 只读）
 *
 * 设计说明：
 * - 聚合 SQL 集中在此（管理域窄接口），billing 的 InvokeLogV2Mapper 保持纯写入职责；
 * - 全表/分区扫描在课程量级（万级/日）毫秒完成，暂不引入预聚合物化；
 * - Map 承载聚合行（列名即 key），管理端看板字段演进频繁，强类型 VO 反而僵化。
 */
@Mapper
public interface InvokeStatsMapper {

    /** 模型被调用次数（删除模型前的引用检查） */
    @Select("SELECT COUNT(*) FROM t_invoke_log_v2 WHERE model_code = #{modelCode}")
    long countByModelCode(@Param("modelCode") String modelCode);

    /** 今日全局统计：calls / successCalls / tokens / cost */
    @Select("SELECT COUNT(*) AS calls, COALESCE(SUM(success), 0) AS successCalls, "
            + "COALESCE(SUM(total_tokens), 0) AS tokens, "
            + "COALESCE(SUM(cost_amount), 0) AS cost "
            + "FROM t_invoke_log_v2 WHERE gmt_create >= CURDATE()")
    Map<String, Object> todayStats();

    /** 近 7 天按模型分布（趋势/占比饼图） */
    @Select("SELECT model_code AS modelCode, COUNT(*) AS calls, "
            + "COALESCE(SUM(total_tokens), 0) AS tokens, "
            + "COALESCE(SUM(cost_amount), 0) AS cost "
            + "FROM t_invoke_log_v2 WHERE gmt_create >= DATE_SUB(CURDATE(), INTERVAL 7 DAY) "
            + "GROUP BY model_code ORDER BY calls DESC")
    List<Map<String, Object>> modelDistribution7d();

    /** 用户今日统计：calls / successCalls / tokens / cost */
    @Select("SELECT COUNT(*) AS calls, COALESCE(SUM(success), 0) AS successCalls, "
            + "COALESCE(SUM(total_tokens), 0) AS tokens, "
            + "COALESCE(SUM(cost_amount), 0) AS cost "
            + "FROM t_invoke_log_v2 WHERE user_id = #{userId} AND gmt_create >= CURDATE()")
    Map<String, Object> userTodayStats(@Param("userId") Long userId);

    /** 用户本月统计 */
    @Select("SELECT COUNT(*) AS calls, COALESCE(SUM(success), 0) AS successCalls, "
            + "COALESCE(SUM(total_tokens), 0) AS tokens, "
            + "COALESCE(SUM(cost_amount), 0) AS cost "
            + "FROM t_invoke_log_v2 WHERE user_id = #{userId} "
            + "AND gmt_create >= DATE_SUB(CURDATE(), INTERVAL DAYOFMONTH(CURDATE()) - 1 DAY)")
    Map<String, Object> userMonthStats(@Param("userId") Long userId);

    /** 智能路由决策日志总数（routing_decision 非空） */
    @Select("SELECT COUNT(*) FROM t_invoke_log_v2 WHERE routing_decision IS NOT NULL")
    long countRoutingDecisions();

    /** 智能路由决策日志分页（FR-05 可解释性回放） */
    @Select("SELECT * FROM t_invoke_log_v2 WHERE routing_decision IS NOT NULL "
            + "ORDER BY id DESC LIMIT #{offset}, #{size}")
    List<InvokeLogV2> pageRoutingDecisions(@Param("offset") long offset, @Param("size") int size);

    /** 全量调用日志总数（管理端用量浏览） */
    @Select("SELECT COUNT(*) FROM t_invoke_log_v2")
    long countAllLogs();

    /** 全量调用日志分页（管理端用量浏览，所有用户） */
    @Select("SELECT * FROM t_invoke_log_v2 ORDER BY id DESC LIMIT #{offset}, #{size}")
    List<InvokeLogV2> pageAllLogs(@Param("offset") long offset, @Param("size") int size);

    /** 近 7 天计费趋势（t_billing_daily 预聚合直查，看板折线图） */
    @Select("SELECT DATE_FORMAT(stat_date, '%Y-%m-%d') AS statDate, SUM(call_count) AS calls, "
            + "COALESCE(SUM(total_tokens), 0) AS tokens, "
            + "COALESCE(SUM(total_cost), 0) AS cost "
            + "FROM t_billing_daily WHERE stat_date >= DATE_SUB(CURDATE(), INTERVAL 7 DAY) "
            + "GROUP BY stat_date ORDER BY statDate")
    List<Map<String, Object>> weeklyTrend();
}

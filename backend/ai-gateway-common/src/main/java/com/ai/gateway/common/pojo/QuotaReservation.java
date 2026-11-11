package com.ai.gateway.common.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 配额预扣流水实体 —— 对应 t_quota_reservation 表（v2 新增，选型书 5.1）
 *
 * 为什么用独立流水表而不是 t_user 单字段？
 * 同一用户多笔并发预扣在行上混叠，无法枚举"哪一笔过期、回滚多少"。
 * 独立流水表让回滚有明确对象，且流水本身就是预扣审计记录。
 *
 * 状态机：
 *   0 = 预留中（reserve 已插入，等待 settle）
 *   1 = 已结算（settle 事务内 UPDATE ... WHERE trace_id AND status=0 抢占，rows=0 即幂等命中）
 *   2 = 已回滚（reconcile 兜底：超过容忍窗口仍未结算，回滚预扣水位）
 *
 * settle 与 reconcile 对同一 trace_id 的并发：
 * 两处 UPDATE 在 reservation 行锁上互斥，后到者 rows=0 跳过 —— 无双结算/无双回滚。
 */
@Data
@TableName("t_quota_reservation")
public class QuotaReservation {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 一次调用的预扣凭证（traceId 全局唯一，uk_trace） */
    private String traceId;

    /** 用户 ID */
    private Long userId;

    /** 预扣估算 Token 数（est_input + est_output，见 TokenEstimator） */
    private Integer estTokens;

    /** 状态：0=预留中 1=已结算 2=已回滚 */
    private Integer status;

    /** 实际消耗 Token 数（settle 时回填，审计对账用） */
    private Integer actualTokens;

    /** 结算时间（status→1 时回填） */
    private LocalDateTime settledTime;

    /** 回滚时间（status→2 时回填） */
    private LocalDateTime rollbackTime;

    private LocalDateTime gmtCreate;
}

package com.ai.gateway.common.pojo;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 调用日志实体 —— 对应 t_invoke_log_v2 分区窄表（v2 核心表，选型书 3.2 D4）
 *
 * 设计要点：
 * - 窄表：无 MEDIUMTEXT 报文列（报文采样出库至本地磁盘，payload_ref 记录 key）；
 * - 幂等键：uk_trace(trace_id, gmt_create) —— 分区表唯一键必须含分区键，
 *   只能防"同秒重复写入"，真实幂等由结算链路 status 抢占保证（选型书 5.2）；
 * - 月分区：RANGE (TO_DAYS(gmt_create))，90 天后 DROP PARTITION 替代 DELETE；
 * - 流式语义预留：ttft_ms（首 token 延迟）/ interrupted（流式中断/取消）（PR-7）；
 * - v2 启用列：routing_decision（智能路由决策 JSON）+ agent_task_id
 *   （X-Agent-Task-Id 任务归因，1.md §8.1/§8.4）。
 */
@Data
@TableName("t_invoke_log_v2")
public class InvokeLogV2 {

    @JsonSerialize(using = ToStringSerializer.class)
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 幂等键（一次调用的 traceId） */
    private String traceId;

    /** 用户 ID */
    private Long userId;

    /** 实际应答模型编码（降级后为备用模型，计费随之） */
    private String modelCode;

    /** 输入 Token 数 */
    private Integer promptTokens;

    /** 输出 Token 数 */
    private Integer completionTokens;

    /** 总 Token 数 = prompt + completion */
    private Integer totalTokens;

    /** 本次调用费用（按实际应答模型生效价计算） */
    private BigDecimal costAmount;

    /** 调用耗时（毫秒） */
    private Integer latencyMs;

    /** 首 token 延迟（毫秒，仅流式有值） */
    private Integer ttftMs;

    /** 是否成功：1=成功, 0=失败/中断 */
    private Integer success;

    /** 是否使用降级模型：1=是, 0=否 */
    private Integer fallbackUsed;

    /** 是否流式中断/取消：1=是, 0=否 */
    private Integer interrupted;

    /** 智能路由决策 JSON（自动路由时落，指定模型时为 null） */
    private String routingDecision;

    /** Agent 任务归因标识（X-Agent-Task-Id 头，≤64 字符，可空） */
    private String agentTaskId;

    /** 报文采样落盘的对象 key（inv/{yyyyMM}/{traceId}.json），未采样为 null */
    private String payloadRef;

    /** 结算时使用的价格版本号（t_model_price.version，复算历史账单用） */
    private Integer priceVersion;

    /** 创建时间（分区键，DB 默认值填充） */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime gmtCreate;
}

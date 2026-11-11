package com.ai.gateway.billing.dto;

import com.ai.gateway.common.pojo.ChatMessage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 结算上下文 —— router 组装、billing 消费（v2 新增）
 *
 * 定位：模型调用结束后，router 把"这次调用发生了什么"的全部事实
 * 打包交给 QuotaService.settle 单事务入账。字段即 t_invoke_log_v2 的列，
 * 结算是账务域唯一的写入口，router 不直接碰日志表。
 *
 * 设计取向：字段冗余（而不是让 billing 反查 router 状态）——
 * 结算所需事实在调用结束一刻已全部产生，快照式传递让两个域
 * 之间没有回读耦合，未来拆分微服务时本对象可直接序列化为消息体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SettleContext {

    /** 预扣流水 ID（reserve 返回，settle 抢占的定位键） */
    private Long reservationId;

    private Long userId;

    /** 全链路追踪 ID（幂等键） */
    private String traceId;

    /** 实际应答模型编码（降级后为备用模型） */
    private String modelCode;

    /** Agent 任务归因（X-Agent-Task-Id，可空） */
    private String agentTaskId;

    /** 是否成功完成（false = 失败/中断/取消） */
    private boolean success;

    /** 是否发生降级（备用模型应答） */
    private boolean fallbackUsed;

    /** 是否流式中断/客户端取消 */
    private boolean interrupted;

    /** 智能路由决策 JSON（自动路由时非空；手动指定模型为 null） */
    private String routingDecision;

    /** 报文采样对象 key（PayloadStore 返回，未采样为 null） */
    private String payloadRef;

    /** 结算价版本（t_model_price.version） */
    private Integer priceVersion;

    /** 实际输入 Token 数 */
    private long promptTokens;

    /** 实际输出 Token 数 */
    private long completionTokens;

    /** 总 Token 数 */
    private long totalTokens;

    /** 本次调用费用（按实际应答模型生效价计算） */
    private BigDecimal costAmount;

    /** 总耗时（毫秒） */
    private long latencyMs;

    /** 首 token 延迟（毫秒，仅流式有值，可空） */
    private Long ttftMs;

    /** 会话内容快照（D5 落库用：输入消息 + assistant 应答，settle 后异步批量写） */
    private List<ChatMessage> chatMessages;
}

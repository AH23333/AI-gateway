package com.ai.gateway.router.dto;

/**
 * 对话请求头集合（1.md §7.1 请求级路由约束，原样字符串传入由 ChatService 解析）
 *
 * - X-Cost-Budget：请求级成本预算（元，正数）；非法值按 OQ-10 口径忽略并告警
 * - X-Latency-SLA：请求级延迟 SLA（毫秒，正整数）；非法值忽略
 * - X-Agent-Task-Id：Agent 任务归因标识（≤64 字符，[A-Za-z0-9_-]），
 *   供平台侧按任务聚合统计（FR-13 平台应用闭环的数据地基）
 */
public record ChatHeaders(String costBudget, String latencySlaMs, String agentTaskId) {
}

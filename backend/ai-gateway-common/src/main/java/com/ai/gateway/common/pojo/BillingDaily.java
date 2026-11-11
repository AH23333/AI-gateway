package com.ai.gateway.common.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每日计费汇总实体 —— 对应 t_billing_daily 表（预聚合思路保留，选型书 3.2）
 *
 * 预聚合是正确的：明细在分区窄表（90 天 TTL），
 * 汇总永久保留，供管理端看板直查（升级 ClickHouse 后由物化视图替代）。
 *
 * 写入方式：settle 事件 → 内存队列批量层 → INSERT ... ON DUPLICATE KEY UPDATE 累加。
 */
@Data
@TableName("t_billing_daily")
public class BillingDaily {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户 ID */
    private Long userId;

    /** 统计日期 */
    private LocalDate statDate;

    /** 模型编码 */
    private String modelCode;

    /** 调用次数 */
    private Integer callCount;

    /** 总输入 Token */
    private Long totalPromptTokens;

    /** 总输出 Token */
    private Long totalCompletionTokens;

    /** 总 Token 数 */
    private Long totalTokens;

    /** 总费用 */
    private BigDecimal totalCost;

    private LocalDateTime gmtCreate;

    private LocalDateTime gmtModified;
}

package com.ai.gateway.common.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模型价格实体 —— 对应 t_model_price 表（v2 新增：价格版本化，选型书 3.7）
 *
 * 机制：改价 = 插入一条 version+1、effective_time=当前时间的新记录，
 * 老记录保留 —— 历史账单凭 price_version 仍可按旧价复算。
 * 生效价 = effective_time <= now 的最大 version 且 status=1 的记录。
 */
@Data
@TableName("t_model_price")
public class ModelPrice {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 模型编码（t_model.model_code） */
    private String modelCode;

    /** 价格版本号：同一模型内递增，账单记录版本号以复算历史 */
    private Integer version;

    /** 输入单价（元/千Token） */
    private BigDecimal priceInput;

    /** 输出单价（元/千Token） */
    private BigDecimal priceOutput;

    /** 生效时间：<= 当前时间的最大版本生效 */
    private LocalDateTime effectiveTime;

    /** 状态：0=停用, 1=启用 */
    private Integer status;

    private LocalDateTime gmtCreate;
}

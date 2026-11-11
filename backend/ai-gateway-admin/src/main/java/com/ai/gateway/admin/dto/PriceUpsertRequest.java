package com.ai.gateway.admin.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 模型价格新增请求（版本化改价，选型书 3.7）
 *
 * 每次调用 = 追加一条 version+1、立即生效的新记录；历史账单凭旧版本复算。
 */
@Data
public class PriceUpsertRequest {

    /** 输入单价（元/千Token，>0） */
    private BigDecimal priceInput;

    /** 输出单价（元/千Token，>0） */
    private BigDecimal priceOutput;
}

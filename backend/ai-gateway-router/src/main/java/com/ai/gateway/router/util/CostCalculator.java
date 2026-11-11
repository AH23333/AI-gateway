package com.ai.gateway.router.util;

import com.ai.gateway.common.pojo.ModelPrice;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 费用计算器（元，6 位小数）
 *
 * 口径（选型书 3.7）：费用 = prompt_tokens × price_input / 1000
 *                    + completion_tokens × price_output / 1000
 * 单价单位"元/千Token"，与商业 API 计费口径一致；
 * 未定价模型按 0 元计费（管理端漏配价格时的容错，日志另行告警）。
 */
public final class CostCalculator {

    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);
    private static final int SCALE = 6;

    private CostCalculator() {
    }

    /** 按生效价计算一次调用费用 */
    public static BigDecimal cost(ModelPrice price, long promptTokens, long completionTokens) {
        if (price == null || price.getPriceInput() == null || price.getPriceOutput() == null) {
            return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal inputCost = price.getPriceInput()
                .multiply(BigDecimal.valueOf(promptTokens))
                .divide(THOUSAND, SCALE, RoundingMode.HALF_UP);
        BigDecimal outputCost = price.getPriceOutput()
                .multiply(BigDecimal.valueOf(completionTokens))
                .divide(THOUSAND, SCALE, RoundingMode.HALF_UP);
        return inputCost.add(outputCost);
    }
}

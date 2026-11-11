package com.ai.gateway.common.exception;

import com.ai.gateway.common.constant.BizCodeEnum;

/**
 * 业务异常
 *
 * 区别于系统异常（NullPointer 等），业务异常是"预期内"的错误：
 * - 用户名已存在
 * - API Key 无效
 * - 配额不足（预扣失败）
 *
 * 携带 code 字段，GlobalExceptionHandler 捕获后按路径分流：
 * - /v1/** → OpenAI error JSON（OpenAI SDK 可识别）
 * - 其他   → Result JSON（前端契约）
 *
 * 推荐用法：BizException.of(BizCodeEnum.XXX[, 动态参数...])
 * 错误码与文案统一收敛在 BizCodeEnum，禁止在业务代码里手写字符串
 */
public class BizException extends RuntimeException {

    /** 业务错误码 */
    private final Integer code;

    public BizException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    /** 从错误码枚举构造（推荐）：无动态参数时直接使用枚举文案 */
    public static BizException of(BizCodeEnum errorCode) {
        return new BizException(errorCode.getCode(), errorCode.getDesc());
    }

    /** 从错误码枚举构造（推荐）：动态参数填充模板占位符 */
    public static BizException of(BizCodeEnum errorCode, Object... args) {
        return new BizException(errorCode.getCode(), errorCode.format(args));
    }

    public Integer getCode() {
        return code;
    }
}

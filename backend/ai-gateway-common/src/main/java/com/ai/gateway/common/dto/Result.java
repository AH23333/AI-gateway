package com.ai.gateway.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一响应体（管理端/前端契约）
 *
 * 所有非 OpenAI 协议接口的返回值都包装为 Result<T>：
 * code=200 → 成功，取 data 字段
 * code≠200 → 失败，取 msg 显示错误信息
 *
 * 注意：/v1/chat/completions 是 OpenAI 协议面，响应不包 Result
 * （OpenAI SDK 无法识别包装层），错误由 GlobalExceptionHandler 翻译为
 * OpenAI error JSON。
 *
 * 使用静态工厂方法创建，避免 new Result() 分散在各处
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {

    /** 业务状态码，200 表示成功 */
    private Integer code;

    /** 提示信息 */
    private String msg;

    /** 响应数据，泛型支持任意类型 */
    private T data;

    /** 成功响应（带数据） */
    public static <T> Result<T> ok(T data) {
        return new Result<>(200, "success", data);
    }

    /** 成功响应（无数据，如删除操作） */
    public static <T> Result<T> ok() {
        return new Result<>(200, "success", null);
    }

    /** 失败响应（自定义错误码） */
    public static <T> Result<T> fail(Integer code, String msg) {
        return new Result<>(code, msg, null);
    }

    /** 失败响应（默认500） */
    public static <T> Result<T> fail(String msg) {
        return new Result<>(500, msg, null);
    }
}

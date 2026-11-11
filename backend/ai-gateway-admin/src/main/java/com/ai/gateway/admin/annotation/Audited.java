package com.ai.gateway.admin.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作审计注解（v2.5 操作审计，1Panel 对标项 2）
 *
 * 标注在管理端写操作方法上，由 {@link com.ai.gateway.admin.aspect.AuditAspect}
 * 环绕拦截：自动记录 who（注入头身份）/ when / what（action + URI + 参数摘要）/ 结果
 * （成功失败 + 耗时 + IP）到 t_audit_log，审计失败不影响业务。
 *
 * 用法：@Audited(action = "USER_STATUS", desc = "启用/禁用账号")
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Audited {

    /** 动作编码（大写下划线，审计页过滤器枚举值，如 USER_STATUS） */
    String action();

    /** 动作描述（人类可读，审计页直接展示，如"启用/禁用账号"） */
    String desc();
}

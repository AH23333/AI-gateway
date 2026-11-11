package com.ai.gateway.common.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作审计日志实体 —— 对应 t_audit_log 表（v2.5 操作审计，1Panel 对标项 2）
 *
 * 四要素：who（user_id/username）/ when（created_at）/ what（action/uri/params）/ 结果（success/error_msg）。
 * 由 admin 域 AuditAspect（AOP 切面）在管理端写操作前后自动记录；
 * 审计落库失败只记错误日志，绝不影响业务主流程。
 */
@Data
@TableName("t_audit_log")
public class AuditLog {

    /** 主键（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作人用户 ID（access 过滤器注入的 X-User-Id） */
    private Long userId;

    /** 操作人用户名（冗余存储，便于检索与展示） */
    private String username;

    /** 动作编码（USER_STATUS / SETTING_UPDATE 等，@Audited 声明） */
    private String action;

    /** 动作描述（人类可读，@Audited 声明） */
    private String actionDesc;

    /** HTTP 方法（PUT/POST/DELETE） */
    private String httpMethod;

    /** 请求 URI（含路径变量，即操作对象，如 /admin/users/5/status） */
    private String uri;

    /** 参数摘要（query + body JSON，敏感字段脱敏，超长截断） */
    private String params;

    /** 结果：1 成功 0 失败 */
    private Integer success;

    /** 失败原因（成功为 NULL） */
    private String errorMsg;

    /** 客户端 IP */
    private String ip;

    /** 耗时（毫秒） */
    private Long costMs;

    /** 操作时间 */
    private LocalDateTime createdAt;
}

package com.ai.gateway.common.constant;

/**
 * 业务错误码枚举（统一错误出口）
 *
 * 两类条目：
 * 1. 通用 HTTP 语义码（400~503）：access 模块过滤器等底层组件使用；
 * 2. 业务明细码（1xxx~8xxx）：Service 层抛 BizException 时使用，
 *    消息支持 %s/%d 占位模板，通过 format(args) 填充动态参数。
 *
 * 前端约定：code==200 成功；非 200 弹出 msg。业务码细化对前端透明。
 * /v1/chat/completions（OpenAI 协议面）错误会由 GlobalExceptionHandler
 * 翻译成 OpenAI error JSON，不影响 OpenAI SDK 兼容。
 */
public enum BizCodeEnum {

    // ==================== 通用 HTTP 语义 ====================
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未授权"),
    FORBIDDEN(403, "禁止访问"),
    NOT_FOUND(404, "资源不存在"),
    INTERNAL_ERROR(500, "服务器内部错误"),
    SERVICE_UNAVAILABLE(503, "服务不可用"),

    // ==================== 1xxx 认证/账号（auth）====================
    USERNAME_ALREADY_EXISTS(1001, "用户名已存在"),
    USERNAME_OR_PASSWORD_ERROR(10401, "用户名或密码错误"),
    ACCOUNT_DISABLED(10403, "账号已被禁用"),
    REFRESH_TOKEN_INVALID(10402, "RefreshToken无效或已过期"),

    // ==================== 2xxx 用户管理（admin）====================
    USER_NOT_FOUND(2001, "用户不存在"),
    LAST_ADMIN_CANNOT_DEGRADE(2002, "系统仅有一名管理员，无法将其降级，至少需保留一名管理员"),

    // ==================== 3xxx 模型与对话（admin/router）====================
    MODEL_NOT_FOUND(3001, "模型不存在: %s"),
    MODEL_DISABLED(3002, "模型已禁用: %s"),
    MODEL_CODE_DUPLICATE(3003, "模型编码已存在"),
    MODEL_CALL_FAILED(3004, "模型调用失败: %s"),
    FALLBACK_MODEL_NOT_FOUND(3005, "备用模型不存在: %s"),
    /** 有调用日志的模型不允许物理删除（历史账单按编码引用），只能禁用 */
    MODEL_DELETE_BLOCKED(3009, "模型已有调用日志，不能删除，请改为禁用"),
    CHAT_RATE_LIMITED(3429, "当前模型「%s」请求过于频繁，请稍后再试"),
    STREAM_INTERRUPTED(3006, "生成中断，请重试"),
    GENERATE_FAILED(3007, "生成失败"),

    // ==================== 4xxx API Key（auth/router）====================
    API_KEY_NOT_FOUND(4001, "API Key 不存在"),
    API_KEY_FORBIDDEN(4403, "无权操作此 API Key"),
    API_KEY_REQUIRED_FOR_CHAT(4003, "请先创建 API Key 再进行对话，前往「API Key」页面创建"),

    // ==================== 5xxx 配额计费（billing，两段式）====================
    /** 预扣阶段失败：used + reserved + est 超出日/月配额上限，整单拒绝、不留脏数据 */
    QUOTA_INSUFFICIENT_PRECHECK(5001, "配额不足，当日或当月剩余配额不足以完成此次请求"),
    QUOTA_EXCEEDED(5002, "用户配额不足，已超出日/月限额"),

    // ==================== 6xxx 请求校验（router）====================
    USER_CONTEXT_MISSING(6001, "无法获取用户身份信息"),
    REQUEST_BODY_EMPTY(6002, "请求体不能为空"),
    /** v2：model 允许不传（不传走智能路由），仅在"既没传 model 又无法路由"时使用 */
    MODEL_CODE_REQUIRED(6003, "模型编码不能为空"),
    MESSAGES_REQUIRED(6004, "消息列表不能为空"),
    TOO_MANY_MESSAGES(6005, "消息数量超过限制，最多允许 %d 条"),
    MESSAGE_CONTENT_REQUIRED(6006, "消息内容不能为空"),
    MESSAGE_TOO_LONG(6007, "单条消息长度超过限制，最多允许 %d 字符"),
    INVALID_ROLE(6008, "无效的角色: %s，必须是 user、assistant、system 或 tool"),

    // ==================== 8xxx 智能路由（router，v2 新增）====================
    /** 过滤链后无可用候选模型（全部被健康/预算/SLA/池过滤） */
    ROUTE_NO_CANDIDATE(8001, "无可用候选模型，请检查模型池/预算/SLA 配置"),
    /** 请求级成本预算头非法（<=0 或非数字），按 OQ-10 口径忽略预算约束并提示 */
    ROUTE_BUDGET_HEADER_INVALID(8002, "X-Cost-Budget 请求头非法（须为正数），本次已忽略该约束");

    /** 业务错误码：非 200 即失败 */
    private final int code;
    /** 错误描述模板，可含 String.format 占位符（%s/%d） */
    private final String desc;

    BizCodeEnum(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    /**
     * 用参数填充消息模板；无参时原样返回。
     * 参数个数与占位符不匹配会抛异常，编译期无法发现，调用时注意对齐。
     */
    public String format(Object... args) {
        if (args == null || args.length == 0) {
            return desc;
        }
        return String.format(desc, args);
    }
}

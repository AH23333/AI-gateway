-- ==============================================================================
-- AI Gateway 智能路由平台 —— 全量建库脚本（v2，选型书 v1.4 §3 / 1.md §8）
--
-- 使用方式：
--   mysql -uroot -p < sql/init.sql          （或 Navicat/DataGrip 直接执行）
--
-- 执行顺序：init.sql（本文件，建库建表）→ seed.sql（种子数据）
--
-- 设计要点速览：
--   · 字符集统一 utf8mb4（中文与 emoji 安全）；
--   · 业务主键全部自增 BIGINT（选型书 3.8，删除 UidGenerator）；
--   · t_invoke_log_v2 按月 RANGE 分区：90 天保留期到点 DROP PARTITION（D4），
--     分区键 gmt_create 必须包含在主键/唯一键内（MySQL 分区硬性限制）；
--   · t_chat_message 不分区：MySQL 8.0 分区表不支持 FULLTEXT，
--     中文检索走 ngram 解析器（2-gram 内建中文分词）；
--   · 凭证三表（t_refresh_token / t_api_key）只存 SHA-256 哈希，明文不落库（修 P7/P8）。
-- ==============================================================================

CREATE DATABASE IF NOT EXISTS ai_gateway
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_general_ci;

USE ai_gateway;

-- ==============================================================================
-- 1. t_user —— 用户与配额账户（v2：新增预扣水位列，选型书 3.1/5.1）
--
-- 配额不变量（由 QuotaUserMapper 条件更新在写入点保证）：
--   used_* + reserved_* <= quota
-- ==============================================================================
CREATE TABLE IF NOT EXISTS t_user (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键（自增）',
    username          VARCHAR(64)  NOT NULL COMMENT '用户名（唯一）',
    password          VARCHAR(100) NOT NULL COMMENT 'BCrypt 密文',
    email             VARCHAR(128)          DEFAULT NULL COMMENT '邮箱（可空）',
    daily_quota       BIGINT       NOT NULL DEFAULT 1000000 COMMENT '每日配额上限（Token）',
    monthly_quota     BIGINT       NOT NULL DEFAULT 10000000 COMMENT '每月配额上限（Token）',
    used_daily        BIGINT       NOT NULL DEFAULT 0 COMMENT '当日已结算用量',
    used_monthly      BIGINT       NOT NULL DEFAULT 0 COMMENT '当月已结算用量',
    reserved_daily    BIGINT       NOT NULL DEFAULT 0 COMMENT '当日在途预扣水位（v2 新增）',
    reserved_monthly  BIGINT       NOT NULL DEFAULT 0 COMMENT '当月在途预扣水位（v2 新增）',
    role              VARCHAR(16)  NOT NULL DEFAULT 'USER' COMMENT '角色：ADMIN/USER',
    status            TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0=禁用 1=启用',
    gmt_create        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    gmt_modified      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username),
    KEY idx_role_status (role, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户与配额账户';

-- ==============================================================================
-- 2. t_refresh_token —— 刷新令牌（v2：哈希化，选型书 3.1）
--
-- token_hash = SHA-256(明文 JWT)，明文仅签发时返回客户端一次；
-- 每次刷新 rotate：旧记录置 status=0，防重用攻击。
-- ==============================================================================
CREATE TABLE IF NOT EXISTS t_refresh_token (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id     BIGINT      NOT NULL COMMENT '关联用户 ID',
    token_hash  CHAR(64)    NOT NULL COMMENT 'SHA-256 哈希（64 位十六进制）',
    expire_time DATETIME    NOT NULL COMMENT '过期时间（与 JWT exp 一致）',
    status      TINYINT     NOT NULL DEFAULT 1 COMMENT '状态：0=禁用 1=启用',
    gmt_create  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_token_hash (token_hash),
    KEY idx_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '刷新令牌（哈希化存储）';

-- ==============================================================================
-- 3. t_api_key —— API Key（v2：哈希化存储，修 P8）
--
-- key_hash  = SHA-256(完整明文 sk-xxx)；
-- key_prefix = 明文前 8 位（列表辨识用）；
-- 明文创建时一次性返回，之后任何路径都无法再取回。
-- ==============================================================================
CREATE TABLE IF NOT EXISTS t_api_key (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id    BIGINT       NOT NULL COMMENT '关联用户 ID',
    key_hash   CHAR(64)     NOT NULL COMMENT 'SHA-256 哈希（64 位十六进制）',
    key_prefix VARCHAR(16)  NOT NULL COMMENT '明文前 8 位（如 sk-9f2a3b）',
    key_name   VARCHAR(64)           DEFAULT 'default' COMMENT '密钥备注名',
    status     TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0=吊销 1=启用',
    gmt_create DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    gmt_expire DATETIME     NOT NULL COMMENT '过期时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_key_hash (key_hash),
    KEY idx_user (user_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'API Key（哈希化存储）';

-- ==============================================================================
-- 4. t_model —— 模型元数据（v2：t_model_config 更名演进，1.md §8.1）
--
-- model_tier：cheap/strong/universal，复杂度匹配候选池依据（FR-12）；
-- baseline_*：Redis 统计样本 < 20 时的冷启动回退值（1.md §6.2）；
-- fallback_code：主模型不可用时的自动降级目标（基线行为保留）。
-- ==============================================================================
CREATE TABLE IF NOT EXISTS t_model (
    id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    model_code          VARCHAR(64)  NOT NULL COMMENT '模型编码（唯一，如 qwen2.5:3b）',
    model_name          VARCHAR(128) NOT NULL COMMENT '展示名称',
    endpoint            VARCHAR(256) NOT NULL COMMENT '上游推理端点完整 URL（OpenAI 兼容 /chat/completions）',
    api_key             VARCHAR(256)          DEFAULT NULL COMMENT '上游鉴权 Key（Bearer；云端厂商必填，本地无鉴权上游留空）',
    model_tier          VARCHAR(16)  NOT NULL DEFAULT 'universal' COMMENT '模型池等级：cheap/strong/universal',
    fallback_code       VARCHAR(64)           DEFAULT NULL COMMENT '备用模型编码（可空）',
    baseline_quality    INT          NOT NULL DEFAULT 60 COMMENT '冷启动基准质量分 [0,100]',
    baseline_latency_ms INT          NOT NULL DEFAULT 30000 COMMENT '冷启动基准延迟（毫秒）',
    status              TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0=禁用 1=启用',
    gmt_create          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    gmt_modified        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_model_code (model_code),
    KEY idx_tier_status (model_tier, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '模型元数据';

-- ==============================================================================
-- 5. t_model_price —— 模型价格版本表（v2 新增：价格版本化，选型书 3.7）
--
-- 改价 = 插入 version+1、effective_time=当前时间的新记录，老版本保留；
-- 生效价 = effective_time <= now 的最大 version 且 status=1 的记录；
-- 历史账单凭 t_invoke_log_v2.price_version 按旧价复算。
-- ==============================================================================
CREATE TABLE IF NOT EXISTS t_model_price (
    id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    model_code     VARCHAR(64)   NOT NULL COMMENT '模型编码',
    version        INT           NOT NULL COMMENT '价格版本号（同模型内递增）',
    price_input    DECIMAL(10,6) NOT NULL COMMENT '输入单价（元/千Token）',
    price_output   DECIMAL(10,6) NOT NULL COMMENT '输出单价（元/千Token）',
    effective_time DATETIME      NOT NULL COMMENT '生效时间',
    status         TINYINT       NOT NULL DEFAULT 1 COMMENT '状态：0=停用 1=启用',
    gmt_create     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_model_version (model_code, version),
    KEY idx_effective (model_code, effective_time, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '模型价格版本表';

-- ==============================================================================
-- 6. t_routing_config —— 路由配置单行表（id 恒为 1）
--
-- 承载 FR-11（路由总开关）与 FR-05（打分权重热更新）：
-- 管理端改本表 → Caffeine 缓存主动失效 → 下一笔请求生效，无需重启。
-- ==============================================================================
CREATE TABLE IF NOT EXISTS t_routing_config (
    id             BIGINT      NOT NULL COMMENT '单行表主键，恒为 1',
    enabled        TINYINT     NOT NULL DEFAULT 1 COMMENT '路由总开关：1=开启 0=关闭（走 default-model）',
    weight_quality DOUBLE      NOT NULL DEFAULT 0.6 COMMENT '质量分权重 w_q',
    weight_cost    DOUBLE      NOT NULL DEFAULT 0.4 COMMENT '成本权重 w_c（w_q + w_c = 1）',
    gmt_modified   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '路由配置（单行表）';

-- ==============================================================================
-- 7. t_quota_reservation —— 配额预扣流水（v2 新增，选型书 5.1）
--
-- 状态机：0=预留中 → 1=已结算（settle）或 2=已回滚（reconcile 兜底）；
-- settle 与 reconcile 并发竞争时，行锁保证只有一个 UPDATE rows=1（无双结算/回滚）；
-- uk_trace：一次调用一条预扣流水，traceId 即预扣凭证。
-- ==============================================================================
CREATE TABLE IF NOT EXISTS t_quota_reservation (
    id            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    trace_id      VARCHAR(64) NOT NULL COMMENT '预扣凭证（traceId 全局唯一）',
    user_id       BIGINT      NOT NULL COMMENT '用户 ID',
    est_tokens    INT         NOT NULL DEFAULT 0 COMMENT '预扣估算 Token 数',
    status        TINYINT     NOT NULL DEFAULT 0 COMMENT '状态：0=预留中 1=已结算 2=已回滚',
    actual_tokens INT                  DEFAULT NULL COMMENT '实际消耗 Token（结算回填，审计对账）',
    settled_time  DATETIME             DEFAULT NULL COMMENT '结算时间（status→1 回填）',
    rollback_time DATETIME             DEFAULT NULL COMMENT '回滚时间（status→2 回填）',
    gmt_create    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（容忍窗口基准）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_trace (trace_id),
    KEY idx_status_create (status, gmt_create)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '配额预扣流水';

-- ==============================================================================
-- 8. t_invoke_log_v2 —— 调用日志分区窄表（v2 核心表，选型书 3.2 D4）
--
-- · 窄表：无报文大字段（报文采样出库至本地磁盘，payload_ref 记录 key）；
-- · 幂等：uk_trace(trace_id, gmt_create)（分区表唯一键必须含分区键，
--   真实幂等由结算链路 status 抢占保证，选型书 5.2）；
-- · 分区：RANGE (TO_DAYS(gmt_create)) 按月分区，90 天保留期到点
--   ALTER TABLE ... DROP PARTITION（毫秒级，替代 DELETE 全表扫描）；
-- · 分区维护：pmax 兜底分区防写入失败；新增分区 = REORGANIZE pmax，
--   课程量级按季度手工执行一次即可（见 README「数据保留」）。
--
-- 首建分区：当前学期（2026-11）起三个月 + pmax 兜底。
-- ==============================================================================
CREATE TABLE IF NOT EXISTS t_invoke_log_v2 (
    id               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键（自增）',
    trace_id         VARCHAR(64)   NOT NULL COMMENT '幂等键（一次调用的 traceId）',
    user_id          BIGINT        NOT NULL COMMENT '用户 ID',
    model_code       VARCHAR(64)   NOT NULL COMMENT '实际应答模型编码（降级后为备用模型）',
    prompt_tokens    INT           NOT NULL DEFAULT 0 COMMENT '输入 Token 数',
    completion_tokens INT          NOT NULL DEFAULT 0 COMMENT '输出 Token 数',
    total_tokens     INT           NOT NULL DEFAULT 0 COMMENT '总 Token 数',
    cost_amount      DECIMAL(12,6) NOT NULL DEFAULT 0 COMMENT '本次调用费用（元，6 位小数）',
    latency_ms       INT           NOT NULL DEFAULT 0 COMMENT '调用耗时（毫秒）',
    ttft_ms          INT                    DEFAULT NULL COMMENT '首 token 延迟（毫秒，仅流式有值）',
    success          TINYINT       NOT NULL DEFAULT 1 COMMENT '是否成功：1=成功 0=失败/中断',
    fallback_used    TINYINT       NOT NULL DEFAULT 0 COMMENT '是否使用降级模型：1=是 0=否',
    interrupted      TINYINT       NOT NULL DEFAULT 0 COMMENT '是否流式中断/取消：1=是 0=否',
    routing_decision TEXT                   DEFAULT NULL COMMENT '智能路由决策 JSON（指定模型时为 NULL）',
    agent_task_id    VARCHAR(64)            DEFAULT NULL COMMENT 'Agent 任务归因标识（X-Agent-Task-Id，≤64 字符）',
    payload_ref      VARCHAR(128)           DEFAULT NULL COMMENT '报文采样对象 key（inv/{yyyyMM}/{traceId}.json）',
    price_version    INT                    DEFAULT NULL COMMENT '结算时使用的价格版本号（复算历史账单用）',
    gmt_create       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（分区键，DB 默认填充）',
    PRIMARY KEY (id, gmt_create),
    UNIQUE KEY uk_trace (trace_id, gmt_create),
    KEY idx_user_time (user_id, gmt_create),
    KEY idx_model_time (model_code, gmt_create)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '调用日志分区窄表（月分区，90 天保留）'
PARTITION BY RANGE (TO_DAYS(gmt_create)) (
    PARTITION p202611 VALUES LESS THAN (TO_DAYS('2026-12-01')),
    PARTITION p202612 VALUES LESS THAN (TO_DAYS('2027-01-01')),
    PARTITION p202701 VALUES LESS THAN (TO_DAYS('2027-02-01')),
    PARTITION pmax     VALUES LESS THAN MAXVALUE
);

-- ==============================================================================
-- 9. t_chat_message —— 会话消息表（v2 新增，选型书 3.3 D5）
--
-- · trace_id 关联 t_invoke_log_v2 窄表（一次调用可能产生多条消息记录）；
-- · 不分区的取舍：MySQL 8.0 分区表不支持 FULLTEXT，保留 ngram 检索能力优先；
--   保留策略 = 定期 DELETE（课程量级足够）；
-- · ngram 解析器：内建 2-gram 中文分词，"大模型" 可被 "大模" "模型" 命中，
--   无需外置 ES（选型书第二章删除项）。
-- ==============================================================================
CREATE TABLE IF NOT EXISTS t_chat_message (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    trace_id   VARCHAR(64)  NOT NULL COMMENT '关联调用 traceId',
    user_id    BIGINT       NOT NULL COMMENT '用户 ID（隐私边界：检索 SQL 强制过滤）',
    role       VARCHAR(16)  NOT NULL COMMENT '角色：user/assistant/system/tool',
    content    MEDIUMTEXT   NOT NULL COMMENT '消息内容',
    gmt_create DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（DB 默认填充）',
    PRIMARY KEY (id),
    KEY idx_trace (trace_id),
    KEY idx_user_time (user_id, gmt_create),
    FULLTEXT KEY ft_content (content) WITH PARSER ngram
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '会话消息表（ngram 全文检索）';

-- ==============================================================================
-- 10. t_billing_daily —— 每日计费汇总（预聚合，选型书 3.2）
--
-- 写入：settle 事件 → 内存批量层 → INSERT ... ON DUPLICATE KEY UPDATE 累加；
-- 读取：管理端看板趋势直查（明细在分区窄表 90 天 TTL，汇总永久保留）。
-- ==============================================================================
CREATE TABLE IF NOT EXISTS t_billing_daily (
    id                      BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id                 BIGINT        NOT NULL COMMENT '用户 ID',
    stat_date               DATE          NOT NULL COMMENT '统计日期',
    model_code              VARCHAR(64)   NOT NULL COMMENT '模型编码',
    call_count              INT           NOT NULL DEFAULT 0 COMMENT '调用次数',
    total_prompt_tokens     BIGINT        NOT NULL DEFAULT 0 COMMENT '总输入 Token',
    total_completion_tokens BIGINT        NOT NULL DEFAULT 0 COMMENT '总输出 Token',
    total_tokens            BIGINT        NOT NULL DEFAULT 0 COMMENT '总 Token 数',
    total_cost              DECIMAL(12,6) NOT NULL DEFAULT 0 COMMENT '总费用（元）',
    gmt_create              DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    gmt_modified            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_stat (stat_date, user_id, model_code),
    KEY idx_date (stat_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '每日计费汇总（永久保留）';

-- ==============================================================================
-- 11. t_system_config —— 系统设置（v2.4 系统设置中心，1Panel 对标项 1）
--
-- · key-value 结构：DB 覆盖 yml，管理端在线改免重启；
-- · 读取走 common 模块 SystemConfigHolder（进程内缓存，改后即时刷新），
--   auth（JWT 有效期）/ access（限流阈值/窗口）/ 登录页（站点名/公告）统一读；
-- · description 由代码定义维护（启动时回写同步），config_value 由管理端维护。
-- ==============================================================================
CREATE TABLE IF NOT EXISTS t_system_config (
    config_key   VARCHAR(64)    NOT NULL COMMENT '配置键（点分层级，如 site.name）',
    config_value VARCHAR(1000)           DEFAULT NULL COMMENT '配置值（文本存储，读取方按需转型）',
    description  VARCHAR(200)   NOT NULL DEFAULT '' COMMENT '人类可读说明（设置页展示）',
    updated_by   BIGINT                  DEFAULT NULL COMMENT '最近修改人用户 ID',
    gmt_modified DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (config_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '系统设置（DB 覆盖 yml，管理端在线改免重启）';

-- ==============================================================================
-- 12. t_audit_log —— 操作审计日志（v2.5 操作审计，1Panel 对标项 2）
--
-- · AOP 切面（admin 域 AuditAspect）拦截管理端写操作（@Audited）自动落库，
--   四要素：who(user_id/username) / when(created_at) / what(action/uri/params) / 结果(success/error_msg)；
-- · 审计落库失败只记 error 日志，绝不影响业务主流程（审计是旁路）；
-- · params 已脱敏（password/secret/token/apikey/credential → ***）并截断 1000 字符；
-- · 只增不改（append-only），过期清理归 §7.1 项③计划任务（暂未实现，勿手工 DELETE）。
-- ==============================================================================
CREATE TABLE IF NOT EXISTS t_audit_log (
    id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id     BIGINT                 DEFAULT NULL COMMENT '操作人用户 ID（X-User-Id 注入）',
    username    VARCHAR(64)   NOT NULL DEFAULT '' COMMENT '操作人用户名（冗余，检索展示）',
    action      VARCHAR(64)   NOT NULL COMMENT '动作编码（USER_STATUS/SETTING_UPDATE 等）',
    action_desc VARCHAR(100)  NOT NULL DEFAULT '' COMMENT '动作描述（人类可读）',
    http_method VARCHAR(10)   NOT NULL DEFAULT '' COMMENT 'HTTP 方法（PUT/POST/DELETE）',
    uri         VARCHAR(255)  NOT NULL COMMENT '请求 URI（含路径变量，即操作对象）',
    params      VARCHAR(1000)          DEFAULT NULL COMMENT '参数摘要（query+body 脱敏截断）',
    success     TINYINT       NOT NULL DEFAULT 1 COMMENT '结果：1 成功 0 失败',
    error_msg   VARCHAR(500)           DEFAULT NULL COMMENT '失败原因（成功为 NULL）',
    ip          VARCHAR(64)            DEFAULT NULL COMMENT '客户端 IP（反代取 X-Forwarded-For 首跳）',
    cost_ms     BIGINT                 DEFAULT NULL COMMENT '耗时（毫秒）',
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    PRIMARY KEY (id),
    KEY idx_audit_user (user_id),
    KEY idx_audit_created (created_at),
    KEY idx_audit_action (action)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '操作审计日志（管理端写操作 AOP 切面落库）';

-- ==============================================================================
-- 建表完成。接下来执行 seed.sql 灌入种子数据（管理员/模型/价格/路由配置/系统设置）。
-- ==============================================================================

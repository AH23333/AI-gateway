-- ==============================================================================
-- AI Gateway —— v1（微服务基线）→ v2（模块化单体）存量数据迁移脚本
--
-- 适用场景：已有基线版 ai_gateway 库、希望保留用户/凭证/模型数据升级到 v2。
-- 全新部署请直接执行 init.sql + seed.sql（不需要本脚本）。
--
-- ⚠ 执行前务必全库备份（DDL 不可回滚）：
--     mysqldump -uroot -p ai_gateway > backup_before_v2.sql
--
-- 迁移映射（基线表 → v2 表）：
--   t_user          → t_user            （新增预扣水位两列）
--   t_refresh_token → t_refresh_token   （token 明文列 → SHA-256 哈希列）
--   t_api_key       → t_api_key         （api_key 明文列 → key_hash + key_prefix）
--   t_model_config  → t_model + t_model_price（价格剥离为版本表）
--   t_invoke_log    → t_invoke_log_v2   （报文大字段剥离，窄表分区化）
--   t_billing_daily → t_billing_daily   （唯一键调整为 v2 口径）
--   t_token_usage / t_dead_letter / WORKER_NODE → 废弃（数据并入 t_invoke_log_v2 后可删）
--
-- 迁移后旧行为变化（需周知用户）：
--   · RefreshToken / API Key 全部失效（哈希化后客户端需重新登录/重建 Key）——
--     保守做法是先保留明文列一周观察，确认无回滚需求后再执行"清理段"。
-- ==============================================================================

USE ai_gateway;

-- ==============================================================================
-- 第一段：结构演进（幂等：已存在则跳过）
-- ==============================================================================

-- 1. t_user：新增两段式计费预扣水位列（选型书 5.1）
ALTER TABLE t_user
    ADD COLUMN IF NOT EXISTS reserved_daily   BIGINT NOT NULL DEFAULT 0 COMMENT '当日在途预扣水位' AFTER used_monthly,
    ADD COLUMN IF NOT EXISTS reserved_monthly BIGINT NOT NULL DEFAULT 0 COMMENT '当月在途预扣水位' AFTER reserved_daily;

-- 2. t_refresh_token：token 明文列 → token_hash（SHA-256）
--    先加列回填，旧明文列暂保留（观察期后再删，见文末清理段）
ALTER TABLE t_refresh_token
    ADD COLUMN IF NOT EXISTS token_hash CHAR(64) NULL COMMENT 'SHA-256 哈希' AFTER user_id;
UPDATE t_refresh_token SET token_hash = SHA2(token, 256) WHERE token_hash IS NULL;
ALTER TABLE t_refresh_token
    DROP INDEX IF EXISTS idx_token,
    ADD UNIQUE INDEX IF NOT EXISTS uk_token_hash (token_hash);

-- 3. t_api_key：api_key 明文列 → key_hash + key_prefix
ALTER TABLE t_api_key
    ADD COLUMN IF NOT EXISTS key_hash   CHAR(64)    NULL COMMENT 'SHA-256 哈希' AFTER user_id,
    ADD COLUMN IF NOT EXISTS key_prefix VARCHAR(16) NULL COMMENT '明文前 8 位'   AFTER key_hash;
UPDATE t_api_key SET key_hash = SHA2(api_key, 256), key_prefix = LEFT(api_key, 8) WHERE key_hash IS NULL;
ALTER TABLE t_api_key
    DROP INDEX IF EXISTS uk_api_key,
    ADD UNIQUE INDEX IF NOT EXISTS uk_key_hash (key_hash);

-- 4. t_model / t_model_price：模型配置拆分（价格版本化，选型书 3.7）
--    注意：此两表是"新建"，从 t_model_config 搬运数据（第二段），随后旧表保留至清理段
CREATE TABLE IF NOT EXISTS t_model (
    id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    model_code          VARCHAR(64)  NOT NULL COMMENT '模型编码',
    model_name          VARCHAR(128) NOT NULL COMMENT '展示名称',
    endpoint            VARCHAR(256) NOT NULL COMMENT '上游推理端点完整 URL（OpenAI 兼容 /chat/completions）',
    api_key             VARCHAR(256)          DEFAULT NULL COMMENT '上游鉴权 Key（Bearer；云端厂商必填，本地无鉴权上游留空）',
    model_tier          VARCHAR(16)  NOT NULL DEFAULT 'universal' COMMENT 'cheap/strong/universal',
    fallback_code       VARCHAR(64)           DEFAULT NULL COMMENT '备用模型编码',
    baseline_quality    INT          NOT NULL DEFAULT 60 COMMENT '冷启动基准质量分',
    baseline_latency_ms INT          NOT NULL DEFAULT 30000 COMMENT '冷启动基准延迟（毫秒）',
    status              TINYINT      NOT NULL DEFAULT 1 COMMENT '0=禁用 1=启用',
    gmt_create          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    gmt_modified        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_model_code (model_code),
    KEY idx_tier_status (model_tier, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '模型元数据';

CREATE TABLE IF NOT EXISTS t_model_price (
    id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    model_code     VARCHAR(64)   NOT NULL COMMENT '模型编码',
    version        INT           NOT NULL COMMENT '价格版本号',
    price_input    DECIMAL(10,6) NOT NULL COMMENT '输入单价（元/千Token）',
    price_output   DECIMAL(10,6) NOT NULL COMMENT '输出单价（元/千Token）',
    effective_time DATETIME      NOT NULL COMMENT '生效时间',
    status         TINYINT       NOT NULL DEFAULT 1 COMMENT '0=停用 1=启用',
    gmt_create     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_model_version (model_code, version),
    KEY idx_effective (model_code, effective_time, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '模型价格版本表';

-- 5. t_routing_config：路由配置单行表（v2 新增）
CREATE TABLE IF NOT EXISTS t_routing_config (
    id             BIGINT   NOT NULL COMMENT '单行表主键，恒为 1',
    enabled        TINYINT  NOT NULL DEFAULT 1 COMMENT '路由总开关',
    weight_quality DOUBLE   NOT NULL DEFAULT 0.6 COMMENT '质量分权重 w_q',
    weight_cost    DOUBLE   NOT NULL DEFAULT 0.4 COMMENT '成本权重 w_c',
    gmt_modified   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '路由配置（单行表）';

-- 6. t_quota_reservation：预扣流水表（v2 新增，结构同 init.sql）
CREATE TABLE IF NOT EXISTS t_quota_reservation (
    id            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    trace_id      VARCHAR(64) NOT NULL COMMENT '预扣凭证（traceId）',
    user_id       BIGINT      NOT NULL COMMENT '用户 ID',
    est_tokens    INT         NOT NULL DEFAULT 0 COMMENT '预扣估算 Token 数',
    status        TINYINT     NOT NULL DEFAULT 0 COMMENT '0=预留中 1=已结算 2=已回滚',
    actual_tokens INT                  DEFAULT NULL COMMENT '实际消耗（结算回填）',
    settled_time  DATETIME             DEFAULT NULL COMMENT '结算时间',
    rollback_time DATETIME             DEFAULT NULL COMMENT '回滚时间',
    gmt_create    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_trace (trace_id),
    KEY idx_status_create (status, gmt_create)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '配额预扣流水';

-- 7. t_invoke_log_v2：分区窄表（v2 新增，结构同 init.sql；首建分区按当前学期调整）
CREATE TABLE IF NOT EXISTS t_invoke_log_v2 (
    id               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    trace_id         VARCHAR(64)   NOT NULL COMMENT '幂等键',
    user_id          BIGINT        NOT NULL COMMENT '用户 ID',
    model_code       VARCHAR(64)   NOT NULL COMMENT '实际应答模型编码',
    prompt_tokens    INT           NOT NULL DEFAULT 0 COMMENT '输入 Token',
    completion_tokens INT          NOT NULL DEFAULT 0 COMMENT '输出 Token',
    total_tokens     INT           NOT NULL DEFAULT 0 COMMENT '总 Token',
    cost_amount      DECIMAL(12,6) NOT NULL DEFAULT 0 COMMENT '费用（元）',
    latency_ms       INT           NOT NULL DEFAULT 0 COMMENT '耗时（毫秒）',
    ttft_ms          INT                    DEFAULT NULL COMMENT '首 token 延迟（毫秒）',
    success          TINYINT       NOT NULL DEFAULT 1 COMMENT '1=成功 0=失败',
    fallback_used    TINYINT       NOT NULL DEFAULT 0 COMMENT '1=使用降级',
    interrupted      TINYINT       NOT NULL DEFAULT 0 COMMENT '1=流式中断/取消',
    routing_decision TEXT                   DEFAULT NULL COMMENT '智能路由决策 JSON',
    agent_task_id    VARCHAR(64)            DEFAULT NULL COMMENT 'Agent 任务归因标识',
    payload_ref      VARCHAR(128)           DEFAULT NULL COMMENT '报文采样对象 key',
    price_version    INT                    DEFAULT NULL COMMENT '价格版本号',
    gmt_create       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（分区键）',
    PRIMARY KEY (id, gmt_create),
    UNIQUE KEY uk_trace (trace_id, gmt_create),
    KEY idx_user_time (user_id, gmt_create),
    KEY idx_model_time (model_code, gmt_create)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '调用日志分区窄表'
PARTITION BY RANGE (TO_DAYS(gmt_create)) (
    PARTITION p202611 VALUES LESS THAN (TO_DAYS('2026-12-01')),
    PARTITION p202612 VALUES LESS THAN (TO_DAYS('2027-01-01')),
    PARTITION p202701 VALUES LESS THAN (TO_DAYS('2027-02-01')),
    PARTITION pmax     VALUES LESS THAN MAXVALUE
);

-- 8. t_chat_message：会话消息表（v2 新增，ngram 全文检索，结构同 init.sql）
--    注意：库级需已开启 ngram（MySQL 8.0 内建，无需额外安装）
CREATE TABLE IF NOT EXISTS t_chat_message (
    id         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    trace_id   VARCHAR(64) NOT NULL COMMENT '关联调用 traceId',
    user_id    BIGINT      NOT NULL COMMENT '用户 ID',
    role       VARCHAR(16) NOT NULL COMMENT 'user/assistant/system/tool',
    content    MEDIUMTEXT  NOT NULL COMMENT '消息内容',
    gmt_create DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_trace (trace_id),
    KEY idx_user_time (user_id, gmt_create),
    FULLTEXT KEY ft_content (content) WITH PARSER ngram
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '会话消息表（ngram 全文检索）';

-- ==============================================================================
-- 第二段：数据搬运（幂等：目标行存在则跳过）
-- ==============================================================================

-- 9. 模型元数据：t_model_config → t_model（价格除外）
--    model_tier 缺省 universal（管理端按池策略再调整）
INSERT INTO t_model
    (model_code, model_name, endpoint, model_tier, fallback_code,
     baseline_quality, baseline_latency_ms, status, gmt_create)
SELECT c.model_code, c.model_name, c.endpoint, 'universal', c.fallback_code,
       60, 30000, c.status, COALESCE(c.gmt_create, NOW())
FROM t_model_config c
WHERE NOT EXISTS (SELECT 1 FROM t_model m WHERE m.model_code = c.model_code);

-- 10. 价格初始版本 v1：t_model_config.price_* → t_model_price
INSERT INTO t_model_price
    (model_code, version, price_input, price_output, effective_time, status)
SELECT c.model_code, 1, c.price_input, c.price_output, COALESCE(c.gmt_create, NOW()), 1
FROM t_model_config c
WHERE NOT EXISTS (SELECT 1 FROM t_model_price p
                  WHERE p.model_code = c.model_code AND p.version = 1);

-- 11. 调用日志：t_invoke_log → t_invoke_log_v2（报文大字段不搬，窄表化）
--     跳过 trace_id 为 NULL 的脏数据；id 不保留（v2 自增重排）
INSERT INTO t_invoke_log_v2
    (trace_id, user_id, model_code, prompt_tokens, completion_tokens, total_tokens,
     cost_amount, latency_ms, success, fallback_used, gmt_create)
SELECT l.trace_id, l.user_id, COALESCE(l.model_code, 'unknown'),
       COALESCE(l.prompt_tokens, 0), COALESCE(l.completion_tokens, 0),
       COALESCE(l.total_tokens, 0), COALESCE(l.cost_amount, 0),
       COALESCE(l.latency, 0), COALESCE(l.success, 1), COALESCE(l.fallback_used, 0),
       COALESCE(l.gmt_create, NOW())
FROM t_invoke_log l
WHERE l.trace_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM t_invoke_log_v2 v WHERE v.trace_id = l.trace_id);

-- 12. 计费汇总唯一键口径调整（v2：uk_stat(stat_date, user_id, model_code)）
--     仅在旧键存在且新键不存在时执行；同批次兼容旧键（同为三列唯一约束，upsert 语义不变）
-- ALTER TABLE t_billing_daily
--     DROP INDEX uk_user_date_model,
--     ADD UNIQUE KEY uk_stat (stat_date, user_id, model_code);
-- ↑ 如需严格统一键名，备份确认后放开本段注释执行。

-- 13. 路由配置种子行（id=1 必须存在，RoutingAdminService 依赖）
INSERT INTO t_routing_config (id, enabled, weight_quality, weight_cost)
SELECT 1, 1, 0.6, 0.4
WHERE NOT EXISTS (SELECT 1 FROM t_routing_config WHERE id = 1);

-- ==============================================================================
-- 第三段：清理（可选，观察一周确认无回滚需求后执行）
-- ==============================================================================
-- 14. 旧表下线（数据已搬运；t_token_usage/t_dead_letter 随 MQ 链路一并废弃）
-- DROP TABLE IF EXISTS t_invoke_log;
-- DROP TABLE IF EXISTS t_model_config;
-- DROP TABLE IF EXISTS t_token_usage;
-- DROP TABLE IF EXISTS t_dead_letter;
-- DROP TABLE IF EXISTS WORKER_NODE;
--
-- 15. 凭证明文列下线（哈希列已回填并验证登录正常后）
-- ALTER TABLE t_refresh_token DROP COLUMN token;
-- ALTER TABLE t_api_key DROP COLUMN api_key;

-- ==============================================================================
-- 迁移完成核对清单：
--   □ SELECT COUNT(*) FROM t_model;          -- 与 t_model_config 行数一致
--   □ SELECT COUNT(*) FROM t_model_price;    -- 每模型至少 1 行（version=1）
--   □ SELECT COUNT(*) FROM t_invoke_log_v2;  -- 与 t_invoke_log 有效行数一致
--   □ SELECT * FROM t_routing_config;        -- id=1 单行
--   □ 登录 admin 成功 → 新建 API Key → /v1/chat/completions 直连验证
-- ==============================================================================

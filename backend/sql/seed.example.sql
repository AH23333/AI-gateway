-- ==============================================================================
-- AI Gateway 智能路由平台 —— 种子数据模板（v2）
--
-- 使用方式：
--   1. 复制本文件为 sql/seed.sql（该文件已被 .gitignore 忽略，不会入库）
--      Windows:  copy sql\seed.example.sql sql\seed.sql
--   2. 把下面两处 REPLACE_WITH_YOUR_ZHIPU_API_KEY 换成自己的智谱 API Key
--      （https://open.bigmodel.cn 免费申请）
--   3. 在 init.sql 之后执行：
--      mysql -uroot -p ai_gateway < sql/seed.sql
--
-- 内容：
--   1. 管理员账号 admin / admin123（BCrypt 密文）；
--   2. 演示用户 demo / demo123；
--   3. 双模型（glm-4-flash 便宜池 + glm-4.5-flash 强模型池，NFR-07 强弱对比）；
--      上游为智谱 GLM 的 OpenAI 兼容端点；
--   4. 各模型初始价格版本 v1（免费模型价格为虚拟值，
--      用于演示"复杂度匹配 + 成本打分"的差异化路由）；
--   5. 路由配置单行（开关开，w_q=0.6 / w_c=0.4，1.md §6.3 默认口径）。
--
-- 注意：seed 语句全部带幂等保护（INSERT ... ON DUPLICATE KEY / IGNORE），
--       重复执行不会报错、不会覆盖手工修改过的行。
-- ==============================================================================

USE ai_gateway;

-- ------------------------------------------------------------------------------
-- 1. 用户（密码哈希由 BCrypt 生成，登录后请立即改密）
--    admin/admin123 —— 管理员
--    demo/demo123   —— 演示用户（配额默认值）
-- ------------------------------------------------------------------------------
INSERT INTO t_user
    (username, password, email, role, status)
VALUES
    ('admin', '$2a$10$NlEz/qln03y.qEai18UTCe9HZblbfDxd6PvGo8v3kyVpLmfOPqPdC', 'admin@example.com', 'ADMIN', 1),
    ('demo',  '$2a$10$aXtlIWHuXRAhE5ZJ5T9m7Ox/9RjOXhsoPFeS4SKURvWu0RpLJEs.q', 'demo@example.com',  'USER',  1)
ON DUPLICATE KEY UPDATE username = username;

-- ------------------------------------------------------------------------------
-- 2. 模型元数据（NFR-07：强弱对比双模型，互为降级）
--    glm-4-flash   —— 便宜池（简单任务）：非思考模型，延迟低、基准质量低
--    glm-4.5-flash —— 强模型池（复杂任务）：思考模型（reasoning_content），质量高
--    基准值为冷启动回退（Redis 统计样本 < 20 时使用，1.md §6.2）
--    ⚠ 两处 api_key 必须替换为自己的智谱开放平台 Key
-- ------------------------------------------------------------------------------
INSERT INTO t_model
    (model_code, model_name, endpoint, api_key, model_tier, fallback_code,
     baseline_quality, baseline_latency_ms, status)
VALUES
    ('glm-4-flash', 'GLM-4-Flash（便宜池）',
     'https://open.bigmodel.cn/api/paas/v4/chat/completions',
     'REPLACE_WITH_YOUR_ZHIPU_API_KEY', 'cheap',
     'glm-4.5-flash', 60, 3000, 1),
    ('glm-4.5-flash', 'GLM-4.5-Flash（强模型池）',
     'https://open.bigmodel.cn/api/paas/v4/chat/completions',
     'REPLACE_WITH_YOUR_ZHIPU_API_KEY', 'strong',
     'glm-4-flash', 75, 8000, 1)
ON DUPLICATE KEY UPDATE model_code = model_code;

-- ------------------------------------------------------------------------------
-- 3. 初始价格版本 v1（元/千Token，虚拟值：强模型约为便宜模型的 2.5 倍）
--    改价走管理端"新增价格版本"（version+1 立即生效），不 UPDATE 老版本。
-- ------------------------------------------------------------------------------
INSERT INTO t_model_price
    (model_code, version, price_input, price_output, effective_time, status)
VALUES
    ('glm-4-flash', 1, 0.002000, 0.004000, NOW(), 1),
    ('glm-4.5-flash', 1, 0.005000, 0.010000, NOW(), 1)
ON DUPLICATE KEY UPDATE model_code = model_code;

-- ------------------------------------------------------------------------------
-- 4. 路由配置单行（id=1；RoutingAdminService.requireConfigRow 依赖此行存在）
-- ------------------------------------------------------------------------------
INSERT INTO t_routing_config
    (id, enabled, weight_quality, weight_cost)
VALUES
    (1, 1, 0.6, 0.4)
ON DUPLICATE KEY UPDATE id = id;

-- ------------------------------------------------------------------------------
-- 5. 系统设置默认行（v2.4 系统设置中心；数值与 yml 同口径，
--    SystemConfigService 启动时也会自检补种缺失行，此处为显式可见）
-- ------------------------------------------------------------------------------
INSERT INTO t_system_config
    (config_key, config_value, description)
VALUES
    ('site.name', 'AI Gateway', '站点名称（登录页与浏览器标题）'),
    ('site.announcement', '', '站点公告（登录页横幅，留空不展示）'),
    ('jwt.access-expire-minutes', '30', 'AccessToken 有效期（分钟，5-1440，改后下次登录生效）'),
    ('access.rate-limit', '100', '限流阈值（每用户/IP/路径/窗口，1-100000）'),
    ('access.rate-window-seconds', '60', '限流窗口（秒，10-3600）')
ON DUPLICATE KEY UPDATE config_key = config_key;

-- ==============================================================================
-- 种子完成。默认账号：
--   管理员 admin / admin123（登录后请立即改密）
--   演示用户 demo  / demo123
-- ==============================================================================

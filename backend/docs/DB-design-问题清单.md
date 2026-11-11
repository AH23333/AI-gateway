# DB-design 项目问题清单（独立评审）

| | |
|---|---|
| 评审日期 | 2026-10-26 |
| 评审对象 | `D:\Java\DB-design`（AI Gateway Platform · LLM API 网关与计费系统），不含 Web-design 需求 |
| 评审方式 | 对照课程要求（`DB-design/设计要求.md`）、老师报告示例（`docs/报告格式示例.md`），逐项核对 init.sql、BillingService、README、系统设计报告源文件 |
| 结论概览 | 13 项问题：数据库本体 3 项（课程评分命门）、一致性 3 项、安全 2 项、工程文档 4 项、其他 1 项；另澄清 4 项核实后无问题 |

---

## 一、数据库本体问题（对照课程要求与老师示例，评分命门）

### P1. 零外键、零 CHECK、零视图、零触发器、零存储过程

- **证据**：对 `DB-design` 全部 `*.sql` 检索 `CREATE VIEW|TRIGGER|PROCEDURE|FUNCTION|FOREIGN KEY` 无任何匹配；init.sql 仅建 9 张表 + 1 条 INSERT。
- **对照**：
  - 课程目标第 3 条明确要求"熟练运用数据库核心操作：数据库连接、SQL语句、**完整性约束、索引、视图、触发器、存储过程**等"；
  - 老师报告示例（火车票系统）DDL 含真实外键 `fk_tc_trainticket ... ON DELETE RESTRICT`、CHECK 约束 `chk_tc_range/chk_tc_rows/chk_tc_seats`、`fk_` 命名规范，并有"6.3.4 检查约束与生成列"专节；
  - 本项目全部约束靠应用层，数据库层仅主键/唯一键/普通索引。
- **叠加问题**：报告 4.5 声称"检查约束：`gmt_expire > gmt_create`、`price_input >= 0`、`quota >= 0` 等**由应用层校验并在 DDL 中补充**"——init.sql 实际一条 CHECK 都没有，报告承诺未兑现。
- **影响**：第一次汇报"数据库设计 15 分"、报告"概念+逻辑设计 20 分"直接受影响；答辩对照 DDL 会被当场看穿。
- **修复建议**（成本低）：补 CHECK（`price_input >= 0`、`status IN (0,1)`、`gmt_expire > gmt_create` 等）；补 1~2 个有业务语义的视图（用户月度用量视图、模型日汇总视图）；补 1 个存储过程或触发器（日汇总一致性校验 / 配额重置）。

### P2. 种子数据仅 1 条，无用户引导脚本

- **证据**：init.sql 唯一一条 INSERT 是 `t_model_config` 的 qwen2.5:3b（第 155 行）；无任何用户、业务数据种子。
- **对照**：课设硬性要求"**系统实体测试数据≥30 条，关联业务数据≥200 条**"。
- **连带问题**：
  1. README 冒烟第①步登录 `testuser99`，在全新库上必然失败（该账号不存在）；
  2. 首个 ADMIN 无引导：注册用户默认 USER 角色，需手工改库提权；
  3. "至少演示 3 项数据统计功能"依赖历史数据，现场现造有风险。
- **修复建议**：新增 `seed.sql`：ADMIN 引导账号 + 测试用户 + ≥2 个模型 + 30/200 量级的用量/调用日志历史数据。

### P3. 系统设计报告与实现不一致（答辩风险）

| 报告说法（章节） | 实际实现 | 出处 |
|---|---|---|
| 金额 `DECIMAL(10,4)`（4.6） | `price_input/output DECIMAL(10,6)`、`cost_amount DECIMAL(10,6)`、`total_cost DECIMAL(12,6)` | init.sql |
| 业务日 `VARCHAR(10)`（4.6） | `t_billing_daily.stat_date` 为 `DATE` | init.sql |
| "API Key 仅有存储前缀"（4.7） | `t_api_key.api_key` 存全量明文（唯一索引等值校验决定了必须全量） | init.sql 第 38 行 |
| "数据库账号最小权限：仅对 ai_gateway.* 读写"（4.7） | README 快速开始用 `root / Root@233!` 直连 | README |
| "检查约束……并在 DDL 中补充"（4.5） | DDL 中无任何 CHECK | init.sql |

- **修复建议**：要么改代码对齐报告，要么改报告对齐代码；二选一，答辩前必须闭环。

---

## 二、一致性与正确性问题（有代码实据）

### P4. 计费消费端无幂等，MQ 重投会双倍扣费

- **证据**：`billing/.../service/BillingService.java#processChatRecord`——直接 `tokenUsageMapper.insert` + `invokeLogMapper.insert` + `deductQuota`，**无任何"先按 traceId 查是否已处理"的检查**；`usage.setMessageId(msg.getTraceId())` 写入了 messageId 却未用于去重。
- **表结构佐证**：`t_token_usage`/`t_invoke_log` 仅有 `KEY idx_trace_id` 普通索引，**无 trace_id/message_id 唯一键**。
- **后果**：RabbitMQ 消息重投（消费超时未 ack、连接断开、redelivery）→ 明细翻倍、**配额双扣**、`t_billing_daily` 双计。
- **澄清**：README 宣称的"幂等 upsert"只覆盖日汇总表对**同一行键**的并发写（`uk_user_date_model` + ON DUPLICATE KEY UPDATE），**不解决重复消息**；报告 4.5"唯一约束保证幂等"的说法对明细表不成立。
- **修复建议**：两明细表加 `uk_trace_id`（或 trace_id+model_code），消费端先查后插 / 捕获重复键跳过；消息体已有唯一 traceId，改造成本低。

### P5. 已服务成功的请求可能"无账"

- **证据**：`BillingService` 第 109-112 行——配额扣减 `rows == 0` 时抛 `QUOTA_EXCEEDED`，`@Transactional` 回滚**整个事务**，两份明细一并消失。
- **后果**：请求已被服务（用户拿到了模型回复），但库里零记录 → 对账缺口，与"精确计费"的产品宣称矛盾；消息重试 3 次每次都超限，最终进死信，全程无账。
- **修复建议**：失败调用以 `success=0` 落库（单独的失败流水），或扣减失败时走"欠费记录 + 告警"而非整单回滚；至少在报告/答辩中明确该取舍。

### P6. 调用日志无限增长，无归档清理机制

- **证据**：`t_invoke_log.request_json TEXT` / `response_json MEDIUMTEXT` 全量落库；报告 6.5 有容量估算但无任何归档/分区/清理落地手段。
- **修复建议**：低优先。加定时清理（保留 N 天）或按月分区即可，答辩可说明。

---

## 三、安全与提交物问题

### P7. 敏感信息随仓库提交

- **证据**：
  - `auth/src/main/resources/application.yml:19-20` 明文 `jwt.secret`；
  - README 含 MySQL root 密码、RabbitMQ 凭据、Nacos/虚拟机 IP（192.168.135.x）。
- **修复建议**：配置占位符化（`${JWT_SECRET:...}` 默认值仅用于本地演示），提交文档用占位符；与报告 4.7 的安全设计宣称保持一致。

### P8. API Key 与 Refresh Token 明文存库

- **证据**：`t_api_key.api_key` 全量明文 + 唯一索引；`t_refresh_token.token VARCHAR(500)` 明文落库（仅前缀索引 100）。
- **后果**：库泄露即全部密钥泄露，与前端"脱敏展示"形成"假脱敏"。
- **修复建议**：存 SHA-256 哈希 + 明文前缀（展示用），校验按哈希等值查询；Refresh Token 同理或直接复用 JWT 黑名单机制。答辩加分项。

---

## 四、工程与文档问题

### P9. README 快速开始命令无法启动全部服务

- **证据**：`README.md:194`：`mvn spring-boot:run -pl gateway -pl auth -pl router -pl billing -pl admin -pl es-sync`。
- **问题**：`spring-boot:run` 会**阻塞在 reactor 的第一个模块（gateway）**上，后续 5 个服务永远不会启动。
- **修复建议**：改为"分终端逐个启动"说明，或提供 dev 启动脚本。

### P10. 全工程零自动化测试

- **证据**：`**/src/test/**/*.java` 无匹配。
- **对照**：演示评分"系统健壮性 8 分"目前纯靠现场手点。
- **修复建议**：至少为计费扣减（超限拒绝、日/月双扣）、降级链、限流三类核心逻辑补关键单测。

### P11. 环境强耦合，换机复现要改多处

- **证据**：init.sql 种子 endpoint 硬编码 `http://192.168.135.1:11434`；README/文档硬编码 `192.168.135.131`（Nacos）等虚拟机 IP。
- **对照**：课程要求"提交全部代码+配置即可复现"。
- **修复建议**：endpoint 改为配置项 + 启动说明；文档 IP 汇总成"环境变量表"。

### P12. 配额重置依赖 admin 单实例定时任务

- **证据**：README"运营后台……日/月配额定时重置"；`used_daily/used_monthly` 靠定时任务清零。
- **风险**：任务漏跑/服务当日未启动，用户次日配额不释放（跨天判断无补偿）。
- **修复建议**：扣减路径做"惰性重置"兜底（发现 used_daily 的最后统计日非今日则先清零），或答辩说明单机课设边界。轻量问题。

---

## 五、核实后确认无问题的项（避免误伤）

| 项 | 结论 |
|---|---|
| README 冒烟 curl 命令 | JSON/请求头格式正确（此前阅读输出中的乱码系工具脱敏所致，已用字面 grep 澄清） |
| 月配额扣减 | `deductQuota` 同一条条件 UPDATE 同时扣 `used_daily` 与 `used_monthly`（`UserMapper.java:17-21`），实现正确 |
| 金额类型 | DECIMAL 存金额、BIGINT 存 Token，类型选型正确 |
| 字符集 | 库/表统一 utf8mb4 + utf8mb4_unicode_ci |
| 防超扣 | `WHERE used + tokens <= quota` 条件更新，SQL 层防超发，等效乐观约束 |
| 报告结构 | 一~九章与老师示例模板完全对齐（含数据字典、ER 图、物理设计、测试章节） |

---

## 六、修复优先级建议

| 优先级 | 事项 | 对应问题 | 成本 |
|---|---|---|---|
| 1 | `seed.sql`（ADMIN 引导 + 测试用户 + ≥2 模型 + 30/200 量级历史数据） | P2 | 低 |
| 2 | 明细表加 `uk_trace_id` + 消费端先查后插 | P4 | 低 |
| 3 | 补 CHECK / 视图 / 存储过程各 1~2 个；报告 4.5~4.7 与实际 DDL 对齐 | P1、P3 | 低 |
| 4 | README 启动命令修正 + 凭据占位符化 | P9、P7 | 低 |
| 5 | API Key / Refresh Token 哈希化存储 | P8 | 中 |
| 6 | 失败调用落账（success=0 流水） | P5 | 中 |
| 7 | 核心链路关键单测 | P10 | 中 |
| 8 | 日志归档、惰性配额重置、endpoint 配置化 | P6、P12、P11 | 低~中 |

---

## 附：证据索引

| 证据 | 位置 |
|---|---|
| 课程硬性要求（数据量、周期、提交） | `DB-design/设计要求.md` 四、五、六节 |
| 老师示例含外键/CHECK/生成列 | `DB-design/docs/报告格式示例.md:560-563、677` |
| 9 张表 DDL 与唯一 INSERT | `DB-design/ai-gateway-platform/sql/init.sql` |
| 计费无幂等 + 超限回滚 | `DB-design/ai-gateway-platform/billing/src/main/java/com/ai/billing/service/BillingService.java:63-112` |
| 日/月双扣原子 SQL | `.../billing/.../mapper/UserMapper.java:17-21` |
| mvn 多模块启动命令 | `DB-design/README.md:194` |
| JWT 明文密钥 | `.../auth/src/main/resources/application.yml:19-20` |
| 报告与实现出入各条 | `DB-design/docs/系统设计报告.md` 4.5~4.7 节 |

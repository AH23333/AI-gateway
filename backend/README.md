# AI Gateway 智能路由平台（模块化单体后端 v2）

> 课程设计项目：将六服务微服务基线重构为 **1 个 JVM 的模块化单体**，
> 在只依赖 **MySQL + Redis + 云端 OpenAI 兼容模型服务**的前提下，实现多模型智能路由、
> 两段式配额计费、OpenAI 协议兼容网关与流式对话全链路。

---

## 1. 项目定位

- **对内**（Web 前端）：登录/配额/Key 管理/看板/聊天记录检索，`Result` 统一契约；
- **对外**（Agent 生态）：`/v1/chat/completions` 完整 OpenAI 协议兼容，
  LangChain / LangGraph / OpenAI SDK 把 `base_url` 指向本网关即可直连（FR-14）；
- **路由**：simple/complex/balanced 复杂度分档 → 质量/成本加权打分 → 失败自动降级。

## 2. 技术栈

| 层 | 选型 | 说明 |
|---|---|---|
| 运行时 | JDK 21（虚拟线程） | IO 密集网关吞吐红利 |
| 框架 | Spring Boot 3.2.5 | Web + Data Redis + Validation |
| 持久层 | MyBatis-Plus 3.5.5（boot3 线） | 条件更新 SQL 手写防超扣 |
| 缓存 | Redis 7/8 + Caffeine | 统计窗口/黑名单/限流 + 进程内元数据缓存 |
| 认证 | jjwt 0.12 + BCrypt + SHA-256 | 双 Token + API Key 哈希化 |
| 文档 | springdoc-openapi（swagger-ui） | 替代 knife4j 2.x |
| 模型 | 智谱 GLM（glm-4-flash / glm-4.5-flash） | OpenAI 兼容云端推理，免费模型 |

## 3. 模块结构（依赖单向无环）

```
ai-gateway-backend
├── ai-gateway-common    共享内核：常量/DTO/实体/异常/工具/MP配置
├── ai-gateway-auth      凭证域：注册登录/双Token/API Key（哈希化）
├── ai-gateway-billing   计费域：预扣-结算-对账/定时重置/批量日志
├── ai-gateway-router    编排域：OpenAI协议/兼容上游客户端/智能路由/流式
├── ai-gateway-admin     管理域：用户/模型价格/路由配置/看板/记录检索
├── ai-gateway-access    入口防护：TraceId → 认证 → 限流（Servlet Filter）
└── ai-gateway-app       启动模块：组装 + application.yml + fat jar
```

请求链路：

```
client → access(TraceIdFilter→JwtAuthFilter→RateLimitFilter)
       → admin/router 控制器 → billing(预扣→结算) / router(路由→上游推理)
       → MySQL / Redis / 本地报文采样
```

## 4. 快速启动（Windows）

### 4.1 前置条件

| 依赖 | 版本/位置 | 检查 |
|---|---|---|
| JDK 21 | `D:\Java\jdk\jdk-21.0.6`（或设 `JAVA_HOME`） | `java -version` |
| MySQL 8 | 本机服务（3306） | `net start MySQL80` |
| Redis | 本机 6379（脚本可自动解压启动） | `redis-cli ping` |
| 智谱 API Key | [open.bigmodel.cn](https://open.bigmodel.cn) 免费申请 | 复制 `sql\seed.example.sql` 为 `sql\seed.sql` 后填入 `api_key` 列 |

> **凭据约定**：仓库只保留脱敏模板 `sql/seed.example.sql`；含真实 Key 的 `sql/seed.sql`、`dump.rdb` 均已由 `.gitignore` 排除。MySQL / JWT / 上游 Key 一律用环境变量或本地文件覆盖，勿把真实凭据提交进仓库。

### 4.2 首次部署（三步）

```bat
cd /d D:\Java\Web-design\backend

:: ⓪ 首次克隆才需要：由模板生成含自己 Key 的种子脚本
copy sql\seed.example.sql sql\seed.sql
::    编辑 sql\seed.sql，把 REPLACE_WITH_YOUR_ZHIPU_API_KEY 换成自己的 Key

:: ① 建库建表 + 种子数据（或用 Navicat 执行 sql\init.sql 与 sql\seed.sql）
mysql -uroot -p < sql\init.sql
mysql -uroot -p < sql\seed.sql

:: ①+ 演示数据（可选，答辩演示用：128 条调用日志、95 条含路由决策、56 条中文会话消息，
::    支撑看板趋势/决策回放/历史检索；可重复执行，详见脚本头部说明）
mysql -uroot -p --default-character-set=utf8 < sql\demo-data.sql

:: ② 构建
mvn clean package -DskipTests

:: ③ 启动（自动探测 MySQL/Redis，缺 jar 自动构建；模型上游为云端服务无需本地进程）
scripts\start-all-windows.bat
```

一键版（建库需要输入 MySQL 密码）：

```bat
scripts\start-all-windows.bat -init
```

### 4.3 日常启停

```bat
:: 全栈一键（backend 目录，推荐：后端+前端+监控编排，详见 docs\交接文档.md §4）
cd /d D:\Java\Web-design\backend
start-all.bat                      :: 全栈启动（-nobrowser 不弹浏览器）
stop-all.bat                       :: 全栈停止（前端/网关/监控/Redis；MySQL 常驻）

:: 仅后端
scripts\start-all-windows.bat      :: 启动
scripts\stop-all-windows.bat       :: 停止（Redis/MySQL 常驻不动）
```

启动成功后：

| 入口 | 地址 |
|---|---|
| 对话接口（OpenAI 协议） | `http://localhost:10011/v1/chat/completions` |
| 接口文档 | `http://localhost:10011/swagger-ui.html` |
| 管理看板 | `http://localhost:10011/admin/dashboard`（ADMIN） |
| 健康检查 | `http://localhost:10011/actuator/health` |

默认账号：`admin/admin123`（管理员）、`demo/demo123`（演示用户），登录后请改密。

### 4.4 存量数据迁移（基线 v1 → v2）

已有基线版数据库时执行 `sql\upgrade-v2.sql`（先备份！），
全新部署**不要**执行该脚本。

## 5. 配置项（环境变量）

所有配置在 `ai-gateway-app/src/main/resources/application.yml`，占位符可被环境变量覆盖：

| 变量 | 默认 | 说明 |
|---|---|---|
| `DB_HOST/DB_PORT/DB_NAME` | localhost/3306/ai_gateway | MySQL |
| `DB_USER/DB_PASSWORD` | root / 本机课设演示值 | 凭据一律用环境变量覆盖；真实口令勿提交进仓库（脚本内默认值仅用于本机演示） |
| `REDIS_HOST/REDIS_PORT/REDIS_PASSWORD` | localhost/6379/空 | Redis |
| `JWT_SECRET` | 内置开发密钥 | **生产必须覆盖**（≥32 字节） |
| `CORS_ORIGINS` | `*` | 前端跨域白名单 |

> 上游模型服务不走环境变量：`t_model.endpoint`（OpenAI 兼容完整 URL）与
> `t_model.api_key`（Bearer 凭证）随模型行存储 —— 不同模型可指向不同厂商
> （GLM/DeepSeek/本地 Ollama 的 `/v1` 兼容端点等）各持各的 Key，管理端可在线改。

## 6. API 双协议约定

### 6.1 管理面（Result 契约，HTTP 200 + code/msg）

```
POST /auth/register|login|refresh|logout        # 认证
GET  /admin/users|dashboard|apikeys             # 管理端（ADMIN）
PUT  /admin/users/{id}/quota|status|role
GET  /admin/audit-logs                          # 操作审计分页（v2.5，ADMIN）
GET/POST/PUT/DELETE /admin/models[...]          # 模型 CRUD + 价格版本
GET/PUT /admin/routing/config|switch|weights|analysis|decisions
GET  /api/user/info|apikeys|chat/logs|chat/search   # 用户自助
```

### 6.2 OpenAI 协议面（`/v1/**`，无 Result 包装）

```bash
# 1) 登录换 Token
curl -X POST http://localhost:10011/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"demo","password":"demo123"}'

# 2) 非流式对话（Bearer 填 AccessToken 或 sk- Key）
curl http://localhost:10011/v1/chat/completions \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"model":"auto","messages":[{"role":"user","content":"你好"}]}'

# 3) 流式（SSE）
curl -N http://localhost:10011/v1/chat/completions \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"model":"auto","stream":true,"messages":[{"role":"user","content":"写首诗"}]}'
```

- `model` 三种取值：具体编码（如 `glm-4-flash`，指定模式）/ `auto`（强制智能路由）/ 缺省（按开关）；
- 请求级约束头（可选）：`X-Cost-Budget`（元）、`X-Latency-SLA`（毫秒）、`X-Agent-Task-Id`（任务归因）；
- 响应附加头 `X-Routing-Decision`：`模式:模型` 摘要（如 `auto:glm-4-flash`、`auto:glm-4.5-flash->fallback:glm-4-flash`）；
- 思考型模型（如 `glm-4.5-flash`）流式时会透传 `delta.reasoning_content` 推理增量帧
  （OpenAI 语义外扩展，标准 SDK 忽略，思考型客户端可渲染）；推理文本不入库、不计费；
- **SSE 错误帧扩展**（README 契约）：流开始后的中途错误以
  `data: {"error":{...}}` 帧下发并正常收尾 `[DONE]`
  （OpenAI 官方流式协议无错误帧定义，SDK 会把该帧当文本，
  LangChain 侧可按 `chunk.contains("error")` 识别——课程演示够用，生产建议改用 HTTP 2 + trailer）。

### 6.3 LangChain 直连示例

```python
from openai import OpenAI
client = OpenAI(base_url="http://localhost:10011/v1", api_key="sk-你的Key")
resp = client.chat.completions.create(
    model="auto",
    messages=[{"role": "user", "content": "解释一下量子纠缠"}])
print(resp.choices[0].message.content)
```

## 7. 核心机制速览

### 7.1 两段式计费（防并发超扣）

```
reserve（预扣）  条件更新：used + reserved + est <= quota 才放行（行锁串行化）
   ↓ 调用模型
settle（结算）   实际用量入账 + 预扣水位释放（单事务，status 0→1 抢占幂等）
   ↓ 60s 兜底
reconcile（对账） 超过 5 分钟未结算的预留 → 回滚水位（status 0→2，行锁互斥防双回滚）
```

### 7.2 智能路由（1.md §6）

```
复杂度识别（估算Token数 + tools/结构化输出）
   simple ≤500 → cheap 池 / complex ≥2000 → strong 池 / 其余 balanced 全池
→ TIER 候选过滤 → HEALTH 过滤（熔断黑名单，fail-open）
→ BUDGET/SLA 过滤（严格）→ 质量分·成本分 min-max 归一 → w_q=0.6/w_c=0.4 加权
→ 失败自动 fallback（首个数据块前可整体切换，之后透传错误帧）
```

### 7.3 系统监控（观测域，v2 新增）

```
ChatController 埋点（beginCall/endCall + 流式 CAS 归位）
→ MetricsRegistry（60s 逐秒环形窗口/在途/活跃流，进程内零依赖）
→ GET /admin/metrics/runtime（admin 模块聚合）
   调用窗口 + JVM/线程 MXBean + BatchLogWriter 队列水位 + Redis PING/MySQL SELECT 1 探活
→ 前端"系统监控"页 5s 轮询（QPS 曲线/队列水位条/依赖状态点，模型健康复用 /admin/routing/analysis）
```

熔断：5 分钟窗口 ≥5 次且错误率 ≥50% → 拉黑 60 秒；统计窗口 Redis ZSet/Hash，TTL 7 天。
冷启动：样本 <20 时回退 `baseline_quality/baseline_latency_ms` 静态值。

### 7.3 数据分层与保留

| 表 | 角色 | 保留 |
|---|---|---|
| `t_invoke_log_v2` | 调用明细（月分区窄表） | 90 天，`DROP PARTITION` 清理 |
| `t_chat_message` | 会话内容（ngram 全文检索） | 90 天，定期 DELETE |
| `t_billing_daily` | 日汇总（看板趋势） | 永久 |
| `t_audit_log` | 操作审计（管理端写操作留痕，v2.5） | append-only；清理归计划任务（§7.1 项③） |
| `data/payload/` | D6 报文采样（每分钟首请求） | 30 天，手工/脚本清理 |

分区维护（每季度一次，毫秒级）：

```sql
ALTER TABLE t_invoke_log_v2 REORGANIZE PARTITION pmax INTO (
  PARTITION p202702 VALUES LESS THAN (TO_DAYS('2027-03-01')),
  PARTITION pmax VALUES LESS THAN MAXVALUE);
ALTER TABLE t_invoke_log_v2 DROP PARTITION p202611;  -- 到期清理
```

### 7.4 凭证安全

- RefreshToken / API Key **只存 SHA-256 哈希**，明文签发时返回一次；
- AccessToken 登出进 Redis 黑名单（TTL=剩余有效期）；
- API Key 格式 `sk-` + 32 位随机 hex，前 8 位作展示前缀；
- BCrypt（10 轮）存登录密码。

### 7.5 操作审计（v2.5 新增，1Panel 业务对标项②）

```
管理端写操作（11 个 @Audited 注解方法）
→ AuditAspect（AOP @Around）旁路组装四要素：
   who（access 过滤器注入的 X-User-Id/X-Username，客户端伪造无效）
   when（created_at） / what（动作+描述+HTTP 方法+URI 含路径变量+脱敏参数摘要）
   / 结果（success 1|0 + error_msg + cost_ms + ip）
→ t_audit_log（append-only，审计落库失败只记日志、绝不影响业务）
→ GET /admin/audit-logs（分页 + 操作人/动作/结果/时间范围过滤）
→ 前端"操作审计"页（ADMIN，/admin/audit-logs）
```

- 参数摘要脱敏：password/secret/token/apikey/credential 值替换 `***`（"key" 不脱敏，保留配置键可读），截断 1000 字符；
- BizException 也记失败审计后原样重抛，交 GlobalExceptionHandler 统一返回；
- 审计查询鉴权复用 RBAC：`/admin/**` 由 access 层守卫（ADMIN）。

## 8. 常见问题

| 症状 | 排查 |
|---|---|
| 启动报 `Access denied for user` | `DB_USER/DB_PASSWORD` 环境变量或 yml 默认值与实际不符 |
| 对话 503 / `MODEL_CALL_FAILED` | 上游不可达或 Key 无效：检查 `t_model.endpoint`/`api_key`（401/403 → 换有效 Key） |
| 401 `API Key 无效` | Key 已吊销/过期，或用户被禁用；重新创建 |
| 429 `请求过于频繁` | 触发固定窗口限流（默认 100 次/60s，可配 `access.rate-limit`） |
| 看板无数据 | 正常——`t_billing_daily` 由结算事件驱动，先成功对话几笔 |
| 流式乱码 | 客户端需按 SSE 解析（`text/event-stream`），不要当 JSON 读 |

## 9. 升级路径（选型书第八章）

模块边界 = 未来服务边界：拆微服务时按模块直接拆出（`admin`/`router` 先拆），
业务代码零改动；统计窗口换 ClickHouse、限流换令牌桶、配额换 Redis 原子扣减，
触发条件与实施顺序见《DB-design-重构技术选型书》v1.4 第八章。

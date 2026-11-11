# DB-design 生产级标准对比评审（AI Gateway Platform）

| | |
|---|---|
| 评审日期 | 2026-10-26 |
| 评审对象 | `D:\Java\DB-design`（AI Gateway Platform），以代码实据为准，非文档转述 |
| 参照系 | 主流生产级 LLM 网关的公开形态与实现：LiteLLM Router（官方文档核实）、AWS Bedrock IPR（arxiv 2509.06274）、Cloudflare AI Gateway / Higress / OneAPI 类产品的形态共识，以及大厂网关通行实践 |
| 与既有文档关系 | 《DB-design-问题清单.md》解决"课程符合性 + 正确性缺陷"（P1~P12）；本文档解决"生产级标准差距"，问题编号 `PR-*`，两份不重复、互补 |
| 核心结论 | 你的直觉成立：该项目同时呈现**重基础设施形态**与**轻生产机制**——把超大规模系统的"形态"搬齐了，却没有搬支撑这种形态的"机制"，且实际规模根本不需要这种形态。这就是"不伦不类"的来源 |

---

## 一、生产级 LLM 网关基准画像

先立标准。一个生产级 LLM 网关在八个维度上的行业通行做法：

### 1.1 架构形态

- 主流 LLM 网关（LiteLLM、OneAPI/NewAPI、Cloudflare AI Gateway、Higress AI 插件）**几乎清一色是单体部署（+ Redis/DB），不是微服务集群**；
- 微服务拆分由**组织规模与团队边界**决定，不由"看起来专业"决定；
- LiteLLM 官方甚至推荐生产环境用 `simple-shuffle` 这类低开销策略，"best performance with minimal latency overhead"——生产网关的第一美德是**链路短**。

### 1.2 计费与配额

- **预扣-结算两段式**（请求前原子预扣/预留，完成后按实际用量结算差额）或**幂等消费**（唯一键去重）；
- Token 用量以**供应商返回的 usage 为准**（本项目做到了）；
- 价格**版本化**（改价不影响历史账单复算），账本 append-only。

### 1.3 上游可靠性

- 多级 fallback（LiteLLM 的 `order` 分级：每级先重试再升级）；
- 重试固定+指数退避、**429 冷却**（cooldown）、重试预算防风暴、可选对冲；
- 每个上游有独立超时预算。

### 1.4 流式语义

- 客户端断开 → **取消上游生成**（省算力）→ 按已产出 token 部分计费（标记 interrupted）；
- TTFT（首 token 延迟）是一级指标；SSE 心跳保活。

### 1.5 限流与多租户

- 分布式令牌桶，按 key/tenant 维度；
- 优先级与负载卸载（过载时先牺牲低优先级）。

### 1.6 技术选型（先分数据类，再选引擎）

选型的正确顺序是**数据分类 → 按读写模式/一致性/规模匹配引擎 → 再定架构形态**（polyglot persistence）。生产级 LLM 网关各数据类的行业收敛选型：

| 数据类 | 读写模式 | 生产主流选型 | 公开实例 |
|---|---|---|---|
| 账号/密钥/配额/账务（OLTP） | 点读点写、强事务 | MySQL 家族（国内：PolarDB/TDSQL/OceanBase MySQL 模式/TiDB 兼容）或 PostgreSQL | LiteLLM=Postgres+Redis；Helicone 交易层=Postgres；Langfuse=PostgreSQL |
| 调用日志/用量流水 | append-only 写、大范围聚合 | **ClickHouse**（+Kafka 管道）；更大规模进数据湖（Parquet/Iceberg） | OpenRouter 以 ClickHouse 为 source of truth；Helicone=ClickHouse+Kafka 扛十亿级日志 |
| 会话历史检索（产品功能） | 写后查、相关性排序 | ES 仍是合法选型；日志型检索收敛到 ClickHouse 全文/tokenbf；语义搜索走向向量库 | — |
| 全量请求/响应报文 | 大对象、写多读少 | 对象存储 S3/OSS/MinIO + 生命周期策略 | TrueFoundry 网关日志走 Parquet 列存 |
| 限流计数/配额预扣 | 高频原子读写 | Redis（Lua/令牌桶） | 行业通用 |
| 消息管道 | 高吞吐解耦 | Kafka/RocketMQ（大厂标配） | Helicone=Kafka；国内大厂 Kafka/RocketMQ 为主 |

> 选型判定：**MySQL 用于 OLTP 层本身是生产正确选型**——错的是让它同时承担 OLAP 日志（t_token_usage/t_invoke_log）与 MEDIUMTEXT 报文仓库；**ES 用于“会话搜索产品功能”也合法**——错的是数据类配错（调用日志该归 OLAP）、量级配不上成本、双写 MQ 同步机制脆弱（应 CDC/对账）。

### 1.7 可观测性

- OpenTelemetry 链路追踪、Prometheus 指标（RED：Rate/Error/Duration）、SLO + 错误预算 + 告警；
- traceId 只是起点，不是可观测性本身。

### 1.8 交付与运维

- 容器化 + IaC + CI/CD、健康探针（liveness/readiness）、优雅停机；
- 密钥进 Vault/环境变量；配置版本化、灰度、可回滚。

### 1.9 选型审视的方法论（回应“是否忽略了选型问题”）

本评审 v1 在给定技术栈之内评“用得对不对”，未把“该不该用/该用什么”作为独立维度——已由 1.6 节选型对照表补齐。需要同时指出：**DB-design 原项目的选型顺序本身就是反的**——先定了 Spring Cloud Alibaba 全家桶（教学栈驱动），存储跟着框架配（MySQL 是 Java 课默认、ES 是“要体现全文检索”的加分项、RabbitMQ 是教程标配），而不是从数据分类出发。生产选型的驱动因素是数据特征：一致性要求、读写模式、规模、查询模式；框架只是实现手段。

---

## 二、总体判断：为什么"不伦不类"

**一句话：形态是超大规模系统的形态，机制是单机玩具的机制，规模是单机演示的规模——三者互相矛盾。**

两个最有力的实据：

1. **管理后台是对话主链路的强依赖单点。** router 每次对话要通过 OpenFeign **HTTP 调用 admin 服务**做 API Key 校验与配额预检，而 Feign 降级策略是：`hasActiveApiKey` 降级返回 `false`、`checkQuota` 降级返回 `false`（`AdminFeignClientFallbackFactory.java:38-56`）——**admin 服务一挂，全站对话不可用**。生产环境中管理后台挂掉绝不应该影响转发链路；这里"拆出去"的 admin 反而成了转发链路的单点。拆分引入的可靠性问题，比它解决的问题多。
2. **一次对话至少 3 次跨服务 HTTP 往返**（`hasActiveApiKey` + `getActiveApiKey` + `checkQuota`，`ChatService.java` 流式/非流式两条链路各 3 处），换来的机制收益为零——进程内这三次都是方法调用或一条 SQL。生产网关的核心诉求是链路短，这里是刻意把链路拉长。

同时，真正该做的生产机制一个没做：计费幂等（双扣）、预扣-结算（无账）、流式取消（部分计费）、指标/SLO/告警、容器化交付——全部缺失（下文逐项列证据）。

---

## 三、维度对比总表

| # | 维度 | 生产级标准 | DB-design 现状 | 证据 | 评定 |
|---|---|---|---|---|---|
| PR-1 | 服务拆分 | 按团队边界拆；LLM 网关主流为单体+Redis/DB | 6 个 JVM 微服务（10011/8091/8092/8093/8084/8085），单机 0-QPS 场景 | README 服务与端口表 | **过度设计** |
| PR-2 | 服务间通信 | 单体内进程调用；跨服务才用 RPC | OpenFeign+Hystrix FallbackFactory 做**同机内部调用**；配置熔断、降级、超时 | feign-api 全模块、AdminFeignController | **过度设计** |
| PR-3 | admin 依赖方向 | 管理面与转发面隔离，管理面故障不影响转发 | checkQuota/hasActiveApiKey 降级=false → admin 挂=全站拒答 | AdminFeignClientFallbackFactory.java:38-56 | **缺陷级错配** |
| PR-4 | 计费时序 | 预扣-结算两段式或幂等消费 | 异步 MQ 计费 + 固定值预检；重投双扣、超限回滚无账 | BillingService.java:63-112（引 P4/P5） | **不足** |
| PR-5 | Token 计量 | 以供应商 usage 为准 | Ollama `prompt_eval_count/eval_count` 与 OpenAI `usage` 双兼容解析 | ChatService.java doChat 第 4 步 | **恰当** |
| PR-6 | 上游韧性 | 多级 fallback+重试退避+429 冷却+超时预算 | 单跳 fallback_code，fallback 失败即报错；无重试/冷却/对冲 | ChatService.java 降级分支 | **不足** |
| PR-7 | 流式语义 | 断开→取消上游→部分计费；TTFT；心跳 | 客户端断开→callback 抛异常→catch 后 return（已生成 token 不计费）；上游无取消传播；180s 硬超时；无 TTFT | ChatService.java doStreamInternal、OllamaClient.java:177-181 | **不足** |
| PR-8 | 限流体系 | 分层但克制：入口限流+按 key/tenant | 网关 Lua + Sentinel GW_FLOW + 接口 FLOW + 热点 PARAM_FLOW 三级，规则持久化 Nacos，控制台端口踩坑（8719/8721） | README 核心设计节 | **过度设计** |
| PR-9 | 配置与缓存 | 配置中心推送/TTL 缓存，多实例一致，变更有审计 | 模型配置 ConcurrentHashMap **永不过期**，改价需手动调刷新接口，且只刷本实例 | ChatService.java modelCache 注释与实现 | **不足** |
| PR-10 | 存储分层 | 报文进对象存储/OLAP，MySQL 不存全量报文 | `t_invoke_log.response_json MEDIUMTEXT` 全量落 MySQL，无归档 | init.sql:101-124（引 P6） | **错配** |
| PR-11 | 检索设施 | 按数据量与查询模式选型 | 为单用户聊天历史引入 ES+IK+第二条 MQ 路由+索引迁移 v2 | README 架构图 | **过度设计** |
| PR-12 | ID 体系 | 基础设施与业务库分离 | UidGenerator 雪花 + `WORKER_NODE` 表混入业务库 ai_gateway | init.sql:143-152 | **过度设计** |
| PR-13 | 可观测性 | OTel+Prometheus+SLO+告警 | 仅 traceId 日志；无指标、无告警、无 TTFT | 全工程无 metrics 依赖 | **不足** |
| PR-14 | 交付运维 | 容器化+CI+健康探针+优雅停机+密钥管理 | 无 Dockerfile/CI（find 无匹配）；密钥明文提交；无探针；`start-all.sh` 手工脚本 | find 结果、application.yml:19-20（引 P7） | **不足** |
| PR-15 | 接口兼容 | OpenAI 兼容 | 统一入口 + OpenAI 报文 + 降级后 `model` 字段跟随实际模型 | README、ChatService | **恰当** |
| PR-16 | 请求防护 | 输入上限校验 | max-messages=50、max-content-length=32768、role 白名单 | ChatService.validateRequest | **恰当** |

---

## 四、过度设计清单（"完全没必要" Top 8）

> 每条先说清：这些组件**本身是成熟做法**，错配的是规模——单体场景引入它们，只会制造新的分布式问题，再用更多组件去兜底。

### OD-1. 六服务微服务拆分

- **现状**：gateway/auth/router/billing/admin/es-sync 六个独立 JVM，手工按顺序启动，启动顺序敏感（README 专节讲等待与就绪探测）。
- **错配**：主流 LLM 网关全是单体（1.1 节）；单机跑六个 JVM 只带来启动编排、端口管理、Feign 链路、跨服务调试成本。
- **生产等价物**：**模块化单体**——一个 Spring Boot 工程、六个 Maven module，模块间进程内调用，保留清晰的包边界；未来真有规模需要时按模块拆出去。
- **收益**：PR-2/PR-3 的全部问题（Feign 降级、admin 单点、启动顺序、Sentinel 三级里的两级）自动消失。

### OD-2. OpenFeign + 熔断降级做内部调用

- **现状**：feign-api 独立模块 + AdminFeignController 专门暴露内部端点 + FallbackFactory 八个方法逐个降级。
- **错配**：把进程内方法调用变成 HTTP，再为这个 HTTP 配熔断——**先制造故障面，再引入复杂性去挡故障面**。
- **证据**：checkQuota 一次 HTTP 的降级返回 false 直接改变业务语义（拒答）。
- **生产等价物**：进程内调用；配额预检一条 SQL（或 Redis 原子预扣）。

### OD-3. RabbitMQ 异步计费 + 死信人工补偿

- **现状**：对话成功→router 算费→MQ→billing 消费→MySQL 事务；失败重试 3 次→死信表→管理端人工"一键重试/忽略"。
- **错配**：把一个**本地事务**问题（记账+扣减）改造成**分布式一致性**问题，然后用死信+人工重试去兜底自己制造的不可靠。生产用 MQ 做计费的前提是消费端幂等（本项目没有，见 P4）。
- **生产等价物**：同步预扣（Redis/DB 原子）+ 完成后结算，或至少幂等消费；MQ 保留给真正解耦的旁路（如 ES 同步、统计）。
- **收益**：P4（双扣）、P5（无账）两个缺陷在设计层面消失。

### OD-4. Elasticsearch 全文检索

- **现状**：chat.record.es 第二条 MQ 路由 → es-sync 消费 → ES(IK) → 前端历史搜索；维护索引 mapping、迁移 v2、另一套启动/踩坑文档。
- **错配**：单用户聊天历史在这个量级，MySQL FULLTEXT / 前缀索引 + 分页完全够用；ES 带来的是第二套数据一致性（MySQL 与 ES 的最终一致 + 死信补偿）。
- **生产等价物**：数据量到千万级、需要复杂检索时再上；上的时候用 CDC 或双写校验，而不是第二条手工 MQ 路由。

### OD-5. Sentinel 三级流控 + 控制台

- **现状**：网关 Redis Lua（已有）之外，再叠 Sentinel GW_FLOW / 接口 FLOW / 热点 PARAM_FLOW 三级，规则 JSON 持久化 Nacos，控制台传输端口 8719/8721 踩坑记录。
- **错配**：网关层 Lua 限流已经覆盖演示场景；三级规则中"接口级/热点参数级"在单用户演示流量下永远打不穿，纯仪式。且 Sentinel 控制台规则与 Nacos 权威存储的同步本身就是生产运维负担（README 自己记录了端口坑）。
- **生产等价物**：保留网关 Redis Lua 一层 + 关键接口的简单并发信号量；真要多级，等真有多级流量。

### OD-6. UidGenerator 雪花 ID + WORKER_NODE 表

- **现状**：引入百度 UidGenerator，`WORKER_NODE` 表建在**业务库 ai_gateway** 里做 workerId 分配。
- **错配**：基础设施状态混入业务库（备份/迁移/权限都被污染）；单机多服务部署下 workerId 分配反而是雪花方案最容易出事的部分。业务表主键用自增或 UUID 即可，需要趋势递增用 snowflake 库的本地配置。
- **生产等价物**：自增主键或 Leaf/雪花（独立运维）；业务库只放业务表。

### OD-7. Nacos 注册中心 + 配置中心（单机六服务）

- **现状**：六服务互发现 + Sentinel 规则 + 业务配置全在 Nacos；规则 JSON 导出提交。
- **错配**：单机上服务发现毫无意义（地址固定）；配置中心的收益（动态推送）被"模型配置缓存在代码里永不过期"（PR-9）抵消——真正的动态配置（模型价格）反而不走 Nacos。
- **生产等价物**：单体后直接配置文件 + `@RefreshScope`；规则随代码版本管理。

### OD-8. MQ 双路由拓扑 + 死信管理界面

- **现状**：topic exchange + 两条路由（mysql/es）+ DLX/DLQ + `t_dead_letter` 表 + 管理端重试/忽略界面 + tools 里的死信拓扑脚本。
- **错配**：为两个消费方维护一套完整异步拓扑和人工补偿工作台；OD-3/OD-4 落地后拓扑不复存在。人工"一键重试"按钮本质是给缺乏幂等设计的系统配的手工对账台。
- **生产等价物**：能同步就同步；必须异步则幂等消费 + 自动对账，人工只处理真正的毒消息。

---

## 五、欠缺清单（生产要素 Top 10，按风险排序）

| # | 缺失要素 | 现状证据 | 生产标准 | 补齐成本 |
|---|---|---|---|---|
| X-1 | **admin 强依赖解除** | 降级=false 全站拒答（PR-3） | 转发面不依赖管理面；配置本地缓存降级可用 | 低（单体化后自动解决） |
| X-2 | **计费两段式/幂等** | 异步单段 + 无唯一键（P4/P5） | 预扣-结算 or 唯一键幂等 | 中 |
| X-3 | **流式取消语义** | 断开→不计费、上游无取消传播（PR-7） | 取消传播 + 部分计费 + interrupted 标记 | 中 |
| X-4 | **上游韧性机制** | 单跳 fallback，无重试/冷却/对冲 | 多级 fallback + 指数退避 + 429 冷却 | 中 |
| X-5 | **配置一致性** | 缓存永不过期 + 跨实例刷新缺失 + 改价滞后（PR-9） | TTL/推送 + 变更审计 + 价格版本化 | 低 |
| X-6 | **可观测性** | 仅 traceId；无指标/SLO/告警/TTFT | OTel + Prometheus + RED + SLO | 中 |
| X-7 | **容器化交付** | 无 Dockerfile/CI/探针；手工启停脚本 | Docker Compose/K8s + CI + readiness | 中 |
| X-8 | **密钥管理** | jwt.secret 明文、API Key 明文库（P7/P8） | Vault/env + 哈希存储 | 低 |
| X-9 | **数据治理** | 报文全量落库无保留策略（P6） | 保留期 + PII 脱敏 + 归档 | 低 |
| X-10 | **容量基线** | 报告 6.5 有估算无实测 | 压测报告支撑容量声明 | 低 |

---

## 六、做对了的（生产正确方向，重构时保留）

1. **统一入口 + OpenAI 兼容报文**——行业标准做法，OpenRouter/LiteLLM 同款思路；
2. **Token 计量以供应商 usage 为准，不估算**（PR-5）——比很多自研网关正确；
3. **降级后按实际应答模型计价、响应 model 字段跟随实际模型**——语义正确且对客户诚实；
4. **traceId 贯穿全链路**——可观测性的正确起点；
5. **配额条件更新防超扣**（`WHERE used + tokens <= quota`）——SQL 层乐观约束正确；
6. **死信队列 + 人工补偿的意识**——机制有错（OD-8），意识正确；
7. **双 Token + JWT 黑名单 + BCrypt**——认证层符合生产习惯；
8. **请求上限校验**（条数/长度/角色白名单）——防资源耗尽的正确做法。

---

## 七、目标架构（生产-lite）与演进路径

### 7.1 目标形态

```
                        ┌──────────────────────────────────────┐
                        │  ai-gateway（模块化单体，1 个 JVM）    │
                        │                                      │
 客户端 ── OpenAI 兼容 ──▶│  gateway 模块（JWT/Lua限流/路由）     │
                        │  auth 模块  │ router 模块 │ admin 模块 │
                        │  billing 模块（同步预扣+结算）         │
                        │  search 模块（可选，MySQL FULLTEXT）   │
                        └───┬──────────┬───────────┬───────────┘
                            │          │           │
                          Redis      MySQL       Ollama
                        (限流/配额/  (配置/账务/  (上游，可扩展
                         黑名单/缓存) 日志分区)    多供应商抽象)
                        + OTel traces → Prometheus → Grafana/告警
                        + Docker Compose + CI
```

### 7.2 三步演进

| 阶段 | 内容 | 对应问题 |
|---|---|---|
| 第一步：做减法 | 六服务→模块化单体；删 Feign/Sentinel 两级/Nacos/WORKER_NODE/ES 与第二条 MQ 路由；计费改同步预扣+结算 | OD-1~8、X-1 |
| 第二步：补机制 | 幂等键、流式取消+部分计费、多级 fallback+退避、TTL 配置缓存+价格版本化、Docker Compose+探针、Prometheus 指标 | X-2~X-8 |
| 第三步：生产级 | 多供应商抽象+429 冷却、多租户优先级、OTel/SLO/告警、灰度发布、评测闭环（与 Web-design 1.md 的生产-lite 清单衔接） | X-4/X-6 及路由层增强 |

### 7.3 汇报/答辩口径建议

被问"为什么拆六个微服务"时，诚实的答法是："**这是对大厂微服务架构的教学式复刻，代价是 admin 成了转发链路单点、计费从本地事务变成分布式一致性问题；生产上 LLM 网关的主流形态是模块化单体，我识别到了这个错配，重构方向是单体化 + 把省下的复杂度投资到幂等、流式取消和可观测性上。**"——主动承认错配并给出演进路径，比辩护六个服务更专业。

---

## 八、一句话总结

DB-design 的问题不是"用了生产组件"，而是**用了只有大规模才需要的组件组合，却没实现让这些组件成立的大规模机制**：六个服务没有换来隔离性（admin 单点拖垮转发），MQ 没有换来可靠性（双扣/无账），ES 没有换来检索能力（量级不需要），Sentinel 三级没有换来稳定性（单用户流量打不穿）。生产级的路是先做减法（单体化）再补机制（幂等/取消/可观测），而不是继续加组件。选型层面同理：不是“MySQL/ES 不行”，而是数据类配错了引擎——OLTP 留在 MySQL 家族，日志进 ClickHouse，报文进对象存储，检索按需选型。

---

## 附录：证据索引

| 证据 | 位置 |
|---|---|
| checkQuota/hasActiveApiKey 降级返回 false | `ai-gateway-platform/feign-api/.../AdminFeignClientFallbackFactory.java:38-56` |
| 一次对话 3 次 Feign 调用、固定预检值、双链路重复实现、永不过期缓存、180s 硬超时 | `router/.../service/ChatService.java`（doStreamInternal / doChat / modelCache） |
| 上游无取消传播、finally 断连 | `router/.../client/OllamaClient.java:177-181` |
| usage 双兼容解析、按实际模型计价 | `ChatService.java` doChat 第 4 步 |
| 6 服务/端口、MQ 双路由、Sentinel 三级、Sentinel 端口踩坑 | `DB-design/README.md` |
| MEDIUMTEXT 报文、WORKER_NODE 混入业务库 | `ai-gateway-platform/sql/init.sql:101-124、143-152` |
| 无 Dockerfile/Jenkinsfile/CI | find 全工程无匹配 |
| jwt.secret 明文 | `auth/src/main/resources/application.yml:19-20` |
| 正确性缺陷（双扣/无账） | 《DB-design-问题清单.md》P4/P5 |
| LiteLLM 生产策略/cooldown/fallback 依据 | docs.litellm.ai/docs/routing（2026-10 核对） |
| Bedrock IPR 质量感知路由依据 | arxiv 2509.06274 |
| Helicone=ClickHouse+Kafka、Langfuse=PostgreSQL | helicone.ai《Complete Guide to LLM Observability 2025》、clickhouse.com 博客 |
| OpenRouter 以 ClickHouse 为日志 source of truth | clickhouse.com《How OpenRouter uses ClickHouse》 |
| 网关日志 Parquet 列存选型 | truefoundry.com AI Gateway logging architecture |

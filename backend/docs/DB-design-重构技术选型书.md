# AI Gateway 重构技术选型书（生产-lite v1.0）

| | |
|---|---|
| 文档版本 | v1.4（计费预扣落表、幂等宣称修正、Windows 交付升为主轨） |
| 修订记录 | v1.0 初版；v1.1（2026-10-26）补 4.1“为什么不微服务”决策记录；v1.2（2026-10-26）硬约束改为 Java 21 + Boot 3.2，新增 4.2 升栈要点、7.0 路线决策与 M0 移植升栈步骤；v1.3（2026-10-26）MinIO 社区版停止预编译二进制分发（source-only，dl.min.io 全线 410），3.4 改为本地磁盘目录 + ObjectStore 接口，M3/M4/3.10 同步修订；v1.4（2026-11-02）评审修订：5.1 两段式计费改为独立预扣流水表 `t_quota_reservation`（原 reserved 单字段方案无法按笔回滚）、5.2 幂等宣称修正（uk_trace 含分区键为弱幂等，settle 需抢占/点查兜底）、3.10 交付主轨反转为 Windows 脚本（课设硬约束 win 全本机演示，Compose 降为加分项）、M0 验收补 SSE 流式回归；1.md 同步升 v3.0 收口单体口径并新增 FR-14 与 8.4 Agent 兼容预留 |
| 编写日期 | 2026-10-26 |
| 输入 | 《DB-design-生产级标准对比评审.md》（PR-1~16/OD-1~8/X-1~10）、《DB-design-问题清单.md》（P1~P12）、1.md 需求基线 v2.1 |
| 目标 | 按正确顺序（**数据分类 → 选型 → 架构**）重定技术方案，替代"框架驱动"的原选型 |
| 硬约束 | Windows 本机可运行；**运行时基线：Java 21（LTS）+ Spring Boot 3.2+**（v1.2 修订：定位从课设合规升级为简历项目，移植进 Web-design 新主干时借删 Spring Cloud 之机升栈；虚拟线程承载 SSE）；单人开发 |
| 选型原则 | ① 机制优先于组件（两段式、幂等、分区不依赖任何中间件）；② 每个"不选"必须给出**升级触发条件**；③ 生产-lite 档与生产档同构——只换引擎不改机制 |

---

## 一、方法论：为什么这个顺序

原项目的选型是**框架驱动**：先定 Spring Cloud Alibaba 全家桶，存储跟着框架配（MySQL 是 Java 课默认、ES 是"体现全文检索"的加分项、RabbitMQ 是教程标配）。正确顺序是**数据驱动**：

```
① 数据分类（系统里到底有哪几类数据？各自的一致性/读写模式/规模/查询模式是什么？）
② 逐类选型（每类数据匹配引擎；配不上成本的不选，给出升级触发条件）
③ 架构形态（同步/异步边界、事务边界、部署形态由①②推出）
```

反过来的顺序必然产出"六服务 + MQ 双写 + ES + 三级流控"的错配（见生产级对比评审第二节）。

---

## 二、数据分类清单

对 AI Gateway 系统全部数据做穷举分类。**这一步决定一切**：

| # | 数据类 | 内容 | 一致性要求 | 读写模式 | 规模量级 | 查询模式 | 保留策略 |
|---|---|---|---|---|---|---|---|
| D1 | 账号与凭证 | 用户、API Key、刷新令牌 | 强（事务） | 点读点写 | 万级 | 点查（username/key） | 永久/随注销 |
| D2 | 配额与账务 | 日/月配额、预扣、结算、费用 | **强（资金级）** | 高频原子读改写 | 每请求 1~2 次 | 点查 + 聚合 | 结算明细 90 天，汇总永久 |
| D3 | 模型与价格元数据 | 模型配置、单价、降级链、（web-design：模型池） | 强，可接受秒级延迟 | 极低频写、高频读 | 百级 | 点查（model_code） | 价格版本化永久 |
| D4 | 调用日志/用量流水 | 每次调用的 token/费用/延迟/结果 | 弱（最终一致，允许秒级丢失窗口） | append-only 写、范围聚合 | 日万~百万行 | 按用户/模型/时间聚合、TopN | 明细 90 天，汇总永久 |
| D5 | 会话历史与检索 | 对话内容、按关键字/模型/时间检索 | 弱 | 写一次、读多样 | 日万条 | FULLTEXT/前缀 + 过滤 + 分页 | 与明细同步 90 天 |
| D6 | 全量请求/响应报文 | 审计用原始报文 | 弱 | 大对象、写多读极少 | 单条 KB~MB | 按 traceId 点查（排查时） | 30 天 |
| D7 | 限流计数 | 网关限流窗口计数 | 单键原子 | 高频原子读改写 | 每请求 1 次 | 无查询，只判断 | 秒~分钟 |
| D8 | 运行时状态 | JWT 黑名单、（web-design：路由统计） | 最终一致 | 高频读写 | 万级 | 点查 | TTL 自清理 |
| D9 | 补偿任务 | 死信/未结算预扣 | 强 | 低频 | 百级 | 状态扫描 | 处理完 30 天 |
| D10 | （v2 预留）路由决策审计 | routing_decision JSON | 弱 | append-only | 同 D4 | 按 traceId/用户回放 | 同 D4 |

**分类结论（决定后文选型的四个关键事实）**：
1. 只有 D1/D2/D9 是强一致性数据——它们的量是"每请求一两次点写"，**一个 MySQL 绰绰有余**；
2. D4/D5/D6 是三类**异构的弱一致数据**——原项目把它们全部塞进 MySQL（其中报文用 MEDIUMTEXT），D5 又额外复制一份进 ES，是选型错配的根源；
3. D7/D8 是纯运行时状态——Redis 的本职工作，原项目这部分反而是对的；
4. 全系统**没有任何一类数据需要 MQ 才能工作**——原项目 MQ 承载的"计费"是 D2（强一致，恰恰不该异步无幂等地异步），"ES 同步"是 D5（量级不需要独立检索设施）。

---

## 三、选型决策表

每类数据给三档：**生产档**（大厂同款）/ **生产-lite 档（本项目采用）**（机制同构、引擎降级到 Windows 单机可运行）/ **升级触发条件**（何时必须换到生产档）。

### 3.1 D1 账号凭证 + D2 配额账务 → MySQL 8（保留）

- **生产档**：MySQL 家族分布式版（PolarDB/TDSQL/OceanBase 兼容模式）或 PostgreSQL；行业实例：LiteLLM/Helicone/Langfuse 交易层全为 Postgres。
- **本项目采用**：**MySQL 8，原样保留**。OLTP 选 MySQL 本身是生产正确选型（见对比评审 1.6）。
- **改造**（机制补齐，非引擎更换）：
  - `t_user` 增加 `reserved_daily BIGINT DEFAULT 0`（预扣字段），两段式计费见第五章；
  - `t_api_key` 存 SHA-256 哈希 + 前缀 8 位明文（修 P8）；
  - `t_refresh_token` 保留哈希化改造；
  - 补 CHECK 约束（P1）。
- **升级触发条件**：单表 > 5000 万行或 QPS > 5000（本项目永不触发，仅完整性列出）。

### 3.2 D4 调用日志/用量流水 → MySQL 分区窄表（lite）/ ClickHouse（生产档）

- **生产档**：ClickHouse + Kafka 管道；更大规模 Parquet/Iceberg 数据湖。行业实例：OpenRouter 以 ClickHouse 为 source of truth；Helicone=ClickHouse+Kafka 扛十亿级日志。
- **本项目采用（生产-lite）**：**MySQL 按月 RANGE 分区窄表**——机制上与 ClickHouse 同构（append-only、宽表聚合、时间分区、TTL=DROP PARTITION），引擎降级的理由：ClickHouse 无 Windows 原生版（WSL/Docker 引入运维面），且本量级下聚合查询 MySQL 分区裁剪完全可扛。**替代 t_token_usage + t_invoke_log 两张 MEDIUMTEXT 大表**：

```sql
CREATE TABLE t_invoke_log_v2 (
  id            BIGINT AUTO_INCREMENT,
  trace_id      CHAR(32)      NOT NULL COMMENT '幂等键',
  user_id       BIGINT        NOT NULL,
  model_code    VARCHAR(50)   NOT NULL,
  prompt_tokens     INT DEFAULT 0,
  completion_tokens INT DEFAULT 0,
  total_tokens      INT DEFAULT 0,
  cost_amount   DECIMAL(10,6) DEFAULT 0,
  latency_ms    INT DEFAULT 0,
  ttft_ms       INT NULL COMMENT '首token延迟（流式）',
  success       TINYINT DEFAULT 1,
  fallback_used TINYINT DEFAULT 0,
  interrupted   TINYINT DEFAULT 0 COMMENT '流式中断/取消',
  routing_decision JSON NULL COMMENT 'v2 预留（web-design）',
  payload_ref   VARCHAR(200) NULL COMMENT '报文对象key，采样命中才填',
  gmt_create    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id, gmt_create),
  UNIQUE KEY uk_trace (trace_id, gmt_create),
  KEY idx_user_time (user_id, gmt_create),
  KEY idx_model_time (model_code, gmt_create)
) ENGINE=InnoDB
  PARTITION BY RANGE (TO_DAYS(gmt_create)) (
    PARTITION p202610 VALUES LESS THAN (TO_DAYS('2026-11-01')),
    PARTITION p202611 VALUES LESS THAN (TO_DAYS('2026-12-01')),
    PARTITION pmax    VALUES LESS THAN MAXVALUE);
```

  要点：**窄表**（无报文列，报文出库见 3.4）、**uk_trace 幂等键**（修 P4）、**月分区**（90 天前 DROP PARTITION 替代 DELETE）、**ttft/interrupted 为流式语义预留**（PR-7）。原 `t_billing_daily` 日汇总思路保留（预聚合是正确的，升级到 ClickHouse 后由物化视图替代）。
- **升级触发条件**：日增量 > 500 万行，**或**管理端聚合查询 P95 > 2s，**或**需要跨全文+聚合的混合分析 → 引入 ClickHouse（配 WSL2/Docker），写入侧只改"批量写"的落点，业务代码不动（因为机制已是 append-only + 幂等键）。

### 3.3 D5 会话历史检索 → MySQL ngram FULLTEXT（lite）/ ES 或向量库（生产档）

- **生产档**：产品级相关性搜索用 Elasticsearch；日志型检索收敛到 ClickHouse 全文/tokenbf；语义搜索走向向量库（pgvector/Milvus）。
- **本项目采用（生产-lite）**：**删除 Elasticsearch 及其 MQ 路由、es-sync 服务**（修 OD-4/OD-8）。会话内容落 `t_chat_message`（trace_id 关联 D4 窄表），检索用 MySQL ngram 全文索引：

```sql
CREATE TABLE t_chat_message (
  id         BIGINT AUTO_INCREMENT,
  trace_id   CHAR(32) NOT NULL,
  user_id    BIGINT   NOT NULL,
  role       VARCHAR(10) NOT NULL,
  content    MEDIUMTEXT NOT NULL,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id, gmt_create),
  UNIQUE KEY uk_trace_role (trace_id, role, gmt_create),
  FULLTEXT ft_content (content) WITH PARSER ngram
) ENGINE=InnoDB PARTITION BY RANGE (TO_DAYS(gmt_create)) (...同上...);
```

  中文检索由 ngram 分词覆盖；本量级下查询延迟毫秒级。
- **升级触发条件**：消息量 > 千万级、需要相关性排序/高亮/聚合面、或多实例部署 → ES（同步方式用 CDC/对账，**不是双写 MQ**）。

### 3.4 D6 全量报文 → 本地对象存储 + 采样（lite）/ S3/OSS + 生命周期（生产档）

- **生产档**：S3/OSS/MinIO + 生命周期策略；行业实例：TrueFoundry 网关日志走 Parquet 列存。
- **本项目采用（生产-lite，v1.3 修订）**：**本地磁盘目录 + `ObjectStore` 接口**（`inv/{yyyyMM}/{traceId}.json`，D4 窄表 `payload_ref` 记录命中样本的 key）。**修订原因**：MinIO 社区版自 2025-10 起仅分发源码（官方 README 声明 source-only；dl.min.io 全线 410，GitHub release 不含二进制），本机无法安全获取 exe。**采样策略**：成功请求采 10%（可配），失败/降级/中断请求 100% 落盘（排查价值高）。生命周期 30 天（脚本清理）。
- **对象存储升级路径**：`ObjectStore` 接口预留 S3 语义（put/get/presign）；迁移虚拟机阶段用源码编译 MinIO（需 Go 工具链）或换 SeaweedFS/RustFS 等 S3 兼容实现，业务代码不动。
- **升级触发条件**：合规审计要求全量留痕 → 采样率调 100% + 对象存储 WORM 锁。

### 3.5 消息管道 → 删除 RabbitMQ，Spring 事件 + 内存队列批量落库（lite）/ Kafka（生产档）

- **生产档**：Kafka/RocketMQ（大厂标配；Helicone=Kafka）。
- **本项目采用（生产-lite）**：**整体删除 RabbitMQ、死信拓扑、t_dead_letter、死信管理页**（修 OD-3/OD-8）。替代机制：
  - **D2 账务：同步写**（两段式，见第五章）——账务根本不该走异步消息，这是原设计最根本的错配；
  - **D4/D5 日志：进程内异步**——`ApplicationEvent` → `LinkedBlockingQueue` → 后台批量 INSERT（多行 VALUES，每 500ms 或 500 条 flush），`@PreDestroy` 停机 flush；
  - **明确承认丢失窗口**：日志异步层崩溃最多丢一个 flush 窗口（≤5s）的明细——账务不受影响（同步），这是有意识、有边界的取舍，写进文档。
- **升级触发条件**：多实例部署（内存队列不跨进程）、写入峰值超出批量落库能力、或出现真正的跨服务消费方 → Kafka（生产档同样要求消费端幂等，uk_trace 机制不变）。

### 3.6 D7 限流 + 配额预扣 → Redis Lua（保留）+ 两段式

- 限流：网关 Redis Lua **保留**（原项目这层是对的）。
- 配额预扣：**Redis 原子预扣 + MySQL 结算对账**（高并发档）或 **MySQL 条件更新预扣**（最简档，本项目采用——`reserved` 字段方案见第五章，少一个一致性域）。
- Sentinel **删除 GW_FLOW/接口 FLOW/热点 PARAM_FLOW 三级中的后两级**，只保留网关 Lua 一层（修 OD-5）；Sentinel Dashboard 删除。

### 3.7 D3 元数据配置 → 配置文件 + @RefreshScope + 价格版本表（lite）/ Nacos/etcd（生产档）

- **删除 Nacos 注册中心**（单体无需服务发现，修 OD-7）；Sentinel 规则 JSON 随代码提交的方案一并作废。
- 模型配置缓存：ConcurrentHashMap 永不过期 **改为 Caffeine expireAfterWrite(60s)**（修 PR-9/X-5）；单体内管理端修改后进程内直接清缓存，多实例一致性问题自然消失。
- **价格版本化**：`t_model_price` 增加 `version` 与 `effective_time`，账单记录价格版本号——改价不影响历史账单复算（生产档做法，成本极低，建议直接做）。
- **升级触发条件**：多实例部署 → 重新引入配置中心（届时选 Nacos/etcd 均可，机制不变）。

### 3.8 ID 体系 → 自增主键 + 本地雪花（lite）

- **删除 UidGenerator 与 WORKER_NODE 表**（修 OD-6/PR-12）：业务表主键自增；traceId 用本地配置 workerId 的雪花工具类（无 DB 分配）或 UUID。
- **升级触发条件**：分库分表 → 分布式 ID 服务（Leaf 类）。

### 3.9 可观测性 → Actuator + Micrometer + Prometheus + Grafana（lite）

- 保留 traceId（原项目正确起点）；新增：RED 三指标（QPS/错误率/时延直方图，含 **ttft 直方图**）、配额预扣/结算计数器、降级计数器；日志改 JSON 结构化输出。
- **升级触发条件**：多服务/多团队 → OTel 全链路 + SLO 错误预算体系。

### 3.10 交付 → Windows 脚本（主）/ Docker Compose（加分）双轨（v1.4 主轨反转）

- **反转理由**：课设硬约束为"提交代码+配置、Windows 全本机复现"，评审机不可假设存在 Docker Desktop/WSL2；交付主轨必须与验收环境一致。
- **主轨（Windows）**：`start-all-windows.bat` / `stop-all-windows.bat`（MySQL 服务探测 → Redis 按需拉起 → Ollama 探测 → 单体启动 → 前端）；README-windows 完整部署指南；`mvn spring-boot:run` 多模块错误命令修正（P9）。
- **加分轨（Compose）**：`docker-compose.yml`（app 基础镜像 eclipse-temurin:21-jre + mysql + redis + prometheus + grafana）+ GitHub Actions/Jenkinsfile（编译-测试-镜像），作为生产形态演示与简历素材，不作为验收依赖。
- 安全基线：`jwt.secret` 等全部占位符化（修 P7）；健康探针 `/actuator/health` readiness/liveness 分组；优雅停机（batch flush + in-flight 请求排空）。

---

## 四、目标架构

### 4.1 决策记录：为什么不用微服务（v1.1 补）

**决策**：六服务微服务拆分收敛为**模块化单体**；门保留，升级触发条件见第八章。

**1）收益逐项检查——微服务的收益是组织性的，本项目一项都拿不到**

| 微服务收益 | 本项目能否拿到 |
|---|---|
| 多团队独立发布 | ✗ 单人开发，一起发布更简单 |
| 独立扩缩容 | ✗ 单机部署，无扩缩容对象 |
| 故障隔离 | ✗ 实际做反：admin 单点拖垮全站转发（PR-3） |
| 技术异构 | ✗ 六服务同一套 Java/Spring，零异构 |
| 团队边界映射 | ✗ 无团队 |

收益全拿不到，成本全额支付。

**2）成本不是假设，是本项目已产出的六个实据**

1. admin 强依赖单点：`checkQuota`/`hasActiveApiKey` Feign 降级返回 false，管理后台故障即全站拒答（PR-3/X-1）；
2. 计费从本地事务降级为分布式一致性问题，产出双扣（P4）与无账（P5）两个真实缺陷，再以死信队列+人工重试工作台兑底自制的不可靠（OD-3/OD-8）；
3. 数据面变厚：每次对话 3 次跨服务 HTTP 往返（Key 校验×2 + 配额预检）；
4. 启动编排复杂：7 项中间件 + 6 个服务按序启动，需专门写等待与就绪探测；
5. 为内部调用配熔断降级：自己制造的故障面自己防（OD-5）；
6. 一次请求横跨 6 个 JVM，排障需跨进程拼日志。

**3）生产事实站在单体这边**：LLM 网关头部实现（LiteLLM、OneAPI/NewAPI、Cloudflare AI Gateway、Higress）无一为微服务集群；该领域数据面第一美德是链路短、决策快（LiteLLM 官方连路由策略都推荐最小开销的 simple-shuffle）。

**4）本质一句话**：微服务是组织问题的解决方案；单人项目上微服务，是用分布式系统的全部成本，购买一个自己并不拥有的组织问题的解法。

**5）门保留**：模块化单体不是泥球——六模块边界与单向依赖（gateway→router→billing）不变；多实例部署、跨团队协作、独立扩缩容信号真实出现时按模块拆出，届时机制替换清单（内存队列→Kafka、Caffeine→配置中心、进程内调用→RPC）见第八章。真正驱动拆分的信号只有一个：组织的边界，而不是功能的多寡。

**答辩主线**（面试口径）：拆过六服务 → 识别 admin 单点、计费一致性、厚数据面三个实锤 → 收敛为模块化单体 → 把省下的复杂度投资到幂等、流式语义与可观测性上。拆过、踩过、收敛过，比“上来就单体”或“坚持微服务”都更有说服力。

```
                            ┌────────────────────────────────────────────────┐
                            │      ai-gateway 模块化单体（1 个 JVM）           │
 客户端 ──── OpenAI 兼容 ────▶  gateway │ auth │ router │ admin │ billing      │
                            │    (JWT/  (凭证) (编排/  (管理)  (预扣-结算,同步) │
                            │    Lua限流)      流式)          │               │
                            │         内部全部进程内调用（无 Feign）           │
                            │   billing ──结算事件──▶ 日志异步层               │
                            │                        (队列+批量INSERT)        │
                            └───────┬──────────┬──────────┬─────────┬────────┘
                                    │同步(账)   │异步(日志) │点查     │
                                  MySQL 8     MySQL 分区   MinIO    Redis
                              (D1/D2/D3/D9    窄表D4/D5   报文D6   (D7/D8,
                               +分区+FULLTEXT)  (90天TTL)  (30天)    Lua/TTL)
                            + Actuator/Micrometer → Prometheus → Grafana
                            + Docker Compose（主轨）/ Windows 脚本（兜底轨）
```

**链路语义（这是与原设计最本质的区别）**：
- **同步链（强一致）**：网关鉴权 → 模型元数据点查（本地缓存）→ 配额预扣（MySQL 条件更新）→ 上游调用（超时预算内）→ 结算（同事务：实际用量、费用、usage 明细）→ 响应；
- **异步链（弱一致）**：结算成功事件 → 内存队列 → 批量写 D4 明细/D5 会话/采样报文 → 完成即返回；
- **删除的跨进程边界**：Feign×N、MQ×2、ES、Nacos——同步链从"网关+6 次 HTTP"缩为"进程内方法调用"。

### 4.2 升栈要点（Java 21 + Spring Boot 3.2，v1.2 新增）

| 项 | 处理 |
|---|---|
| Spring Cloud 全家桶 | **直接删除**（Feign/Nacos/Sentinel/Hystrix 兼容层）——既是重构目标，也扫清 Boot 3 最大兼容障碍 |
| javax → jakarta | 全量机械替换（servlet/validation/annotation） |
| MyBatis-Plus | 3.4.2 → 3.5.5+（Boot 3 兼容线） |
| jjwt | 0.9.1 → 0.12.x（parser API 重写，仅 auth 模块小改） |
| Lombok | 升 1.18.30+（JDK 21 必须） |
| Knife4j 2.0.9 | → 4.x / springdoc（Boot 3 线） |
| UIDGenerator（百度雪花） | 删除（3.8 已定，顺带消除其 JDK 21 兼容风险） |
| 虚拟线程 | `spring.threads.virtual.enabled=true`；SSE 流式处理每连接一线程（见 5.3） |
| 可观测 | Boot 3 原生 Micrometer/OTel 集成，3.9 落地更顺 |

> 升级窗口逻辑：Spring Cloud 是 Boot 3 的最大路障，而重构恰好把它清零——错过本次窗口，后续升栈成本只会更高。

---

## 五、关键机制详设（引擎无关，升级时原样迁移）

### 5.1 配额两段式（reserve / settle / reconcile）（v1.4 重写：预扣落流水表）

> v1.4 修正：原方案在 `t_user` 行上仅维护 `reserved_daily` 单字段、reconcile 引用了不存在的 `reserve_time` 列——同一用户多笔并发预扣在行上混叠，无法枚举"哪一笔过期、回滚多少"。预扣必须以独立流水表承载，回滚才有明确对象，且流水本身即预扣审计。

```sql
CREATE TABLE t_quota_reservation (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  trace_id   CHAR(32)  NOT NULL COMMENT '一次调用的预扣凭证',
  user_id    BIGINT    NOT NULL,
  est_tokens INT       NOT NULL COMMENT '预扣估算（按请求上下文长度估算，替代固定 2000）',
  status     TINYINT   NOT NULL DEFAULT 0 COMMENT '0=预留中 1=已结算 2=已回滚',
  gmt_create DATETIME  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_trace (trace_id)
) COMMENT '配额预扣流水（D2，与 t_user 同域强一致）';

-- reserve（请求前，同事务）：
BEGIN;
UPDATE t_user SET reserved_daily = reserved_daily + :est
 WHERE id = :uid AND used_daily + reserved_daily + :est <= daily_quota;   -- rows=0 → 拒绝（整单拒绝，不留脏数据）
INSERT INTO t_quota_reservation (trace_id, user_id, est_tokens) VALUES (:traceId, :uid, :est);
COMMIT;

-- settle（完成后，同事务）：结算差额、关闭预扣、落明细
BEGIN;
UPDATE t_user SET used_daily = used_daily + :actual, reserved_daily = reserved_daily - :est WHERE id = :uid;
UPDATE t_quota_reservation SET status = 1 WHERE trace_id = :traceId;      -- rows=0 → 已处理过，幂等命中，整体跳过
INSERT INTO t_invoke_log_v2 (...) VALUES (...);
COMMIT;

-- reconcile（兜底定时任务，分钟级）：抢占式回滚，防并发重复处理
UPDATE t_quota_reservation SET status = 2
 WHERE status = 0 AND gmt_create < NOW() - INTERVAL 5 MINUTE
 ORDER BY id LIMIT 100;                                                    -- 抢占：status 0→2
-- 对抢占成功的行逐笔回滚：UPDATE t_user SET reserved_daily = reserved_daily - est_tokens WHERE id = :uid;
```

- 解决：P4（重复结算——settle 以 reservation.status 抢占 + uk_trace 双重幂等）、P5（超限回滚无账——失败也结算 `success=0` 落明细）、"固定预检 2000 token"（按上下文估算）；
- settle 与 reconcile 对同一 trace_id 的并发：两处 UPDATE 在 reservation 行锁上互斥，后到者 rows=0 跳过，无双结算/无双回滚；
- 月配额同构（`reserved_monthly`），维持原"日/月同条条件更新"的正确性。

### 5.2 幂等（v1.4 宣称修正）

- **诚实边界**：分区表唯一键必须含分区键，`uk_trace (trace_id, gmt_create)` 中 `gmt_create` 为 insert 时间——**同一 trace_id 相隔 >1s 的重复写入唯一键挡不住**，它只是弱幂等（同秒内防御）；
- **单体内的真实保证来自同步链路**：M2 后结算与明细写入在同一个进程内事务中，单次调用即单次写入，不存在 MQ 重投场景；
- **显式兜底**：settle 事务内先执行 5.1 的 `UPDATE t_quota_reservation ... WHERE trace_id` 抢占，rows=0 即已处理过、整体跳过——为未来多实例/重试预留；
- 会话消息（D5）同构：写入前按 trace_id 点查。应用层捕获重复键计为幂等命中指标。

### 5.3 流式语义（PR-7 补齐）

- 客户端断开 → 释放 SSE emitter 并标记 `interrupted=1`，**取消上游读取**（OllamaClient 增加 abort 路径）→ 已产出 token 正常走 settle，明细标记 interrupted；
- TTFT：首 delta 时刻 − 请求时刻，落 `ttft_ms`，暴露 Prometheus 直方图；
- SSE 心跳（每 15s comment 帧），180s 硬超时改为可配置；
- 承载方式：**Java 21 虚拟线程（每连接一线程）**替代固定 16 线程池——阻塞式写法不变，长连接并发上限摆脱平台线程数约束。

### 5.4 保留策略汇总

| 数据 | 保留 | 手段 |
|---|---|---|
| D4/D5 明细分区 | 90 天 | 定时 DROP PARTITION（瞬间完成，无 DELETE 锁） |
| D6 报文 | 30 天 | MinIO 生命周期/清理脚本 |
| D2 汇总（t_billing_daily） | 永久 | 体积小 |
| D9 死信/未结算 | 处理完 30 天 | 定时清理 |

---

## 六、删除清单与保留清单

**删除（对应 OD/PR）**：es-sync 服务与 ES（OD-4）、RabbitMQ+死信拓扑+死信管理页（OD-3/8）、Nacos（OD-7）、Sentinel 后两级+Dashboard（OD-5）、Feign 全部（OD-2）、WORKER_NODE/UidGenerator DB 分配（OD-6）、六服务拆分（OD-1）、`t_invoke_log` MEDIUMTEXT（PR-10）、固定值配额预检。

**保留（原项目正确部分）**：MySQL、Redis Lua 限流、网关统一入口 + OpenAI 兼容、usage 实测计费、按实际应答模型计价、traceId、双 Token+黑名单+BCrypt、请求上限校验、条件更新防超扣、`t_billing_daily` 预聚合思路。

---

## 七、迁移步骤（M0–M6，顺序即依赖）

### 7.0 路线决策：移植进新主干（而非原地修或重写）

- **不重写**：auth（双 Token+黑名单+BCrypt）、admin、Vue3 前端、gateway Lua 限流、校验与错误码体系——与架构错配无关的资产直接移植（约占代码量 60%），重写零简历价值；
- **不原地修**：DB-design 仓库**原样冻结为"v1 证据"**（演进故事的前后对比素材）；Web-design 新建仓库，initial commit 标注"v1 原样移植"，此后 M1 起分幕提交；
- **借机升栈**：升 Boot 3 的最大障碍是 Spring Cloud Hoxton 不兼容——而重构本来就要删 Feign/Nacos/Sentinel，Spring Cloud 依赖随之清零，这是唯一一次低成本升栈窗口（javax→jakarta 为机械替换）；
- **先升栈、后动逻辑**：M0 只动框架层、功能一行不改，把最机械的风险前置，每步结束系统均可运行。

| 步 | 内容 | 验收标准 |
|---|---|---|
| M0 | **移植 + 升栈**：v1 代码移植进 Web-design 新仓库 → 升 Java 21 + Boot 3.2+（依赖升级见 4.2，功能一行不改）→ 冒烟跑通 | 新栈上全功能冒烟通过（**含 SSE 流式回归用例**：连接保持、分帧完整、断开标记 interrupted——Boot 2.3→3.2 的 MVC async 行为差异是 M0 最大隐性风险）；Spring Cloud 依赖清零；`java -version` 为 21 |
| M1 | **单体化**：六模块合并为一个 Spring Boot 工程，Feign 全部改进程内调用；admin 不再是转发链路依赖（配置本地缓存+降级读旧值） | 全功能冒烟通过；`admin` 进程概念消失；对话链路 0 次跨服务 HTTP |
| M2 | **两段式计费**：reserved 字段 + reserve/settle/reconcile + uk_trace 幂等；删除 MQ 计费链路 | 并发压测下无双扣/无账；kill -9 后 reconcile 回滚预扣 |
| M3 | **日志出库**：建分区窄表 D4 + t_chat_message D5 + 报文采样落本地磁盘目录（payloadRef，ObjectStore 接口）；异步批量落库；原大表只读归档 | 明细表无 MEDIUMTEXT；批量写 P99 < 50ms；停机 flush 无丢失（窗口内可容忍除外） |
| M4 | **删中间件**：ES/Nacos/Sentinel 后两级下线；检索切 ngram FULLTEXT；配置切 Caffeine+@RefreshScope | 启动中间件清单从 7 项减到 2 项（MySQL/Redis，+上游 Ollama） |
| M5 | **可观测+交付**：Micrometer 指标+Grafana 看板；Docker Compose 主轨+Windows 脚本兜底；密钥占位符化 | README 一键启动；Grafana 能看到 QPS/P95/ttft/预扣指标 |
| M6 | **web-design 预留接线**：D4 表 routing_decision 列、模型池字段（1.md FR-12）、ttft 直方图——路由层开发直接落在本选型地基上 | 1.md FR-01 可在本架构上直接实现 |

---

## 八、升级触发条件汇总（lite → 生产档的信号）

| 信号 | 动作 |
|---|---|
| 日志日增 > 500 万行或聚合 P95 > 2s | MySQL 分区窄表 → ClickHouse（机制不变：append-only+幂等键+分区+TTL） |
| 多实例部署 | Caffeine→配置中心；内存队列→Kafka；本地缓存一致性→广播失效 |
| 检索需相关性/语义 | FULLTEXT → ES / 向量库（同步走 CDC，不走双写） |
| 报文全量审计合规 | 采样 100% + 对象存储 WORM |
| 跨团队/跨服务消费方出现 | Kafka + 消费端幂等（uk_trace 不变） |

---

## 九、与 Web-design 需求基线的衔接

- 本选型书是 1.md v2.1 的**运行时地基**：路由层（RoutingEngine）作为单体 router 模块内的一个组件开发，不做新服务；
- FR-06/FR-09 的决策审计落 D4 窄表 `routing_decision` JSON 列（已是 append-only 分区表，天然承载）；
- FR-07 的"统计旁路"落 Redis（D8），由 billing 结算事件进程内驱动——1.md 中"复用 RabbitMQ 旁路"的表述**按本文修订为"结算事件进程内旁路"**，与 NFR-03 幂等语义一致；
- 1.md 3.4 最小交付集不受影响；**本文 v1.4 评审已通过，1.md 已同步升 v3.0 收口单体口径**（FR-05/07/11/13、NFR-03、8.2/8.3、第 9 章环境与部署、附录 A 全部按本文修订），并按 agent 生态目标新增 FR-14（报文透传完整性）与 8.4 Agent 兼容预留；
- 新增衔接：D4 表在 `routing_decision` 之外增加可空列 `agent_task_id`（1.md 8.1），LangGraph 等 agent 框架经 `X-Agent-Task-Id` 头透传落库，支撑"一次 agent 任务的成本归因"——网关与 agent 应用的平台化衔接点。

---

## 附录：选型依据

| 依据 | 来源 |
|---|---|
| Helicone=ClickHouse+Kafka（十亿级日志）、交易层 Postgres | helicone.ai《Complete Guide to LLM Observability 2025》、clickhouse.com 博客 |
| OpenRouter 以 ClickHouse 为日志 source of truth | clickhouse.com《How OpenRouter uses ClickHouse》 |
| LiteLLM=Postgres+Redis 单体形态 | docs.litellm.ai（2026-10 核对） |
| 网关日志 Parquet 列存 | truefoundry.com AI Gateway logging architecture |
| MySQL 分区表唯一键须含分区键 | MySQL 8.0 分区限制（本文 D4/D5 表结构已遵守） |
| 原架构错配实据 | 《DB-design-生产级标准对比评审.md》PR/OD/X 全 series |

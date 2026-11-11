# AI Gateway · 智能路由与稳定性治理平台

把大模型上游包装成一套**有账号体系、有计量计费、有流量防护、有可观测性**的 OpenAI 兼容 API 服务。
Java 21 + Spring Boot 3 模块化单体（1 JVM / 7 模块单向无环依赖），Vue 3 管理控制台。

> 课程设计项目的重构版本：从六服务 Spring Cloud 微服务收敛为模块化单体，把工程投入从「堆组件」转到可观测性、一致性与故障韧性上。前一版本的评审记录一并保留在 `backend/docs/`。

## 它能做什么

- **OpenAI 协议兼容**：`/v1/chat/completions` 支持同步 JSON 与 SSE 流式；LangChain / LangGraph / OpenAI SDK 改 `base_url` 即可直连，`X-Agent-Task-Id` 支持 agent 任务级成本归因。
- **智能路由**：请求复杂度分档 → 预算/SLA/健康度过滤 → 质量·成本加权打分自动选型；上游错误率超阈值熔断拉黑，失败自动降级并按实际应答模型计价。决策 100% 落库可回放。
- **两段式计费**：预扣 - 结算 - 对账（独立预扣流水表 + MySQL 条件更新行锁防超扣 + 状态抢占幂等 + 定时回滚超时预留）。
- **稳定性治理平台**：60s 逐秒指标窗口 / 在途请求 / 活跃流，JVM 与队列水位、Redis/MySQL 依赖探活、分级告警；Prometheus + Grafana 8 面板看板，traceId 全链路。
- **数据分层**：调用明细按月 RANGE 分区窄表（90 天 `DROP PARTITION` 归档）、会话检索走 MySQL ngram FULLTEXT、大报文采样出库存本地目录。

## 目录

| 路径 | 内容 |
|---|---|
| `backend/` | Spring Boot 3 多模块后端、`sql/` 建库脚本、`scripts/` 启停、`monitoring/config/` 监控配置唯一事实源 |
| `frontend/` | Vue 3 + TypeScript + Element Plus 管理控制台 |
| `backend/docs/` | 需求规格、重构技术选型书、生产标准对比评审、问题清单、交接文档、踩坑记录 |
| `backend/README.md` | 完整启动步骤、API 契约、核心机制说明（**先看这个**） |

## 快速开始（Windows）

```bat
cd /d backend
copy sql\seed.example.sql sql\seed.sql   :: 首次：填入自己的智谱 API Key
mysql -uroot -p < sql\init.sql
mysql -uroot -p < sql\seed.sql
mvn clean package -DskipTests
start-all.bat                            :: 后端 + 前端 + Prometheus/Grafana 一键编排
```

前置：JDK 21、MySQL 8、Redis（脚本可自启）、Node.js；上游为 OpenAI 兼容的云端模型服务，无需本地 GPU。详见 `backend/README.md`。

| 入口 | 地址 |
|---|---|
| 对话接口 | `http://localhost:10011/v1/chat/completions` |
| 接口文档 | `http://localhost:10011/swagger-ui.html` |
| 管理控制台 | `http://localhost:3000`（admin / admin123） |
| Grafana | `http://localhost:3300` |

## 凭据约定

仓库**只保留脱敏模板** `backend/sql/seed.example.sql`。含真实 API Key 的 `seed.sql`、`dump.rdb`、日志与监控中间件二进制均由 `.gitignore` 排除。
MySQL / Redis / JWT / 上游 Key 请在本地用环境变量覆盖；默认账号与脚本内的本机口令仅为课设演示值，登录后请立即改密，勿用于任何共用或生产环境。

## 验证记录

- 4 波 ×5 并发 + 双路 SSE 共 23 次调用 100% 成功；runtime 指标 / Prometheus / 调用明细 / 日汇总**四路对账一致**。
- Grafana 看板 10 条 PromQL 与 `/actuator/prometheus` 实际暴露指标逐条对齐；系统设置中心 11 项断言全过（含重启持久化）。

本项目为课程设计 / 学习用途。

# 可观测性设计

本文档描述 WGH RAG System 的指标（Metrics）、追踪关联（Tracing/Correlation）、日志规范与告警建议。
所有能力默认开启，不依赖外部 APM；生产通过独立管理端口（默认 `9090`）暴露给 Prometheus。

---

## 1. 总体方案

| 维度 | 方案 | 落地位置 |
| --- | --- | --- |
| 指标 | Micrometer + Prometheus registry | `rag-common` 的 `RagMetrics` 统一埋点；`rag-app` 引入 `micrometer-registry-prometheus` |
| 抓取 | 独立管理端口 `/actuator/prometheus` | `management.server.port`（默认 9090） |
| 请求关联 | `X-Request-Id` → MDC → 日志 / SSE / 异步线程 | `RequestIdFilter` + `MdcContext` + `TaskDecorator` |
| 日志 | 单行文本，包含 `[rid=...]` | `logback-spring.xml` |
| 可视化 | Grafana 预置仪表盘 | `deploy/grafana/dashboards/wgh-rag-overview.json` |
| 告警 | Prometheus rules（建议） | 见 §6 |

设计约束：

- **有限标签**：指标标签只允许来自固定枚举（route / stage / provider / dimension / status / nodeType），禁止写入 `userId`、`windowId`、`sessionId`、`token` 等高基数值。
- **埋点失败不影响主流程**：指标写入为内存计数，异常不向上抛。
- **默认关闭的功能不额外开销**：未启用的检索/工作流分支不会产生对应指标。

---

## 2. 指标字典

统一前缀 `wgh_`；`application="wgh-rag-system"` 为全局公共标签（`management.metrics.tags.application`）。

### 2.1 对话

| 指标 | 类型 | 标签 | 含义 |
| --- | --- | --- | --- |
| `wgh_chat_requests_total` | Counter | `route` = `rag` / `agent` / `workflow` / `cache`；`share` = `true` / `false` | 完成的对话请求数（按最终走的链路分类） |
| `wgh_chat_duration_seconds` | Timer（含 histogram） | `route`、`share` | 单次对话端到端耗时（请求进入到 SSE 链路结束） |
| `wgh_chat_first_token_seconds` | Timer（含 histogram） | `route` | 首 token 延迟（进入请求到第一个流式片段） |
| `wgh_chat_errors_total` | Counter | `stage` = `retrieve` / `llm` / `stream` / `agent` | 各阶段失败次数 |

### 2.2 检索

| 指标 | 类型 | 标签 | 含义 |
| --- | --- | --- | --- |
| `wgh_retrieval_duration_seconds` | Timer（含 histogram） | `hybrid`、`rerank` = `true/false` | 多知识库联合检索耗时 |
| `wgh_retrieval_hits_total` | Counter | `hit` = `true/false` | 检索是否有结果（用于命中率） |
| `wgh_retrieval_errors_total` | Counter | 无 | 检索抛异常次数 |

### 2.3 LLM

| 指标 | 类型 | 标签 | 含义 |
| --- | --- | --- | --- |
| `wgh_llm_calls_total` | Counter | `provider`（模型提供商枚举） | 完成计费的 LLM 调用次数 |
| `wgh_llm_tokens_total` | Counter | `type` = `input` / `output` | token 用量 |
| `wgh_llm_cost_total` | Counter（浮点） | 无 | 按价格表估算的成本（元） |

### 2.4 配额与公开访问

| 指标 | 类型 | 标签 | 含义 |
| --- | --- | --- | --- |
| `wgh_quota_exceeded_total` | Counter | `type` = `chat` / `token` | 配额拦截次数 |
| `wgh_public_access_requests_total` | Counter | `dimension` = `share-token` / `share-ip` / `share-chat` / `share-kb` / `share-embed` / `api-key` / `api-ip` | 公开访问限流维度被检查的次数 |
| `wgh_rate_limit_rejected_total` | Counter | `dimension` 同上 | 触发限流拒绝（429）次数 |

### 2.5 工作流

| 指标 | 类型 | 标签 | 含义 |
| --- | --- | --- | --- |
| `wgh_workflow_runs_total` | Counter | `status` = `completed` / `failed` / `paused` | 工作流运行结束状态（含子工作流） |
| `wgh_workflow_node_failures_total` | Counter | `nodeType` = `LLM` / `TOOL` / `RETRIEVAL` 等 | 节点执行失败次数 |

此外 `/actuator/prometheus` 还包含 Micrometer 自动采集的 JVM（堆/GC/线程）、HikariCP 连接池、HTTP 服务端延迟等标准指标。

---

## 3. 埋点位置

| 能力 | 类 / 方法 | 说明 |
| --- | --- | --- |
| 对话请求/耗时/首 token/错误 | `RagChatService#streamChat`、`#streamChatWithTools`、`#settleCostAndQuota` | 在 SSE 子线程统一用 `Timer.Sample` 计时，`finally` 记录请求数与耗时；路由分支处回填 `route` |
| 检索 | `RagRetrieveService#searchMultiple` | 包装原检索实现，正常/异常路径都记录耗时，命中按结果集是否为空 |
| LLM token/成本 | `RagChatService#settleCostAndQuota` | 与现有计费落库同一处，避免重复统计 |
| 配额 | `QuotaService#checkAndIncrementChat` | 抛出 429 前按 chat/token 分类计数 |
| 公开访问/限流 | `PublicAccessRateLimiter#acquire` | 每次维度检查计请求量，拒绝时计 rejected |
| 工作流 | `WorkflowEngine#executeOneNode`、`#finishRun`、`#returnFromHitl` | 节点失败、运行完成/失败/暂停 |

---

## 4. 追踪与请求关联

### 4.1 请求 ID 规则

- 入站读取 `X-Request-Id`，合法格式为 `[A-Za-z0-9._-]{8,64}`；
- 缺失或非法（含 CRLF/空白等头注入字符）时生成 UUID；
- 响应头回写同一个值，客户端可据此关联服务端日志；
- 写入 MDC 键 `requestId`，logback 输出 `[rid=...]`；请求结束后 `finally` 清理，避免线程池复用串号。

### 4.2 异步与 SSE 透传

- `RequestIdFilter` 同时参与 `ASYNC` / `ERROR` 分发，SSE 完成阶段仍能关联原请求；
- 自建线程（聊天 SSE、多 Agent 运行、工作流调度）使用 `MdcContext.wrap(...)`；
- Spring 线程池（`applicationTaskExecutor`、`workflowExecutor`）通过 `TaskDecorator` 自动透传。

### 4.3 跨服务追踪

当前未接入 OpenTelemetry/W3C `traceparent`。如需与上游网关/APM 串联，可在 `RequestIdFilter` 中读取 `traceparent` 并写入 MDC 的 `traceId`，同时保留 `X-Request-Id` 作为业务关联 ID；不影响现有日志格式与指标。

---

## 5. 日志规范

- 格式：`时间 [线程] [rid=requestId] 级别 logger - 消息`
- 级别：业务模块默认 `INFO`（可通过 `WGH_LOG_LEVEL` 调整），第三方框架 `WARN/INFO`；
- 敏感信息：分享 token、嵌入 grant、API Key 等**不得明文输出**，统一走 `LogSanitizer.maskSecret`（保留首尾 4 位）；
- 高频路径：检索、逐 chunk 细节使用 `DEBUG`，生产默认不输出；
- 落盘：`prod` 写入 `/var/log/wgh-rag`，按大小/天数滚动（100MB / 30 天 / 10GB 上限）。

---

## 6. 告警建议

| 告警 | 表达式（示例） | 阈值建议 |
| --- | --- | --- |
| 对话错误率升高 | `sum(rate(wgh_chat_errors_total[5m])) / clamp_min(sum(rate(wgh_chat_requests_total[5m])), 1e-9)` | > 5% 持续 10 分钟 |
| 首 token 延迟劣化 | `histogram_quantile(0.95, sum by (le) (rate(wgh_chat_first_token_seconds_bucket[5m])))` | > 5s 持续 10 分钟 |
| 检索命中率下降 | `sum(rate(wgh_retrieval_hits_total{hit="true"}[15m])) / clamp_min(sum(rate(wgh_retrieval_hits_total[15m])), 1e-9)` | < 0.5 持续 30 分钟 |
| 检索异常 | `rate(wgh_retrieval_errors_total[5m])` | > 0 持续 5 分钟 |
| 限流拒绝激增 | `sum(rate(wgh_rate_limit_rejected_total[5m]))` | 较基线突增 5 倍 |
| 配额超限激增 | `sum(rate(wgh_quota_exceeded_total[5m]))` | 较基线突增 5 倍 |
| 工作流失败率 | `sum(rate(wgh_workflow_runs_total{status="failed"}[10m])) / clamp_min(sum(rate(wgh_workflow_runs_total[10m])), 1e-9)` | > 10% |
| LLM 成本异常 | `sum(rate(wgh_llm_cost_total[1h])) * 3600` | 超过小时预算 |
| 管理端口不可达 | `up{job="wgh-rag-backend"} == 0` | 持续 2 分钟 |

告警落点建议：Prometheus Alertmanager → 飞书/邮件；生产按 P1（服务不可用）/P2（错误率与延迟）/P3（成本与配额）分级。

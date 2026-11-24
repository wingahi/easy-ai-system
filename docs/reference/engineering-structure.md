# 工程结构与技术栈

> 本文是 [README](../../README.md) 架构与技术栈章节的完整版，描述 **2026-11-24 现行**的模块划分、依赖约束、关键设计与技术栈。
>
> 与 [总体设计](../design/design-overview.md) 的分工：本文负责“代码怎么分模块、依赖朝哪走、用了什么技术”；总体设计负责认证、对话、Agent、工作流、公开访问、存储、可观测性等能力的运行时设计与关键流程。总体设计中的 §2.1 技术栈、§3.1 模块清单为 2026-11-03 基线，模块数量与技术选型以本文为准。

## 模块结构

`rag-backend` 共 24 个 Maven 模块（1 个父 POM + 23 个子模块）。

```text
wgh-rag-system/
├── rag-backend/                  # Spring Boot 多模块（Java 21，23 个子模块 + 父 POM）
│   ├── rag-common/               # 公共契约与基础配置：Result / 异常 / 指标 / 安全契约 / 分享端口 / 执行器配置
│   ├── rag-execution/            # 零依赖执行契约：JobHandler / RunAdapter / 车道 / 事件 / sink
│   ├── rag-data/                 # BaseEntity / MyBatis-Plus 配置 / 类型处理器
│   ├── rag-log/                  # @Audit 切面与审计日志
│   ├── rag-auth/                 # OIDC 资源服务器 + 本地登录 + 用户/角色/权限 + 用户信息 Redis 缓存
│   ├── rag-quota/                # 配额、计费口径（无产出不计费）、扣费流水、退款补偿与人工退款/核销
│   ├── rag-config/               # 配置属性映射
│   ├── rag-context/              # 上下文预算装配（按类型 + 优先级）
│   ├── rag-storage/              # 可插拔文件存储：local / 阿里云 OSS
│   ├── rag-llm/                  # 动态模型、厂商工厂、缓存热更新、模型路由 / 故障转移
│   ├── rag-document/             # 文档解析（12 格式 / PDF 版面 / OCR / 表格）与 9 种切分
│   ├── rag-kb/                   # 知识库 / 文件生命周期、入库编排、向量化与检索增强
│   ├── rag-eval/                 # 评测数据集版本化、召回 / 生成侧评测（依赖 rag-kb）
│   ├── rag-chat/                 # 窗口/会话、上下文压缩、流式 RAG（传输无关）、SSE 事件重放、分享、反馈
│   ├── rag-tool/                 # HTTP / MCP / Skill / 函数调用工具
│   ├── rag-sandbox/              # Python / JavaScript 代码沙箱（默认关闭）
│   ├── rag-agent/                # 单 Agent：Planner / Executor / Reflection / HITL
│   ├── rag-workflow/             # 工作流引擎 + WORKFLOW_RUN 后台作业 Handler
│   ├── rag-agent-multi/          # 多 Agent：Crew / Graph / GroupChat / runtime
│   ├── rag-scheduler/            # 调度层：三车道、准入、作业表、租约、单飞、周期提交、实例心跳/失联回收/对账
│   ├── rag-public-access/        # 分享 / Widget / 开放 API 实现层（无 Controller）
│   ├── rag-controller/           # REST 适配器：Controller + SSE sink（不含业务实现）
│   ├── rag-app/                  # 启动模块 + application*.yml + 集成测试 + 显式装配
│   ├── config/                   # 配置增量片段（config/migrations/，非自动加载）
│   ├── sql/                      # schema/01-schema.sql（47 张表）/ seed/02-data.sql / migrations/
│   ├── eval/                     # eval_recall.py（Recall@K）、eval_rag.py（含 LLM Judge）
│   └── scripts/                  # e2e / 冒烟 / 压测脚本
├── deploy/                       # K8s 清单（base / K1 / K2 / K3）、Prometheus 告警规则、Grafana 面板与观测栈
├── rag-frontend/                 # Vue 3 前端（含 public/widget.js）
└── docs/                         # 设计与测试文档（索引见 docs/README.md）
```

## 关键依赖约束

- `rag-common` 不依赖任何内部模块，也不含实体 / Mapper / MyBatis 配置；它同时承载公共契约、跨模块端口（`ChatShareTargetPort` / `KnowledgeBaseShareTargetPort`）与统一异步执行器配置；
- `rag-execution` 是零依赖契约层，只定义 `JobHandler` / `RunAdapter` / 车道 / 事件 / `ExecutionStreamSink` 等类型，不依赖任何业务模块；
- `rag-scheduler` 只依赖 `rag-execution`、`rag-data`、`rag-config` 与基础库，不依赖任何执行域实现；执行域模块只依赖 `rag-execution`，不依赖 `rag-scheduler`；
- 执行域偏序为 `rag-agent → rag-workflow → {rag-chat, rag-agent-multi}`，`rag-chat` 与 `rag-agent-multi` 互不依赖；
- `rag-public-access` 依赖方向固定为 `rag-controller → rag-public-access`，并与 `rag-chat` / `rag-kb` 通过 `rag-common` 端口解耦，由 Enforcer 禁止反向依赖；
- `rag-storage` 仅依赖 `rag-common`，上层业务通过 `FileStorageService` 抽象使用；
- `rag-auth` 持有用户 / 角色 / 权限实体与 Mapper，供 Controller 的 RBAC 管理接口使用；
- `rag-kb` 已不再承载解析 / 切分与评测：解析 / 切分在 `rag-document`，评测在 `rag-eval`（`rag-eval → rag-kb`）；
- `rag-controller` 仅做 REST / SSE 适配，不包含业务实现；SSE 适配器 `SseExecutionStreamSink` 只在此层出现。

更完整的模块依赖与边界说明见 [design-overview.md](../design/design-overview.md) 与 [design-detailed.md](../design/design-detailed.md)。

## 关键设计

- **单库承载业务与向量**：PostgreSQL + pgvector 省去独立向量数据库；pgvector 不可用时不阻断应用启动，但向量检索相关能力不可用。
- **Redis 可选**：限流、分享密码锁定、嵌入授权可集中到 Redis；不可用时自动降级为单机内存，不阻断启动。
- **SSE 统一协议**：聊天、Agent、工作流、多 Agent 共用事件风格；`content` 事件使用 JSON 包装文本，避免换行丢失，前端状态复位以事件驱动。
- **传输无关事件出口**：执行域只依赖 `ExecutionStreamSink`；SSE 适配器 `SseExecutionStreamSink` 留在 `rag-controller`，聊天侧用 `ChatEventPublisher` / `ChatStreamHandle` 管理事件与取消，后台作业与交互式链路可复用同一套事件契约。
- **统一调度中心**：`rag-scheduler` 提供三车道、`wgh_execution_job` 作业表、租约与 Redis 单飞；后台型作业走 `JobHandler`，周期型作业实现 `RecurringJob` 按时间桶提交；`wgh.scheduler.enabled=false` 可整体关闭，交互式聊天不受影响。
- **无产出不计费**：计费以 `StreamBillingObserver` + `ChatTurnContext.markOutput()` 的「有效产出」信号为准，首个非空内容成功下发才结算，全程无产出立即退款；对话与 HITL 续跑用 `afterCommit` 提交闸门避免异步早于事务提交，`usage_epoch` 保证退款只回退自己那一笔。
- **多副本调度语义**：`wgh_scheduler_instance` 记录实例心跳，失联实例（心跳超过 45s）持有的作业提前回收而不等租约自然到期；启动先接管同 `instance-id` 残留；`SCHEDULER_RECONCILE` 只对账业务状态，自动修复范围限定为可证明安全的 `PENDING` 排队作业。
- **SSE 可重放**：事件出口经 `StreamReplayService` 写 Redis 缓冲并分配序号，重连按 `Last-Event-ID` 补齐；客户端断开只停止推送，不再取消执行或把 Agent / 多 Agent 运行标记为失败。
- **用户信息缓存**：鉴权按 `userId` 读 Redis 缓存（TTL 30 分钟、分布式锁回填、写后删除），Redis 故障降级直读数据库。
- **优雅停机与探针**：`server.shutdown=graceful` + 45s 停机阶段，`liveness` 只看进程活性、`readiness` 聚合 `db`，与 `deploy/k8s/` 探针路径一致。
- **跨线程与跨实例追踪上下文**：HTTP 请求由 Brave 生成 `traceId`；线程池通过 `ContextPropagatingTaskDecorator` 同时透传 MDC 与 Observation；调度作业提交时把 `traceId` 写入 `wgh_execution_job.trace_id`，执行实例恢复后，看门狗与交互式车道延续同一上下文。
- **多实例 JWKS 共享**：保留进程内 L1 缓存；`wgh.auth.oidc.jwks-shared-cache-enabled` 开启时增加 Redis L2（按 `sha256(jwksUri)` 定位、存原始 JWKS JSON），冷启动/过期先读 L2，未知 `kid` 先重读 L2 再回源并回写；Redis 异常全部降级本地，关闭时与改造前一致。
- **先落库后推送**：Agent / 工作流执行结果先持久化再发送 SSE，刷新页面后仍可恢复。
- **多环境配置**：dev / test / prod 分离；生产敏感配置通过环境变量注入。
- **逻辑删除**：所有删除走 `deleted` 字段，便于审计与恢复。

## 技术栈

| 层次 | 技术 |
|------|------|
| 前端 | Vue 3 · Vite · Pinia · Vue Router · Element Plus · SCSS · marked |
| 后端 | Spring Boot 3.4 · Java 21 · Maven 多模块 · Spring Security |
| 数据 | PostgreSQL 15+ · pgvector · MyBatis-Plus（逻辑删除 + 分页） |
| LLM | LangChain4j · OpenAI / DASHSCOPE / DeepSeek / 自建 OpenAI 兼容端点 |
| 工具 | LangChain4j Tool Calling · MCP（stdio / Streamable HTTP）· HTTP · 本地反射 · Skill |
| Agent | Planner / StepExecutor / Reflection / HITL；多 Agent 含 Crew / Graph / GroupChat |
| 工作流 | 自研 DAG 引擎：12 类节点、并行 fan-out/fan-in、版本快照、CRON / Webhook |
| 调度 | 自研 `rag-scheduler`：交互式 / 后台 / 维护三车道，`wgh_execution_job` 作业表 + 租约，Redis 单飞，周期作业按时间桶提交；多副本实例心跳 / 失联回收 / 作业对账 |
| 可观测 | Prometheus 指标 + Micrometer Tracing（Brave）：本地生成 `traceId` / `spanId`，线程池与调度作业透传上下文，日志输出 `[trace=...]`；`deploy/prometheus/rules/` 告警规则与 `deploy/grafana/dashboards/` 面板 |
| 鉴权 | 外部 OIDC token（JWKS 非对称公钥验签；进程内 L1 + 可选 Redis L2 共享）+ 本地会话凭证 + RBAC + API Key（SHA-256） |
| 评估 | 检索侧 Recall@K / Precision@K / MRR / NDCG；生成侧 LLM-as-Judge |
| 测试 | JUnit 5 + Mockito；shell e2e / 冒烟 / 压测；离线召回率评测脚本 |

---

[返回 README](../../README.md)

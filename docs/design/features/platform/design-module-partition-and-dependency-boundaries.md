# 模块职责重划分与依赖边界（面向统一调度中心切入与多实例部署）

> **版本**：v1.3
> **日期**：2026-11-20
> **修订**：v1.3 记录 P1 依赖净化落地（声明漂移清理、`SsrfGuard` 下沉、Enforcer 规则）并复核两项待办；v1.2 新增 §4.8（P0 `rag-execution` 契约模块落地与命名取舍）；v1.1 新增 §4.7（`rag-controller` 按业务域分包）；v1.0 初稿。
> **性质**：分析与方案文档。不修改业务代码、不移动包、不改配置、不执行 DDL、不做 git 操作。
> **范围**：`rag-backend` 下 19 个 Maven 模块的职责、依赖方向、职责重叠，以及为「统一调度中心」切入所需的目标划分与搬迁路径。
> **复用来源（结论以此为准，本文不重新论证）**：
> - [design-execution-scheduler-center.md](design-execution-scheduler-center.md)：五类执行体、S0–S3 排期、作业类型
> - [design-unified-scheduler-and-tenant-policy.md](design-unified-scheduler-and-tenant-policy.md)：三 lane、租户策略、作业表、准入
> - [design-distributed-tenant-scheduling.md](design-distributed-tenant-scheduling.md)：A–D 合并阶段、集群口径、独立服务升级条件
> - [design-multi-tenancy-isolation-roadmap.md](design-multi-tenancy-isolation-roadmap.md)：阶段 0–4、`TenantContext` 传递
> - [design-overview.md](../../design-overview.md) §3：现行 17 模块清单、分层边界与已有 Enforcer 约束
> **未验证/推断项**：见 §9

---

## 0. 结论摘要

1. **编译期无环，但分层只到「层」，没到「次序」**。现有模块图是一张 DAG（无 Maven 环、无模块直接 import `rag-controller`），但「编排层」内部 4 个模块（`rag-chat` / `rag-agent` / `rag-workflow` / `rag-agent-multi`）互相依赖，没有任何声明式的先后次序；同时存在 3 处「声明了依赖却完全没用」的漂移（`rag-chat→rag-context`、`rag-workflow→rag-context`、`rag-agent-multi→rag-chat`），已于 P1 清理（见 §5 P1 落地结果）。
2. **真正阻碍调度中心切入的不是模块数量，而是「执行」这件事没有归属模块**：
   - 执行入口分散在 7 个 Controller / 6 个 Service（§3 B2）；
   - 执行发生在 HTTP 请求线程（单 Agent HITL、评测）或裸 `new Thread`（聊天、多 Agent）；
   - 运行态存在进程内 `Map`（工作流 emitter、存储迁移任务、恢复 in-flight、多 Agent 黑板）；
   - 没有「作业」抽象，也没有统一的提交/查询/取消契约，恢复逻辑落在 `rag-controller` 的 1033 行 `TaskRecoveryService` 里直接 switch 三种引擎。
3. **目标划分采取「加两层、拆两刀、纯库化三个模块」**：
   - **新增 2 个横切模块**：`rag-execution`（作业抽象与执行契约，纯库）、`rag-scheduler`（准入 / lane / 租约 / 策略实现，库）。
   - **拆 2 个模块**：`rag-kb` → `rag-kb` + `rag-document`（解析/切分）+ `rag-eval`（评测）；`rag-controller` → 只保留 HTTP/SSE 适配，`TaskRecoveryService` 拆为 `RunCoordinator` + 各域 adapter。
   - **纯库化 3 个模块**：`rag-kb`、`rag-public-access`、`rag-tool`（去掉对 web 类型与 web starter 的依赖）。
   - **合并 3 处职责**：定时触发并入 `rag-scheduler`（CRUD 留在 `rag-workflow`）；`public-access` 的 servlet/security 适配并入 `rag-controller`；`rag-chat.evaluation` 在 rag-chat 重构合流后并入 `rag-eval`。
4. **依赖方向从「层内无序」收紧为「层内偏序」**：`agent → workflow → chat`、`agent → workflow → agent-multi`；执行域模块**只依赖 `rag-execution` 的接口，不依赖 `rag-scheduler`**；`rag-scheduler` **只依赖 `rag-execution`，不依赖任何 Agent/工作流实现**。这是让调度中心「切口最小」的关键约束。
5. **多实例口径**：`INTERACTIVE` 允许实例内（必须标注「单实例上限」），`BACKGROUND` 一律 DB 租约 + `FOR UPDATE SKIP LOCKED`，`MAINTENANCE` 一律 advisory lock；业务状态、取消标记、触发器归属、运行快照四类状态必须离开进程内。当前只有 `StorageMaintenanceLock`（单飞）与 `ChatCancellationStore`（跨实例取消标记）两处具备集群语义，其余都是「单实例正确、多实例重复」。
6. **搬迁分 P0–P4 五步，每步可编译、可回滚**；P0/P1 完全不触碰业务行为。**`rag-chat` 为冻结区**：只有 P2 的一处线程创建点与配额顺序是合流门，必须等 rag-chat 内部重构合并后再落（§6.3）。
7. **最大风险**：`rag-chat` 正在另一条线做内部重构（当前工作区已可见 `ChatSsePublisher`、`ChatMessageStore`、`ChatSessionStore`、`ChatContextBudgetService`、`ChatEvaluationService` 等 9 个新类，`RagChatService` 净减约 900 行），而它同时是「裸线程」「配额先于准入」「SSE 直接发」三处缺陷的所在地。若在合流前改执行入口，会产生双重改动冲突；若延后，则调度中心的 S0 验收（A13「无裸线程」）无法闭环。缓解方式见 §6.3。

---

## 1. 范围、方法与约定

### 1.1 本次做了什么

- 逐个模块读取 `pom.xml`，并用「实际 import 扫描」校正声明依赖（`com.wgh.rag.<pkg>` 前缀统计），得到 §2.2 的双列矩阵。
- 逐个读取执行相关入口类：`RagChatService`、`ChatWorkflowService`、`AgentOrchestrator`、`WorkflowService`、`WorkflowTriggerService`、`MultiAgentRuntime`、`MultiAgentController`、`AgentController`、`TaskRecoveryService`、`EvalDatasetService`、`ConnectorScheduler`、`UploadSessionCleaner`、`StorageMigrationJobService`、`StorageMaintenanceLock`、`KnowledgeFileService`、`MigrationJobStateStore`、`ChatCancellationStore`。
- 统计 27 个 `@RestController`、244 个 handler 注解、25 个类级 `@RequestMapping`。

### 1.2 判定口径（复用，不新造）

- lane 归属、执行体编号 J1–J10、阶段 S0–S3 / A–D 一律沿用 [design-execution-scheduler-center.md](design-execution-scheduler-center.md) 与 [design-unified-scheduler-and-tenant-policy.md](design-unified-scheduler-and-tenant-policy.md)。
- 分层名称沿用 [design-overview.md](../../design-overview.md) §3.4（启动层 / 接入层 / 编排层 / 领域层 / 基础层），本文在其上增加「执行契约层」与「调度层」，并把「编排层」改称「执行域层」以对齐调度语义。

### 1.3 本文新增术语（只用于描述目标态）

| 术语 | 含义 |
|---|---|
| 执行体（executor） | 真正推进任务的代码单元：对话流、Agent plan、工作流 run、多 Agent run、评测、入库、维护任务 |
| 执行契约 | 与具体执行体无关的接口集合：提交 / 查询 / 取消 / 事件 sink / 状态快照 |
| 执行域模块 | 实现某类执行体的业务模块（chat / agent / workflow / agent-multi / kb / document / eval） |
| 触发者（trigger） | 周期性或事件性发起执行的一方：CRON、连接器轮询、清理任务、Webhook |
| 冻结区 | 本方案不修改代码的模块（当前仅 `rag-chat`） |

---

## 2. 现状：职责、入口与依赖

### 2.1 模块职责与关键入口

> 「行数」为 `src/main/java` 下 `*.java` 的物理行统计，用于衡量搬迁成本，不代表复杂度。

| 模块 | 文件/行 | 关键入口类 | 对外能力（被谁用） | 主要问题 |
|---|---|---|---|---|
| `rag-common` | 19 / 1069 | `Result`、`ResultCode`、`BusinessException`、`SecurityUtils`、`LoginUser`、`MdcContext`、`MdcAsyncConfig`、`RagMetrics`、`DomainEventPublisher`、`GlobalExceptionHandler`、`RequestIdFilter`、`OpenApiConfig` | 全部模块 | 纯契约与 web/security 混装（`spring-boot-starter-web` + security），导致「执行库」天然带 web 类型 |
| `rag-data` | 11 / 610 | `BaseEntity`、`MybatisPlusConfig`、`DataScopeInterceptor`、`DataScopeGuard` | 全部持久化模块 | `BaseEntity` 无 `version` 字段；乐观锁需新表自带 |
| `rag-config` | 11 / 846 | `RagProperties`、`PgVectorConfig`、`RagRedisConfig`、`RedisCommandPort`、`RedisDistributedLock`、`RagEvaluationProperties` | llm / kb / tool / agent / workflow / chat / public-access | Redis 端口已是「可降级」范式，可被调度中心复用 |
| `rag-context` | 3 / 253 | `ContextAssembler`、`ContextSegment`、`SegmentType` | 仅 `rag-agent`、`rag-agent-multi` | 纯算法库，但被 3 个模块无理由声明依赖 |
| `rag-storage` | 16 / 1475 | `FileStorageService`、`LocalFileStorageService`、`AliyunOssFileStorageService`、`StorageKeys`、`StorageMigrationService` | `rag-kb`、`rag-controller`（经 kb） | 迁移服务自带线程池与进度状态，属执行体而非存储抽象 |
| `rag-log` | 6 / 260 | `@Audit`、`AuditAspect`、`AuditLogService`（`@Async`） | auth / controller / 各写操作 | `@Async` 用 Boot 默认执行器，未纳入受管池 |
| `rag-auth` | 29 / 2172 | `SecurityConfig`、`OidcAuthenticationFilter`、`LocalTokenService`、`PermissionAspect`、`JwksProvider` | `rag-controller`、`rag-public-access` | 认证上下文为 `ThreadLocal`，异步边界需显式传递 |
| `rag-quota` | 11 / 660 | `QuotaService`、`EffectiveQuota`、`QuotaAdminGuard` | `rag-chat`、`rag-controller` | 「先查后加」非原子；与调度准入无顺序约定 |
| `rag-llm` | 36 / 2605 | `ModelRouter`、`ChatModelFactoryRegistry`、`ModelCacheManager`、`ModelPriceService`、`TokenEstimator` | kb / agent / workflow / chat / controller | 进程内模型缓存（可接受，平台级） |
| `rag-tool` | 26 / 3067 | `ToolExecutionEngine`、`HttpToolExecutor`、`McpToolExecutor`、`FunctionCallingToolExecutor`、`ToolInvoker`、`ToolCircuitBreaker`、`SkillLlmClient`（`SsrfGuard` 已于 P1 下沉到 `rag-common.net`） | chat / agent / workflow / agent-multi / sandbox | P1 已移除未使用的 `spring-boot-starter-web` 与 `rag-context` 声明；熔断器为进程内状态 |
| `rag-kb` | 106 / 11443 | `KnowledgeBaseService`、`KnowledgeFileService`、`RagRetrieveService`、`SemanticCacheService`、`QueryTransformService`、`RerankService`、`EvalDatasetService`、`RetrievalEvaluationService`、`ConnectorService`、`ConnectorScheduler`、`UploadSessionCleaner`、`AgentMemoryService`、`AgentMemoryCleaner`、`StorageMigrationJobService`、`StorageOrphanCleanupScheduler`、`VectorStorePort`、`KeywordSearchPort`、`parser/*`、`splitter/*` | chat / agent / workflow / agent-multi / public-access / controller | 一个模块承担 7 类职责（KB CRUD / 文档流水线 / 检索 / 缓存 / 评测 / 连接器 / 存储维护）；含 4 个每实例调度器（连接器、上传清理、记忆清理、孤儿清理）+ 1 个静态线程池 |
| `rag-chat` | 41 / 5738（重构进行中） | `RagChatService`、`ChatWorkflowService`、`ChatWindowService`、`ContextCompressionService`、`PromptTemplateService`、`FeedbackService`、`LlmJudgeService`、`RetrievalMetricsCalculator`、`ChatCancellationStore`、`ChatImageService`、`SkillLlmClientImpl`；重构新增 `ChatSsePublisher`、`ChatMessageStore`、`ChatSessionStore`、`ChatContextBudgetService`、`ChatEvaluationService`、`ChatCostSettler`、`ChatPromptAssembler`、`ChatToolLoader`、`ChatCitationAssembler` | controller / public-access | **冻结区**：裸线程、配额先于准入、`SseEmitter` 出现在 service 签名 |
| `rag-agent` | 20 / 2423 | `AgentOrchestrator`、`Planner`、`StepExecutor`、`ReflectionEngine`、`HitlGate`、`PlanLifecycleService`、`AgentTaskMemory` | chat / workflow / controller | 计划执行无并发上限；恢复入口依赖调用方同步等待 |
| `rag-workflow` | 45 / 4650 | `WorkflowEngine`、`GraphValidator`、`NodeExecutorRegistry`、14 个 `*NodeExecutor`、`WorkflowService`、`WorkflowTriggerService`、`WorkflowTemplateRegistry` | chat / agent-multi / controller | 交互/后台混池、先插 RUNNING 后提交、emitter Map、每实例 CRON、每 run 建 4 worker |
| `rag-agent-multi` | 30 / 4190 | `MultiAgentRuntime`、`CrewCoordinator`、`GraphCoordinator`、`GroupChatCoordinator`、`AgentStepCheckpointService`、`InMemoryBlackboard` | controller | 每 run 裸线程 + 每 run 4–8 worker；黑板进程内 |
| `rag-public-access` | 23 / 2218 | `ShareLinkService`、`ApiKeyService`、`EmbedAccessService`、`PublicAccessRateLimiter`、`DefaultPublicAccessStore`、`ApiKeyAuthenticationFilter`、`PublicAccessSecurityConfig`、`PublicAccessExceptionHandler` | controller | 领域库内混装 servlet filter / security config / `HttpServletRequest` 解析 |
| `rag-controller` | 35 / 6689 | 26 个 Controller（244 个 handler）、`TaskRecoveryService`（1033 行）、`RunAccessGuard`、`openai/*` 适配 | `rag-app` | 接入层承载运行时编排与 Mapper 直连；执行入口分散 |
| `rag-app` | 1 / 24 | `RagApplication` | — | 装配是「传递依赖」而非显式组合；无 `server.shutdown=graceful` |
| `rag-sandbox` | 15 / 1219 | 代码执行沙箱（默认关闭） | controller | 与调度无直接关系，本次不调整 |

### 2.2 依赖矩阵

> 行 = 依赖方，列 = 被依赖方。**D** = pom 中显式声明；**U** = 实际 import 命中；`D` 但无 `U` 即「声明漂移」。

| ↓依赖 / 被依赖→ | common | data | config | context | storage | log | auth | quota | llm | tool | kb | chat | agent | workflow | multi | public | ctrl | app |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `rag-common` | — | | | | | | | | | | | | | | | | | |
| `rag-data` | D/U | — | | | | | | | | | | | | | | | | |
| `rag-config` | D/U | | — | | | | | | | | | | | | | | | |
| `rag-context` | | | | — | | | | | | | | | | | | | | |
| `rag-storage` | D/U | | | | — | | | | | | | | | | | | | |
| `rag-log` | D/U | D/U | | | | — | | | | | | | | | | | | |
| `rag-auth` | D/U | D/U | | | | D/U | — | | | | | | | | | | | |
| `rag-quota` | D/U | D/U | | | | | | — | | | | | | | | | | |
| `rag-llm` | | D/U | D/U | | | | | | — | | | | | | | | | |
| `rag-tool` | D/U | D/U | D/U | | | | | | | — | | | | | | | | |
| `rag-kb` | D/U | D/U | D/U | | D/U | | | | D/U | | — | | | | | | | |
| `rag-chat` | D/U | D/U | D/U | | | | | D/U | D/U | D/U | D/U | — | D/U | D/U | | | | |
| `rag-agent` | D/U | D/U | D/U | D/U | | | | | D/U | D/U | D/U | | — | | | | | |
| `rag-workflow` | D/U | D/U | D/U | | | | | | D/U | D/U | D/U | | D/U | — | | | | |
| `rag-agent-multi` | D/U | D/U | D/U | D/U | | | | | D/U | D/U | D/U | | D/U | D/U | — | | | |
| `rag-public-access` | D/U | D/U | D/U | | | | D/U | | | | D/U | D/U | | | | — | | |
| `rag-controller` | D/U | D/U | D/U | | | D/U | D/U | D/U | D/U | D/U | D/U | D/U | D/U | D/U | D/U | D/U | — | |
| `rag-app` | | D | | | | D | D | | | | | | | | | | D | — |

> P1 落地后（2026-11-20）上表已按实际 import 扫描校正：`rag-tool→rag-context`、`rag-chat→rag-context`、`rag-workflow→rag-context`、`rag-agent-multi→rag-chat` 与 `rag-kb→rag-tool` 均已不再声明，见 §5 P1 落地结果。

### 2.3 依赖问题清单

**（1）无环，但同层无次序（结构性风险）**

编排层内部的 5 条同层边没有任何声明次序：

```
rag-chat        → rag-workflow, rag-agent
rag-workflow    → rag-agent
rag-agent-multi → rag-workflow, rag-agent（对 chat 的未使用声明已于 P1 删除）
rag-public-access → rag-chat, rag-kb
```

后果：任何人给 `rag-agent` 增加对 `rag-workflow` 的调用（例如 Agent 要用工作流节点），Maven 立刻成环；而且「谁是顶层执行体」无法判断，调度中心无法确定「统一入口该放在哪一层」。

**（2）反向依赖（层级倒置）**

| 位置 | 现象 | 为什么是倒置 |
|---|---|---|
| `rag-controller/.../service/TaskRecoveryService.java` | 接入层持有 `AgentOrchestrator`、`WorkflowEngine`、`MultiAgentRuntime`、`RagChatService`，并自带 2 线程执行器、Redis 锁、按类型 switch 续跑 | 运行编排与恢复属于执行域，不属于 HTTP 接入层；同时它直连 5 个 Mapper，违反 [design-overview.md](../../design-overview.md) §3.4「接入层禁止直接访问 Mapper」 |
| `rag-public-access` → `rag-chat` / `rag-kb` | 分享/API Key 域直接依赖 `ChatSessionMapper`、`ChatWindowService`、`KnowledgeBaseMapper`（`ShareLinkService`） | 适配型模块硬绑业务实现，业务改签名即连带改分享域 |
| `rag-kb` → `rag-tool` | 仅为 `SsrfBlockedException` / `SsrfGuard` 两个类 | 两个领域层的横向耦合；安全工具应有独立落点。**P1 已消除**：两个类下沉到 `rag-common.net`，`rag-kb` 不再依赖 `rag-tool` |
| `rag-public-access` → `rag-chat` / `rag-kb` | 分享/API Key 域直接依赖 `ChatSessionMapper`、`ChatWindowService`、`KnowledgeBaseMapper`（`ShareLinkService`），pom 中显式声明两者 | 适配型模块硬绑业务实现，业务改签名即连带改分享域。**复核结论（2026-11-20）**：仍然存在，按 §5 P1 的界定留到 P2 合流门之后处理 |
| `rag-chat` service 返回 `SseEmitter` | `RagChatService#streamChat`、`ChatWorkflowService` 的签名里直接出现 web 类型 | 执行域反向依赖 web 层，导致「无 HTTP 连接就不能运行」。**复核结论（2026-11-20）**：仍然存在（`RagChatService#streamChat`/`streamShareChat`、`ChatWorkflowService#hitlConfirm` 签名未变），留到 P2 合流后处理 |

**（3）职责重叠**

| 重叠对象 | 证据 | 影响 |
|---|---|---|
| 评测 | `rag-kb`：`EvalDatasetService` / `RetrievalEvaluationService` / `Eval*` 实体；`rag-chat`：`LlmJudgeService` / `RetrievalMetricsCalculator` / `EvaluationScheme` / `JudgeModelSelector` | 两套检索指标口径，两套 judge 调用；评测作业化时不知道 handler 该放哪 |
| 恢复/续跑 | `rag-controller.TaskRecoveryService`（switch 三引擎）、`rag-agent` 的 HITL/`PlanLifecycleService`、`rag-agent-multi.AgentStepCheckpointService` | 三处「从断点继续」，语义与幂等规则不一致 |
| 计划任务 | `WorkflowTriggerService`（CRON）、`ConnectorScheduler`、`UploadSessionCleaner`、`AgentMemoryCleaner`、`StorageOrphanCleanupScheduler`、`SemanticCacheService` 清理、`StorageMigrationJobService` | 7 处「每实例自建调度器 + 线程池」，只有 2 处做了单飞（`StorageMaintenanceLock`、`AgentMemoryCleaner` 的 xact advisory lock） |
| 上下文装配 | `rag-context.ContextAssembler`（纯算法）、`rag-chat.ContextCompressionService`、`rag-agent.AgentTaskMemory`、`rag-kb.AgentMemoryService` | 「拼上下文」有 4 个落点，长任务上下文改造会互相覆盖 |
| 运行状态枚举 | `WorkflowConstants` / `AgentConstants` / `MultiAgentConstants` / `KnowledgeFile` 状态字段 | 没有统一 `ExecutionStatus`；管理端无法按统一状态聚合 |
| 事件 sink | `rag-common.DomainEventPublisher`、`rag-agent.AgentEventPublisher`（薄包装） | 已有统一出口雏形，但签名是 `BiConsumer`，无法携带 `seq`/`executionId`，不能支撑重放 |
| SSE/Web 关注点 | 4 个非 web 模块 import `SseEmitter`；`rag-kb` / `rag-chat` 的 service 接受 `MultipartFile`；`rag-public-access` 含 servlet filter 与 security config | 「执行库」与「接入层」边界被 web 类型穿透 |

**（4）声明漂移（P1 已清理）**

| 声明 | 实际使用 | 处理 |
|---|---|---|
| `rag-chat` → `rag-context` | 0 处 import | P1 删除 |
| `rag-workflow` → `rag-context` | 0 处 import | P1 删除 |
| `rag-agent-multi` → `rag-chat` | 0 处 import | P1 删除 |
| `rag-tool` → `spring-boot-starter-web` | 0 处 web 类型使用 | P1 删除 |
| `rag-tool` → `rag-context` | 0 处 import（本次扫描新发现，矩阵中原本即标为 `D` 无 `U`） | P1 删除 |
| `rag-kb` → `rag-tool` | 仅 `SsrfGuard` / `SsrfBlockedException` | P1 将两个类下沉到 `rag-common.net` 后删除 |

---

## 3. 阻碍调度中心落地的具体点

> 编号 B1–B10 与 [design-execution-scheduler-center.md](design-execution-scheduler-center.md) §1 的执行体编号 J1–J10 一一对应，本文只补充「结构原因」，不重复论证选项。

### B1 任务在请求线程内同步执行

| 场景 | 证据 | 后果 |
|---|---|---|
| 单 Agent HITL 恢复 | `rag-controller/.../AgentController.java` 的 `hitlConfirm` 直接 `agentOrchestrator.resume(...)` 同步返回（L117–L120） | 长计划占 Tomcat 线程 + DB 连接；无并发上限 |
| 评测 | `rag-kb/.../EvalDatasetService.java#L181-L205`：`@Transactional` 包住整段循环检索与指标计算 | 请求线程持连接数分钟；失败整段回滚但模型调用已消耗 |
| 存储迁移 | `StorageMigrationJobService` 在请求线程内创建任务并立即提交自己的线程池 | 提交成功与否依赖进程存活，无执行记录 |

### B2 执行入口分散在各 Controller / Service

| 执行体 | 入口 |
|---|---|
| 聊天流（J1） | `ChatController` → `RagChatService#streamChat` |
| 聊天工作流（J2） | `RagChatService` → `ChatWorkflowService` → `WorkflowService#runForChat` |
| 工作流手动/静默/调试（J3/J4） | `WorkflowController` → `WorkflowService#run/runHeadless/debugStep`；`WorkflowTriggerController` → `WorkflowTriggerService` |
| 单 Agent（J8） | `ChatController`（Agent 分支）→ `AgentOrchestrator#orchestrate`；`AgentController#hitlConfirm` → `resume` |
| 多 Agent（J9） | `MultiAgentController#streamRun` → `MultiAgentRuntime#execute` |
| 评测（J10） | `EvalDatasetController#evaluate` → `EvalDatasetService#evaluate` |
| 维护/计划（J5–J7） | `ConnectorController#sync`；`UploadSessionCleaner`；`AgentMemoryCleaner`；`StorageOrphanCleanupScheduler`；`StorageMaintenanceController` |
| 统一续跑（临时聚合） | `TaskRecoveryController` → `TaskRecoveryService#resumeTask`（`switch(type)` 三个分支） |

26 个 Controller / 244 个 handler 中，真正「发起执行」的有 7 个入口类。没有一处是「提交执行体 → 得到句柄」。

### B3 运行状态存在进程内

| 状态 | 位置 | 多实例后果 |
|---|---|---|
| 工作流 SSE emitter | `rag-workflow/.../WorkflowService.java#L62` 的 `Map<Long, SseEmitter>` | 重连/换实例无法附着 |
| 存储迁移任务与进度 | `rag-kb/.../StorageMigrationJobService.java#L62` 的 `Map<String, MigrationJob>`；`MigrationJobStateStore#L50-L51` 的 `localJobs/localCancel` | Redis 失效时任务「只在本实例可见」 |
| 恢复互斥 | `TaskRecoveryService#L128` 的 `inFlight` | 仅同实例防重；跨实例靠 Redis 锁但无租约 |
| 多 Agent 黑板 | `MultiAgentContext` 的 `InMemoryBlackboard` | 跨实例不可见（当前可接受，S2 后需外置） |
| 连接器同步中集合 | `ConnectorService` 的 `syncing` | 多实例同时抓取 |
| 文件处理池 | `KnowledgeFileService` 的静态 `FILE_PROCESSOR` | 每实例独立，`CallerRunsPolicy` 会回压 Tomcat |
| 工具熔断器 | `ToolCircuitBreaker` 进程内 Map | 各实例独立开合 |

### B4 缺少统一的作业抽象与提交/查询/取消接口

现状是「5 套业务状态 + 5 套取消语义」：`WorkflowRun`、`AgentPlan/AgentStep`、`MultiAgentRun/Step`、`ChatSession`、`MigrationJob`。各自有常量类，没有共同父类型；取消分别是 `WorkflowService#cancel`、`AgentOrchestrator#cancel`、`MultiAgentRuntime#cancelRun`、`ChatCancellationStore`、存储迁移 cancel key。管理端无法用一种方式列出「正在跑什么」。

### B5 跨模块直接调用具体实现

- `TaskRecoveryService` 直接注入 `AgentOrchestrator` / `WorkflowEngine` / `MultiAgentRuntime` / `RagChatService`。
- `AgentController` 直接注入 `AgentOrchestrator` 与 `RagChatService`。
- `WorkflowService#run` 直接 `new SseEmitter` 并维护 emitter Map。
- `rag-agent.StepExecutor` 直接依赖 `rag-kb.RagRetrieveService` 具体类。
- `rag-workflow.AgentNodeExecutor` 直接依赖 `rag-agent.StepExecutor` / `Planner` 具体类。

全仓库仅 `rag-kb/port`（`VectorStorePort`、`KeywordSearchPort`）采用了端口化写法——这证明团队已有端口范式，可直接推广。

### B6 执行层依赖 web 层

`SseEmitter` 出现在：`rag-workflow.WorkflowService`、`rag-chat.RagChatService`/`ChatWorkflowService`、`rag-agent.StepExecutor`、`rag-agent-multi` 的 6 个类。`MultipartFile` 出现在 `rag-kb.KnowledgeFileService`/`ChunkUploadService`、`rag-chat.ChatImageService`。`jakarta.servlet` 出现在 `rag-public-access` 的 5 个类。

后果：调度中心要在「无 HTTP 连接」的场景执行同一套代码时，必须绕开这些签名；这正是「后台作业化」最难的一步。

### B7 先插 RUNNING 再提交（失败边界）

`WorkflowService#createRun` 先 `runMapper.insert(run)` 且 `status=RUNNING`（L403/L409），随后 `workflowExecutor.execute(...)`（L332/L372/L435）。池满时 `ThreadPoolTaskExecutor` 默认 `AbortPolicy` 抛 `TaskRejectedException`，run 永久 `RUNNING`。`runHeadless` 的 catch 在 `execute` 内部，覆盖不到提交阶段（L435–L444）。

### B8 计划任务每实例注册，且无单飞

| 任务 | 位置 | 单飞 |
|---|---|---|
| CRON 工作流 | `WorkflowTriggerService#init`（L52–L63）每实例加载全部启用触发器并本地 `schedule` | 无 |
| 连接器同步 | `ConnectorScheduler#start`（L29–L49）每实例 1 线程 fixed-delay | 无 |
| 上传清理 | `UploadSessionCleaner#start` 每实例 1 线程 | 无 |
| Agent 记忆清理 | `AgentMemoryCleaner#start` 每实例 1 线程 | **有**（`pg_try_advisory_xact_lock`） |
| 存储孤儿清理 | `StorageOrphanCleanupScheduler` 每实例 `ThreadPoolTaskScheduler` | **有**（`StorageMaintenanceLock`） |
| 语义缓存清理 | `SemanticCacheService` 写入节奏触发 | 无 |

### B9 准入与配额顺序缺失

`RagChatService#streamChat` 在创建 emitter 与执行线程之前调用 `quotaService.checkAndIncrementChat(...)`（L149–L151）。若将来在提交阶段被调度器拒绝，用户已被扣一次额度。正确顺序 `admission → quota → execute → release` 已在 [design-unified-scheduler-and-tenant-policy.md](design-unified-scheduler-and-tenant-policy.md) §3.5 定稿。

### B10 无优雅停机与排水

`rag-app/src/main/resources/*.yml` 中不存在 `server.shutdown`、`spring.lifecycle.timeout-per-shutdown-phase`。裸线程（聊天、多 Agent）不受 Spring 管理，SIGTERM 后直接消失。

---

## 4. 目标模块划分

### 4.1 目标分层

```text
┌──────────────────────────────────────────────────────────────────────┐
│ 装配层   rag-app                                                     │
│          注册 lane 池 / 调度器 / TaskDecorator / 优雅停机协调器        │
├──────────────────────────────────────────────────────────────────────┤
│ 接入层   rag-controller（纯 HTTP/SSE 适配，无 Mapper、无引擎调用）     │
│          rag-public-access（领域库：分享 / API Key / 限流）           │
├──────────────────────────────────────────────────────────────────────┤
│ 调度层   rag-scheduler                                               │
│          准入 / lane / 作业表 / 租约 / 策略 / 运行时快照 / 触发器       │
├──────────────────────────────────────────────────────────────────────┤
│ 执行契约 rag-execution                                               │
│          提交 / 查询 / 取消 / 事件 sink / JobHandler / RunRegistry    │
├──────────────────────────────────────────────────────────────────────┤
│ 执行域   rag-agent → rag-workflow → { rag-chat , rag-agent-multi }    │
│          rag-kb / rag-document / rag-eval（执行体的业务实现）          │
├──────────────────────────────────────────────────────────────────────┤
│ 领域能力 rag-llm / rag-tool / rag-quota / rag-auth / rag-log          │
├──────────────────────────────────────────────────────────────────────┤
│ 基础层   rag-common / rag-data / rag-config / rag-storage / rag-context│
└──────────────────────────────────────────────────────────────────────┘
```

依赖方向：**只允许向下**。`rag-scheduler` 与执行域并列，彼此不依赖；两者都只依赖 `rag-execution`，装配在 `rag-app` 完成（依赖倒置 + Spring 注入 `List<JobHandler>`）。

### 4.2 模块处置表

| 处置 | 模块 | 说明 |
|---|---|---|
| **新增** | `rag-execution` | 作业抽象与执行契约。纯库，零依赖（仅 JDK 21，刻意不依赖 `rag-common` 以隔离 web 传递依赖）。见 §4.4、§4.8 |
| **新增** | `rag-scheduler` | 调度实现：三 lane、准入、`wgh_execution_job` 持久化与租约、策略缓存、公平队列、运行时快照、触发器归属。只依赖 `rag-execution` + `rag-data` + `rag-config` |
| **新增（拆分）** | `rag-document` | 从 `rag-kb` 拆出 `parser/*` 与 `splitter/*`（Tika/PDF/Excel 解析、OCR、切分策略、规范化）。**只依赖 `rag-common`**，不依赖 `rag-kb`/`rag-storage`/`rag-llm`，避免成环。文件处理流水线（`KnowledgeFileService`）、分片上传与 `INGEST_FILE` handler 仍留在 `rag-kb`，由 `rag-kb` 调用本模块 |
| **新增（拆分）** | `rag-eval` | 从 `rag-kb` 拆出：`EvalDataset*`、`RetrievalEvaluationService`、评测结果归档。依赖 `rag-kb`（检索）+ `rag-llm`。rag-chat 重构合流后并入 `LlmJudgeService` 等 |
| **拆分** | `rag-controller` | 只保留 HTTP/SSE 适配。`TaskRecoveryService` 拆为「通用 `RunCoordinator`（放 `rag-scheduler`，只依赖 `RunRegistry` 接口）+ 各域 `RunAdapter`（放对应执行域模块）」；删除 Mapper 直连 |
| **纯库化** | `rag-kb` | 去掉 `MultipartFile`：上传边界改为 Controller 接收后传 `InputStream` + 元数据；去掉对 `rag-tool` 的依赖（`SsrfGuard` 下沉到 `rag-common` 的 `net` 包）；触发器（连接器 / 上传清理 / 记忆清理 / 孤儿清理 / 语义缓存清理）改由 `rag-scheduler` 提交，模块内只保留 `JobHandler` |
| **纯库化** | `rag-public-access` | `ApiKeyAuthenticationFilter`、`PublicAccessSecurityConfig`、`PublicAccessExceptionHandler` 与 `HttpServletRequest` 解析上移到 `rag-controller`；模块只保留分享 / Key / 限流 / 存储域能力 |
| **纯库化** | `rag-tool` | 移除未被使用的 `spring-boot-starter-web`；`SsrfGuard` 迁出到 `rag-common` |
| **保持纯库** | `rag-context` | 零依赖纯算法库，被 agent / agent-multi 共用；**不移入 `rag-common`**（避免污染纯契约层），由 Enforcer 禁止其新增任何模块依赖 |
| **合并职责** | `rag-workflow` 的触发调度 | `WorkflowTriggerService` 只保留触发器 CRUD 与校验；「何时触发」交给 `rag-scheduler`（MAINTENANCE lane 单飞 + 提交 `WF_RUN`） |
| **合并职责** | `rag-chat` 的评测 | `LlmJudgeService` / `RetrievalMetricsCalculator` / `EvaluationScheme` / `JudgeModelSelector` 在 rag-chat 重构合流后迁入 `rag-eval` |
| **保持** | `rag-agent` / `rag-workflow` / `rag-agent-multi` | 保留为独立执行域，但需去除 `SseEmitter` 与每 run 自建池 |
| **保持** | `rag-common` / `rag-data` / `rag-config` / `rag-storage` / `rag-llm` / `rag-quota` / `rag-auth` / `rag-log` / `rag-sandbox` | 不改边界；`rag-app` 增加显式装配 |

### 4.3 目标依赖约束（逐模块）

> 「禁止」列将由 Maven Enforcer（模块级）+ ArchUnit（包级）双重校验；落地方式见 §4.5。

| 模块 | 允许依赖 | 禁止依赖 | 关键约束 |
|---|---|---|---|
| `rag-common` | JDK、spring-web/security（现状） | 任何业务模块 | 保持零 ORM；新增代码不得引入 `mybatis-plus` |
| `rag-data` | `rag-common` | 业务模块 | — |
| `rag-config` | `rag-common` | 业务模块 | — |
| `rag-context` | 无 | 任何模块 | 纯算法；不得引入 Spring 注解 |
| `rag-storage` | `rag-common` | `rag-kb`（迁移任务目前在 kb，迁移后不得反向） | 存储抽象不得感知 KB |
| `rag-log` / `rag-auth` / `rag-quota` | `rag-common`、`rag-data`（auth 另含 log） | 执行域 / 调度层 | — |
| `rag-llm` | `rag-data`、`rag-config` | 执行域 / 调度层 | — |
| `rag-tool` | `rag-common`、`rag-data`、`rag-config` | `rag-llm`（沿用 SPI 反转）、`rag-kb`、执行域、web starter | P1 已移除 web starter 与未使用的 `rag-context` 声明 |
| `rag-document` | `rag-common`（仅工具类） | `rag-kb`、`rag-storage`、`rag-llm`、执行域、web 类型 | 纯解析/切分算法库；不得回写业务表 |
| `rag-kb` | `rag-data`、`rag-storage`、`rag-config`、`rag-llm`、`rag-document` | `rag-tool`（P1 起，SSRF 工具已下沉）、`rag-chat`、`rag-agent`、`rag-workflow`、`rag-scheduler`、web 类型 | 不得出现 `SseEmitter` / `MultipartFile` / `jakarta.servlet` |
| `rag-eval` | `rag-kb`、`rag-llm`、`rag-execution` | `rag-chat`（迁入前置除外）、web 类型 | 只暴露 `EvalPort` 与 `EVAL_RUN` handler |
| `rag-agent` | `rag-kb`、`rag-tool`、`rag-llm`、`rag-context`、`rag-execution` | `rag-workflow`、`rag-chat`、`rag-agent-multi`、`rag-scheduler`、web 类型 | 执行域最底层；HITL 状态机自持 |
| `rag-workflow` | `rag-agent`、`rag-kb`、`rag-tool`、`rag-llm`、`rag-execution` | `rag-chat`、`rag-agent-multi`、`rag-scheduler`、web 类型 | 引擎不得创建 `SseEmitter`，只接受 `ExecutionEventSink` |
| `rag-chat` | `rag-agent`、`rag-workflow`、`rag-kb`、`rag-tool`、`rag-quota`、`rag-execution` | `rag-agent-multi`、`rag-scheduler`、web 类型（service 层） | **冻结区**：改动需与重构线合流 |
| `rag-agent-multi` | `rag-agent`、`rag-workflow`、`rag-tool`、`rag-execution` | `rag-chat`（删除未使用声明）、`rag-scheduler`、web 类型 | 与 `rag-chat` 并列，互不依赖 |
| `rag-public-access` | `rag-common`、`rag-data`、`rag-auth`、`rag-config`、`rag-chat`、`rag-kb` | `rag-controller`、`rag-scheduler`、servlet/security web 类型 | 纯领域库 |
| `rag-execution` | 无（仅 JDK 21） | 所有业务模块、`rag-common`、`rag-data`、`rag-controller`、`org.springframework.web`、`jakarta.servlet` | 契约层，不含实现；零依赖是为了让调度层与执行域都能单向依赖它而不引入 web |
| `rag-scheduler` | `rag-execution`、`rag-data`、`rag-config`、`rag-log` | `rag-chat`、`rag-agent`、`rag-workflow`、`rag-agent-multi`、`rag-kb`、`rag-document`、`rag-eval`、`rag-controller` | 只认 `JobHandler` / `RunAdapter` 接口与 `job_type` 字符串；`RunCoordinator`（统一查询/续跑/取消）在此实现，不感知具体引擎 |
| `rag-controller` | 全部业务模块 + `rag-scheduler`（管理端只读） | `rag-app` | 唯一组合根；不得直连 Mapper（存量迁移后） |
| `rag-app` | `rag-controller` + 显式业务模块（用于装配） | — | 显式声明被装配模块，不靠传递依赖 |

**执行域偏序（新增的硬约束）**：

```
rag-agent  <  rag-workflow  <  rag-chat
                          <  rag-agent-multi
```

即 `agent` 不得依赖 `workflow`/`chat`/`multi`；`workflow` 不得依赖 `chat`/`multi`；`chat` 与 `multi` 互不依赖。这条偏序是防止「未来某天给 Agent 加工作流调用」直接成环的唯一手段。

**P1 落地状态（2026-11-20）**：上述「禁止」中可在工件级表达的条目，已通过 `maven-enforcer-plugin` 的 `bannedDependencies`（`searchTransitive=false`，随 `validate` 阶段校验）落在各模块 `pom.xml`：`rag-agent`（workflow/chat/multi/scheduler）、`rag-workflow`（chat/multi/scheduler）、`rag-chat`（multi/scheduler）、`rag-agent-multi`（chat/scheduler）、`rag-kb`（tool/chat/agent/workflow/multi/scheduler）、`rag-tool`（web starter/llm/kb/执行域）。根 `pom.xml` 由 P0 线占用，故规则写在模块级。web 类型约束（`SseEmitter`/`MultipartFile`/`jakarta.servlet`）属包级规则，存量未清理，P2 后再以 ArchUnit 表达；当前以 pom 注释记录目标态。

### 4.4 执行契约（`rag-execution`）最小接口集

> 只定义签名语义，不定义实现。命名可在落地时微调，但**职责边界不可扩张**。

| 类型 | 职责 | 备注 |
|---|---|---|
| `ExecutionLane` | `INTERACTIVE` / `BACKGROUND` / `MAINTENANCE` | 复用既有三 lane，不新增 |
| `ExecutionStatus` | `PENDING/LEASED/RUNNING/SUCCEEDED/FAILED/DEAD/CANCELLED` | 业务状态（如 `HITL_PENDING`）保留在业务表，映射到本枚举 |
| `ExecutionRequest` | `jobType`、`lane`、`tenantId`、`ownerUserId`、`bizKey`、`payload`、`priority`、`scheduledAt`、`timeout` | 提交时**捕获**不可变上下文，禁止执行期回读 `ThreadLocal` |
| `ExecutionHandle` | `executionId`、`status`、`submittedAt` | 提交即返回，不阻塞 |
| `ExecutionSnapshot` | 状态、attempt、进度摘要、错误码、时间戳 | 供查询/管理端 |
| `ExecutionEventSink` | `emit(executionId, seq, event, payload)` | 取代 `SseEmitter` 与 `BiConsumer` 双轨；SSE 只是其中一种实现 |
| `JobHandler` | `jobType()`、`execute(JobContext)`、`cancel(JobContext)` | 执行域实现，调度层只认接口 |
| `JobContext` | 上下文 + sink + 取消令牌 + 心跳回调 | 心跳由调度层注入，执行域不感知租约细节 |
| `ExecutionFacade` | `submit` / `query` / `cancel` / `attach` | 执行域唯一入口 |
| `RunRegistry` | `register(RunAdapter)`、`find(type,id)`、`listActive`、`resume` | 替代 `TaskRecoveryService` 的 `switch` |
| `RunAdapter` | `runType()`、`load(id)`、`resume(id, ctx)`、`cancel(id)` | 每个执行域实现一个 |
| `ExecutionErrorCodes` | `SCHEDULER_REJECTED`、`TENANT_CONCURRENCY_EXCEEDED`、`JOB_QUEUE_TIMEOUT`、`LEASE_LOST`、`INCOMPATIBLE_PAYLOAD` | 与 [design-unified-scheduler-and-tenant-policy.md](design-unified-scheduler-and-tenant-policy.md) §3.8 对齐 |

**关键约束**：`ExecutionEventSink` 必须允许「无订阅者」（后台作业）与「SSE 订阅者」（交互式）两种实现；调度层不得 import `SseEmitter`。

### 4.5 约束的落地方式

| 约束 | 手段 | 说明 |
|---|---|---|
| 模块级禁止依赖 | Maven Enforcer `bannedDependencies`（沿用现有 `enforce-public-access-not-consumed` 写法） | P1 已落地 6 条：`rag-tool` / `rag-kb` / `rag-agent` / `rag-workflow` / `rag-chat` / `rag-agent-multi`。`rag-execution` / `rag-scheduler` / `rag-document` / `rag-eval` 待对应模块拆分（P3/P4）后补 |
| 包级禁止 import | ArchUnit 测试（新增测试依赖，只在 P0 加规则，不写业务测试） | 规则：执行域不得 import `org.springframework.web..`、`jakarta.servlet..`；`rag-scheduler` 不得 import 执行域包 |
| 执行域偏序 | Enforcer + 依赖图断言 | `rag-agent` 的 pom 出现 workflow/chat/multi 即失败（P1 已生效） |
| 显式组合根 | `rag-app` 的 pom 显式列出被装配模块 | 避免「加了模块但没生效」（P1 未执行，见 §9.7） |

> 说明：ArchUnit 目前不在依赖里。P0 只引入 Enforcer 模块级规则即可获得 80% 收益；包级规则可在 P2 执行域改造时再引入。根 `pom.xml` 由 P0 线并行占用，P1 的规则全部写在模块 `pom.xml` 中，不修改父 POM。

### 4.6 多实例语义落点

| 状态 / 能力 | 目标归属 | 多副本语义 |
|---|---|---|
| 业务运行记录（run/plan/step） | 各执行域表（已有） | 共享 DB，天然一致 |
| 后台作业与租约 | `rag-scheduler` 的 `wgh_execution_job` | `FOR UPDATE SKIP LOCKED` + `lease_owner/lease_expire_time/heartbeat_time`，一作业一执行者 |
| 租户策略 | `rag-scheduler` 的 `wgh_tenant_schedule_policy` | 本地缓存 + epoch 轮询，60s 内收敛 |
| 后台并发/排队 | `wgh_tenant_runtime_slot` + PENDING 计数 | 集群口径 |
| 交互式并发 | 实例内信号量（S3 前） | **必须标注「单实例上限」**；S3 切 DB/Redis 全局槽 |
| 定时触发归属 | `rag-scheduler` | 触发器表 + advisory lock，一个 slot 只跑一次 |
| 取消标记 | `ExecutionCancellationStore`（Redis 优先） | 复用 `ChatCancellationStore` 的降级范式，降级时告警 |
| SSE 订阅 | `rag-controller`（仅传输） | 重连靠状态轮询；事件重放留 S3（`wgh_execution_event`） |
| 运行快照 | `SchedulerRuntimeRegistry`（每实例）+ DB 聚合 | 管理端读「实例心跳 + 作业表」，不做跨实例 RPC |
| 本地文件 | `rag-storage` | 多副本必须 OSS 或共享卷；key 加租户前缀 |
| 策略/模型缓存 | 各模块进程内 | 允许不一致（平台级缓存），但必须有失效机制 |

---

### 4.7 接入层包结构（`rag-controller`）

Controller 已按业务域从根包拆到子包。**路由、方法签名、参数、响应结构、Service 调用均未改动**；仅包名、随之必需的 import、测试注解扫描路径调整。

```text
com.wgh.rag.controller
├── chat            ChatController, PromptTemplateController
├── kb              KnowledgeBaseController, TagController, ConnectorController
├── eval            EvalDatasetController, RetrievalEvaluationController
├── agent           AgentController, MultiAgentController
├── workflow        WorkflowController, WorkflowTriggerController
├── admin           AdminUserController, AdminRoleController, AdminStatsController,
│                   LlmModelController, ModelPriceController
├── quota           QuotaAdminController, QuotaUserController
├── auth            AuthController
├── publicaccess    PublicShareController, OpenApiController,
│                   ApiKeyAdminController, ShareLinkAdminController
│   └── openai      OpenAiCompatController, OpenAiCompletionSink,
│                   OpenAiErrorHandler, OpenAiSseTap
├── ops             StorageMaintenanceController, TaskRecoveryController
├── dto             （跨域请求/响应模型，暂留）
├── service         TaskRecoveryService（尚未搬离接入层）
└── support         RunAccessGuard
```

| 子包 | 分组依据 | 对应目标模块（§4.2） |
|---|---|---|
| `chat` | 对话会话生命周期与提示词装配同属对话域；`PromptTemplateController` 服务的是会话输入而非独立配置 | `rag-chat`（冻结区，仅包归属） |
| `kb` | 知识库、文档、标签、连接器共享 KB 归属与权限校验（`/api/kb/**`） | `rag-kb` |
| `eval` | 评测数据集与检索评测共享同一套数据集/指标语义 | `rag-eval` |
| `agent` | 单 Agent 与多 Agent 共用 plan/HITL/run 语义，仅在编排形态上不同；放同一包便于 HITL 入口统一 | `rag-agent`、`rag-agent-multi` |
| `workflow` | 工作流定义、运行、触发器同属工作流域；触发器的「何时触发」后续交给调度层，但入口仍在此 | `rag-workflow` |
| `admin` | 平台级用户/角色/统计/模型/价格管理，没有独立业务模块承载，属横切管理面 | 跨 `rag-auth` / `rag-llm` / `rag-quota` |
| `quota` | 管理侧与用户侧配额读写同域，拆开会导致两处重复配额语义 | `rag-quota` |
| `auth` | 登录、刷新、登出、当前用户，属认证域 | `rag-auth` |
| `publicaccess` | 分享链接、开放 API、API Key、OpenAI 兼容协议都是「对外访问面」；`openai` 是其中一种协议适配，故作为子包 | `rag-public-access` |
| `ops` | 存储维护与任务续跑属运维动作，未来分别归调度层与存储维护作业 | `rag-scheduler` / `rag-kb`（storage） |

**为什么 `dto` / `service` / `support` 暂留根包**：`dto` 被 `admin` 与 `quota` 共用，`RunAccessGuard` 被 `agent`、`ops` 等多域共用，`TaskRecoveryService` 的搬迁依赖 `RunCoordinator`（P4）。此时下沉任一者都会制造「子包反向依赖根包」的新问题，留给 P3/P4 一起处理。

**为什么 `admin` 不再细分**：5 个 Controller 各自只有一个资源，再拆 `admin/user`、`admin/model` 只会增加空壳包；判断标准是「一个子包对应一个可独立搬迁的目标模块或一个横切管理面」，而不是「一个资源一个包」。

**包结构变更带来的最小适配**（不属于业务改动）：

| 位置 | 调整 | 原因 |
|---|---|---|
| `rag-controller/src/test/.../controller/*.java` | 对已迁移 Controller 增加显式 import；`openai` 测试包名改到 `controller.publicaccess.openai` | 原先依赖同包可见性 |
| `AdminEndpointPermissionTest` | 对包级可见的 `ChatController.maskExecutorConfig` 改为反射调用 | 不允许改 Controller 可见性，只能调整测试的访问方式 |
| `rag-app` 的 `QuotaApiTest` / `LocalAuthApiTest` | 更新 Controller import | 外部模块显式 import |

**扫描路径**：`@SpringBootApplication` 扫描根为 `com.wgh.rag`，子包天然被覆盖；`GlobalExceptionHandler` 未限定 `basePackages`，`OpenAiErrorHandler` 的 `assignableTypes` 与 `OpenAiCompatController` 同包迁移，均无需额外配置。

### 4.8 P0 落地：`rag-execution` 契约模块

> 状态：**已落地（2026-11-20）**。模块已注册进根 POM，零业务模块引用，全仓 `-DskipTests compile` 通过。

**模块坐标**：`rag-backend/rag-execution`，包根 `com.wgh.rag.execution`，无任何依赖（仅 JDK 21）。

```text
com.wgh.rag.execution
├── api      ExecutionFacade（提交/查询/取消/订阅）
├── job      JobHandler、JobContext、ExecutionCancellationToken
├── model    ExecutionLane、ExecutionStatus、ExecutionRequest、ExecutionHandle、
│            ExecutionSnapshot、ExecutionLease、JobPayload
├── event    ExecutionEvent、ExecutionEventSink、ExecutionEventTypes
├── run      RunAdapter、RunRegistry
└── error    ExecutionErrorCodes、ExecutionException、
             ExecutionCancelledException、LeaseLostException
```

| 契约 | 关键方法 / 结构 | 支撑阶段 |
|---|---|---|
| `ExecutionFacade` | `submit` / `query` / `cancel` / `attach` | P2 交互式统一、P3 后台提交 |
| `JobHandler` | `jobType()` / `execute(JobContext)` / `cancel(JobContext)` | P3 各域作业化 |
| `JobContext` | `executionId` / `request` / `payload` / `events` / `cancellation` / `heartbeat` | P2、P3 |
| `ExecutionEventSink` | `emit(ExecutionEvent)` / `close()` | P2 剥离 `SseEmitter`、S3 重放 |
| `ExecutionRequest` | 作业类型、lane、租户、属主、bizKey、payload、priority、scheduledAt、timeout | P2、P3 |
| `ExecutionLease` | 持有者、获取/过期/续租时间、`isExpired` | P3 租约 |
| `ExecutionCancellationToken` | `isCancelled` / `reason` / `throwIfCancellationRequested` | P2、P3 |
| `RunAdapter` / `RunRegistry` | `runType` / `load` / `resume` / `cancel`；注册与查找 | P3/P4 替代 `TaskRecoveryService` 的 switch |
| `ExecutionErrorCodes` | 8 个错误码，与 §3.8 对齐 | P2、P3 |

**命名与取舍（需要拍板时以此为准）**：

1. **零依赖而非依赖 `rag-common`**：`rag-common` 携带 `spring-boot-starter-web`，一旦依赖会把 web 传进执行契约层；契约自带错误码与异常体系，不引入 `Result`/`BusinessException`。
2. **`tenantId` 默认 `default`**：单租户调用方不必显式构造；多租户阶段由 `TenantContext` 填充，契约不变。
3. **`timeout = null` 表示车道默认值**：不在契约里写死时长，避免与三车道的策略配置冲突。
4. **`RunRegistry` 不提供 `resume`**：续跑必须构造带租约/事件/取消的 `JobContext`，只能由调度层协调器完成；§4.4 的 `resume` 职责落在 `RunAdapter#resume`。
5. **`attach` 返回 `AutoCloseable`**：SSE 断开即释放订阅；`fromSeq` 为 S3 事件重放预留。
6. **事件类型只收通用 7 类**（started/progress/waiting_input/completed/failed/cancelled/heartbeat）：业务事件由执行体自定义 type，避免把对话或工作流细节写进契约。
7. **`JobPayload.schemaVersion` 默认 1**：为 P3 跨进程 payload 演进预留；不兼容时置 `DEAD(INCOMPATIBLE_PAYLOAD)`。

**接线点（P2/P3 再做，本轮不做）**：调度层实现 `ExecutionFacade` 与 `RunRegistry`；各执行域实现 `JobHandler` / `RunAdapter`；接入层实现 SSE 版 `ExecutionEventSink`。

---

## 5. 分阶段搬迁步骤

> 每个阶段标注：**动作** / **可编译性** / **回滚方式** / **验收** / **冲突面**。
> 「可编译」指每一步结束时 `mvn -q -DskipTests compile` 通过（P0、P1 已执行，P2–P4 未执行）。

### P0 契约先行（无行为变化）

| 项 | 内容 |
|---|---|
| 动作 | 1) 新增 `rag-execution` 模块，只放 §4.4 的接口、值对象、错误码、指标名；2) 在根 POM 注册模块但不被任何模块引用；3) 增加 Enforcer 白名单规则（新模块不得被业务模块依赖，仅 scheduler/执行域可依赖）；4) 记录「`SseEmitter` / `MultipartFile` / `jakarta.servlet` 使用清单」作为冻结基线 |
| 可编译 | 是（零引用） |
| 回滚 | 删除模块 + 删 POM 条目 |
| 验收 | 构建通过；新增模块不含任何实现类；依赖图与 §2.2 完全一致 |
| 状态 | **已落地（2026-11-20）**：`rag-backend/rag-execution` 已注册，零业务引用，全仓 `-DskipTests compile` 通过；详见 §4.8 |
| 冲突面 | 无（不触碰 `rag-chat`） |

### P1 依赖净化（无行为变化）

| 项 | 内容 |
|---|---|
| 动作 | 1) 删除 5 处未使用声明（`rag-chat→rag-context`、`rag-workflow→rag-context`、`rag-agent-multi→rag-chat`、`rag-tool→spring-boot-starter-web`、`rag-tool→rag-context`）；2) `SsrfGuard` / `SsrfBlockedException` 从 `rag-tool` 下沉到 `rag-common.net`，`rag-kb` 不再依赖 `rag-tool`；3) 在 6 个模块补 Enforcer 依赖方向约束；4) `rag-app` 改为显式声明被装配模块；5) 把 `TaskRecoveryService` 的「按类型 switch」抽成 `RunAdapter` 形状，但实现先委托原逻辑并暂留在 `rag-controller` |
| 可编译 | 是 |
| 回滚 | 逐个 commit 反向 revert（每项独立提交） |
| 验收 | 依赖图与 §4.3 的「禁止」列无冲突；`TaskRecoveryController` 行为不变 |
| 状态 | **部分落地（2026-11-20）**：1)–3) 已完成并验证；4)、5) 本轮未执行（见下表说明） |
| 冲突面 | 低。`TaskRecoveryService` 在 `rag-controller`，不在冻结区 |

**P1 落地结果（2026-11-20）**

| 改动 | 落点 | 验证 |
|---|---|---|
| 删除 `rag-chat→rag-context` | `rag-backend/rag-chat/pom.xml` | 全仓 `-DskipTests compile` 通过；`rag-chat` 的 `src/main`、`src/test` 均无 `com.wgh.rag.context` import |
| 删除 `rag-workflow→rag-context` | `rag-backend/rag-workflow/pom.xml` | 同上，0 处 import |
| 删除 `rag-agent-multi→rag-chat` | `rag-backend/rag-agent-multi/pom.xml` | 同上，0 处 import |
| 删除 `rag-tool→spring-boot-starter-web` | `rag-backend/rag-tool/pom.xml` | `rag-tool` 无 `org.springframework.web` / `jakarta.servlet` / `MultipartFile` 引用；编译通过 |
| 删除 `rag-tool→rag-context`（本轮扫描新发现） | `rag-backend/rag-tool/pom.xml` | 0 处 import |
| `SsrfGuard` / `SsrfBlockedException` 下沉 | 新位置 `rag-backend/rag-common/src/main/java/com/wgh/rag/common/net/`；旧 `rag-tool/.../tool/security/` 已删除 | 代码逐行搬移（仅包名与 javadoc 引用调整，配置键 `wgh.tool.ssrf.*` 保持不变）；调用点 `HttpToolExecutor`、`UrlConnector`、`ConnectorService` 及 4 个测试文件同步改 import |
| 切断 `rag-kb→rag-tool` | `rag-backend/rag-kb/pom.xml`（删 `rag-tool`，补显式 `rag-common`） | `rag-kb` 的 `src/main`、`src/test` 均无 `com.wgh.rag.tool` import；`rag-kb` `test-compile` 通过 |
| Enforcer 依赖方向约束（6 条） | `rag-tool`、`rag-kb`、`rag-agent`、`rag-workflow`、`rag-chat`、`rag-agent-multi` 的 `pom.xml` | `mvn validate` 全部 `BannedDependencies passed`；全仓编译通过 |
| web 类型约束 | 无法用工件级 Enforcer 表达（存量 `SseEmitter` 未清理） | 以 `rag-agent` / `rag-workflow` / `rag-tool` 的 pom 注释记录目标态，待 P2/P4 引入 ArchUnit |
| `rag-app` 显式装配 | 未改 `rag-backend/rag-app/pom.xml` | 本轮范围未包含（且根 POM 由 P0 线占用），保留在 §9.7 待拍板 |
| `TaskRecoveryService` → `RunAdapter` 形状 | 未改 | 依赖 P2/P4 的 `RunRegistry` 落地，属后续阶段 |

> 构建口径：JDK 21（`/usr/libexec/java_home -v 21`）、持 `/tmp/wgh-maven-build.lockdir`、离线 `-o`、`-DskipTests compile`（未执行 `package`/`install`，未覆盖运行中服务的 jar）。

### P2 交互式执行统一（S0 止血）

| 项 | 内容 |
|---|---|
| 动作 | 1) `rag-scheduler` 实现 `INTERACTIVE` lane：受管池 + 有界队列 + `AdmissionController`（先全局上限）+ `SchedulerRuntimeRegistry` + Micrometer；2) `MultiAgentController` 的 2 处 `new Thread` 改 `ExecutionFacade#submitInteractive`；3) `AgentController#hitlConfirm` 与聊天 Agent 分支改为「提交 + 返回 planId/轮询」或「提交 + SSE 附着」，执行不再占请求线程；4) `WorkflowService` 拆 `workflowInteractiveExecutor` / `workflowBackgroundExecutor`，`run()` 改用 `ExecutionEventSink`；5) 修复 B7：提交失败立即置 `FAILED(SCHEDULER_REJECTED)`；6) `server.shutdown=graceful` + drain 协调器；7) `app` 注册统一 `TaskDecorator` 捕获 `RequestContext` |
| 可编译 | 是（原子提交按模块分批，每批可编译） |
| 回滚 | 保留旧 Bean 名与旧方法签名；新 lane 通过配置开关 `wgh.scheduler.interactive.enabled` 控制，关闭即回退到原线程模型 |
| 验收 | 复用 S0 验收（[design-execution-scheduler-center.md](design-execution-scheduler-center.md) §3.1）：500 并发不再无界建线程；池满不停留 RUNNING；单 Agent 提交 1 秒内返回；SIGTERM 有终态 |
| 冲突面 | **高**：`RagChatService#streamChat` 的 `new Thread`（L202）与配额顺序（L149–L151）是 rag-chat 重构线的同一区域。见 §6.3 |

### P3 后台作业化（S1）

| 项 | 内容 |
|---|---|
| 动作 | 1) `wgh_execution_job` + claim/心跳/回收/重试/死信（复用既有 SQL 口径）；2) `PolicyCache` + `wgh_tenant_schedule_policy` 默认桶；3) 注册 handler：`EVAL_RUN`、`INGEST_FILE`、`WF_RUN`、`AGENT_PLAN`、`CONNECTOR_SYNC`、`UPLOAD_CLEANUP`、`SEMCACHE_CLEANUP`、`AGENTMEM_CLEANUP`、`STORAGE_ORPHAN_SCAN`、`KB_REBUILD`；4) 删除 `rag-kb` / `rag-workflow` 内的 7 处本地调度器（先保留开关一版）；5) 评测接口改 `202 + jobId`；6) 管理端只读接口 `/api/admin/scheduler/jobs`、`/runtime` |
| 可编译 | 是（handler 与被替换的调度器按域逐个提交） |
| 回滚 | 配置开关 `wgh.scheduler.background.enabled`；作业表只新增不删；旧调度器保留一版 |
| 验收 | 评测不占请求线程；kill -9 后 ≤90s 离开 RUNNING；两副本同一 CRON/连接器只执行一次；计划任务有执行记录 |
| 冲突面 | 中：`rag-kb` 的 `AgentMemoryService` 在 rag-chat 链路被调用，但只改「谁触发清理」，不改方法签名 |

### P4 模块拆分与收敛

| 项 | 内容 |
|---|---|
| 动作 | 1) `rag-kb` → `rag-kb` + `rag-document`（先搬 `parser`/`splitter` 两个包）+ `rag-eval`（再搬评测）（先拆包、再拆模块，每一步移动后立即编译）；2) `rag-public-access` 纯库化（servlet/security 适配上移 `rag-controller`）；3) 删除 `TaskRecoveryService`，`/admin/tasks/*` 改由 `rag-scheduler` 的 `RunCoordinator` + 各域 `RunAdapter` 提供；4) `rag-chat.evaluation` 迁入 `rag-eval`（**须在 rag-chat 重构合并后**）；5) `rag-kb` 去掉 `MultipartFile`，上传边界上移 |
| 可编译 | 是（拆包与拆模块分离，包移动不改逻辑） |
| 回滚 | 每次只拆一个子包；`rag-kb` 保留 `@Deprecated` 转发类一版；POM 依赖可回退 |
| 验收 | 依赖图满足 §4.3 全部「禁止」列；无模块 import `SseEmitter` / `MultipartFile`（`rag-controller` 除外）；`rag-scheduler` 依赖列表里没有任何执行域模块 |
| 冲突面 | 中：`rag-chat` 的评测类迁移需与重构线约定先后 |

### 5.1 搬迁顺序的依赖关系

```text
P0 ──► P1 ──► P2 ──► P3 ──► P4
              │        │
              │        └── 需要 P2 的 sink 抽象（后台执行同样要发事件）
              └── 需要 rag-chat 重构合流（唯一硬阻塞）
```

**不建议调整的地方**：P4 的 `rag-kb` 拆分必须在 P3 之后。理由是 P3 会把入库、评测、连接器改造成 handler；先拆模块会让「文件移动」与「执行模型改造」两件事同时进行，冲突面翻倍。

---

## 6. 与既有工作线的关系

### 6.1 与调度中心 S0–S3 / 合并阶段 A–D 的映射

| 本文阶段 | 调度阶段 | 合并阶段 | 多租户阶段 | 产出 |
|---|---|---|---|---|
| P0 | 前置 | A 之前 | — | 契约模块 + 依赖规则 |
| P1 | 前置 | A 之前 | — | 依赖净化，无行为变化 |
| P2 | S0 | A | 阶段 0 | 受管池、准入、优雅停机、失败边界 |
| P3 | S1 | B | 阶段 1 | 作业表、后台 lane、计划任务作业化 |
| P4 | S2 前置 | C 之前 | 阶段 2 前置 | 模块边界收敛，供策略/管理端落地 |

### 6.2 与向量库 / K8s 规划的关系

- `rag-kb` 拆分不影响 `VectorStorePort` / `KeywordSearchPort` 的位置（仍在 `rag-kb`）；`rag-document` 只做解析与切分，不接触向量端口，入库编排仍在 `rag-kb`。
- P2 的租约、单飞、优雅停机是 K8s 多副本的前置条件（[design-kubernetes-orchestration.md](design-kubernetes-orchestration.md) 已定）；本文不改该结论。

### 6.3 `rag-chat` 冻结区与合流门

**冻结范围**：`rag-chat` 全部 `src/main/java`，不做包移动、不做类拆分、不改方法签名。

**唯一允许的改动点（P2，合流后）**：

| 文件 | 位置 | 改动 | 为什么必须 |
|---|---|---|---|
| `RagChatService.java` | `streamChat` 中 `new Thread(MdcContext.wrap(...)).start()`（重构中当前位置约 L175） | 替换为 `executionFacade.submitInteractive(...)` | A13「无裸线程」 |
| `RagChatService.java` | `quotaService.checkAndIncrementChat(...)`（约 L142）与提交的先后 | 调整为 `admission → quota → execute` | B9；否则拒绝会误扣额度 |
| `ChatWorkflowService.java` | 事件回调签名 | 若 `WorkflowService` 改用 `ExecutionEventSink`，此处适配 | 编译依赖，非行为改动 |
| `ChatSsePublisher.java`（重构新增） | `send(SseEmitter, ...)` 静态方法 | 新增 `ExecutionEventSink` 的 SSE 实现，内部仍可委托本类 | 让「SSE 传输」与「执行」解耦的唯一低冲突落点 |

**对重构进度的事实核对（2026-11-20）**：`rag-chat` 工作区已抽出 9 个协作类，`RagChatService` 净减约 900 行，但三处关键缺陷**仍存在**：`streamChat` 仍是 `new Thread`（约 L175）、配额仍先于 emitter 与执行（约 L142）、`ChatSsePublisher` 仍是 `SseEmitter` 的静态包装而非传输无关 sink。因此 P2 的合流点与本文判断一致，只是行号随重构滚动，落地时以「方法名 + 语义」定位，不要依赖行号。

**过渡方案（不改 `rag-chat` 时）**：在 `ChatController` 侧对 `streamChat` 做「准入信号量 + 提交计数」。**必须明确其局限**：`streamChat` 内部立刻返回 emitter 并在子线程执行，控制器侧无法感知子线程结束，因此只能限制「提交速率」，不能限制「在途执行数」，A13 与 A11 无法闭环。此方案仅用于 P2 前半段不阻塞其它模块，不能作为终态。

**合流检查单**：rag-chat 重构合并后，先对齐三件事再落 P2——(a) `streamChat` 的线程创建位置是否变化；(b) 是否已内建取消/断开语义；(c) `ChatWorkflowService` 是否已抽象事件出口。三项对齐后，P2 的 chat 改动是 1–3 行的替换。

---

## 7. 目标依赖矩阵（终态）

| ↓依赖 / 被依赖→ | common | data | config | context | storage | llm | tool | kb | doc | eval | agent | workflow | chat | multi | execution | scheduler | public | ctrl |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `rag-execution` | ✔ | | | | | | | | | | | | | | — | | | |
| `rag-scheduler` | ✔ | ✔ | ✔ | | | | | | | | | | | | ✔ | — | | |
| `rag-document` | ✔ | | | | | | | | — | | | | | | | | | |
| `rag-kb` | ✔ | ✔ | ✔ | | ✔ | ✔ | | — | ✔ | | | | | | | | | |
| `rag-eval` | ✔ | ✔ | ✔ | | | ✔ | | ✔ | | — | | | | | ✔ | | | |
| `rag-agent` | ✔ | ✔ | ✔ | ✔ | | ✔ | ✔ | ✔ | | | — | | | | ✔ | | | |
| `rag-workflow` | ✔ | ✔ | ✔ | | | ✔ | ✔ | ✔ | | | ✔ | — | | | ✔ | | | |
| `rag-chat` | ✔ | ✔ | ✔ | | | ✔ | ✔ | ✔ | | ✔（评测迁移后） | ✔ | ✔ | — | | ✔ | | | |
| `rag-agent-multi` | ✔ | ✔ | ✔ | ✔ | | ✔ | ✔ | ✔ | | | ✔ | ✔ | | — | ✔ | | | |
| `rag-public-access` | ✔ | ✔ | ✔ | | | | | ✔ | | | | | ✔ | | | | — | |
| `rag-controller` | ✔ | ✔ | ✔ | | | ✔ | ✔ | ✔ | | ✔ | ✔ | ✔ | ✔ | ✔ | | ✔（只读） | ✔ | — |
| `rag-app` | ✔ | ✔ | ✔ | | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ |

**终态断言**（可作为 CI 检查项）：

1. 不存在 `rag-scheduler → 执行域` 的任何依赖。
2. 不存在执行域 → `rag-controller` / `rag-app` 的依赖。
3. 执行域与 `rag-kb`/`rag-document`/`rag-eval` 中不出现 `org.springframework.web`、`jakarta.servlet`、`MultipartFile`。
4. `rag-agent` 只依赖 `agent` 以下的模块；`chat` 与 `multi` 互不依赖。
5. `rag-public-access` 不依赖 `rag-controller`。

---

## 8. 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| **rag-chat 并行重构冲突（最大风险）** | P2 的唯一 chat 改动点正好是重构区（`streamChat` 线程创建、配额顺序、SSE 出口）；工作区已有 9 个新类，行号与结构仍在变 | P0/P1/P3/P4 完全不碰 chat；P2 在合流后按「方法名 + 语义」做 1–3 行替换；优先在 `ChatSsePublisher` 增加 `ExecutionEventSink` 实现（新增方法，不改既有调用）；控制器侧过渡方案只用于不阻塞，不作为验收依据 |
| 交互式 lane 引入拒绝策略 | 现有前端无 429 处理，行为从「无限排队」变为「快速失败」 | 灰度提高上限；同步补前端 429/503；`Retry-After` |
| `rag-kb` 拆分（106 文件 / 11.4k 行） | 移动即冲突，尤其 parser/splitter 与入库链路 | 放到 P4 最后；先拆包再拆模块；每步独立可编译；保留 `@Deprecated` 转发类一版 |
| `SsrfGuard` 下沉改包名 | 影响 `rag-kb.connector` 与 `rag-tool.security` 调用点、以及可能的反射配置 | 只移动类不实现；包名旧引用一次性替换；编译验证。**P1 已完成**：落到 `com.wgh.rag.common.net`，无反射配置引用，全仓编译通过 |
| 执行契约过度设计 | `rag-execution` 膨胀成第二个业务模块 | §4.4 明确「只定义签名」；新增类型需在评审中说明对应 S0–S3 的哪条验收 |
| 作业 payload 演进 | 旧作业新代码无法执行 | `payload.schemaVersion`；不兼容置 `DEAD(INCOMPATIBLE_PAYLOAD)`（沿用既有结论） |
| `rag-public-access` 纯库化 | filter 上移会改变安全链注册顺序 | 先移动类、保持 Bean 名与 `@Order` 不变；安全回归用例覆盖 |
| Enforcer 规则收紧过快 | 存量违规代码导致构建失败 | 规则分两步：先 `warn`（Enforcer 不支持 warn，则先在 CI 脚本里检查），再 `fail` |

**不建议做的取舍**（沿用既有结论，不再论证）：

- 不拆独立调度服务（[design-distributed-tenant-scheduling.md](design-distributed-tenant-scheduling.md) §4.4 的升级条件当前不满足）。
- 不引入 Temporal/Camunda 类引擎。
- 不把 `rag-chat` 纳入本次搬迁（冻结区）。
- 不为了「模块更细」把 `rag-context`、`rag-storage`、`rag-llm` 再拆。
- 不在 P2 之前做管理端可视化（会展示「假全局」数据）。

---

## 9. 未验证与待拍板

1. **未做运行压测**：线程放大倍数、排队时长、连接池耗尽均为静态推断，未实测。
2. **未验证多副本行为**：仓库只有单副本 compose，所有多实例结论来自代码路径。
3. **`rag-chat` 重构的最终形态未知**：工作区已可见 9 个新协作类与 `RagChatService` 的大幅瘦身，但重构尚未提交、也未合流；`streamChat` 的线程创建点、配额顺序、`ChatSsePublisher` 是否会被替换为端口，都可能继续变化。P2 的合流点需在该线合并后复核。
4. **`rag-kb` 拆分粒度待确认**：`rag-document` 是否只含 `parser`/`splitter`（本文按此划分，文件处理流水线与分片上传留在 `rag-kb`）、`rag-eval` 是否包含 judge，需要在 P4 启动前与负责人确认。
5. **`SsrfGuard` 归属**：**已定（P1）**——下沉到 `rag-common` 的 `com.wgh.rag.common.net`，未新建独立 `rag-net` 模块；当前调用方为 `rag-tool.HttpToolExecutor` 与 `rag-kb` 的 URL 连接器。
6. **`rag-tool` 的 web starter 是否真无用**：**已移除（P1）**。静态扫描 0 处 `org.springframework.web` / `jakarta.servlet` / `MultipartFile`，全仓 `-DskipTests compile` 通过；但因 `rag-common` 仍传递携带 web starter，运行时依赖面未变，完整启动验证仍建议在 P2 前后补一次。
7. **`rag-app` 显式装配的收益**：**仍未执行（P1 本轮未纳入）**。会让 `rag-app` 的 POM 变长，但能防止「模块加了却没生效」；是否采用需团队确认。
8. **ArchUnit 引入时机**：P0 只加 Enforcer 模块级规则；包级规则是否值得引入测试依赖，待 P2 评估。

---

## 10. 修订记录

| 日期 | 版本 | 修订内容 |
|---|---|---|
| 2026-11-20 | v1.3 | **P1 依赖净化落地**：删除 5 处未使用声明（新增确认 `rag-tool→rag-context`）；`SsrfGuard`/`SsrfBlockedException` 下沉到 `rag-common.net` 并切断 `rag-kb→rag-tool`；在 6 个模块 `pom.xml` 增加 Enforcer 依赖方向约束（未改根 POM）；全仓 `-DskipTests compile` 通过。同步校正 §2.1/§2.2/§2.3 的矩阵与漂移清单，补齐 §4.3/§4.5 落地状态，更新 §9.5–§9.7。**复核未改动**：`rag-public-access→rag-chat/rag-kb` 硬绑、`rag-chat` service 签名暴露 `SseEmitter` 两项结论仍然成立，按计划留到 P2 合流门之后。`rag-app` 显式装配与 `TaskRecoveryService→RunAdapter` 形状本轮未执行。 |
| 2026-11-20 | v1.2 | 新增 §4.8（P0 `rag-execution` 契约模块落地与命名取舍）。 |
| 2026-11-20 | v1.1 | 新增 §4.7（`rag-controller` 按业务域分包的结构与分组依据）。 |
| 2026-11-20 | v1.0 | 初稿：模块职责、依赖矩阵、目标划分与 P0–P4。 |

---

## 附录 A：证据索引

> 路径相对于仓库根目录；行号为本次核对时的实际位置。

| 结论 | 证据 |
|---|---|
| 26 个 Controller / 244 个 handler | `rag-backend/rag-controller/src/main/java/com/wgh/rag/controller/`（`@RestController` 26 个，另 1 个 `@RestControllerAdvice`；`@GetMapping`/`@PostMapping` 等 244 处） |
| 聊天每请求裸线程 | `rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/service/RagChatService.java#L175-L198`（重构中，行号会滚动） |
| 聊天配额先于执行 | 同上 `#L142`（重构中） |
| 服务层直接持有 `SseEmitter` | 同上 `#L151`（重构中）；`rag-backend/rag-workflow/src/main/java/com/wgh/rag/workflow/service/WorkflowService.java#L37`、`#L62` |
| rag-chat 重构进行中 | 工作区未提交：新增 `ChatSsePublisher`、`ChatMessageStore`、`ChatSessionStore`、`ChatContextBudgetService`、`ChatEvaluationService`、`ChatCostSettler`、`ChatPromptAssembler`、`ChatToolLoader`、`ChatCitationAssembler`；`RagChatService` 约 -900 行 |
| 单 Agent 同步执行 | `rag-backend/rag-controller/src/main/java/com/wgh/rag/controller/AgentController.java#L117-L120` |
| 多 Agent 每 run 裸线程 | `rag-backend/rag-controller/src/main/java/com/wgh/rag/controller/MultiAgentController.java#L91-L99`、`#L123-L131` |
| 工作流先插 RUNNING 后提交 | `rag-backend/rag-workflow/src/main/java/com/wgh/rag/workflow/service/WorkflowService.java#L403-L409`、`#L332`、`#L372`、`#L435-L444` |
| 工作流池 2/8/100 | `rag-backend/rag-workflow/src/main/java/com/wgh/rag/workflow/config/WorkflowExecutorConfig.java` |
| 工作流每 run 建 4 worker | `rag-backend/rag-workflow/src/main/java/com/wgh/rag/workflow/engine/WorkflowEngine.java#L468-L477` |
| 每实例加载 CRON | `rag-backend/rag-workflow/src/main/java/com/wgh/rag/workflow/service/WorkflowTriggerService.java#L52-L63` |
| 连接器每实例调度 | `rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/connector/ConnectorScheduler.java#L29-L49` |
| 上传清理每实例 | `rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/UploadSessionCleaner.java#L40-L55` |
| Agent 记忆清理每实例 + 单飞 | `rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/AgentMemoryCleaner.java`（`@PostConstruct` + 单线程调度；`pg_try_advisory_xact_lock`） |
| 孤儿清理每实例 + 单飞 | `rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/storage/StorageOrphanCleanupScheduler.java#L58`（`StorageMaintenanceLock`） |
| 单飞实现（2 处） | `rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/storage/StorageMaintenanceLock.java#L25-L56`；`AgentMemoryCleaner.java#L33`、`#L101-L114` |
| 评测同步 + 长事务 | `rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/EvalDatasetService.java#L181-L205` |
| 文件处理静态池 + CallerRuns | `rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KnowledgeFileService.java#L61-L75`、`#L445-L455` |
| 存储迁移任务进程内状态 | `rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/storage/StorageMigrationJobService.java#L62`、`MigrationJobStateStore.java#L50-L51` |
| 恢复服务在 controller 层 | `rag-backend/rag-controller/src/main/java/com/wgh/rag/controller/service/TaskRecoveryService.java#L99-L147`、`#L224-L242` |
| 取消标记 Redis 优先范式 | `rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/service/ChatCancellationStore.java#L14-L21`、`#L50-L100` |
| 端口化范式 | `rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/port/VectorStorePort.java`、`KeywordSearchPort.java` |
| 既有模块清单与分层 | `docs/design/design-overview.md` §3.1–§3.4 |
| 既有 Enforcer 规则 | `rag-backend/pom.xml#L305-L324` |
| 无优雅停机配置 | `rag-backend/rag-app/src/main/resources/` 下 5 个 yml 均无 `server.shutdown` |
| 事件出口雏形 | `rag-backend/rag-common/src/main/java/com/wgh/rag/common/event/DomainEventPublisher.java`、`rag-backend/rag-agent/src/main/java/com/wgh/rag/agent/service/AgentEventPublisher.java` |

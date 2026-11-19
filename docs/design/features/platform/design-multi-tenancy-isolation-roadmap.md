# 多租户隔离落地规划（复用阶段 0–4）

> **版本**：v1.0
> **日期**：2026-11-19
> **性质**：规划文档。不修改业务代码、不改配置、不执行 DDL、不重启服务、不做 git 操作。
> **定位**：本文是「多租户隔离」的统一规划层。**方案选型、薄弱点编号（G-01～G-15）、阶段 0–4 的定义均以既有分析为准，不重新论证、不改口径**；本文只做落地排期、跨块接口约定与验收编排。
> **复用来源（结论以此为准）**：
> - [multi-tenancy-analysis-2026-11-18.md](../../../review/multi-tenancy-analysis-2026-11-18.md)：选型、现状、G-01～G-15、阶段 0–4
> - [design-distributed-tenant-scheduling.md](design-distributed-tenant-scheduling.md) §5.1：阶段 0–3 与调度 S0–S3、恢复 L0–L3 的合并映射（A–D）
> - [design-unified-scheduler-and-tenant-policy.md](design-unified-scheduler-and-tenant-policy.md) §3：`tenant_id` 在调度侧的使用边界
> - [design-execution-scheduler-center.md](design-execution-scheduler-center.md)：调度中心的阶段与作业类型
> **未验证/推断项**：见 §8

---

## 0. 结论摘要

1. **一句话现状（复用）**：当前系统是「单组织 + 用户级资源可见性」的单体应用，不是多租户系统；`wgh_user.tenant_id` 是死字段，`TEAM` 与 `PUBLIC` 等价。
2. **目标选型（复用，不改）**：共享库共享表 + `tenant_id` 为主线，PostgreSQL RLS 为纵深防御，大客户按需独立 schema / 独立数据库（阶段 4）。
3. **落地阶段（复用，不改编号）**：阶段 0 先修越权（不引入租户）→ 阶段 1 租户模型与身份上下文 → 阶段 2 数据面全量隔离 → 阶段 3 企业级加固 → 阶段 4 混合分层（按需）。
4. **与调度对齐（复用 A–D）**：阶段 0 ↔ A/S0/L0，阶段 1 ↔ B/S1/L1，阶段 2 ↔ C/S2/L2，阶段 3 ↔ D/S3/L3；阶段 4 不进入主线，按需单独立项。
5. **本次新增的规划约束**：
   - **阶段 0 必须先于任何「多副本」动作**。当前 `docker-compose.yml` 可 `--scale backend=N`（`#L51-L53`），但没有租户上下文时扩容只会放大越权面与重复执行。
   - **阶段 1 的 `TenantContext` 必须与调度中心的 `TaskDecorator` 同批交付**，否则 S1 的作业化会制造大量 `create_by=system` 的无主资源。
   - **阶段 2 的租户过滤必须同时覆盖向量元数据与检索端口**，不能只改 SQL 拦截器；否则独立向量库落地后租户过滤会再次缺失（见 §5.3）。
6. **验收主线不是「功能可用」，而是「跨租户负向测试全通过」**：每类资源都必须有「B 租户访问 A 租户必须 404/403」的自动化用例（C10）。
7. **明确不做的取舍（复用）**：不在阶段 0–2 引入 schema-per-tenant / DB-per-tenant；不把 RLS 当作唯一手段；不用 Redis 作为租户配额的唯一事实源。

---

## 1. 现状与差距清单（摘要，不重复论证）

### 1.1 一句话判断

现有隔离强度 = 「5 张表的列表查询 + `create_by`/`visibility`」。`selectById`、原生 SQL、JOIN、子资源入口、开放 API、异步线程均不在保护范围内。

### 1.2 薄弱点（复用 G-01～G-15，按落地阶段归位）

| 阶段 | 必须关闭的薄弱点 | 说明 |
|---|---|---|
| 阶段 0 | G-01、G-02、G-03、G-04、G-08、G-09 | 开放 API 全局通行、子资源只校验父对象存在、检索测试无可见性、多 Agent 运行态无归属、分享/API Key 列表全量 |
| 阶段 1 | G-06、G-14、G-10（部分） | 异步线程 `create_by=system`、`tenant_id` 死字段、审计/配额无租户维度 |
| 阶段 2 | G-05、G-07、G-10、G-11、G-12、G-13 | 拦截器覆盖面、`PUBLIC` 语义、配额/审计、存储/Redis 命名空间、标签全局唯一、模型全局共享 |
| 阶段 3 | G-15 | 数据库超级用户导致 RLS 失效 |

> 完整证据与影响见 [multi-tenancy-analysis-2026-11-18.md](../../../review/multi-tenancy-analysis-2026-11-18.md) §3.12。

### 1.3 关键事实（决定排期）

| 事实 | 影响 |
|---|---|
| 单 `DataSource` + `currentSchema=public` + 单 Hikari | 阶段 4 之前不需要多数据源基础设施 |
| pgvector 每知识库一张物理表，表名存 `collection_name`（`01-schema.sql#L101`） | 阶段 2 只需保证「向量访问必经 KB 鉴权」，不必按租户拆向量库 |
| 语义缓存/Agent 记忆是共享向量表，metadata 只有 `windowId` / `scopeKey` | 阶段 2 必须给 metadata 与查询条件同时加 `tenantId` |
| 审计切面 `resourceId` 是「第一个 Long 参数」的尽力提取 | 不能作为租户归属依据，阶段 1 起改用 `TenantContext` |
| 现有 `DataScopeInterceptor` 只覆盖 5 张表且仅列表查询 | 阶段 2 需引入租户插件，而不是继续扩白名单 |

---

## 2. 目标模型（复用 §5.2，不改）

### 2.1 租户与用户

- 一个租户 = 一个企业/组织/法律实体；一个用户默认属于一个租户；平台管理员单独标记。
- `wgh_tenant(id BIGINT, code VARCHAR UNIQUE, name, status, plan, isolation_mode, create_time)`。
- `wgh_user.tenant_id` 启用；用户唯一约束改为 `(tenant_id, username)`（或保留全局唯一 + 租户内展示名，二选一需产品拍板，见 §8）。

### 2.2 上下文

```java
record RequestContext(Long tenantId, Long userId, String username, String requestId) {}
```

- Web 入口：过滤器解析令牌后设置 `TenantContext`（ThreadLocal）+ 写 MDC。
- 异步边界：**提交时捕获**，执行前设置、finally 清理；统一 `TaskDecorator`。
- 作业：`wgh_execution_job.tenant_id` / `owner_user_id` 显式存储，worker 从 payload 恢复。
- 禁止：执行线程里重新从 ThreadLocal 读、从请求参数取租户。

### 2.3 可见性语义

| 值 | 阶段 1 之后的语义 | 备注 |
|---|---|---|
| `PRIVATE` | 租户内仅自己 | 不变 |
| `TEAM` | 租户内团队（未来） | 当前仍等价于租户内可读 |
| `PUBLIC` | **租户内公开**（不再是全系统） | 语义变更，需迁移说明 |
| 平台内置资源 | 平台级共享（`builtin` 标记） | 内置 Prompt/工具 |

### 2.4 隔离层次

| 层 | 手段 | 生效阶段 |
|---|---|---|
| 应用层 | `TenantLineInnerInterceptor` + 现有 `DataScopeInterceptor`（visibility） | 阶段 2 |
| 数据库层 | RLS + 非 owner 角色 + `SET LOCAL app.tenant_id` | 阶段 3 |
| 存储层 | key 前缀 `t/{tenantId}/...` | 阶段 2 |
| 缓存层 | Redis `wgh:t:{tenantId}:...` | 阶段 2 |
| 向量层 | 共享向量表 metadata 加 `tenantId`；KB 集合经 KB 鉴权 | 阶段 2 |
| 调度层 | `tenant_id` 进作业表/策略表/运行槽 | 阶段 1 |

---

## 3. 分阶段落地路径

> 阶段定义、改动范围与验收均复用分析文档 §6；本节只补充**排期粒度、依赖关系、与其他三块的接口**。

### 3.1 阶段总表

| 阶段 | 目标 | 合并阶段 | 调度 | 恢复 | 多副本 | 工作量/风险（复用） |
|---|---|---|---|---|---|---|
| 0 | 先修越权，不引入租户 | A | S0 | L0 | 可上 2 副本 | S–M / 低–中 |
| 1 | 租户模型与身份上下文 | B | S1 | L1 | 可 | M / 中 |
| 2 | 数据面全量隔离 | C | S2 | L2 | 可 | L / 高 |
| 3 | 企业级加固（RLS、计量、生命周期） | D | S3 | L3 | 可，精确口径 | M–L / 中–高 |
| 4 | 混合分层（按需） | 不进入主线 | — | — | 视客户 | L / 高 |

### 3.2 阶段 0：先修越权（不引入租户）

**改动（复用）**：知识库子资源全量加父 KB 可读/可写校验；评测数据集改「可见 + 可写」；开放 API 绑定 owner 并继承可见性；多 Agent 运行态补归属校验；分享链接与 API Key 列表按 owner 过滤。

**本次补充的排期约束**
- 必须在任何 `--scale backend>1` 之前完成；否则越权面随副本数放大。
- 与调度 S0 同批：`server.shutdown=graceful`、受管池、单飞一起交付，避免两次发布两次回归。
- 阶段 0 **不新增 `tenant_id` 字段**，验收以「用户 A/B 负向测试」为准。

**验收（复用）**：B 不能读/写 A 的 PRIVATE 知识库、文件、评测、检索；B 不能通过 `/v1/*`、`/api/open/**` 访问 A 的资源；B 不能操作 A 的多 Agent 运行；现有单租户功能回归通过。

**风险**：开放 API 收紧后，历史上「全局 Key」的调用方会 403/404，需要兼容期与公告。

### 3.3 阶段 1：租户模型与身份上下文

**改动（复用）**：`wgh_tenant`；启用 `wgh_user.tenant_id`；令牌/OIDC claim 写租户；`TenantContext` + 过滤器；平台管理员与租户管理员分离；租户表加 `tenant_id`（先可空，回填后 NOT NULL）。

**本次补充**
- `TaskDecorator` 与调度中心同批：所有池（interactive/background/maintenance）、`@Async`、工作流 worker、Crew worker 统一包装。
- `wgh_execution_job`、`wgh_tenant_schedule_policy` 从上线即带 `tenant_id`，默认桶 `0`。
- 审计切面改为从 `TenantContext` 取租户，不再依赖参数猜测。
- 存量数据全部归入默认租户，并在管理端标注「迁移租户」。

**验收（复用 + 补充）**
- 日志/MDC/审计都能看到 `tenantId`；无租户令牌被拒绝。
- 异步线程创建的资源 `create_by` / `tenant_id` 正确，不再是 `system`。
- 作业在 worker 中执行时能读到正确租户。
- 跨租户用户管理被拒绝。

**风险**：漏传上下文会产生「无主」数据；需要启动自检（扫描 `tenant_id IS NULL` 的增量）与负向测试。

### 3.4 阶段 2：数据面全量隔离

**改动（复用）**：租户插件 + `DataScopeInterceptor` 组合；子资源先校验父资源租户；存储 key 加租户；Redis key 加命名空间；向量 metadata 加 `tenantId`；配额/审计/API Key 加租户；`PUBLIC` 语义改为租户内公开。

**本次补充**
- **向量层必须通过端口改造**：`VectorStorePort` / `KeywordSearchPort` 的实现里，凡共享集合（语义缓存、Agent 记忆）必须带 `tenantId` 过滤；KB 集合仍走 KB 鉴权，但要在端口上加「租户断言」参数，避免未来接入独立向量库时遗漏（见 §5.3）。
- **独立搜索引擎接入前，租户过滤必须在检索编排层完成**（不依赖引擎侧权限），否则换引擎即回归。
- 原生 SQL 清单（`ChatMessageMapper`、`SemanticCacheMapper`、配额 Mapper、RBAC Mapper）逐条改造，纳入代码评审清单。

**验收（复用 + 补充）**
- 每种资源都有跨租户负向测试（A→B 必须 404/403）。
- 存储/缓存/向量命名空间可按租户隔离与导出。
- 配额与审计可按租户聚合。
- 性能基线不劣化超过约定阈值（需压测确认）。

**风险**：全量拦截器改造面大；pgvector 表/集合命名与租户前缀迁移需要窗口；大租户可能拖慢同库其他租户（需限流配合）。

### 3.5 阶段 3：企业级加固

**改动（复用）**：非 owner 应用角色；所有租户表 `ENABLE` + `FORCE ROW LEVEL SECURITY`；`SET LOCAL app.tenant_id`；按租户计量计费；租户生命周期（停用/导出/删除/备份）；平台管理端代管留痕。

**本次补充**
- RLS 生效后，调度器的 claim/心跳 SQL 也必须带租户上下文或使用独立的「调度角色」白名单，否则后台作业会读不到行。
- 租户删除必须覆盖：数据库行、向量集合、对象存储前缀、Redis 命名空间、调度作业与策略。

**验收（复用）**：原始 SQL 直连也无法跨租户读取；导出/删除演练通过；账单与用量核对一致；审计可按租户检索。

### 3.6 阶段 4：混合分层（按需）

**触发条件（复用）**：强合规/数据驻留、物理隔离要求、超大客户付费意愿。**不进入 A–D 主线**。

**实施要点**：`wgh_tenant.isolation_mode` 路由；租户迁出/迁入工具；独立池的备份、监控、升级编排；调度中心通过 `tenant_id → datasource` 路由支持，策略模型不变。

---

## 4. 数据模型变更清单（摘要）

> 完整逐表改造点见分析文档 §3.5。此处只列**改动类型**，避免与源文档产生两套口径。

| 类型 | 对象 | 变更 |
|---|---|---|
| 新增 | `wgh_tenant` | 租户主体 |
| 启用 | `wgh_user.tenant_id` | 唯一约束、登录/令牌链路 |
| 加列 | 所有租户-owned 表 | `tenant_id BIGINT`，先可空后 NOT NULL |
| 唯一键调整 | `wgh_tag`、`wgh_user`、`wgh_quota_usage` | 前导加 `tenant_id` |
| 索引调整 | 高频列表表 | `(tenant_id, create_by, visibility)` 等前导索引 |
| 语义变更 | `visibility='PUBLIC'` | 改为租户内公开 |
| 新增（调度） | `wgh_execution_job`、`wgh_tenant_schedule_policy`、`wgh_tenant_runtime_slot` | 见调度文档 §5.1 |
| 新增（阶段 3） | 计量/账单视图 | 按租户聚合 token/chat/storage/retrieval |

---

## 5. 与其他三块的接口约定

### 5.1 与任务调度中心

| 约定 | 内容 |
|---|---|
| 字段 | 作业表/策略表/运行槽从第一天带 `tenant_id`，默认 `0` |
| 顺序 | `admission → quota → execute → release`；避免拒绝后已扣额度 |
| 上下文 | 作业 payload 携带 `tenantId`/`ownerUserId`；worker 执行前设置、finally 清理 |
| 管理端 | 租户管理员只能看/改本租户；平台管理员跨租户需审计 |
| 阶段 | 阶段 1 必须与 S1 同批，否则作业化会产出无主数据 |

### 5.2 与 K8s 编排

| 约定 | 内容 |
|---|---|
| 前置 | 阶段 0 完成前禁止多副本；阶段 1 完成前禁止按租户做资源配额（ResourceQuota 只能按命名空间，不等于租户） |
| 命名空间 | 建议「平台命名空间」而不是「每租户命名空间」；租户隔离在应用层与数据库层完成，K8s 命名空间不作为隔离手段 |
| 标签 | 工作负载打 `app.kubernetes.io/*` 与 `wgh.io/component`，不要按租户打标签（租户数量不可控） |
| 资源 | 单租户限流由调度策略承担，不依赖 K8s ResourceQuota |

### 5.3 与向量库 / 搜索引擎

| 约定 | 内容 |
|---|---|
| 过滤位置 | 租户过滤在检索编排层 + 端口层双重保证，不依赖引擎 ACL |
| metadata | 共享集合写入 `tenantId`；KB 集合保留 `kbId`，经 KB 鉴权后再检索 |
| 索引 | 若未来改为共享大集合，必须建 `(tenantId, ...)` 前导过滤索引/分区 |
| 切换 | 后端切换（pgvector → 独立向量库）时，租户过滤能力属于「切换门槛」，缺失则不允许切换 |

---

## 6. 风险与不做的代价

### 6.1 主要风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 阶段 0 只修一部分入口 | 越权面残留，后续阶段误以为已隔离 | 以 G-01～G-04、G-08、G-09 为关闭清单，逐条负向测试 |
| `PUBLIC` 语义变更 | 存量共享资源突然不可见 | 迁移脚本 + 变更公告 + 管理端「可见范围」批量调整 |
| 租户上下文漏传 | 无主数据、配额串桶 | 启动自检 + `tenant_id IS NULL` 增量告警 + 负向测试 |
| 拦截器漏覆盖原生 SQL | 跨租户读写 | SQL 评审清单 + RLS 兜底（阶段 3） |
| RLS 与连接池/异步 | 会话变量残留或缺失 | `SET LOCAL`（事务级）+ 独立调度角色 + 压测 |
| 阶段 4 过早启动 | 运维成本翻倍 | 明确触发条件，按客户单独立项 |

### 6.2 不做的代价（复用）

- 不做阶段 0：开放 API 与子资源入口持续越权，多租户无从谈起。
- 不做阶段 1：异步/调度任务无归属，审计与配额无法按租户。
- 不做阶段 2：缓存、存储、向量跨租户泄露，合规风险。
- 不做阶段 3：无法证明数据库层隔离，企业客户无法过安全评审。

---

## 7. 验收标准与负向测试

### 7.1 验收标准（补充分析文档 §6 的通用项）

| 编号 | 标准 | 阶段 |
|---|---|---|
| M1 | 每类资源都有 A→B 负向自动化用例，且全部 404/403 | 0/2 |
| M2 | 令牌缺失/未知/停用租户的请求一律拒绝 | 1 |
| M3 | 异步线程、作业、定时任务的 `tenant_id` 与发起人一致 | 1 |
| M4 | 存储/缓存/向量命名空间可按租户导出与删除 | 2 |
| M5 | 配额检查与自增原子化，并发 100/limit 10 成功数 ≤10 | 2 |
| M6 | 原始 SQL 直连（非应用路径）无法跨租户读取 | 3 |
| M7 | 租户导出/删除/恢复演练通过，且可回滚 | 3 |
| M8 | 正常聊天 P95 不劣化（阈值需压测确认） | 2 |

### 7.2 负向测试矩阵（最小集）

| 资源 | 用例 | 期望 |
|---|---|---|
| 知识库 | B 用 A 的 kbId 读/写/检索 | 404 或 403 |
| 知识库文件 | B 上传/删除/重切/预览 A 的文件 | 404 |
| 评测数据集 | B 读/改/评测 A 的数据集 | 404 |
| 聊天窗口 | B 按 windowId 访问 A 的 PRIVATE 窗口 | 404 |
| 多 Agent 运行 | B 用 A 的 runId stream/state/hitl/cancel | 404 |
| 开放 API | B 的 Key 列 A 的窗口 / 检索 A 的 KB | 空列表 / 404 |
| 语义缓存 | 同 windowId（跨租户构造） | 不返回他租户答案 |
| 审计 | A 租户管理员查 B 的审计 | 403 |

---

## 8. 未验证与待拍板项

1. **用户唯一约束**：`(tenant_id, username)` 还是「全局唯一 + 租户内展示名」，需要产品与运维拍板（影响登录与 OIDC 映射）。
2. **`PUBLIC` 语义切换的迁移方式**：一次性改语义，还是新增 `TENANT_PUBLIC` 取值分阶段切换，需要拍板。
3. **平台管理员是否默认读业务数据**：分析文档建议「默认不读、代管需审计」，需安全评审确认。
4. **阶段 4 是否需要**：取决于是否有强合规客户，当前无输入。
5. **性能影响**：租户条件与拦截器对列表查询的影响未压测。
6. **存量无主资源归属**：`create_by='system'` 的历史资源归属哪个租户，需要业务确认。

---

## 附录 A：证据索引

| 结论 | 证据 |
|---|---|
| 无租户实体、`tenant_id` 死字段 | `rag-backend/sql/schema/01-schema.sql#L1191`、`rag-auth/.../entity/WghUser.java#L41` |
| 可见性仅 5 表 + 列表查询 | `rag-backend/rag-data/.../scope/DataScopeContext.java#L27-L32`、`DataScopeInterceptor.java#L51-L101` |
| 子资源绕过归属校验 | `rag-kb/.../service/KnowledgeFileService.java#L78-L98`、`EvalDatasetService.java#L378-L383` |
| 开放 API 无 owner/租户 | `rag-public-access/.../config/ApiKeyAuthenticationFilter.java#L68-L71`、`rag-controller/.../OpenAiCompatController.java#L179-L201` |
| 语义缓存仅窗口隔离 | `rag-kb/.../service/SemanticCacheService.java#L32-L42` |
| 存储 key 无租户 | `rag-storage/.../service/StorageKeys.java#L11-L42` |
| Redis key 无租户 | `rag-public-access/.../service/PublicAccessRateLimiter.java#L71-L73` |
| 超级用户连接 | `docker-compose.yml#L10-L13` |
| 阶段 0–4 定义 | `docs/review/multi-tenancy-analysis-2026-11-18.md` §6 |
| A–D 合并映射 | `docs/design/features/platform/design-distributed-tenant-scheduling.md` §5.1 |

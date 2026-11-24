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
| 数据库层 | RLS + 非 owner 角色 + `SET LOCAL app.tenant_id` | 阶段 3（现状租户键为 `VARCHAR` code，策略不做 `::bigint` 转换，见 §3.5.1） |
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

### 3.1.1 落地进度（2026-11-24）

> 状态口径：**已落地** = 代码与迁移脚本已提交到工作区；**已验证** = 受影响模块编译通过（单测随并发重构稳定后补跑）；**待决策** = 需产品/架构拍板后再动。

| 阶段 | 状态 | 已完成 | 未完成 / 说明 |
|---|---|---|---|
| 0 | 已落地，编译已验证 | 子资源父 KB 可读/可写校验；评测「可见+可写」；开放 API 绑定 Key owner 并继承可见性；多 Agent 运行态归属校验；分享/API Key 列表按 owner 过滤 | 历史「全局 Key」调用方会 403/404，需公告兼容期 |
| 1 | 已落地，编译已验证 | `wgh_tenant` + `05_tenant.sql`；`TenantContext`（ThreadLocal+MDC）+ `AuthContextPropagation` 覆盖 @Async/调度/工作流/Agent 超时线程；本地 token 与 OIDC claim 写租户；认证后过滤器写上下文；提交时冻结、worker 恢复、finally 清理；审计/API Key/调用日志租户列；`/api/admin/tenants` 与 `tenant:manage` | 采用 `VARCHAR` 租户 code（非 BIGINT id）；`wgh.tenant.enforcement` 默认 false；未新增独立 `platform_admin` 角色；`username` 仍全局唯一 |
| 2 | 已落地（SQL/存储/缓存/向量 metadata） | SQL 隔离插件 + 38 张表白名单 + `06_tenant_data_plane.sql`；存储非默认租户前缀 `t/{tenantId}/kb/`（默认租户沿用 `kb/`，存量零迁移）；孤儿扫描按租户前缀收敛且未知归属一律跳过；SSE 重放/取消标记租户命名空间；`PUBLIC` 收敛为租户内公开；向量/KB chunk metadata 写入 `tenantId`，语义缓存与 Agent 记忆共享集合按租户过滤，检索编排层对非默认租户下发端口级租户断言 | 默认租户与无上下文链路不加租户条件（兼容改造前无 `tenantId` 的存量向量）；Redis 中的平台级缓存（用户信息/JWKS）不做租户化 |
| 3 | dev 已启用并验收，prod/test 未执行 | RLS 评审与 9 项决策定稿；`09`～`15` 脚本；Prompt/Tool 显式 `scope` 标记；**`wgh_rag_dev` 已执行前置迁移 + 09→A→B→D→12 并逐组验收通过** | prod/test 未执行任何 DDL/授权；应用仍以 `postgres` 超级用户连接，RLS 对应用当前不可见；事务级 GUC、登录审计/调用日志写入顺序、Prompt/Tool 插件放行待随切换 `wgh_app` 实施 |
| 4 | 未开始 | — | 按客户立项 |

**阶段 2 说明**
- SQL 隔离默认开启（`wgh.tenant.sql-isolation-enabled=true`），无租户上下文（启动/维护/匿名公开链路）与平台管理员（`admin`）不追加条件；业务入口仍需保留父资源校验，插件只做纵深防御。
- `PUBLIC` 不再需要新增 `TENANT_PUBLIC` 取值：租户条件会把 PUBLIC 行限制在本租户内；平台级公开内容后续单独引入平台标记。
- 审计与网关层：审计切面、账号安全审计（登录场景显式传目标用户租户）、API 调用日志均带 `tenant_id`；API Key 所属租户停用/未知时直接拒绝；停用租户的令牌即使非严格模式也不会回落到默认租户。
- 缓存层：SSE 重放缓冲与对话取消标记已改为 `{root}:t:{tenantId}:...`（临时 key，无需迁移；跨租户猜 streamId 只会查到自己命名空间）；用户信息缓存与 JWKS 属平台级共享，不做租户化。
- 存储 key：默认租户沿用历史 `kb/{kbId}/{uuid}/{file}`（存量对象零迁移、读写不变）；其他租户为 `t/{tenantId}/kb/...`。`UploadSession.tenant_id` 在初始化时冻结，续传/合并不再依赖线程上下文。
- 孤儿清理：只调用 `StorageInventoryService.listByPrefix(tenantPrefix)`，禁止全量列举；`KnowledgeFile`/`UploadSession` 查询显式限定 `tenant_id`；对象 key 无法解析租户或不在扫描前缀内一律跳过、不删除；删除前仍有保护期与元数据校验。
- **上线前只读核对流程**：先用 `POST /api/admin/storage/orphans/scan?delete=false&tenantId=default` 记录 `scanned/orphans/missingReferences`，与旧版本全量扫描结果对比。预期差异：默认租户完全一致（存量对象都在 `kb/`，引用同一批记录）；非 `kb/` 前缀对象（迁移临时文件等）不再纳入，属预期减少。差异无法逐条解释时禁止开启 `delete=true`。
- 向量层（本批已完成，默认行为不变）：KB chunk 的 `metadata.tenantId` 在入库时写入（取 `TenantContext`，本地兜底线程池按提交时上下文传播），事实层回填缺省时按 `wgh_kb_chunk.tenant_id` 补齐；语义缓存与 Agent 记忆的共享向量集合写入时强制带 `tenantId`，查询时非默认租户追加租户条件；检索编排层（`RagRetrieveService`）对向量与关键词两路统一下发租户断言。端口过滤类型为「租户编码字符串」（`VectorFilter` / `KeywordFilter` 的 `tenantId` 为 `String`），与 `TenantContext`、调度 `tenant_id` 口径一致。默认租户与无上下文链路不加条件，存量无 `tenantId` 的向量仍可读；RLS 策略只做兜底，不承担召回过滤。
- **存储命名空间安全红线（定稿）**：清理逻辑无法确定归属时一律跳过、不删除；扫描范围按租户前缀收敛，不做全库推断；上线前先只读统计「按新前缀规则会被判为孤儿」的对象数量并与旧口径对比，差异全部可解释后才允许开启删除。`UploadSession.tenantId` 必须与孤儿清理前缀同批交付。

**迁移登记**：SQL 增量 `sql/migrations/20261124-01/05_tenant.sql`（租户目录/身份归属）、`06_tenant_data_plane.sql`（34 张业务表 tenant_id，幂等、含回填）；配置增量 `config/migrations/20261124-01/09_tenant.yml`；两者均需部署前手动执行/合并，不纳入迁移 Job。

**已定决策（2026-11-24）**：
1. 租户标识沿用 `VARCHAR` 租户 code（与调度 `tenant_id` 口径一致），不引入 BIGINT 代理键。
2. `wgh.tenant.enforcement` 默认 `false`；**多租户正式上线前必须置为 `true`**，这是上线前置条件（写入发布检查单）。
3. 暂不新增 `platform_admin` 角色，现有 `admin` 即平台管理员；**风险**：admin 权限集中，无法区分「平台运维」与「租户业务管理员」，拆分角色列为后续项。
4. `wgh_user.username` 保持全局唯一，不做租户内唯一；**已知限制**：不同租户不能使用相同用户名。

**仍待决策**：
1. 阶段 2 的 `PUBLIC` 语义：一次性改为租户内公开，还是新增 `TENANT_PUBLIC` 分阶段切换。
2. 阶段 3 的 RLS 数据库角色与连接池切换窗口。

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

### 3.5.1 阶段 3 RLS 设计评审（2026-11-24，只出方案，不改代码/不建策略）

> 本文是评审产出，**未执行任何 DDL**。所有表名、字段类型、已实现能力均以当前工作区为准；与原方案冲突处已在 §3.5.1.2 标注偏差，实施时以现状为准。

#### 3.5.1.1 结论摘要

1. 采用「应用层租户插件 + 数据库 RLS」双层纵深防御：插件负责请求期早过滤与开发期可读性，RLS 负责数据库层兜底；**不以 RLS 替代插件**。
2. RLS 的**硬前置条件**尚未满足：当前应用与迁移脚本都以 `postgres` 超级用户连接（`application.yml` 的 `WGH_DB_USERNAME` 默认 `postgres`、k8s `configmap-backend.yaml` 的 `SPRING_DATASOURCE_USERNAME: "postgres"`、`migrate/run-migrations.sh` 默认 `PGUSER=postgres`）。超级用户与表 owner 默认绕过 RLS，**不换非 owner 角色则 RLS 形同虚设**。
3. 租户标识是 `VARCHAR(64)` 租户 code（默认 `default`），不是原方案的 BIGINT。策略必须做**文本比较**，原 `current_setting('app.tenant_id')::bigint` 会直接报错。
4. 38 张租户表中，**32 张可标准开启 RLS**；`wgh_share_link`、`wgh_api_key`（免登录/预认证链路）、`wgh_api_call_log`、`wgh_audit_log`（异步/登录前写入）4 张需要专门策略或独立数据库角色；`wgh_prompt_template`、`wgh_tool` 因平台内置资源共享，待标记方案确认后再定。
5. 会话变量使用事务级 `set_config('app.tenant_id', ?, true)`（等价 `SET LOCAL`），**必须在事务内**。已拍板**不切换 Hikari 全局 `auto-commit=false`**：连接池保持现状，需要 RLS 的访问一律落在既有事务内，只读查询显式使用只读事务。缺失变量时 `current_setting(..., true)` 返回 NULL，比较结果为 NULL → 0 行，天然 fail-closed。

#### 3.5.1.2 与原方案的偏差（以现状为准）

| 项 | 原方案 | 当前现状 | 评审处理 |
|---|---|---|---|
| 租户键类型 | `tenant_id BIGINT`，策略 `::bigint` | `VARCHAR(64)` 租户 code，默认 `default` | 策略用 `tenant_id = current_setting('app.tenant_id', true)`，不做类型转换 |
| 租户表范围 | “所有租户-owned 表” | `TenantTableRegistry` 白名单 38 张（迁移 06 新增 34 张 + 既有 4 张：`wgh_kb_chunk`/`wgh_api_key`/`wgh_api_call_log`/`wgh_audit_log`） | RLS 与白名单逐表对齐；另评估调度 4 表 |
| 调度表 | `wgh_execution_job` 等带 `tenant_id` | 存在且已带 `tenant_id`，但**不在** MyBatis 租户插件白名单 | RLS 单独处理：调度角色/策略白名单（见 §3.5.1.3 D 组、决策点 8） |
| 平台管理员 | 可跨租户代管 | 应用层 `admin` 角色被插件直接 bypass | 单 DB 角色下 RLS 仍会拦截；需双角色或显式策略（决策点 1） |
| `PUBLIC` 语义 | 改为租户内公开 | 已由租户插件天然收敛为租户内公开 | RLS 策略与插件一致，不需要额外语义分支 |
| `wgh_user` | 纳入租户改造 | 已 `NOT NULL DEFAULT 'default'`，但不在租户表白名单 | 认证发生在租户上下文之前，**不纳入 RLS**，保持全局可查 |
| 缓存命名空间 | `wgh:t:{tenantId}:...` | 仅 SSE 重放/取消标记租户化；用户信息缓存、JWKS 仍平台级 | 与 RLS 无直接关系，但租户删除清单需区分平台级缓存 |
| 唯一键 | 前导加 `tenant_id` | `wgh_user.username` 仍全局唯一（已定决策） | 不影响 RLS，记录为已知限制 |

#### 3.5.1.3 表分级与 RLS 适用性

**A. 标准开启（32 张）**：策略统一为 `USING (tenant_id = current_setting('app.tenant_id', true))` + `WITH CHECK` 同表达式；`ENABLE` + `FORCE ROW LEVEL SECURITY`。

- 知识库域：`wgh_knowledge_base`、`wgh_knowledge_file`、`wgh_knowledge_file_version`、`wgh_kb_chunk`、`wgh_kb_connector`、`wgh_kb_connector_run`、`wgh_kb_ground_truth`、`wgh_upload_session`、`wgh_tag`、`wgh_kb_tag_rel`、`wgh_file_tag_rel`
- 对话域：`wgh_chat_window`、`wgh_chat_session`、`wgh_chat_message`、`wgh_semantic_cache`
- 评测域：`wgh_eval_dataset`、`wgh_eval_case`、`wgh_eval_result`
- Agent 域：`wgh_agent_memory`、`wgh_agent_plan`、`wgh_agent_step`、`wgh_m_agent_crew`、`wgh_m_agent_run`、`wgh_m_agent_step`
- 工作流域：`wgh_workflow`、`wgh_workflow_version`、`wgh_workflow_run`、`wgh_workflow_node_run`、`wgh_workflow_trigger`
- 配额域：`wgh_user_quota_config`、`wgh_quota_usage`、`wgh_quota_charge`

策略模板（仅供评审，未执行）：
```sql
ALTER TABLE wgh_knowledge_base ENABLE ROW LEVEL SECURITY;
ALTER TABLE wgh_knowledge_base FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON wgh_knowledge_base
  USING (tenant_id = current_setting('app.tenant_id', true))
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
```

**A'. 平台内置资源（2 张）**：`wgh_prompt_template`、`wgh_tool`。两者同时存在租户自建与平台内置资源。已定（§3.5.1.9 #4）：增加显式 `scope`（`platform` / `tenant`），不靠 `tenant_id='default'` 隐式表达；DDL/策略见 `12_rls_prompt_tool_scope.sql`，应用侧插件放行待随灰度实施。

> 注：配额三表的后台退款/汇总作业会跨租户批处理，**不能**走 `wgh_scheduler`（该角色对业务表无授权），必须按租户逐个进入 `wgh_app` + 租户 GUC 短事务，方案见 §3.5.3.4。

**B. 需要特殊策略（4 张）**

| 表 | 特殊原因 | 建议 |
|---|---|---|
| `wgh_share_link` | `/api/public/**` 匿名访问，请求无登录租户；按 token 查询发生在解析租户之前 | 方案一：独立“公开访问”DB 角色，仅允许 `SELECT` 且策略 `USING (true)`（行数少、token 唯一）；方案二：在专用连接上设置 `app.share_token` 并按 token 策略放行。**禁止**把 `app.tenant_id` 设为通配符 |
| `wgh_api_key` | `ApiKeyAuthenticationFilter` 验 Key 时租户未知，必须先按 `key_hash` 查到 Key 才能得到 `tenant_id` | 独立“预认证”角色：仅 `SELECT` 且策略 `USING (true)`（或仅按 `key_hash` 的等值查询），查到后立即切回租户角色/设置 GUC |
| `wgh_api_call_log` | 过滤器的 `finally` 中写日志，当前实现先 `TenantContext.clear()` 再 `record()` | 二选一：调整顺序在清理前写；或写日志时显式 `set_config('app.tenant_id', key.tenantId)`。RLS 的 `WITH CHECK` 要求 GUC 与行值一致 |
| `wgh_audit_log` | 登录成功/失败发生在认证过滤器写上下文之前；异步落库线程可能无 GUC | 登录审计：写库前显式按目标用户租户设置 GUC；异步审计：依赖 `AuthContextPropagation` 恢复上下文，或在 `AuditLogService` 连接内按 `log.tenantId` 设置 GUC |

**C. 不建议开启 RLS（平台级）**

- `wgh_tenant`（租户目录，平台管理）、`wgh_user`/`wgh_role`/`wgh_permission`/`wgh_role_permission`/`wgh_user_role`（认证与 RBAC 先于租户上下文）、`wgh_llm_model`/`wgh_embedding_model`/`wgh_model_price`（全局共享模型与价格）、`wgh_scheduler_instance`（集群级）。
- 平台内置 `wgh_prompt_template`/`wgh_tool`：已定增加显式 `scope` 列（`platform` / `tenant`），由 `wgh_platform` 管理平台资源，普通租户只读（§3.5.1.9 #4）。

**D. 调度 4 表**

`wgh_execution_job`、`wgh_tenant_schedule_policy`、`wgh_tenant_runtime_slot` 已带 `tenant_id`（`VARCHAR`，默认 `default`；slot 主键为 `(tenant_id, lane)`），但不在应用租户插件白名单；`wgh_execution_event` 没有 `tenant_id`。已定（§3.5.1.9 #5）：
- 作业 claim/心跳/扫描使用**调度专用 DB 角色** `wgh_scheduler`，仅对这 3 张表开放 `USING (true)`，其他表无权限；
- **不做**按租户分片扫描；
- `wgh_execution_event` 不开 RLS，通过 `execution_id` 归属校验。

#### 3.5.1.4 连接池与会话变量（关键设计）

**目标**：每条 SQL 执行时，当前数据库事务内 `app.tenant_id` 与该请求/作业的租户一致；连接归还后不残留；异步线程不串号。

**推荐方案（事务级 GUC + 连接代理）**

1. 应用使用非 owner 角色 `wgh_app`（`NOSUPERUSER NOBYPASSRLS`），迁移/建表继续用 owner（`postgres`）。
2. **连接池保持现状**：不全局设置 `auto-commit=false`。需要 RLS 的写路径沿用既有 `@Transactional`，只读路径显式使用 `@Transactional(readOnly=true)`；由 Spring 事务管理器提交/回滚，事务级 GUC 随事务结束自动失效。
3. 在事务开始时设置 GUC（建议 `TransactionSynchronization` / AOP / MyBatis 拦截器，确保在当前事务内、首条语句执行前完成）：
   `SELECT set_config('app.tenant_id', ?, true)`（第三参 `true` = 事务级，等价 `SET LOCAL`）。**不要**在自动提交模式下通过 `DataSource.getConnection()` 设置，语句结束即失效。
4. MyBatis/业务代码不改写 SQL；插件与 RLS 各自独立生效。
5. 同一事务内复用连接不重复设置；`afterCompletion` 不做 `RESET`（事务结束自动失效）。
6. 无租户上下文（启动、迁移、匿名公开、调度扫描）→ 不设置 GUC；此时 `current_setting(..., true)` 为 NULL，标准策略返回 0 行，必须由专用角色/策略处理这些链路（见 B/D 组）。

**不推荐**
- `SET`（会话级）：连接池复用会残留到下一个租户；必须每次借出/归还 `RESET`，遗漏即串租户。
- `setConnectionInitSql`：只能写死值，无法按请求变化。
- 在自动提交模式下 `SET LOCAL`：语句结束即失效，等于没设。

**边界与验证**
- 嵌套事务/`REQUIRES_NEW`：内层新事务需要重新设置 GUC（连接可能不同）；代理在每次物理借出时设置即可覆盖。
- 连接池 `minimumIdle` 预热连接：代理只在实际借出时设置，预热不影响。
- 若引入 PgBouncer：必须使用 `transaction` 模式；`SET LOCAL` 随事务生效可兼容；`session` 模式需确保归还前清理。
- 必须补一条**运行时自检**：启动时以 `wgh_app` 执行 `SELECT current_user, rolsuper, rolbypassrls`，发现超级用户/BYPASSRLS 直接告警或拒绝启动（建议做成开关，避免迁移环境误伤）。

#### 3.5.1.5 与 MyBatis 租户插件的职责边界

| 关注点 | MyBatis 插件（阶段 2 已上线） | RLS（阶段 3） |
|---|---|---|
| 目标 | 请求期尽早过滤、减少无效数据、兼容无 RLS 环境 | 数据库层兜底，防漏写/原生 SQL/直连 |
| 覆盖 | 白名单 38 表；无上下文与 admin 时跳过 | 按策略表逐表；无 GUC 时默认拒绝 |
| 失效场景 | 原生 SQL、绕过 Mapper、手工连接、插件被关闭 | 超级用户/表 owner（未 FORCE）、专用豁免角色 |
| 去留 | **保留**，不因 RLS 下线 | 不替代插件 |

**避免重复过滤的副作用**
- 双层都会生成 `tenant_id = 'code'` 条件，属于冗余但无害；PostgreSQL 会在计划期合并相同谓词，成本可忽略。
- 真正的冲突在**管理端跨租户**：插件对 `admin` 直接跳过，但 RLS 仍按 GUC 过滤。若平台管理员只用一个 DB 角色，跨租户列表会变空。解决方式见决策点 1：
  - 双角色：普通请求用 `wgh_app`；平台管理端用 `wgh_platform`（仅对管理只读视图/审计表 `USING (true)`，不给 BYPASSRLS）。
  - 单角色 + 显式 `app.platform=true` 策略：策略写成 `current_setting('app.platform', true) = 'on' OR tenant_id = ...`，但一旦 GUC 可被业务设置，等于把开关交给应用，安全性弱于双角色。
- **建议**：保留插件 + RLS，平台/调度用独立角色；不为“避免重复过滤”而关闭任何一层。

#### 3.5.1.6 性能与索引影响

1. 策略谓词是 `tenant_id = 常量`，与现有 `idx_*_tenant` 单列索引兼容；高频列表应改为 `tenant_id` 前导的组合索引，例如：
   - `wgh_chat_message(tenant_id, session_id, id)`（已建 `(tenant_id, session_id)`）
   - `wgh_knowledge_file(tenant_id, knowledge_base_id, deleted)`
   - `wgh_audit_log(tenant_id, create_time DESC)`（已建）
   - `wgh_api_call_log(tenant_id, create_time DESC)`（已建）
   - 其余按实际慢查询补，避免一次性堆 38 个冗余索引。
2. `current_setting('app.tenant_id', true)` 为 STABLE 函数，计划器可在语句内复用；实际以 `EXPLAIN (ANALYZE, BUFFERS)` 验证是否出现逐行调用。
3. 部分索引（如 `deleted = 0`）应把 `tenant_id` 放在前导列，否则 RLS 条件无法用上部分索引。
4. 写入侧：`WITH CHECK` 只做常量比较，开销可忽略；批量 INSERT（eval/chat message）需确认每行 `tenant_id` 与 GUC 一致，否则整批失败。
5. 向量检索：pgvector 的 ANN 索引与 RLS 过滤叠加时，旧版本可能先 ANN 再过滤导致召回下降；建议检索编排层先按 `tenantId` 元数据过滤（阶段 2 遗留批），RLS 只做兜底，并用真实数据回归 recall@k。
6. 只读请求显式开只读事务会延长连接占用，需关注长事务与 `idle_in_transaction_session_timeout`；建议设置 `idle_in_transaction_session_timeout` 并压测 P95（连接池参数本身保持现状）。

#### 3.5.1.7 灰度与回滚路径

**前置（0）**
1. 新建 `wgh_app`（非 owner、无 SUPERUSER/BYPASSRLS）、`wgh_platform`、`wgh_scheduler` 角色，完成最小授权（表 DML、序列 USAGE、默认权限）。
2. 在 dev/test 用 `wgh_app` 跑全量回归 + 负向用例；确认 GUC 在事务内生效（而不是只对单条自动提交语句生效）。
3. 迁移 Job 仍用 owner 执行 DDL/回填。

**建策略（1）**
- 幂等脚本：`DROP POLICY IF EXISTS ...; CREATE POLICY ...; ALTER TABLE ... ENABLE/FORCE ROW LEVEL SECURITY;`
- 先在 test 库按 A→B→C→D 分组开启，每组观察错误率与慢查询。

**灰度（2）**
- 环境顺序：dev → test → prod；prod 先只读副本/影子库验证策略，再切主库。
- 表分组：A 标准表（先评测/标签/语义缓存/Agent，再 KB/对话/上传与配额）→ B 免登录/日志 4 表 → 平台内置 Prompt/Tool → D 调度 3 表；对应脚本 `10`～`13`。
- 观察指标：`new row violates row-level security policy` 错误数、0 行异常、P95、连接池等待、慢查询。
- 应用层开关只控制插件；**RLS 只能由 DDL 开关**，因此必须准备运维脚本而非配置项。

**回滚**
1. 单表级：`ALTER TABLE <t> DISABLE ROW LEVEL SECURITY;`（仅改元数据，不重写表，秒级）；保留策略定义便于再次开启。
2. 全局级：按分组逆序 `DISABLE`；必要时把应用连接角色临时切回 owner（**仅作最后手段**，等于放弃数据库层兜底，必须审计留痕）。
3. 数据修复：RLS 只影响可见性，不改数据；回滚不需要数据迁移。
4. 每次开关记录操作人、时间、表清单、观察指标，写入审计。

#### 3.5.1.8 验收与测试矩阵（实施阶段执行）

| 用例 | 方式 | 期望 |
|---|---|---|
| 直连无 GUC 查询 | `psql` 以 `wgh_app` 连接，不设置 `app.tenant_id` | 38 张表均 0 行；写入被拒 |
| 直连设置租户 A | `BEGIN; SELECT set_config('app.tenant_id','A',true); ...` | 仅 A 行；插入 B 行被 `WITH CHECK` 拒绝 |
| 事务回滚后复用连接 | 同一连接回滚后查 B | 不残留 A，返回 0 行 |
| 管理端跨租户 | `wgh_platform` 角色访问审计/租户列表 | 可见；业务表仍按策略 |
| 公开分享 | 匿名链路按 token 访问 | 仅能通过专用角色/策略读到目标链接 |
| 调度 claim | `wgh_scheduler` 扫描作业 | 可见多租户作业；无权访问业务表 |
| 异步作业 | JobRunner 恢复租户上下文后写入 | `tenant_id` 与 GUC 一致 |
| 性能 | `EXPLAIN (ANALYZE, BUFFERS)` | 走 `tenant_id` 前导索引，无逐行 `current_setting` |

#### 3.5.1.9 决策结论（2026-11-24 定稿）

| # | 决策点 | 结论 |
|---|---|---|
| 1 | 数据库角色 | 接受 `wgh_app` / `wgh_platform` / `wgh_scheduler` 三角色；`wgh_app` 为应用连接角色，非 owner、非 SUPERUSER、非 BYPASSRLS、NOINHERIT。 |
| 2 | 免登录链路 | `wgh_share_link` / `wgh_api_key` 使用独立只读角色 `wgh_preauth`；**不给应用角色开 GUC 例外**，不允许通配符豁免。 |
| 3 | 连接池 | **不接受** Hikari 全局 `auto-commit=false`。需要 RLS 的访问全部落在既有事务内；只读查询显式使用只读事务；连接池保持现状。 |
| 4 | 平台内置 Prompt/Tool | 增加显式 `scope` 标记（`platform` / `tenant`），不靠 `tenant_id='default'` 隐式表达归属；`wgh_platform` 管理平台资源。 |
| 5 | 调度表 | 使用专用 `wgh_scheduler` 角色跨租户访问调度 3 表，不做按租户分片；`wgh_execution_event` 不开 RLS。 |
| 6 | 日志跨租户只读 | 沿用建议：`wgh_platform` 对 `wgh_audit_log` / `wgh_api_call_log` 只读跨租户；业务表不授予平台角色。 |
| 7 | MyBatis 插件 | 保留双层防御，不因 RLS 下线插件。 |
| 8 | PgBouncer | 若后续引入，必须使用 `transaction` 模式，并按 §3.5.1.4 验证事务级 GUC。 |
| 9 | 灰度范围 | prod 允许按表分组分批开启（`10`～`13`），回滚依赖单表 `DISABLE`。 |

#### 3.5.1.10 实施脚本与验收矩阵（2026-11-24，dev 已执行并验收；prod/test 未执行）

> 执行状态（2026-11-24）：脚本已在 **`wgh_rag_dev`** 执行并逐组验收通过，未修改应用连接配置（后端仍用 `postgres`）；`wgh_rag_prod` / `wgh_rag_test` 未执行 DDL 与授权，也未登记到 `kustomize` / `run-migrations.sh`。

**dev 执行记录（2026-11-24）**
- 备份：`/tmp/wgh_rag_dev_pre_rls-20261124-184140.dump`（`pg_dump -Fc -d wgh_rag_dev`，11 MB）。
- 前置迁移（dev 缺失，按依赖补齐）：`05_tenant.sql`（`wgh_tenant` + 默认租户，用户回填 6 行，审计/API Key/调用日志空值 0）→ `06_tenant_data_plane.sql`（34 表补 `tenant_id`，37 张表空值残留 0）→ `04_tenant_schedule_policy.sql`（策略表/运行槽）→ `08_execution_event.sql`（`13` 的 `COMMENT ON TABLE` 前置）。
- 策略执行顺序：`09` → `10`（A 32 表）→ 验收 → `11`（B 4 表）→ 验收 → `13`（D 3 表）→ 验收 → `12`（Prompt/Tool 2 表）→ 验收。最终 41 张表 `rowsecurity=t`、`forcerowsecurity=t`，共 52 条策略。
- 实测证据（专用角色直连，临时开启 LOGIN、验收后已恢复 `NOLOGIN` 并清空口令）：
  - `wgh_app` 无 GUC：`wgh_knowledge_base` 0 行、`wgh_execution_job` 0 行；GUC=`default`：8 行、`tenant_id<>'default'` 0 行；GUC=`t-other`：0 行。
  - `wgh_app` 越权写：`UPDATE ... SET tenant_id='t-other'` 被 `new row violates row-level security policy` 拒绝，数据未变。
  - `wgh_app` + GUC=`t-other` 仅见 3 条 `scope='platform'` Prompt；GUC=`default` 见 5 条（3 平台 + 2 租户）。
  - `wgh_preauth`：`share_link` 4 / `api_key` 4 可读，业务表 `permission denied`。
  - `wgh_scheduler`：调度作业 1233 行可读，业务表 `permission denied`。
  - `wgh_platform`：审计 458 / 调用日志 322 跨租户可读，业务表 `permission denied`。
  - `EXPLAIN`（`wgh_app` + GUC）：`Bitmap Index Scan on idx_chat_message_tenant`，`Index Cond: tenant_id = current_setting('app.tenant_id', true)`，谓词已下推。
- 集群级影响：`CREATE ROLE` 对同实例所有库可见；已核对 prod/test 的 `has_table_privilege` / 序列权限均为 0，无对象授权变更（仅存在 PostgreSQL 默认的 PUBLIC CONNECT）。
- 应用侧事实：后端 PID 40908 以 `postgres`（SUPERUSER）连接 `wgh_rag_dev`，**RLS 当前对应用不可见**；启用状态对现有应用无行为影响。
- 回滚：按 §3.5.1.10「回滚」执行；本次仅 dev 有变更，prod/test 无需回滚，整库可用上述 dump 恢复。

**脚本清单**（`rag-backend/sql/migrations/20261124-01/`）

| 文件 | 内容 | 是否可重复执行 |
|---|---|---|
| `09_rls_roles.sql` | 创建/收敛 `wgh_app`、`wgh_platform`、`wgh_scheduler`、`wgh_preauth`；CONNECT/USAGE、表与序列授权、默认权限、角色成员关系 | 是 |
| `10_rls_group_a_standard.sql` | A 组 32 张标准表 `ENABLE` + `FORCE` + `p_tenant_isolation`（`wgh_app`） | 是 |
| `11_rls_group_b_preauth_logs.sql` | `share_link` / `api_key` 租户策略 + `wgh_preauth` 只读 + `wgh_platform` 只读；`api_call_log` / `audit_log` 租户策略 + 平台只读 | 是 |
| `12_rls_prompt_tool_scope.sql` | Prompt/Tool 增加 `scope` 列与约束、按 `builtin`/`is_example` 回填 `platform`、租户/平台策略 | 是 |
| `13_rls_group_d_scheduler.sql` | 调度 3 表租户策略 + `wgh_scheduler` 全量策略；`wgh_execution_event` 明确不开 RLS | 是 |
| `14_rls_rollback.sql` | 41 张表按 D→平台资源→B→A 逆序 `DISABLE`；保留策略与 `scope` 列 | 是 |
| `15_rls_verify.sql` | 只读验收：角色属性、成员关系、RLS 开关、策略清单、空租户检查、无 GUC/有 GUC、预认证/调度越权、`EXPLAIN` | 只读 |

**角色与连接约定**
- 应用连接用户名切到 `wgh_app`（`WGH_DB_USERNAME`），口令由 Secret 注入；迁移 Job 继续用 owner `postgres`。
- `wgh_app` 为 `NOINHERIT`，普通请求仅使用自身权限；平台/调度/预认证链路在事务内 `SET LOCAL ROLE wgh_platform|wgh_scheduler|wgh_preauth`，事务结束自动还原，**禁止会话级 `SET ROLE`**。
- `wgh_preauth` 只有 `wgh_share_link` / `wgh_api_key` 的 `SELECT`；解析出租户后必须回到 `wgh_app` + GUC 才能做业务读写。
- `wgh_scheduler` 只有调度 3 表与实例心跳的 DML，没有任何业务表权限。

**应用侧硬前置（本批未改代码，随灰度批次实施）**
1. 事务边界：需要 RLS 的写路径沿用 `@Transactional`；只读路径显式 `@Transactional(readOnly=true)`。不要再依赖自动提交连接。
2. 事务级 GUC：在事务开始、首条语句前执行 `SELECT set_config('app.tenant_id', ?, true)`；建议 `TransactionSynchronization` / AOP / MyBatis 拦截器统一处理；不要在 `getConnection()` 时设置。
3. 登录审计与 API 调用日志：分别在认证前写入、`TenantContext.clear()` 后写入，必须先修正为「显式设置目标租户 GUC 再写」，否则 B 组不能开启。
4. Prompt/Tool：MyBatis 租户插件需放行 `scope='platform'` 行，否则 RLS 放行也会被插件条件挡住；实体/服务需显式写 `scope`。
5. 平台管理端：业务表跨租户读取不授予 `wgh_platform`；如需开放，另行评审追加只读策略。RLS 开启后 admin 的业务列表默认只看到自身租户，这是预期行为变化。
6. 启动自检：以 `wgh_app` 查询 `current_user, rolsuper, rolbypassrls`，发现超级用户/BYPASSRLS 告警或拒绝启动（建议开关化）。

**灰度顺序**
1. 角色与授权（`09`）→ dev/test 全量回归。
2. A 组（`10`）→ 观察 24h。
3. B 组（`11`）→ 必须先完成应用侧前置 3。
4. 平台内置 Prompt/Tool（`12`）→ 必须先完成应用侧前置 4。
5. 调度（`13`）→ 验证 claim/心跳/回收。
6. prod：先影子库/只读副本验证，再按组切主库。

**验收矩阵**

| # | 场景 | 角色/方式 | 期望 |
|---|---|---|---|
| 1 | 角色属性 | `pg_roles` | `wgh_app`：super=f、bypassrls=f、inherit=f、login 由部署侧开启 |
| 2 | 成员关系 | `pg_auth_members` | 仅 3 条：platform/scheduler/preauth → app；登录用户不直接持有这三个角色 |
| 3 | 无 GUC 读 A 组 | `wgh_app` 直连，不设 GUC | 32 表均 0 行 |
| 4 | 无 GUC 写 A 组 | `wgh_app` INSERT | `new row violates row-level security policy` 或 `WITH CHECK` 失败 |
| 5 | GUC=A 读 | `BEGIN; SET LOCAL ROLE wgh_app; set_config('app.tenant_id','A',true)` | 仅 A 行；B 行不可见 |
| 6 | GUC=A 写 B 行 | 同上，INSERT tenant_id='B' | 被 `WITH CHECK` 拒绝 |
| 7 | 事务回滚复用连接 | 回滚后同一连接再查 | 不残留 A，0 行 |
| 8 | 预认证链路 | `SET LOCAL ROLE wgh_preauth` | 可读 `share_link`/`api_key`；业务表 `permission denied` |
| 9 | 平台日志只读 | `SET LOCAL ROLE wgh_platform` | 审计/调用日志跨租户可见；业务表无权限 |
| 10 | 平台内置资源 | `wgh_app` + GUC=非 default | 可读 `scope='platform'` 的 Prompt/Tool；不可写 |
| 11 | 平台资源管理 | `SET LOCAL ROLE wgh_platform` | 可写平台 Prompt/Tool 与租户目录 |
| 12 | 调度 claim | `SET LOCAL ROLE wgh_scheduler` | 跨租户作业可见；业务表无权限 |
| 13 | 调度租户查询 | `wgh_app` + GUC=A | 仅 A 的作业/策略/运行槽 |
| 14 | 异步作业写入 | JobRunner 恢复租户上下文 | 行的 `tenant_id` 与 GUC 一致 |
| 15 | 性能 | `EXPLAIN (ANALYZE, BUFFERS)` | 走 `tenant_id` 前导索引；无逐行 `current_setting` |
| 16 | 单表回滚 | `ALTER TABLE <t> DISABLE ROW LEVEL SECURITY` | 秒级生效；策略保留；无数据变更 |

**回滚**
1. 单表：`ALTER TABLE <t> DISABLE ROW LEVEL SECURITY;`（元数据操作，秒级）。
2. 分组：执行 `14_rls_rollback.sql` 对应段落（D→平台资源→B→A 逆序）。
3. 全局：执行完整 `14_rls_rollback.sql`；保留策略与 `scope` 列，便于修复后重新 `ENABLE/FORCE`。
4. 最后手段：应用连接临时切回 owner，等于放弃数据库层兜底，必须审计留痕。
5. RLS 只影响可见性、不改数据，回滚不需要数据迁移。

**残余风险**
- 未完成上述应用侧前置就开启 B 组或 Prompt/Tool，会出现「审计/调用日志写入被拒」或「平台内置资源不可见」。
- `wgh_app` 若被误授予 SUPERUSER/BYPASSRLS，RLS 立即失效；依赖启动自检与授权审计。
- 平台管理员跨租户业务读取在 RLS 下默认不可见；如需开放需二次评审。
- `wgh_prompt_template.name` 仍全局唯一，平台与租户同名模板会冲突（已知限制）。

### 3.5.2 应用数据库连接用户切换（postgres → wgh_app）

> 目标：应用运行连接从 `postgres`（SUPERUSER，RLS 完全不生效）切到 `wgh_app`（NOINHERIT、非 superuser、无 bypassrls）。
> 硬顺序：**先补代码，再切连接用户**。反向操作会让所有启用 RLS 的表直接返回 0 行。
> 本节是影响面与分阶段方案；dev 已完成切换（应用连接用户为 `wgh_app`），prod/test 未执行。

> **待评估（当前未采用）：生产 pgvector 扩展安装口径。**
> 曾评估「DBA 以超级用户预建扩展 + 应用侧 `WGH_PGVECTOR_SKIP_EXT=true`」的方案，**暂不采用**，
> 仅作为待评估方案记录在此。
> 实测事实（PostgreSQL 15 + pgvector 0.8.7）：`trusted = true` 只允许非超级用户执行扩展安装脚本，
> **不会**把 `extowner` 改成超级用户——以 `wgh_app` 执行 `CREATE EXTENSION vector` 后，实测
> `pg_extension.extowner = wgh_app`。若要求扩展属主为 `postgres`，仍须由超级用户创建。
> dev 现状：数据卷内 `vector` 扩展仍为 `postgres` 属主；容器切换到 trusted 派生镜像后**未重装、未降级**，
> 版本仍为 0.8.7。`skip-create-vector-extension` 在 dev 恢复为 `false`，仅用于扩展缺失时自愈。
> 生产若改为「应用自建扩展」，需额外评估 `wgh_app` 持有扩展属主带来的 `DROP/ALTER EXTENSION` 权限面。

#### 3.5.2.1 影响面清单

| 影响面 | 现状 | 切换后的约束 / 整改点 |
|---|---|---|
| 连接池与初始化 SQL | 单 Hikari（`maximum-pool-size=20`、`connection-test-query=SELECT 1`、`initialization-fail-timeout=-1`） | 保持连接池参数不变，**不启用** Hikari 全局 `auto-commit=false`，不用 `connection-init-sql` 设置租户（会话级 GUC 会随连接复用串租户）。GUC 只允许事务级 `set_config('app.tenant_id', ?, true)` |
| 事务边界 | 大量读路径无 `@Transactional`，依赖自动提交；租户插件在 SQL 层追加条件 | 切 `wgh_app` 后，**没有事务就没有 GUC**：未加 GUC 的租户表查询返回 0 行。需提供事务管理器级 GUC 注入，并给只读入口补 `@Transactional(readOnly=true)` |
| 连接复用 | 由 Hikari 复用连接；当前无会话级租户状态 | 只用 `SET LOCAL`，事务结束自动失效；禁止任何“取出连接时 SET、归还前 RESET”的方案（异常路径会串租户） |
| 免登录链路 | `ApiKeyAuthenticationFilter` 在认证前以当前连接读 `wgh_api_key`；分享链接同理 | 这两张表受 B 组 RLS 保护。需在**独立短事务**内 `SET LOCAL ROLE wgh_preauth` 完成校验，结束后回到 `wgh_app`；再按 Key/链接租户设置 GUC 访问业务表。`wgh_preauth` 只有 SELECT，不得承载业务查询 |
| 调度事务 | `ExecutionJobMapper` 的 claim/心跳/回收、`SchedulerInstanceRegistry`、`TenantSlotManager` 全部是跨租户 SQL，且多数不在业务事务内 | D 组 RLS 只放行 `wgh_scheduler`。领取/续租/回收/实例注册/策略加载需在独立事务内 `SET LOCAL ROLE wgh_scheduler`；**作业入队与业务读写仍用 `wgh_app` + GUC**，不能整段事务切调度角色（业务表未授权给 `wgh_scheduler`） |
| 审计写入顺序 | `AuditAspect` 在请求线程 `saveAsync`（`@Async`）；`AccountAuditService` 登录场景已支持显式租户；`ApiCallLogService.record` 在 `TenantContext.clear()` 之后执行 | `wgh_audit_log`/`wgh_api_call_log` 属 B 组 RLS。异步/延后写入必须在**自己的事务**里显式设目标租户 GUC（不能依赖线程上下文），或在清理上下文前落库 |
| Prompt/Tool | 实体与服务没有 `scope` 字段；租户插件对所有租户表统一追加 `tenant_id` | 12 号脚本已加 `scope` 列与 RLS 策略。代码需补 `scope` 写入与查询语义（平台行可见），并让插件对 `scope='platform'` 放行；否则切库后内置 Prompt/Tool 不可见 |
| 管理员跨租户 | `TenantLineHandlerImpl` 对 `admin` 角色忽略租户条件 | RLS 下 `wgh_app` 无管理员例外，`admin` 只能看到本租户业务数据。跨租户运维视图会收窄（审计/调用日志仍由 `wgh_platform` 提供），需产品确认 |
| 异步/线程池 | `AuthContextPropagation` 已传播 `TenantContext`（@Async、调度、工作流、Agent 超时） | 只解决“租户值”，不解决“GUC”。所有异步写路径必须在事务开始时注入 GUC，否则 RLS 拒绝/0 行 |
| 原生 SQL 与 Mapper | 租户插件只覆盖白名单表；部分 Mapper 手写 SQL | 手写 SQL 同样受 RLS 约束；需逐条确认在事务内且有 GUC（检索、配额、语义缓存、评审、调度） |
| 迁移脚本执行用户 | `deploy/k8s/base/migrate/run-migrations.sh` 默认 `PGUSER=postgres` | **保持 postgres**：DDL、角色、策略需要 owner/超级用户；`wgh_app` 只承载运行时 DML，不授 DDL |
| 健康检查与监控 | readiness 聚合 `db`（`SELECT 1`）；liveness 只看进程 | `SELECT 1` 不需要 GUC，切 `wgh_app` 后仍可用；新增启动自检（角色属性）与“GUC 未下发”告警指标；Actuator 端口/探针不变 |
| 备份恢复 | `pg_dump/pg_restore` 由运维以 `postgres` 执行 | 保持超级用户执行；`wgh_app` 不作为备份账号，避免权限不足与越权面 |
| K8s Secret / 配置 | `configmap-backend.yaml` 写死 `SPRING_DATASOURCE_USERNAME: "postgres"`；口令走既有 Secret | 改为 `wgh_app`；口令放 `wgh-db-app` Secret 的 `WGH_DB_PASSWORD`；配置文件只保留 `${WGH_DB_USERNAME:wgh_app}` / `${WGH_DB_PASSWORD:}` 占位，不写明文；迁移 Job 的 Secret 不变 |
| 连接初始化失败策略 | `initialization-fail-timeout=-1`（启动时连不上不阻断） | 切库后保留该策略，但启动自检失败要显式告警；readiness 的 `db` 会暴露真实不可用 |

#### 3.5.2.2 分阶段整改方案

**阶段 A：代码整改（不切用户，可独立验证）**

| 编号 | 内容 | 落点 |
|---|---|---|
| A1 | 事务级 GUC 注入：自定义 `PlatformTransactionManager`（继承 `DataSourceTransactionManager`），在 `doBegin` 成功后对绑定连接执行 `SET LOCAL app.tenant_id`；无租户上下文时不设置并在入口显式拒绝 | `rag-data` 数据源配置 |
| A2 | 只读事务补齐：为租户表的只读入口补 `@Transactional(readOnly=true)`（知识库/文件/检索、对话与消息、评测、Agent 记忆、配额、Prompt/Tool 列表等），禁止无事务访问租户表 | `rag-kb`/`rag-chat`/`rag-agent*`/`rag-quota`/`rag-tool` |
| A3 | 免登录链路：新增 `wgh_preauth` 短事务模板（`REQUIRES_NEW` + `SET LOCAL ROLE wgh_preauth` + 只读），仅用于 `share_link`/`api_key` 校验；校验完回到 `wgh_app` 并设目标租户 GUC | `rag-public-access` |
| A4 | 调度事务：为 claim/心跳/回收/实例注册/策略加载增加独立事务模板，事务内 `SET LOCAL ROLE wgh_scheduler`；入队与业务写保持 `wgh_app` + GUC；对 `wgh_execution_event` 不做角色切换 | `rag-scheduler` |
| A5 | Prompt/Tool：实体/Mapper 补 `scope`；创建时按“平台内置/租户自建”写值；查询语义为“平台行 + 本租户行”；租户插件对这两张表放行平台行（方式见待决策项） | `rag-chat`/`rag-tool` |
| A6 | 审计与调用日志：`AuditLogService` 异步写入改为显式租户 + 独立事务 + GUC；`ApiCallLogService.record` 改为 `REQUIRES_NEW` 并显式传 Key 租户（不依赖已清理的上下文）；登录审计保持显式租户并纳入事务 | `rag-log`/`rag-public-access`/`rag-auth` |
| A7 | 启动自检：新增 `ApplicationRunner`，校验 `current_user`、`rolsuper=false`、`rolbypassrls=false`、`rolinherit=false`；不满足时记录 ERROR 并按配置决定是否阻止启动（dev 先告警） | `rag-app` 或 `rag-data` |
| A8 | 配置与占位符：`application*.yml` 用户名默认值改 `${WGH_DB_USERNAME:wgh_app}`、口令 `${WGH_DB_PASSWORD:}`；dev 通过环境变量注入；K8s ConfigMap 改用户名、Secret 增 `WGH_DB_PASSWORD`；迁移 Job 与备份脚本保持 `postgres` | `application*.yml`、`deploy/k8s/base` |
| A9 | 验证：单测覆盖 GUC 注入、preauth/scheduler 角色事务、审计显式租户、Prompt/Tool 平台行、自检；全量编译；不改运行中服务 | Maven（构建锁） |

**阶段 B：dev 切连接用户（需一次重启，见待决策项）**
1. 为 `wgh_app` 配置 LOGIN 与强口令（Secret/环境变量，不落库外明文）；
2. dev 以 `WGH_DB_USERNAME=wgh_app`、`WGH_DB_PASSWORD` 注入启动；
3. 迁移 Job/备份仍用 `postgres`；
4. 回滚：改回 `WGH_DB_USERNAME=postgres` 重启即可（RLS 对超级用户不生效）。

**阶段 C：dev 切库后验证清单**
- 跨租户读：租户 A 会话读不到租户 B 的知识库/文件/对话/评测/Agent 记忆；`wgh_app` 无 GUC 时租户表为 0 行。
- 缺少租户上下文：登录前/公开链路/维护线程的行为符合预期；未设 GUC 的写操作被 RLS 拒绝并可观测。
- 免登录：分享链接与 API Key 校验可用，`wgh_preauth` 不能读业务表；API 调用日志归属正确。
- 调度：作业可领取/续租/回收；多实例不重复领取；作业执行期的业务写归属正确。
- 平台资源：内置 Prompt/Tool 可见；租户自建不可跨租户。
- 审计：登录成功/失败、管理操作、开放 API 调用日志的 `tenant_id` 正确。
- 冒烟：登录、知识库上传/检索、对话流式、评测、多 Agent、工作流、配额扣减。
- 监控：readiness `db` 正常；自检日志为 `wgh_app / non-superuser / no-bypassrls / NOINHERIT`。

#### 3.5.2.3 待决策项

1. **dev 切库需要一次后端重启**，与“不重启任何正在运行的服务”约束冲突：由谁在什么窗口重启？本线只准备配置与代码，不主动重启。
2. **`admin` 跨租户业务可见性收窄**：切 `wgh_app` 后 admin 只能看本租户业务数据（审计/调用日志仍走 `wgh_platform`）。是否接受？若不接受，需要单独设计“平台运维代管”通道。
3. **调度角色的实现形态**：A4 采用“独立事务 + `SET LOCAL ROLE wgh_scheduler`”（改动小、无第二数据源），还是新增独立 `wgh_scheduler` 数据源/`SqlSessionFactory`（隔离更彻底、改造大）。
4. **Prompt/Tool 平台行放行方式**：租户插件对这两张表整体忽略并由服务层显式拼 `(tenant_id=? OR scope='platform')`，还是引入专用拦截器改写 SQL。前者简单但依赖服务层覆盖，后者集中但需自研拦截器。
5. **只读事务覆盖面**：是否要求“所有租户表读路径必须有事务”并用测试/静态扫描兜底；遗漏路径在切库后会静默返回 0 行。跨租户读的例外路径见 §3.5.3。

### 3.5.3 平台级跨租户扫描与跨租户指标方案（#2 / #4 / #6，方案稿）

> **状态**：方案稿，2026-11-24。本节只做设计，不改代码、不改配置、不重启实例、不做 git 提交。
> **前置**：§3.5.2 切 `wgh_app` 之后，「无事务 = 无 GUC = RLS 返回 0 行」；「有事务但租户上下文为空」会被 `TenantTransactionTemplate` 兜底成 `default`。
> **适用**：`SchedulerReconcileJobHandler`（#2）、`RetrievalMetricsGauge`（#4）、以及所有只在默认租户生效的周期维护作业（#6）。

#### 3.5.3.1 三条链路的共同根因

| 链路 | 要读 | 要写 | 需要的数据库身份 | 现状 |
|---|---|---|---|---|
| #2 作业对账 | `wgh_workflow_run` / `wgh_agent_plan` / `wgh_m_agent_run`（A 组 RLS）+ `wgh_execution_job`（D 组） | `wgh_execution_job.cancel_pending` | 业务表要 `wgh_app` + 租户 GUC；调度表要 `wgh_scheduler` | `execute` 无事务，当前靠 `postgres` 超级用户绕过 RLS |
| #4 向量表数量 Gauge | `wgh_knowledge_base` 聚合计数（A 组） | 无 | 需要跨租户只读 | 无租户上下文，切库后 RLS 静默返回 0 |
| #6 平台级扫描 | 各租户业务表 | 各租户业务表/调度表 | 每个租户一次 `wgh_app` + GUC | 周期作业按默认租户执行，只覆盖 `default` |

三条链路共享两个硬约束：

1. **一个事务只有一个有效角色、一个 `app.tenant_id`**。`TenantAwareDataSourceTransactionManager#doBegin` 只在事务开始时下发一次 GUC（`rag-data/.../TenantAwareDataSourceTransactionManager.java#L36-L59`）；`TenantTransactionTemplate` 是 `REQUIRED` 传播，嵌套进已有事务时**不会重设 GUC**。因此「逐租户读」必须是最外层事务边界，不能包在一个大事务里循环。
2. **`SET LOCAL ROLE` 只能在事务内生效，且事务内切换角色不会自动补权限**。`wgh_scheduler` 只对调度 3 表有权限；`wgh_app` 只对业务表有权限。把「读业务表」和「写调度表」放进同一个事务，只能靠中途 `SET LOCAL ROLE` 来回切，代价见 §3.5.3.2 方案 C。

共同结论：**跨租户 + 跨角色必须拆成「每租户一个短事务」，不要试图用一个事务解决。**

#### 3.5.3.2 #2 `SchedulerReconcileJobHandler.execute`

**现状事实**

- `jobMapper.selectReconcileCandidates(jobType, since, batch)` 的 SQL 没有 `tenant_id` 条件（`rag-scheduler/.../ExecutionJobMapper.java#L279-L289`），候选天然跨租户。
- `businessState(...)` 通过 `selectById` / `selectList` 读 `wgh_workflow_run`、`wgh_agent_plan`、`wgh_m_agent_run`（`SchedulerReconcileJobHandler.java#L270-L320`），三张表都在 RLS A 组。
- `repair(...)` 调 `jobMapper.cancelPending(...)` 写 `wgh_execution_job`（`#L241-L250`），属于 RLS D 组。
- 整个 `execute(...)` 没有 `@Transactional`（`#L119-L177`）。

**约束**

- `wgh_scheduler` 对三张业务表**没有授权**：无权限会直接报 `permission denied`，不是返回 0 行。
- `wgh_app` 对 `wgh_execution_job` **没有授权**：修复写入必须在 `wgh_scheduler` 身份下完成。
- 业务记录必须按 `job.tenantId` 设 GUC；用默认租户读会读不到非默认租户的业务行（MyBatis 插件加 `tenant_id='default'`，RLS 也只放行 `default`）。
- 对账的正确性红线：**业务读失败不能当成 `business_missing`**。否则会把「读不到」误判为「业务不存在」，错误取消仍在排队的作业。

**方案对比**

| 方案 | 做法 | 事务/连接代价 | 锁与失败影响 | 结论 |
|---|---|---|---|---|
| A 三段式：扫→判→写 | ① `runReadOnlyAs(wgh_scheduler)` 取候选；② 每条作业 `transactions.execute(job.tenantId, true, ...)` 读业务记录并判定；③ 需要修复时 `runAs(wgh_scheduler)` 单独短事务执行 `cancelPending` | 1 次候选扫描 + 每个不同租户 1 次只读事务 + 每次修复 1 次写事务；每条作业之间不持有事务 | 只有 `cancelPending` 的行级短锁；单条业务读失败只跳过该条，不影响本轮其他作业 | **推荐**，与 `JobStore` 已有的「租户路径用 app+GUC、跨租户协调用 scheduler 角色」划分一致 |
| B 按租户分批（A 的优化） | 先把候选按 `tenant_id` 分组，每租户一个只读事务批量读业务记录，再按租户批量修复 | 1 + 租户数（读）+ 租户数（写）次短事务；`IN` 查询需要限制条数 | 单租户内一条失败会影响该租户整批；租户内 `IN` 列表过长会放大单次查询 | 候选量大、租户多时再上；默认 `batchSize` 较小时收益有限 |
| C 读写同事务（`readOnly=false`，先读后写） | 一个事务内先 `SET LOCAL ROLE wgh_scheduler` 取候选，再切回 `wgh_app` 逐租户 `set_config` 读业务，最后切回 `wgh_scheduler` 写 | 连接占用 = 整轮对账时长；事务快照长期不释放 | 修复的 `UPDATE` 行锁在整个事务期间持有；任一业务读失败会回滚整轮（除非逐个加 savepoint）；跨租户 `set_config` 只保留最后一个值 | **技术可行但不采用**：长事务、锁窗口、异常隔离都最差 |
| D 不拆：继续用超级用户 / 只对账默认租户 | 维持现状或接受漏对账 | 0 改造 | 违背 RLS 目标；非默认租户永远不对账 | 不采用 |

**推荐形态（A，候选量大时叠加 B）**

```text
execute:
  candidates = roles.runReadOnlyAs("wgh_scheduler",
      () -> jobMapper.selectReconcileCandidates(jobType, since, batch))
  for job in candidates:
      ctx = job.tenantId                       // 必须有值，缺失即跳过并告警
      business = transactions.execute(ctx, true, () -> readBusiness(job))
      decision = decide(job, business, now)    // 纯内存判断，不碰数据库
      if decision.repair:
          roles.runAs("wgh_scheduler", () -> jobMapper.cancelPending(job.id, reason))
```

三个实现要求：

1. `readBusiness` 只能依赖 `job.tenantId`，不能读线程上的 `TenantContext`（`JobRunner` 恢复的是默认租户）。
2. 业务读抛异常 → 该条记 `skipped`（新增计数），**不进入** `repair` 分支；`business_missing` 的判定必须基于「查询成功且返回 null」。
3. 修复写仍走现有条件 UPDATE（`cancelPending` 带状态条件），并发被领取时返回 0，按现有逻辑跳过。

**指标口径影响**

- `scanned` 口径不变（跨租户候选作业条数）。
- 新增 `skipped`（租户缺失/停用、业务读失败、未知 runType）与 `tenantFailed`；`mismatches` 只统计「成功读出业务状态后的不一致」，避免读失败污染不一致率。
- `findings` 仍只输出 `executionId`、业务 id/status，不输出业务内容，跨租户对账不额外泄露数据。
- 如果 `skipped` 连续多轮大于 0，应告警（说明有租户读不到或租户目录异常）。

#### 3.5.3.3 #4 `RetrievalMetricsGauge`

**现状事实**

- `tableCount()` 在 Prometheus 抓取线程执行，`selectCount(wgh_knowledge_base WHERE collection_name IS NOT NULL)`，无任何租户上下文（`rag-kb/.../RetrievalMetricsGauge.java#L39-L52`）。
- 有 60 秒 TTL；查询失败沿用上次值，但**查询成功返回 0 不会报错**。
- 切 `wgh_app` 后：无 GUC → RLS 返回 0 行 → Gauge 变成 0。MyBatis 插件层因无上下文不加条件，RLS 层却是 0 行，**两层口径不一致且静默**。
- 不能简单套 `transactions.execute(true, ...)`：上下文为空时模板按 `default` 设 GUC，只会统计默认租户，指标从「平台总量」悄悄变成「默认租户量」。

**方案对比**

| 方案 | 做法 | 口径 | 代价 |
|---|---|---|---|
| A 平台角色读 | 给 `wgh_platform` 增加 `wgh_knowledge_base` 的 `SELECT` 授权 + `FOR SELECT TO wgh_platform USING (true)` 策略；Gauge 内 `roles.runReadOnlyAs("wgh_platform", ...)` | 全租户真实总量，与当前 `postgres` 口径一致（0 变化） | 扩权：`wgh_platform` 可读 KB 行（不只是计数）；需要新增迁移脚本与安全评审 |
| B SQL 层聚合 | B1：`SECURITY DEFINER` 函数，owner 为 `postgres`（超级用户绕过 RLS），只返回 `count(*)`，`GRANT EXECUTE TO wgh_app`，函数内固定 `search_path`、无参数；B2：函数 owner 为专用只读角色，配 `USING (true)` 策略 | 同 A，全租户总量 | B1 暴露面最小（应用只能拿到数字，读不到行），但引入超级用户 definer，评审成本最高；B2 等于 A 多一层封装。**注意**：`FORCE RLS` 对表 owner 也生效，所以 B2 的 owner 不能用普通业务角色，必须配策略或 `BYPASSRLS` |
| C 退化近似指标 | C1：改成租户维度 Gauge（`label=tenantId`），需要遍历租户；C2：无上下文时返回 `NaN`/保持上次值/不注册 | 不再是平台总量；C1 高基数，C2 指标缺失 | 0 迁移，但看板、容量规划与告警全部要改口径；不满足「向量表总量」的原始用途 |

**推荐**：优先 A（与既有角色体系一致、改动最小）；若安全评审不接受 `wgh_platform` 对 KB 表的行级可见，改用 B1（只暴露计数）。C 只作为临时降级，不作为长期方案。

**指标口径与自检**

- A/B 的指标语义 = 「平台全租户中 `collection_name` 非空的 KB 数」，与切库前完全一致，无需改看板。
- 新增自检：若「租户目录中启用的租户数 > 0」且 Gauge 连续两个刷新周期为 0，打 `vector_table_count_suspicious_zero` 告警，防止再次出现「RLS 静默归零」。
- Gauge 回调必须保持不抛异常（现有 `try/catch` 保留），刷新仍为 60 秒一次短事务，不参与请求链路。

#### 3.5.3.4 #6 平台级全租户扫描

**现状根因**

- `JobRunner.run(...)` 用 `job.getTenantId()` 设置上下文（`rag-scheduler/.../JobRunner.java#L188-L194`），`finally` 清理。
- `JobDispatcher.submitRecurring(...)` 构造 `ExecutionRequest` 时**没有设置 tenant**（`JobDispatcher.java#L164-L169`），`JobStore.resolveTenant(...)` 回落到 `TenantContext.tenantIdOrDefault()`（`JobStore.java#L380-L383`），即 `default`。
- 于是周期作业执行时：MyBatis 插件追加 `tenant_id='default'`，RLS 的 GUC 也是 `default`，两层都只覆盖默认租户。

**需要改的遍历入口**

| # | 入口 | 现状扫描范围 | 改法 |
|---|---|---|---|
| 1 | `KbConnectorSyncJobHandler` → `ConnectorScheduler.tick()` | `wgh_kb_connector` 仅默认租户 | 遍历启用租户；每租户一个只读短事务取到期候选；`connectorService.syncScheduled` 在该租户上下文下执行（其自身事务），**单个连接器失败要单独 try/catch**（当前一个失败会中断本轮剩余连接器） |
| 2 | `SchedulerReconcileJobHandler` | 候选跨租户，业务读仅默认租户 | 按 §3.5.3.2 三段式 |
| 3 | `RunResumeScanJobHandler` → `RunningTaskService.listStaleCandidates` | `wgh_agent_plan` / `wgh_m_agent_run` / `wgh_workflow_run` 仅默认租户 | 每租户只读事务扫描；投递续跑作业时 `ExecutionRequest` 必须显式带 `tenant(tenantId)`，否则续跑作业又落回默认租户 |
| 4 | `QuotaRefundJobHandler` → `QuotaChargeLedger.findDueRefunds` | `wgh_quota_charge` 仅默认租户 | 每租户扫描到期流水；退款写路径用该租户的读写事务 |
| 5 | `KbUploadCleanupJobHandler` → `UploadSessionCleaner.cleanupExpired` | `wgh_upload_session` 仅默认租户 | 每租户扫描/删除 |
| 6 | `KbAgentMemoryCleanupJobHandler` → `AgentMemoryCleaner.cleanupExpired` | `wgh_agent_memory` + 向量集合仅默认租户 | 每租户清理；向量删除必须带 `tenantId` 过滤 |
| 7 | `KbSemcacheCleanupJobHandler` → `SemanticCacheService.cleanupExpired` | `wgh_semantic_cache` + 向量仅默认租户 | 每租户清理；同上 |
| 8 | `KbOrphanCleanupJobHandler` → `StorageOrphanCleanupScheduler.runOnce` | `scan(delete)` 取 `TenantContext.tenantIdOrDefault()`，即默认租户 | 改为逐租户调用 `scan(tenantId, delete)`；仍禁止全库列举推断（§3.1.1 存储红线） |
| 9 | `RetrievalMetricsGauge` | 无租户上下文 | 见 §3.5.3.3，不属于遍历，但同属平台口径 |
| 10 | `POST /api/admin/storage/orphans/scan` | 已支持 `tenantId` 参数，按单租户执行 | 保持单租户；若要「全部租户」批量，显式新增编排并默认关闭 |

**不需要改的入口**：`JobDispatcher` 的 claim / reclaim / requeue / expire / purge 已经由 `JobStore` 走 `wgh_scheduler` 角色做跨租户协调（`JobStore.java#L34-L39`），不按租户遍历，避免把这套机制再复制一遍。

**租户枚举从哪里取**

- 数据源：`wgh_tenant`（平台表，不在租户插件白名单、不启 RLS）中 `status = 1` 且未逻辑删除的租户；`default` 必选（默认租户不允许停用，`TenantService#updateStatus`）。
- 新增一个窄接口（如 `TenantDirectory.listActiveCodes()`），只投影 `code`，不要 `select *`；60 秒本地缓存，租户创建/停用后可主动失效。
- 枚举失败（数据库异常/结果为空）→ **本轮跳过并告警**，不要回落 `default` 静默执行；否则报表显示「成功」但实际漏掉了所有非默认租户。
- 设置单轮租户上限与游标（按 `id` 分页），避免租户数增长后一次拉全量。

**单租户失败如何隔离**

- 外层：每个租户独立 `try/catch`，记录 `platform_scan_tenant_failed{job,tenant}` 指标 + WARN 日志，继续下一个租户。
- 内层：每个业务条目（连接器/run/流水）独立 `try/catch`，同租户内一条失败不影响其余条目。
- 作业终态：返回统计载荷 `tenantsTotal / tenantsOk / tenantsFailed / itemsScanned / itemsFailed`；仅「枚举失败」或「全部租户失败」让作业失败进入重试，部分失败算成功并告警（待决策，见 §3.5.3.5）。
- 取消：每租户、每条都检查 `context.cancellation()`，保证长轮次可被租约/取消打断。
- 幂等：保留现有 `bizKey` 与条件 UPDATE；部分失败重试时必须保证已成功租户不会被重复处理出副作用。

**如何避免和业务写入抢锁或长时间占连接**

1. **禁止一个事务跨租户**：每租户一个毫秒级短事务，事务内只做数据库读写，**不做 HTTP 抓取、对象存储、向量库调用**（连接器同步、孤儿删除必须放在事务外）。
2. 扫描用普通 `SELECT`（MVCC 不阻塞业务写），不加 `FOR UPDATE`；修复用条件 `UPDATE`（`cancelPending` 已带 `status` 条件），锁只落在单行。
3. 每租户 `LIMIT batch`，租户之间**默认串行**（并发度 1，可配置到 2–4），避免打满 Hikari（`maximum-pool-size=20`）。
4. 只读事务设 `statement_timeout`（建议 5s），并保留 `idle_in_transaction_session_timeout`；慢租户只影响自己这一轮。
5. 维护车道已有 Redis 单飞（`JobRunner` 对 `MAINTENANCE` 车道加锁），多实例同一时刻只有一份平台扫描；租户轮次内部不要再嵌套分布式锁。
6. 热点表按 `id` 游标分页，不用大 `OFFSET`；对账/退款的候选本身带时间窗与保护期，天然低冲突。
7. 连接占用估算：串行扫描时同一时刻最多占用 1 条连接；连接器/退款的后续写事务各自独立，不叠加在扫描事务上。

**建议的实施顺序（确认后）**

1. 租户枚举（`TenantDirectory`）+ 平台扫描的公共骨架（逐租户短事务、失败隔离、统计载荷）。
2. `ConnectorScheduler` 与 `SchedulerReconcileJobHandler`（#2，用户已点名的两条）。
3. 其余周期作业（续跑扫描、配额退款、上传/记忆/语义缓存清理、孤儿扫描）。
4. `RetrievalMetricsGauge`（#4），最后做，避免与扫描改造互相干扰。

**验收（实施后执行，本轮不执行）**

- 至少造 2 个启用租户 + 1 个停用租户：平台扫描能覆盖两个启用租户，停用租户按决策处理。
- 人为让某租户的业务查询抛异常：该租户记失败，其余租户继续完成，作业不整体失败。
- 观察扫描期间的 `pg_locks` / 连接数：无跨租户长事务、无表级锁、Hikari 活跃连接不超过约定上限。
- 对账：非默认租户的 `PENDING` 作业在业务终态后能被取消；业务读失败时**不得**被取消（防止误杀）。

#### 3.5.3.5 待决策项（#2 / #4 / #6）

1. **#2 修复写路径**：继续直接调 `jobMapper.cancelPending`，还是收敛到 `JobStore`（复用租约/条件更新/指标）？后者更一致，但需要把取消能力暴露成 `JobStore` 方法。
2. **#2 业务读失败的处理**：跳过并告警（推荐），还是整轮失败重试？若跳过，是否强制要求「业务读成功」才允许 `business_missing` 自动修复（推荐强制）。
3. **#2 是否按租户分组批量读**（方案 B）：先按方案 A 逐条实现，候选量上来后再切 B，是否接受？
4. **#4 指标口径**：保持平台全量（A/B），还是允许降级为租户维度/缺失（C）？若保持全量，选 A（`wgh_platform` 加 SELECT + 只读策略）还是 B1（`SECURITY DEFINER` 超级用户函数，只暴露 count）？
5. **#4 扩权评审**：是否接受 `wgh_platform` 对 `wgh_knowledge_base` 的全行只读可见？不接受则只能走 B1。
6. **#6 扫描组织方式**：单个平台作业内部遍历租户（改动小、作业数不变，推荐），还是每租户提交一个周期作业（隔离更好、复用租户并发槽位，但作业数 × 租户数）？
7. **#6 部分失败的终态**：部分租户失败算成功 + 告警，还是失败重试（需保证已成功租户幂等）？
8. **#6 停用租户是否扫描**：建议业务扫描跳过，但对账/存储清理保留（避免残留作业与对象永久滞留），需拍板。
9. **#6 配置项**：平台扫描开关、租户并发度、单轮租户上限、`statement_timeout` 是否本轮一起纳入？本轮约束是不改配置，需确认下一轮范围。

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
| 加列 | 所有租户-owned 表 | `tenant_id BIGINT`，先可空后 NOT NULL（**偏差**：已按现状落地为 `VARCHAR(64)` 租户 code，默认 `default`，见 §3.5.1.2） |
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
| 字段 | 作业表/策略表/运行槽从第一天带 `tenant_id`，默认 `default`（**偏差**：原方案写默认 `0`，现状为 `VARCHAR` 的 `default`） |
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
| metadata | 共享集合（语义缓存、Agent 记忆）写入 `tenantId`；KB 集合同时写 `kbId` 与 `tenantId`，经 KB 鉴权后再由编排层下发租户断言 |
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
7. **平台级扫描与跨租户指标**：#2 / #4 / #6 的方案见 §3.5.3，待决策项见 §3.5.3.5（含指标口径、扫描组织方式、部分失败终态）。

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

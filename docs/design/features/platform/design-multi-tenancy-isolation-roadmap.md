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
| 3 | 方案与脚本已备，未执行 | RLS 评审与 9 项决策定稿；`09`～`15` 脚本（角色/授权/A/B/Prompt-Tool/D/回滚/验收）；Prompt/Tool 显式 `scope` 标记（schema 已同步） | 未执行任何 DDL、未建角色、未切 `wgh_app`；应用侧事务级 GUC、登录审计/调用日志写入顺序、Prompt/Tool 插件放行待随灰度实施 |
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

**迁移登记**：SQL 增量 `sql/migrations/20261124/05_tenant.sql`（租户目录/身份归属）、`06_tenant_data_plane.sql`（34 张业务表 tenant_id，幂等、含回填）；配置增量 `config/migrations/20261124/09_tenant.yml`；两者均需部署前手动执行/合并，不纳入迁移 Job。

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

> 注：配额三表的后台退款/汇总作业会跨租户批处理，必须走调度角色或按租户分片执行，否则 RLS 下读不到其他租户数据。

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

#### 3.5.1.10 实施脚本与验收矩阵（2026-11-24，仅备稿，未执行）

> 本节脚本均为评审稿：**未执行任何 DDL、未创建角色、未修改连接配置**；也未登记到 `kustomize` / `run-migrations.sh`。

**脚本清单**（`rag-backend/sql/migrations/20261124/`）

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

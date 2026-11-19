# 多租户隔离方案分析与引入可行性评估

> **版本**：v1.0
> **日期**：2026-11-18
> **性质**：只读分析。未修改任何业务代码，未执行 DDL，未改动数据库，未重启服务。
> **分析范围**：`rag-backend` 全部 17 个 Maven 模块、`sql/schema` 与 `sql/migrations`、`application*.yml`、`docker-compose.yml`、`rag-frontend` 中与可见性/身份相关的片段。
> **方法**：先扫描模块、表结构与配置，再对认证、归属、可见性、配额、存储、缓存、审计、开放 API 逐文件读代码取证。文中每个关键结论均给出 `文件路径#行号`；**未读到代码或未验证的推断会明确标注"未验证"**。
> **证据口径**：链接指向当前工作区文件；"主流做法"指 PostgreSQL / Spring Boot / SaaS 多租户的通用工程实践，不代表唯一解。

---

## 0. 结论先行

### 0.1 一句话判断

**当前系统不是多租户系统，而是"单组织 + 用户级资源可见性"的单体应用。它可以支撑"一家企业内部多员工"的使用，但以现状无法支撑"多企业互不信任的 SaaS 多租户"或"多法律实体强隔离"的企业级场景。**

### 0.2 关键结论

| # | 结论 | 依据 |
|---|---|---|
| 1 | 系统里没有"租户/组织"实体，也没有租户上下文。`wgh_user.tenant_id` 是**死字段**：建表与实体存在，但全仓库无任何读写。 | [01-schema.sql#L1191](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/sql/schema/01-schema.sql#L1191)、[WghUser.java#L41](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-auth/src/main/java/com/wgh/rag/auth/entity/WghUser.java#L41)；全仓库搜索 `setTenantId/getTenantId/tenant-id` 无命中 |
| 2 | 当前隔离的基本单位是**用户名 `create_by`**，规则是 `create_by = 当前用户 OR visibility <> 'PRIVATE'`，只覆盖 5 张资源表，且只在列表查询时激活。 | [DataScopeContext.java#L27-L32](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-data/src/main/java/com/wgh/rag/data/scope/DataScopeContext.java#L27-L32)、[DataScopeInterceptor.java#L51-L101](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-data/src/main/java/com/wgh/rag/data/scope/DataScopeInterceptor.java#L51-L101) |
| 3 | `TEAM` 与 `PUBLIC` 语义相同，都表示"所有登录用户可读"，并不存在团队边界。 | [ResourceVisibility.java#L9-L44](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-data/src/main/java/com/wgh/rag/data/scope/ResourceVisibility.java#L9-L44)；前端提示也写明"登录用户可读" [visibility.js#L8](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-frontend/src/utils/visibility.js#L8) |
| 4 | 多个**子资源入口绕过归属校验**：知识库文件、分片上传初始化、评测数据集、知识库检索测试只校验"父对象存在"，不校验"当前用户可写/可读"。 | [KnowledgeFileService.java#L78-L98](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KnowledgeFileService.java#L78-L98)、[EvalDatasetService.java#L378-L383](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/EvalDatasetService.java#L378-L383) |
| 5 | 开放 API（`/v1/**`、`/api/open/**`）的 API Key **不绑定任何用户或租户**，可列出全系统窗口、按 ID 访问任意窗口、按 ID 检索任意知识库。 | [ApiKeyAuthenticationFilter.java#L47-L71](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-public-access/src/main/java/com/wgh/rag/publicaccess/config/ApiKeyAuthenticationFilter.java#L47-L71)、[OpenApiController.java#L111-L143](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-controller/src/main/java/com/wgh/rag/controller/OpenApiController.java#L111-L143)、[OpenAiCompatController.java#L179-L201](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-controller/src/main/java/com/wgh/rag/controller/openai/OpenAiCompatController.java#L179-L201) |
| 6 | 配额、审计、存储、Redis、向量表都**没有租户维度或命名空间**，无法直接做按租户计量、按租户限流、按租户备份/导出。 | [QuotaService.java#L75-L132](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-quota/src/main/java/com/wgh/rag/quota/service/QuotaService.java#L75-L132)、[AuditLog.java#L14-L49](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-log/src/main/java/com/wgh/rag/log/entity/AuditLog.java#L14-L49)、[StorageKeys.java#L11-L42](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-storage/src/main/java/com/wgh/rag/storage/service/StorageKeys.java#L11-L42) |
| 7 | **推荐方案**：共享库共享表 + `tenant_id` 作为第一阶段主线，PostgreSQL RLS 作为纵深防御，按租户等级做混合分层（大客户/强合规客户独享 schema 或数据库）作为兜底。 | 见 §5 |
| 8 | **企业级多租户可用性**：引入上述方案并完成阶段 0–3 后可以支撑；仅靠现状的 resource visibility **只能支撑单企业多用户**，不能支撑多企业隔离。 | 见 §4、§7 |

### 0.3 推荐分阶段组合（概览）

```
阶段 0：先修越权（不引入租户）        —— 阻断现存跨用户读写
阶段 1：租户模型与身份上下文          —— tenant + tenant_id + TenantContext
阶段 2：数据面全量隔离                —— 全部表/子资源/存储/缓存/配额/审计/开放 API
阶段 3：企业级加固                    —— RLS、按租户计量计费、生命周期、备份恢复
阶段 4：混合分层（按需）              —— 大客户独立 schema / 独立数据库
```

---

## 1. 目标与"企业级多租户可用"的硬性条件

### 1.1 本文的"租户"定义

建议把**一个租户 = 一个企业/组织/法律实体**；一个用户默认只属于一个租户；租户内再讨论"团队/项目"（当前系统的 `TEAM` 不是这个概念）。这样最贴合本系统现有"用户 → 资源"模型，改造成本最低。

若未来需要"一个用户属于多个租户、可切换"，则需要成员关系表与租户切换令牌；不建议在第一阶段引入。

### 1.2 企业级可用的硬性条件

| # | 条件 | 说明 |
|---|---|---|
| C1 | 租户身份来自可信凭证 | 只能由登录令牌/会话派生，不能由请求参数或前端声明 |
| C2 | 每个租户数据行都有 `tenant_id` | 包括子资源；不能只靠父子关系推导 |
| C3 | 所有读写都带租户条件 | 代码显式条件 + 拦截器/RLS 兜底，二者至少一层始终生效 |
| C4 | 子资源必须校验父资源租户 | 防止"父对象存在即放行"造成的 IDOR |
| C5 | 平台管理员 / 租户管理员分离 | 租户管理员不能跨租户；平台管理员可运维但不能默认读业务数据 |
| C6 | 租户级配额、限流、计量、计费 | 不能只有 `username` 维度 |
| C7 | 租户级存储前缀与缓存命名空间 | 便于导出、删除、迁移、排障 |
| C8 | 审计日志可归属到租户 | 满足合规与追责 |
| C9 | 租户生命周期 | 创建、停用、导出、删除、备份、恢复 |
| C10 | 跨租户负向测试 | 每个资源类型都要有"B 租户访问 A 租户必须失败"的自动化用例 |
| C11 | 性能可接受 | `tenant_id` 进入联合索引前导列；向量检索不被租户条件拖垮 |

---

## 2. 主流多租户隔离方案对比

### 2.1 方案矩阵

| 维度 | 独立数据库（DB per tenant） | 独立 Schema（Schema per tenant） | 共享表 + `tenant_id` | 共享表 + RLS | 混合/分层 |
|---|---|---|---|---|---|
| 隔离强度 | 最高（物理隔离） | 高（逻辑库级） | 中（应用层保证） | 中高（数据库强制） | 按租户等级分层 |
| 单租户数据泄露半径 | 仅该库 | 仅该 schema | 全表（一个条件漏写即越权） | 全表，但策略兜底 | 小租户共享、大客户独享 |
| 基础设施成本 | 最高（连接、备份、监控×N） | 较高（schema 对象×N） | 最低 | 低 | 中 |
| 运维复杂度 | 高（迁移、升级×N） | 高（DDL 广播、search_path） | 低（一次升级） | 中（策略/角色/会话变量） | 高（双形态并存） |
| 扩展性（租户数） | 差（几十~几百） | 中（几百~几千） | 好（万级） | 好（万级） | 好（分层承载） |
| 单租户性能 | 最好（无租户条件） | 好 | 好（`tenant_id` 前导索引） | 略差（每查询多一个谓词） | 好 |
| 迁移/升级难度 | 极高 | 高 | 低 | 中 | 高 |
| 跨租户统计/运营 | 难（需汇总） | 中 | 易（同表聚合） | 易（需绕过策略） | 中 |
| 备份/恢复粒度 | 天然按库 | 可按 schema | 只能逻辑导出 | 只能逻辑导出 | 按等级 |
| 合规/数据驻留 | 最容易证明 | 较容易 | 难（同表同库） | 中（同库不同行） | 可满足 |
| 适用规模 | 少量大客户、强监管 | 中大型客户、需逻辑隔离 | SaaS 标准版、海量租户 | 共享表的安全增强 | 商业化 SaaS 常见终态 |

### 2.2 各方案的企业落地取舍

#### 2.2.1 独立数据库

- **优点**：物理隔离最强；单租户故障、慢查询、数据损坏不影响他人；可按客户做独立备份、加密密钥、数据驻留；合规解释成本最低。
- **缺点**：连接池、迁移脚本、监控、日志、升级都要按租户编排；租户数一多，运维成本线性上升；跨租户运营报表需要 ETL。
- **典型取舍**：只给"大客户/ regulated 客户"用；中小客户仍走共享池。若一开始就全量独立库，产品很难规模化。
- **对本项目**：技术上可行（PostgreSQL 连接串可按租户切换），但现有代码是单 `DataSource` + 单 Hikari + MyBatis-Plus 单拦截器；pgvector 每 KB 建物理表，独立库后建表/重建/迁移都要路由到对应库，改造成本最高。

#### 2.2.2 独立 Schema

- **优点**：同一数据库实例内逻辑隔离；备份可按 schema；权限可按 schema 授予；比独立库省资源。
- **缺点**：DDL 要广播到所有 schema；`search_path` 与会话绑定，连接池复用时会串 schema；MyBatis-Plus 的全局表名/拦截器需要按租户重写；pgvector 扩展与向量表在每个 schema 下重复创建；租户数上千后 catalog 压力明显。
- **典型取舍**：租户数量可控（几十到几百）、需要"看起来像独立库"的中大型客户。
- **对本项目**：`application.yml` 使用 `currentSchema=public` 固定 schema [application.yml#L16](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-app/src/main/resources/application.yml#L16)；改成动态 schema 需要改连接初始化与所有原生 SQL，风险高于共享表。

#### 2.2.3 共享表 + `tenant_id`

- **优点**：一次建表、一次升级；租户开通只是插一行；跨租户运营/计费最容易；配合 `tenant_id` 前导索引性能可控；适合万级租户。
- **缺点**：隔离完全依赖应用代码；任何一处漏写 `tenant_id` 就是跨租户越权；原生 SQL、JOIN、缓存、异步线程都是高风险点；同表同库难以做物理级合规证明。
- **典型取舍**：SaaS 标准版的主流选择；必须配套"拦截器 + 代码评审清单 + 负向测试"三件套。
- **对本项目**：最贴合现状。已有 `create_by + visibility` 的应用层过滤经验，`tenant_id` 可作为更强的前置条件叠加；表结构改动是机械性的，但**子资源与原生 SQL 的覆盖**才是工作量所在。

#### 2.2.4 数据库行级安全（RLS）

- **优点**：数据库强制，应用漏写条件也不越权；策略集中管理；适合作为共享表方案的纵深防御。
- **缺点**：需要为每个请求设置会话变量（如 `SET LOCAL app.tenant_id`）；连接池复用、异步线程、事务边界都要正确处理；**表 owner / superuser 默认绕过 RLS**，必须使用非 owner 应用角色并 `FORCE ROW LEVEL SECURITY`；调试与慢查询分析更复杂。
- **典型取舍**：金融、医疗、政企等"必须证明数据库层也隔离"的场景；通常与共享表 `tenant_id` 同时使用，而不是二选一。
- **对本项目**：`docker-compose.yml` 当前用 `postgres` 超级用户连接 [docker-compose.yml#L11-L13](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/docker-compose.yml#L11-L13)。**在更换应用数据库角色之前，RLS 实际不会生效**。这是引入 RLS 的前置条件，不能忽略。

#### 2.2.5 混合/分层

- **形态**：`isolation_mode = shared | schema | database`；标准版共享表，旗舰版独立 schema/DB；平台侧统一租户目录与路由。
- **优点**：成本与隔离强度可按客户分层；大客户愿意为隔离付费；小客户不被基础设施成本拖累。
- **缺点**：两套（或三套）数据面并存，迁移、监控、备份、升级复杂度显著上升；跨池统计需要汇总层。
- **典型取舍**：商业化 SaaS 的终态，但**不应作为第一步**。先用共享表把产品跑通，再对 Top 客户做独立池。

### 2.3 企业落地时的高频关注点

| 关注点 | 共享表 + `tenant_id` | 独立 schema/DB |
|---|---|---|
| 数据合规/数据驻留 | 同库同表，需靠逻辑隔离与审计证明 | 天然可按客户部署 |
| 跨租户越权 | 最大风险；需拦截器 + RLS + 负向测试 | 风险最低，但连接路由错误仍可能串库 |
| 备份恢复 | 只能按租户逻辑导出；整库恢复会带上所有租户 | 可按库/schema 恢复 |
| 按租户计量 | 最容易（同表聚合） | 需跨库汇总 |
| 租户删除 | `DELETE WHERE tenant_id=?`，需处理向量表/对象存储/缓存 | 删库/删 schema 最干净 |
| 性能噪声 | 大租户可能拖慢同库其他租户；需限流与索引 | 天然隔离 |
| 迁移/升级 | 一次完成 | 需编排所有租户 |

### 2.4 选型结论

**推荐"共享表 + `tenant_id` 为主线，RLS 为兜底，混合分层为可选的商业化终态"。**

理由：

1. 本系统是单体 Spring Boot + 单 PostgreSQL + 单 Redis，现状没有多数据源/动态 schema 基础设施；共享表改造面最小、可渐进。
2. 现有 `create_by + visibility` 已经是应用层过滤，`tenant_id` 是同一模式的加强版，团队认知与代码习惯可复用。
3. 配额、审计、运营统计都集中在同一库，按租户聚合成本最低。
4. pgvector 已经"每知识库一张物理向量表"，向量层天然按知识库分片；只要知识库有 `tenant_id`，向量访问通过知识库路由即可，不需要为每个租户再拆一套向量库。
5. 独立 schema/DB 更适合作为"大客户/强合规"溢价能力，而不是全量默认。

---

## 3. 本系统现状评估（基于真实代码）

### 3.1 总体架构与部署形态

- 单体多模块：`rag-app` 聚合启动，业务拆分为 `rag-auth`、`rag-kb`、`rag-chat`、`rag-workflow`、`rag-agent`、`rag-agent-multi`、`rag-quota`、`rag-storage`、`rag-log`、`rag-public-access`、`rag-data`、`rag-controller` 等。
- 数据层：MyBatis-Plus + 单 PostgreSQL（`currentSchema=public`）+ pgvector；Redis 单库（`database: 0`）。
- 部署：[docker-compose.yml](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/docker-compose.yml) 单 `postgres` 实例、单 `redis` 实例、单 `backend` 实例；PostgreSQL 使用 `postgres` 超级用户。
- **结论**：部署形态是"单实例单库"，没有任何按租户路由的基础设施。

### 3.2 认证与用户体系（rag-auth）

#### 3.2.1 双认证轨道

- 本地账号：HS256 内部会话凭证，登录时校验 `wgh_user` 状态、有效期、口令版本；角色/权限实时查库。[OidcAuthenticationFilter.java#L151-L240](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-auth/src/main/java/com/wgh/rag/auth/security/OidcAuthenticationFilter.java#L151-L240)
- 外部 OIDC：JWKS 验签，用户名取 `username-claim`（默认 `sub`），角色取 `roles-claim` 后映射到 `wgh_role.role_code`。[OidcAuthenticationFilter.java#L103-L145](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-auth/src/main/java/com/wgh/rag/auth/security/OidcAuthenticationFilter.java#L103-L145)、[application.yml#L78-L97](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-app/src/main/resources/application.yml#L78-L97)
- 登录用户上下文只有 `userId`、`username`、`roles`、`permissions`、`passwordMustChange`，**没有 tenant/org**。[LoginUser.java#L11-L24](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-common/src/main/java/com/wgh/rag/common/security/LoginUser.java#L11-L24)

#### 3.2.2 用户表与"死字段"

- `wgh_user.tenant_id VARCHAR(64)` 存在，但没有任何业务代码读写；全仓库搜索 `setTenantId/getTenantId/tenant-id` 无命中。[01-schema.sql#L1185-L1207](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/sql/schema/01-schema.sql#L1185-L1207)、[WghUser.java#L41](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-auth/src/main/java/com/wgh/rag/auth/entity/WghUser.java#L41)
- 外部 OIDC 用户**不落 `wgh_user`**（只验签，不建号），因此即使 OIDC 令牌带 `tenant` claim，当前也没有落库与映射逻辑。
- 本地账号由 `AdminUserController` 创建/禁用/重置口令，账号名唯一；禁用是 `status=0` 而不是删除。[AdminUserController.java#L120-L220](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-controller/src/main/java/com/wgh/rag/controller/AdminUserController.java#L120-L220)

#### 3.2.3 改造点

| 改造项 | 说明 | 量级 |
|---|---|---|
| 租户实体与成员关系 | 新增 `wgh_tenant`；`wgh_user.tenant_id` 启用；外部 OIDC 用户首次进入时落库/映射租户 | M |
| 租户 claim | 本地令牌写入 `tenantId`；OIDC 读取 `tenant`/`tid` claim；缺失或未知租户拒绝 | S |
| 租户上下文 | `TenantContext`（ThreadLocal）+ 过滤器填充 + 异步透传 | S-M |
| 平台管理员/租户管理员 | 新增平台角色；租户管理员只能管理本租户 | M |

---

### 3.3 角色与权限（RBAC）

- 表：`wgh_role`、`wgh_permission`、`wgh_user_role`、`wgh_role_permission` 都是**全局表**，无 `tenant_id`。[01-schema.sql#L1215-L1250](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/sql/schema/01-schema.sql#L1215-L1250)
- 权限校验：`@RequiresPermission` + `PermissionAspect`；角色 `admin/editor/viewer` 等为全局角色。
- 管理接口：`AdminRoleController`、`AdminUserController` 可管理全局角色与用户；`QuotaAdminController` 可管理任意用户配额。
- **风险**：租户 A 的管理员若拿到全局 admin 角色，就能管理租户 B。多租户下必须区分"平台管理员"和"租户管理员"，并把角色、权限授予、用户管理都加上租户范围。

**改造点**：角色表增加 `tenant_id`（或保留全局角色 + 新增租户成员关系）；`wgh_user_role` 增加租户维度；管理端接口按租户过滤；平台角色单独命名空间。量级 **M**。

---

### 3.4 资源归属与可见性（核心）

#### 3.4.1 归属字段来源

- 所有继承 `BaseEntity` 的实体在插入时由 `MetaObjectHandlerImpl` 自动填充 `createBy = 当前用户名`；**匿名/异步线程回落为 `system`**。[MetaObjectHandlerImpl.java#L21-L44](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-data/src/main/java/com/wgh/rag/data/mybatis/MetaObjectHandlerImpl.java#L21-L44)
- 因此：
  - `create_by` 是**用户名字符串**，不是 `wgh_user.id` 外键；
  - 后台任务、调度、异步落库写入的资源 owner 是 `system`；
  - 用户被禁用后重新启用/同名重建的语义依赖用户名稳定（当前没有改名接口，风险较低，但缺乏外键约束）。

#### 3.4.2 可见性模型

- 五张资源表有 `visibility`：`wgh_knowledge_base`、`wgh_chat_window`、`wgh_workflow`、`wgh_tool`、`wgh_prompt_template`。[DataScopeContext.java#L27-L32](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-data/src/main/java/com/wgh/rag/data/scope/DataScopeContext.java#L27-L32)
- 取值 `PRIVATE / TEAM / PUBLIC`；`isShared()` 把 `TEAM` 和 `PUBLIC` 都视为"非所有者可读"。[ResourceVisibility.java#L38-L44](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-data/src/main/java/com/wgh/rag/data/scope/ResourceVisibility.java#L38-L44)
- 列表查询通过 `DataScopeContext.runWithScope` 激活拦截器，拦截器追加 `(create_by = '当前用户' OR visibility <> 'PRIVATE')`。[DataScopeInterceptor.java#L90-L100](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-data/src/main/java/com/wgh/rag/data/scope/DataScopeInterceptor.java#L90-L100)
- 详情/更新/删除通过 `DataScopeGuard` 做 owner/visibility 判断：他人 PRIVATE 按 404 处理；他人 TEAM/PUBLIC 可读不可写。[DataScopeGuard.java#L18-L44](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-data/src/main/java/com/wgh/rag/data/scope/DataScopeGuard.java#L18-L44)

#### 3.4.3 拦截器边界（重要）

- **只在显式激活期间生效**：`runWithScope` 未被调用的查询（按 ID 取数、调度任务、分享链路、Agent/工作流运行、异步线程）不改写 SQL。[DataScopeContext.java#L15-L58](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-data/src/main/java/com/wgh/rag/data/scope/DataScopeContext.java#L15-L58)
- **只处理单表 `PlainSelect`**；命中白名单表的 JOIN 直接抛错（失败关闭），UNION/CTE 命中白名单表也抛错。[DataScopeInterceptor.java#L74-L109](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-data/src/main/java/com/wgh/rag/data/scope/DataScopeInterceptor.java#L74-L109)
- **不覆盖子资源**：`wgh_knowledge_file`、`wgh_eval_dataset`、`wgh_kb_connector`、`wgh_upload_session`、`wgh_agent_plan/step`、`wgh_workflow_run/node_run`、`wgh_m_agent_*`、`wgh_chat_session/message` 都不在 5 表白名单内。

#### 3.4.4 当前调用点

`runWithScope` 只出现在 5 处列表/分页：知识库、聊天窗口、工作流、工具、Prompt 模板。[Grep 结果](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KnowledgeBaseService.java#L42-L54) 等。其余业务查询默认不受可见性约束。

**改造点**：把"租户条件"下沉到统一拦截器（MyBatis-Plus `TenantLineInnerInterceptor` 或扩展现有拦截器），并要求所有按 ID 的读写都经过"父资源租户校验 + 子资源租户校验"。量级 **L**（覆盖面大）。

---

### 3.5 各业务表归属字段现状

下表按"是否已有归属字段 / 是否有可见性 / 隔离缺口"盘点。**结论：绝大多数子表只有 `create_by`，没有 `visibility`，也没有 `tenant_id`。**

| 表 | 现有归属字段 | 可见性 | 现状隔离方式 | 多租户改造点 |
|---|---|---|---|---|
| `wgh_llm_model` / `wgh_embedding_model` | `create_by` | 无 | 全局共享，管理员维护 | 平台级或 `tenant_id`（支持 BYOK） |
| `wgh_knowledge_base` | `create_by` | `visibility` | 5 表白名单 + owner | `tenant_id` + 索引 `(tenant_id, create_by, visibility)` |
| `wgh_knowledge_file` | `knowledge_base_id`、`create_by` | 无 | 仅父 KB 存在性 | `tenant_id`，所有入口校验父 KB 可写/可读 |
| `wgh_knowledge_file_version` | `file_id` | 无 | 随文件 | 可冗余 `tenant_id` 便于清理 |
| `wgh_kb_ground_truth` | `knowledge_base_id`、`create_by` | 无 | 随 KB | `tenant_id` + 父 KB 校验 |
| `wgh_eval_dataset` / `wgh_eval_case` / `wgh_eval_result` | `knowledge_base_id`/`dataset_id`、`create_by` | 无 | **只校验 KB 存在** | `tenant_id` + 父 KB 可写校验 |
| `wgh_chat_window` | `create_by` | `visibility` | 5 表白名单 + owner | `tenant_id` + 语义改为"租户内可见" |
| `wgh_chat_session` | `chat_window_id`、`user_identifier`、`create_by` | 无 | 窗口 + 用户名 | `tenant_id`；`user_identifier` 加入租户前缀或改用 userId |
| `wgh_chat_message` | `session_id`、`create_by` | 无 | 随会话 | `tenant_id` |
| `wgh_tool` | `create_by` | `visibility` | 5 表白名单 + owner | `tenant_id` |
| `wgh_agent_plan` / `wgh_agent_step` | `session_id`/`plan_id`、`create_by` | 无 | 随会话 | `tenant_id` + 运行态归属校验 |
| `wgh_workflow` | `create_by` | `visibility` | 5 表白名单 + owner | `tenant_id` |
| `wgh_workflow_version` / `trigger` / `run` / `node_run` | `workflow_id`/`run_id`、`create_by` | 无 | 随工作流；`RunAccessGuard` 仅覆盖部分 | `tenant_id`；运行态统一归属校验 |
| `wgh_m_agent_run` / `step` / `crew` | `chat_window_id`/`session_id`、`create_by` | 无 | 随窗口/会话；**运行态接口无归属校验** | `tenant_id` + 运行态归属校验 |
| `wgh_audit_log` | `username`、`resource_type/id` | 无 | 仅用户名 | `tenant_id` + 操作者 |
| `wgh_model_price` | `create_by` | 无 | 全局唯一 `(provider, model_name)` | 平台级或租户级价格 |
| `wgh_quota_usage` | `username` + `date_key` | 无 | 用户级唯一 | `tenant_id` + `(tenant_id, username, period_key)` |
| `wgh_user_quota_config` | `username` | 无 | 用户级唯一 | `tenant_id` + 租户默认/用户覆盖 |
| `wgh_semantic_cache` | `window_id`、`create_by` | 无 | 窗口 + 向量 metadata `windowId` | `tenant_id`；向量 metadata 增加 `tenantId` |
| `wgh_prompt_template` | `create_by` | `visibility` | 5 表白名单 + owner | `tenant_id`；内置模板改为平台级共享 |
| `wgh_tag` | `create_by` | 无 | **全局唯一 name** | `tenant_id` + `UNIQUE(tenant_id, name)` |
| `wgh_kb_tag_rel` / `wgh_file_tag_rel` | `kb_id`/`file_id` + `tag_id` | 无 | 关系表 | 通过父资源校验；可选冗余 `tenant_id` |
| `wgh_user` | `tenant_id`（未使用） | - | 全局用户名唯一 | 启用 `tenant_id`；唯一约束改 `(tenant_id, username)` |
| `wgh_role` / `permission` / `user_role` / `role_permission` | 无 | - | 全局 RBAC | 租户范围或成员关系 |
| `wgh_share_link` | `created_by`/`create_by` | 无 | 管理端全量列表 | `tenant_id`；分享策略按租户 |
| `wgh_api_key` | `created_by`/`create_by` | 无 | **管理端全量列表；Key 不绑定资源** | `tenant_id` + owner + 资源范围 |
| `wgh_api_call_log` | `api_key_id`、`username` | 无 | 按 Key | `tenant_id` |
| `wgh_kb_connector` / `run` | `knowledge_base_id`、`create_by` | 无 | `DataScopeGuard` 已覆盖 | `tenant_id` |
| `wgh_upload_session` | `knowledge_base_id`、`create_by` | 无 | `requireSession` 校验 owner | `tenant_id`；init 需校验 KB 可写 |
| `wgh_agent_memory` | `scope_type`/`scope_id`、`create_by` | 无 | 向量 metadata `scopeKey` | `tenant_id`；`scopeKey` 加租户前缀 |

> 说明：表结构定义集中在 [01-schema.sql](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/sql/schema/01-schema.sql)，上表行号可从上文 `CREATE TABLE` 列表定位；`20261113/01_resource_visibility.sql` 只给 5 张表补了 `visibility`，未涉及租户。

---

### 3.6 pgvector 向量表与语义缓存的隔离方式

#### 3.6.1 知识库向量表

- 每个知识库一张物理向量表，表名存在 `wgh_knowledge_base.collection_name`，全局唯一。[01-schema.sql#L101](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/sql/schema/01-schema.sql#L101)
- 新建知识库时 `collection_name = "wgh_kb_" + System.currentTimeMillis()`；重建时 `wgh_kb_<kbId>_<ts>`。[KnowledgeBaseService.java#L77](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KnowledgeBaseService.java#L77)、[KnowledgeBaseService.java#L159](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KnowledgeBaseService.java#L159)
- 向量表本身没有 `tenant_id`，隔离完全依赖"知识库归属 → collection_name → 表"的链路。只要所有向量访问都先经过知识库鉴权，这条链路是可用的；但一旦有"按 collection 名直接访问"的入口，就会绕过租户。

#### 3.6.2 共享向量表

- 语义缓存：向量写入 `wgh_semantic_cache_vec`，用 metadata `windowId` 过滤；问答对写 `wgh_semantic_cache`。[SemanticCacheService.java#L30-L42](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/SemanticCacheService.java#L30-L42)、[SemanticCacheService.java#L64-L81](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/SemanticCacheService.java#L64-L81)
- Agent 记忆：向量写入 `wgh_agent_memory_vec`，用 metadata `scopeKey` 过滤；`scopeKey = SESSION:{sessionId}` 或 `TASK:{taskId}`。[AgentMemoryService.java#L31-L46](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/AgentMemoryService.java#L31-L46)、[RagChatService.java#L1691-L1726](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/service/RagChatService.java#L1691-L1726)
- **风险**：共享向量表没有租户过滤列；当前依赖 `windowId` / `sessionId` 全局唯一且入口已鉴权。多租户下建议在 metadata 与 SQL 条件中都加 `tenantId`，并给 `scopeKey` 加租户前缀，避免未来出现跨租户 ID 碰撞或按 ID 直查。

#### 3.6.3 改造点

| 项 | 改造 |
|---|---|
| 知识库向量表 | 表名加租户前缀（`wgh_kb_t{tenantId}_{kbId}`）或仅依赖 KB 租户；所有 collection 访问必须经 KB 鉴权 |
| 语义缓存 | 行表与向量 metadata 增加 `tenantId`；查询条件增加 `tenantId` |
| Agent 记忆 | `scopeKey` 加租户前缀；召回条件增加 `tenantId` |
| 缓存失效 | `selectWindowKbMappings()` 当前全表扫描所有窗口，多租户下需按租户限定 [SemanticCacheMapper.java#L21](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/mapper/SemanticCacheMapper.java#L21) |

量级 **M**。

---

### 3.7 配额体系（rag-quota）

- 计数与配置都是**按 `username`**：
  - `wgh_quota_usage` 唯一键 `(username, date_key)`；
  - `wgh_user_quota_config` 唯一键 `username`。[01-schema.sql#L964-L998](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/sql/schema/01-schema.sql#L964-L998)
- 解析顺序：用户自定义配置（启用）> 全局默认；周期支持 DAY/MONTH。[QuotaService.java#L70-L87](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-quota/src/main/java/com/wgh/rag/quota/service/QuotaService.java#L70-L87)
- 计数使用 `INSERT ... ON CONFLICT` 原子累加，并发安全。[QuotaService.java#L90-L132](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-quota/src/main/java/com/wgh/rag/quota/service/QuotaService.java#L90-L132)
- 兜底用户：无登录上下文落到 `anonymous`；分享链路、异步线程共享同一份匿名配额。[DefaultQuotaUserResolver.java#L17-L24](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-quota/src/main/java/com/wgh/rag/quota/service/DefaultQuotaUserResolver.java#L17-L24)
- 开放 API 的用量落到 `api_<keyId>`，与真实用户/租户无关。[OpenApiController.java#L145-L160](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-controller/src/main/java/com/wgh/rag/controller/OpenApiController.java#L145-L160)

**结论**：当前配额是"用户级"，不能按组织计量；计费维度只有 chat 次数与 token，没有存储、检索次数、向量量等维度。

**改造点**：
1. `wgh_quota_usage` / `wgh_user_quota_config` 增加 `tenant_id`；
2. 增加租户级默认配额与租户级聚合视图；
3. 限流维度增加 `tenant`（Redis key 加租户前缀）；
4. 计费维度扩展：token、chat、storage、retrieval、embedding；
5. 开放 API Key 绑定租户后，用量归集到租户。量级 **M**。

---

### 3.8 存储层（local / OSS）

- Key 规则：`kb/{kbId}/{fileUuid}/{安全化文件名}`；分片：`kb/{kbId}/{uploadUuid}/parts/{index}.part`。[StorageKeys.java#L11-L42](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-storage/src/main/java/com/wgh/rag/storage/service/StorageKeys.java#L11-L42)
- 本地存储根目录 `WGH_UPLOAD_DIR`；OSS 全局 `key-prefix` 配置，可空。[application.yml#L116-L134](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-app/src/main/resources/application.yml#L116-L134)
- 由于 `kbId` 全局唯一，跨租户**不会发生路径覆盖**；但没有租户前缀，导致：
  - 按租户导出/删除/迁移需要先查库再拼 key；
  - 对象存储生命周期策略无法按租户配置；
  - 排障时无法从 key 直接看出归属。

**改造点**：key 改为 `t/{tenantId}/kb/{kbId}/...`；OSS 侧按前缀配置生命周期；迁移脚本按旧 key 重写。量级 **S-M**。

---

### 3.9 缓存 / Redis 命名空间

- Redis 目前只用于公开访问限流与分享密码锁定：
  - `wgh:pa:rl:{dimension}:{path}:{subject}` [PublicAccessRateLimiter.java#L71-L73](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-public-access/src/main/java/com/wgh/rag/publicaccess/service/PublicAccessRateLimiter.java#L71-L73)
  - `wgh:pa:pwd:{fail|lock}:{token}:{ip}` [SharePasswordGuard.java#L26](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-public-access/src/main/java/com/wgh/rag/publicaccess/service/SharePasswordGuard.java#L26)
- Redis 单库 `database: 0`，没有租户前缀/分库。[application.yml#L137-L143](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-app/src/main/resources/application.yml#L137-L143)
- 多租户下需要：
  1. 所有 key 加 `wgh:t:{tenantId}:`；
  2. 按租户限流（API Key、IP、分享 token 的 subject 不能跨租户共享）；
  3. 可选：按租户分配 Redis DB（不推荐，DB 数量有限且运维复杂）。

量级 **S**。

---

### 3.10 审计日志（rag-log）

- `AuditAspect` 记录 `username`、`action`、`resourceType`、`resourceId`、`detail`、`ip`、`status`。[AuditAspect.java#L44-L58](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-log/src/main/java/com/wgh/rag/log/aspect/AuditAspect.java#L44-L58)
- `AuditLog` 实体没有 `tenantId`。[AuditLog.java#L14-L49](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-log/src/main/java/com/wgh/rag/log/entity/AuditLog.java#L14-L49)
- 开放 API 调用日志有 `api_key_id`、`username`、`path`，同样没有租户。[01-schema.sql#L1307-L1324](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/sql/schema/01-schema.sql#L1307-L1324)
- `resourceId` 是"第一个 Long/Integer 参数"的尽力提取，可能不是真实资源 ID；多租户下不能作为可靠归属依据。[AuditAspect.java#L61-L72](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-log/src/main/java/com/wgh/rag/log/aspect/AuditAspect.java#L61-L72)

**改造点**：审计表与调用日志增加 `tenant_id`；审计切面从 `TenantContext` 取值；补充"跨租户访问被拒绝"的安全事件。量级 **S**。

---

### 3.11 开放 API 入口（`/v1/*`、`/api/open/**`、API Key scope）

这是当前**最严重、最直接**的多租户阻断项。

#### 3.11.1 鉴权模型

- 独立过滤器只处理 `/api/open/` 与 `/v1/`；校验 Key 哈希、吊销、过期、IP 白名单、路径对应 scope。[ApiKeyAuthenticationFilter.java#L47-L105](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-public-access/src/main/java/com/wgh/rag/publicaccess/config/ApiKeyAuthenticationFilter.java#L47-L105)
- 认证主体是字符串 `"api_" + key.getId()`，权限只有 `ROLE_API_CLIENT`；**没有 `LoginUser`、没有租户、没有 owner**。[ApiKeyAuthenticationFilter.java#L68-L71](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-public-access/src/main/java/com/wgh/rag/publicaccess/config/ApiKeyAuthenticationFilter.java#L68-L71)
- scope 只有 `chat:write`、`kb:read`、`model:read`、`usage:read`、`*`；**没有资源范围**。[ApiKeyService.java#L185-L197](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-public-access/src/main/java/com/wgh/rag/publicaccess/service/ApiKeyService.java#L185-L197)

#### 3.11.2 已验证的越权面

| 入口 | 现状 | 风险 |
|---|---|---|
| `GET /v1/models` | `windowService.page(1, 50, null)`，可见性作用域的主体是 `api_<id>`，因此返回所有 `PUBLIC/TEAM` 窗口（全系统） | 枚举他人窗口 |
| `POST /v1/chat/completions` | `resolveWindow` 先按 `windowId` 直接 `findById`，**无可见性校验** | 按数字 ID 与任意窗口对话（含 PRIVATE） |
| `POST /api/open/v1/chat/stream` | `windowService.getById(windowId)`，无可见性校验 | 同上 |
| `GET /api/open/v1/kb/search` | 直接 `retrieveService.searchMultiple(List.of(kbId), ...)`，**无 KB 可见性校验** | 按 ID 检索任意知识库 |
| `GET /api/open/v1/usage` | 用量按 `api_<keyId>` 统计 | 无法归属到用户/租户 |

证据：[OpenAiCompatController.java#L179-L201](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-controller/src/main/java/com/wgh/rag/controller/openai/OpenAiCompatController.java#L179-L201)、[OpenApiController.java#L76-L143](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-controller/src/main/java/com/wgh/rag/controller/OpenApiController.java#L76-L143)。

#### 3.11.3 改造要求

1. `wgh_api_key` 增加 `tenant_id` 与 `owner_user_id`（或 `created_by` 强绑定）；
2. Key 必须继承 owner 的租户与资源可见性，而不是"全局通行证"；
3. `/v1/models` 只返回该租户可读窗口；
4. `/v1/chat/completions`、`/api/open/v1/chat/stream` 必须校验窗口可见性；
5. `/api/open/v1/kb/search` 必须校验 KB 可见性；
6. API Key 管理列表按租户过滤；
7. scope 增加资源范围（如 `kb:read:<kbId>` 或租户级 scope）。

量级 **M**（但这是阶段 0 的必做项）。

---

### 3.12 已验证的隔离薄弱点清单

> 严重度：P0 = 直接跨用户/跨租户读写；P1 = 条件性越权或体系缺失；P2 = 设计/运维层面的隐患。

| ID | 严重度 | 问题 | 证据 | 影响 |
|---|---|---|---|---|
| G-01 | P0 | 开放 API Key 不绑定用户/租户，可列全系统窗口、按 ID 访问任意窗口与知识库 | [ApiKeyAuthenticationFilter.java#L68-L71](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-public-access/src/main/java/com/wgh/rag/publicaccess/config/ApiKeyAuthenticationFilter.java#L68-L71)、[OpenAiCompatController.java#L179-L201](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-controller/src/main/java/com/wgh/rag/controller/openai/OpenAiCompatController.java#L179-L201)、[OpenApiController.java#L111-L125](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-controller/src/main/java/com/wgh/rag/controller/OpenApiController.java#L111-L125) | 横向越权，多租户不可用 |
| G-02 | P0 | 知识库文件上传/列表/删除/重切/预览/重建/分片初始化只校验 KB 存在，不校验可写/可读 | [KnowledgeFileService.java#L78-L98](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KnowledgeFileService.java#L78-L98)、[KnowledgeFileService.java#L240-L245](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KnowledgeFileService.java#L240-L245)、[KnowledgeFileService.java#L273-L280](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KnowledgeFileService.java#L273-L280)、[KnowledgeFileService.java#L340-L346](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KnowledgeFileService.java#L340-L346)、[ChunkUploadService.java#L67-L69](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/ChunkUploadService.java#L67-L69) | 可污染/读取他人知识库 |
| G-03 | P0 | 评测数据集只校验 KB 存在，不校验可读/可写 | [EvalDatasetService.java#L65-L92](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/EvalDatasetService.java#L65-L92)、[EvalDatasetService.java#L378-L383](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/EvalDatasetService.java#L378-L383) | 可读/改他人评测数据 |
| G-04 | P1 | 知识库检索测试 `GET /api/kb/{kbId}/search` 无可见性校验 | [KnowledgeBaseController.java#L198-L213](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-controller/src/main/java/com/wgh/rag/controller/KnowledgeBaseController.java#L198-L213) | 可按 ID 检索他人 KB |
| G-05 | P1 | 数据权限拦截器只覆盖 5 张表，且只在列表查询时激活；`selectById`、原生 SQL、JOIN、异步线程默认不受控 | [DataScopeContext.java#L27-L32](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-data/src/main/java/com/wgh/rag/data/scope/DataScopeContext.java#L27-L32)、[DataScopeInterceptor.java#L51-L101](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-data/src/main/java/com/wgh/rag/data/scope/DataScopeInterceptor.java#L51-L101) | 隔离依赖开发者自觉 |
| G-06 | P1 | `create_by` 在异步/匿名线程回落 `system`，后台创建的资源无法归属到用户 | [MetaObjectHandlerImpl.java#L37-L44](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-data/src/main/java/com/wgh/rag/data/mybatis/MetaObjectHandlerImpl.java#L37-L44) | 资源"无主"，多租户下无法归属 |
| G-07 | P1 | `TEAM = PUBLIC`，没有团队/组织边界；`PUBLIC` 对所有登录用户可见 | [ResourceVisibility.java#L9-L44](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-data/src/main/java/com/wgh/rag/data/scope/ResourceVisibility.java#L9-L44) | 多租户下 `PUBLIC` 会造成跨租户泄露 |
| G-08 | P1 | 多 Agent 运行态接口（stream/state/hitl/cancel/steps）无归属校验。`runId` 为随机串（`mag-` + UUID 前 16 位，[MultiAgentRuntime.java#L254-L256](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-agent-multi/src/main/java/com/wgh/rag/agentmulti/runtime/MultiAgentRuntime.java#L254-L256)），枚举难度较高，但一旦泄露即可被任意登录用户操作 | [MultiAgentController.java#L82-L151](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-controller/src/main/java/com/wgh/rag/controller/MultiAgentController.java#L82-L151) | 运行态越权（缺纵深防御） |
| G-09 | P1 | 分享链接、API Key 管理列表是全量列表，没有 owner/租户过滤 | [ShareLinkService.java#L270-L283](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-public-access/src/main/java/com/wgh/rag/publicaccess/service/ShareLinkService.java#L270-L283)、[ApiKeyService.java#L210-L220](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-public-access/src/main/java/com/wgh/rag/publicaccess/service/ApiKeyService.java#L210-L220) | 管理端跨租户可见 |
| G-10 | P1 | 配额、审计、调用日志都没有租户维度 | [01-schema.sql#L964-L998](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/sql/schema/01-schema.sql#L964-L998)、[01-schema.sql#L916-L940](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/sql/schema/01-schema.sql#L916-L940)、[AuditLog.java#L14-L49](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-log/src/main/java/com/wgh/rag/log/entity/AuditLog.java#L14-L49) | 无法按租户计量/审计 |
| G-11 | P1 | 存储 key、Redis key 没有租户命名空间 | [StorageKeys.java#L11-L42](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-storage/src/main/java/com/wgh/rag/storage/service/StorageKeys.java#L11-L42)、[PublicAccessRateLimiter.java#L71-L73](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-public-access/src/main/java/com/wgh/rag/publicaccess/service/PublicAccessRateLimiter.java#L71-L73) | 导出/删除/限流难以按租户 |
| G-12 | P2 | `wgh_tag.name` 全局唯一，标签体系跨用户/跨租户共享 | [01-schema.sql#L1111-L1124](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/sql/schema/01-schema.sql#L1111-L1124) | 租户间标签冲突/信息泄露 |
| G-13 | P2 | 模型配置与价格全局共享，没有 BYOK/租户级模型隔离 | [01-schema.sql#L14-L89](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/sql/schema/01-schema.sql#L14-L89)、[01-schema.sql#L942-L962](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/sql/schema/01-schema.sql#L942-L962) | 一个租户的模型调用成本/额度影响他人 |
| G-14 | P2 | `wgh_user.tenant_id` 是死字段，容易误以为已支持租户 | [01-schema.sql#L1191](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/sql/schema/01-schema.sql#L1191)、[WghUser.java#L41](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-auth/src/main/java/com/wgh/rag/auth/entity/WghUser.java#L41) | 认知风险 |
| G-15 | P2 | 数据库连接使用 `postgres` 超级用户；RLS 若直接引入不会生效 | [docker-compose.yml#L7-L20](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/docker-compose.yml#L7-L20) | 安全兜底失效 |

#### 3.12.1 原生 SQL / JOIN 的具体点位

现有 `DataScopeInterceptor` 对白名单表的 JOIN 是"失败关闭"，但以下原生 SQL 涉及**非白名单表**，不会被拦截，也不会自动带租户条件：

- `ChatMessageMapper` 中 `wgh_chat_message JOIN wgh_chat_session`、再 `JOIN wgh_chat_window` 的统计查询。[ChatMessageMapper.java#L36-L62](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/mapper/ChatMessageMapper.java#L36-L62)
- `SemanticCacheMapper.selectWindowKbMappings()` 全表扫描 `wgh_chat_window`。[SemanticCacheMapper.java#L21](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/mapper/SemanticCacheMapper.java#L21)
- `QuotaUsageMapper` / `UserQuotaConfigMapper` 的 `INSERT ... ON CONFLICT`、`UPDATE` 原生 SQL。[QuotaUsageMapper.java#L20-L60](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-quota/src/main/java/com/wgh/rag/quota/mapper/QuotaUsageMapper.java#L20-L60)
- `RoleMapper` / `PermissionMapper` / `UserRoleMapper` / `RolePermissionMapper` 的 JOIN 原生 SQL。[RolePermissionMapper.java#L23-L37](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-auth/src/main/java/com/wgh/rag/auth/mapper/RolePermissionMapper.java#L23-L37)

多租户下这些 SQL 必须逐条改造：显式加 `tenant_id`，或保证被 MyBatis-Plus 租户插件覆盖（插件对 JOIN 的别名处理需要验证）。

---

### 3.13 其他现状

- **内置资源**：内置工具示例、内置 Prompt 模板被回填为 `PUBLIC`。[01_resource_visibility.sql#L32-L36](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/sql/migrations/20261113/01_resource_visibility.sql#L32-L36)。多租户下应改为"平台级共享"语义，或每个租户初始化一份。
- **历史数据**：迁移说明明确"历史 `create_by` 多为 `system`，升级后默认仅管理员可见"。[01_resource_visibility.sql#L11-L12](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/sql/migrations/20261113/01_resource_visibility.sql#L11-L12)。多租户改造需要先确定这些无主资源的归属租户。
- **公开分享/Widget**：分享链接按 token 访问，不受 `visibility` 影响；这是产品设计，但在多租户下需要"租户是否允许公开分享"的策略开关，否则任何租户都可以把自己的数据公开到互联网。
- **前端**：`TEAM` 的提示文案已暴露其真实语义"登录用户可读"。[visibility.js#L8](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-frontend/src/utils/visibility.js#L8)

---

## 4. 可行性与作用评估

### 4.1 技术可行性

| 维度 | 评估 |
|---|---|
| 代码可改造性 | **高**。单一 Spring Boot 单体、MyBatis-Plus、PostgreSQL，已有应用层可见性过滤经验；`tenant_id` 是同一模式的加强版 |
| 数据库可改造性 | **高**。共享表加列 + 回填 + 联合索引是标准操作；pgvector 表按 KB 分片，天然可共存 |
| 基础设施 | **中**。当前单库单 Redis，共享表方案无需新增基础设施；混合分层才需要多数据源/路由 |
| 主要技术难点 | 1) 原生 SQL/JOIN 全覆盖；2) 异步线程/调度任务租户上下文透传；3) 开放 API 资源范围；4) RLS 需要非 owner 应用角色与会话变量；5) pgvector 共享表 metadata 过滤 |
| 回归风险 | **高**。租户条件会改变大量查询结果；必须配套负向测试与灰度 |

### 4.2 作用

引入多租户后可以获得：

1. **多企业隔离**：租户之间默认不可见、不可写，满足 SaaS 交付前提。
2. **租户级运营**：按租户配额、限流、计量、账单、用量报表。
3. **租户级生命周期**：开通、停用、导出、删除、备份、恢复。
4. **合规可证明**：审计日志带租户维度；RLS 提供数据库层兜底；大客户可升级独立 schema/DB。
5. **平台化能力**：平台管理员可运维多个租户；租户管理员自治；模型/价格可按租户配置（BYOK）。

### 4.3 风险

| 风险 | 说明 | 缓解 |
|---|---|---|
| 跨租户越权 | 任何一个漏写 `tenant_id` 的查询都可能泄露 | 拦截器 + RLS + 负向测试 + 代码评审清单 |
| 数据迁移错误 | 存量数据归属不清（`system`、历史 PUBLIC） | 先建默认租户，逐表回填，保留审计 |
| 性能退化 | 所有查询多一个谓词；大租户可能拖慢同库 | `tenant_id` 前导索引、租户级限流、慢查询监控 |
| 功能回归 | `PUBLIC` 语义从"全局"改为"租户内"会影响现有共享行为 | 明确定义 `TENANT_PUBLIC` 与 `PLATFORM_PUBLIC`，前端同步 |
| RLS 误判 | 表 owner/superuser 绕过 RLS；连接池会话变量污染 | 独立应用角色、`FORCE ROW LEVEL SECURITY`、`SET LOCAL` 事务内设置 |
| 运维复杂度 | 混合分层后两套数据面 | 先共享表，独立池只在确有客户需求时启用 |

### 4.4 工作量与风险等级（相对量级）

> 量级定义：**S** = 少量文件/单模块；**M** = 跨模块、含表结构变更；**L** = 全链路 + 数据迁移 + 大量回归。此处只给相对量级，不作为排期承诺。

| 阶段 | 主要改动面 | 工作量 | 风险 |
|---|---|---|---|
| 阶段 0 越权修复 | `rag-controller`、`rag-kb`、`rag-public-access`、`rag-controller/openai`；约 10–15 个文件 | S–M | 低–中 |
| 阶段 1 租户模型 | `rag-auth`、`rag-common`、`rag-data`、`sql/schema`、OIDC 配置；约 20–30 个文件 | M | 中 |
| 阶段 2 数据面隔离 | 全部业务模块 + 全部表 + 存储/Redis/向量/配额/审计/API Key；约 60–80 个文件 + 迁移脚本 | L | 高 |
| 阶段 3 企业加固 | `sql` RLS、计量计费、生命周期、平台管理端；约 20–30 个文件 + DB 角色/策略 | M–L | 中–高 |
| 阶段 4 混合分层 | 数据源路由、迁移工具、运维脚本 | L | 高（可选） |

---

## 5. 推荐方案

### 5.1 总体选型

**共享库共享表 + `tenant_id` + 统一租户拦截器 + PostgreSQL RLS 兜底；大客户/强合规客户按需独立 schema 或独立数据库。**

不推荐第一阶段就做 schema-per-tenant 或 DB-per-tenant，原因：

1. 现有单 `DataSource`、单 Hikari、`currentSchema=public`、MyBatis-Plus 单拦截器，动态 schema/多数据源改造成本远高于加列。
2. pgvector 每个知识库建物理表，schema/DB 拆分后建表、重建、迁移都要路由，复杂度成倍增加。
3. 当前租户规模未知，过早做物理隔离会拖慢产品迭代；共享表方案可以先上线，再按客户分层。

### 5.2 关键设计决策

| 决策 | 建议 |
|---|---|
| 租户与用户关系 | 一个用户属于一个租户；平台管理员单独标记；未来再做多租户成员关系 |
| 可见性语义 | `PRIVATE` = 租户内仅自己；`TEAM` = 租户内团队（未来）；`PUBLIC` 改为"租户内公开"；内置资源用 `PLATFORM` 或 `builtin` 标记 |
| 租户 ID 类型 | `BIGINT` 主键 + `VARCHAR` code；所有业务表用 `tenant_id BIGINT NOT NULL` |
| 租户上下文 | `TenantContext` ThreadLocal；过滤器解析令牌后设置；异步任务显式传递 |
| 拦截器 | 优先 MyBatis-Plus `TenantLineInnerInterceptor`（自动覆盖 `selectById/updateById/deleteById` + JOIN），保留/改造现有 `DataScopeInterceptor` 处理 visibility |
| 平台表 | `wgh_llm_model`、`wgh_embedding_model`、`wgh_model_price`、`wgh_permission` 等可设为平台级（`tenant_id` 可空或忽略）；租户需要 BYOK 时再加租户覆盖表 |
| RLS | 阶段 3 引入；使用非 owner 应用角色 + `FORCE ROW LEVEL SECURITY`；每个事务 `SET LOCAL app.tenant_id` |
| 向量表 | 表名加租户前缀或保持 KB 路由；共享向量表 metadata 加 `tenantId` |
| 存储 | `t/{tenantId}/kb/{kbId}/...` |
| Redis | `wgh:t:{tenantId}:...` |
| API Key | `wgh_api_key` 增加 `tenant_id` + `owner_user_id`；资源范围继承 owner 可见性 |

---

## 6. 分阶段落地路径

### 阶段 0：先修越权（不引入租户）

**目标**：在引入租户之前，先把已存在的跨用户读写堵住。这一阶段不改变数据模型，风险最低，收益最大。

**改动范围**：

1. 知识库子资源全部加"父 KB 可读/可写"校验：
   - `KnowledgeFileService.upload / reSplit / delete / previewSplit / listByKbId`
   - `ChunkUploadService.init`
   - `KnowledgeBaseController.search`
2. 评测数据集 `requireKb` 改为"KB 可见 + 可写"。
3. 开放 API：
   - `ApiKey` 绑定 owner（先用 `created_by`）并继承可见性；
   - `/v1/models` 只列 owner 可见窗口；
   - `/v1/chat/completions`、`/api/open/v1/chat/stream` 校验窗口可见性；
   - `/api/open/v1/kb/search` 校验 KB 可见性。
4. 多 Agent 运行态接口补归属校验（复用 `RunAccessGuard` 思路，按 `runId → session/window → owner`）。
5. 分享链接、API Key 管理列表按 owner 过滤（或明确仅平台管理员可见）。

**验收标准**：

- 用户 A 与用户 B 的负向测试全部通过：
  - B 不能读/写 A 的 PRIVATE 知识库及其文件、评测数据集、检索接口；
  - B 不能通过 `/v1/models`、`/v1/chat/completions`、`/api/open/v1/kb/search` 访问 A 的资源；
  - B 不能操作 A 的多 Agent 运行；
- 现有单租户功能回归通过；
- 不新增租户字段，不改变 `visibility` 语义。

**工作量/风险**：S–M / 低–中。

---

### 阶段 1：租户模型与身份上下文

**目标**：让"租户"成为一等公民，所有请求都有可信租户上下文。

**改动范围**：

1. 新增 `wgh_tenant`：
   - `id`、`code`、`name`、`status`、`plan`、`isolation_mode`、`create_time`；
   - 唯一 `code`。
2. `wgh_user.tenant_id` 启用：
   - 本地账号创建时必须指定租户；
   - 外部 OIDC 首次进入时按 `tenant` claim 映射/创建用户；
   - 唯一约束从 `username` 改为 `(tenant_id, username)`（或保留全局唯一 + 租户内展示名）。
3. 令牌与上下文：
   - 本地令牌写入 `tenantId`；
   - OIDC 读取 `tenant`/`tid` claim；
   - `TenantContext` + 过滤器；缺失/未知/停用租户直接拒绝；
   - 异步线程、调度任务显式传递租户（MDC 同步记录）。
4. 角色体系：
   - 新增 `platform_admin`；
   - 租户管理员只能管理本租户用户/角色/配额；
   - 平台管理员默认不读业务数据，需显式"代管"并审计。
5. 表结构：
   - 所有租户-owned 表增加 `tenant_id BIGINT`；
   - 先允许 NULL，回填默认租户后再设 NOT NULL；
   - 索引以 `tenant_id` 为前导列。

**验收标准**：

- 新请求的日志/MDC/审计都能看到 `tenantId`；
- 无租户令牌被拒绝；
- 跨租户用户管理被拒绝；
- 存量数据全部归入默认租户；
- 单元测试覆盖令牌解析、OIDC claim 映射、上下文透传。

**工作量/风险**：M / 中。

---

### 阶段 2：数据面全量隔离

**目标**：所有资源读写都带租户条件，子资源不再绕过。

**改动范围**：

1. 引入 `TenantLineInnerInterceptor`（或扩展现有拦截器），自动为所有租户表追加 `tenant_id`；平台表加入忽略清单。
2. 改造 `DataScopeInterceptor`：租户条件 + visibility 条件组合；明确 JOIN/UNION/CTE 的处理策略。
3. 子资源校验：
   - 文件、评测、连接器、上传会话、会话、消息、运行记录、记忆：先校验父资源租户，再校验自身租户；
   - 所有 `selectById` 改为租户感知（插件或显式条件）。
4. 存储：`StorageKeys` 增加 `tenantId` 参数，key 改为 `t/{tenantId}/...`；迁移旧对象。
5. Redis：所有 key 加 `wgh:t:{tenantId}:`；限流 subject 加租户维度。
6. 向量：
   - 知识库 collection 名加租户前缀（或确认只经 KB 路由）；
   - 语义缓存/记忆的向量 metadata 与 SQL 条件加 `tenantId`。
7. 配额：`tenant_id` + 租户级默认/聚合。
8. 审计/API 调用日志：`tenant_id`。
9. API Key：`tenant_id` + owner + 资源范围。
10. 语义修正：`PUBLIC` 改为租户内公开；内置资源用平台级标记；分享策略按租户开关。

**验收标准**：

- 每个资源类型都有跨租户负向测试（A 租户访问 B 租户必须 404/403）；
- 原生 SQL、JOIN、异步任务、调度任务都有租户条件（代码评审清单 + 测试）；
- 存储/缓存/向量命名空间可按租户隔离；
- 配额与审计可按租户聚合；
- 性能基线不劣化超过约定阈值（需压测确认）。

**工作量/风险**：L / 高。

---

### 阶段 3：企业级加固

**目标**：从"应用层隔离"升级为"数据库层可证明隔离 + 可运营"。

**改动范围**：

1. RLS：
   - 创建非 owner 应用数据库角色；
   - 所有租户表 `ENABLE` + `FORCE ROW LEVEL SECURITY`；
   - 策略 `tenant_id = current_setting('app.tenant_id')::bigint`；
   - 事务内 `SET LOCAL app.tenant_id`（通过 MyBatis 拦截器/事务同步）；
   - 平台运维使用独立角色或 `app.bypass_rls`（需审计）。
2. 计量计费：
   - 按租户聚合 token、chat、storage、retrieval、embedding；
   - 账单导出、配额告警。
3. 生命周期：
   - 租户停用/启用；
   - 租户数据导出（逻辑导出 + 对象存储前缀打包）；
   - 租户删除（数据库行 + 向量表 + 对象 + 缓存）；
   - 备份恢复策略按 plan 分层。
4. 租户配置：
   - 分享/Widget 开关；
   - 模型与 BYOK；
   - SSO 配置（可选）。
5. 平台管理端：
   - 租户列表、配额、用量、审计；
   - 平台管理员"代管"需二次确认并留痕。

**验收标准**：

- 用原始 SQL 直连（非应用路径）也无法跨租户读取；
- 租户导出/删除演练通过；
- 计量账单与用量核对一致；
- 审计可按租户检索。

**工作量/风险**：M–L / 中–高。

---

### 阶段 4：混合分层（按需）

**目标**：为大客户/强合规客户提供独立 schema 或独立数据库，同时保持平台统一运营。

**改动范围**：

1. `wgh_tenant.isolation_mode`：`shared | schema | database`。
2. 数据源路由：
   - `database`：按租户切换 DataSource；
   - `schema`：按租户设置 `search_path` 或动态表前缀；
   - `shared`：现有逻辑。
3. 迁移工具：租户从共享池迁出/迁入。
4. 运维：独立池的备份、监控、升级编排。

**验收标准**：

- 一个租户可完整运行在独立库/schema，且无法访问共享池其他租户；
- 迁出/迁入可回滚；
- 平台侧统计仍可汇总。

**工作量/风险**：L / 高（仅在确有客户需求时启动）。

---

## 7. 不引入多租户，仅靠现有 resource visibility 能支撑到什么程度

### 7.1 能支撑

- **一家企业内部多员工**：
  - 员工之间可以用 `PRIVATE` 隔离个人知识库/窗口/工作流/工具/Prompt；
  - 团队共享可以用 `TEAM`（实际等价于"所有登录用户可读"）；
  - 管理员可以看全部。
- **单企业对外分享**：
  - 分享链接、Widget 嵌入、开放 API 可以对外提供服务；
  - 但所有对外能力共享同一套资源池。
- **单企业内的配额与审计**：
  - 可以按用户限流、按用户审计。

### 7.2 不能支撑

- **多企业互不信任**：
  - `PUBLIC/TEAM` 对所有登录用户可见，租户 A 的资源会被租户 B 看到；
  - 子资源入口存在跨用户读写（G-02/G-03/G-04）；
  - 开放 API 是全局通行证（G-01）；
  - 没有租户级配额、计费、备份、删除、导出；
  - 没有租户管理员/平台管理员分离；
  - 数据库层没有 RLS 兜底。
- **强合规/数据驻留**：
  - 同库同表，无法证明物理隔离；
  - 无法按租户独立加密密钥/备份。
- **按租户商业化**：
  - 无法按租户出账单、按租户限流、按租户统计存储与检索成本。

### 7.3 结论

**现有 resource visibility 是"单组织内的用户级权限"，不是"多租户隔离"。** 如果产品定位只是"一家企业自建知识库"，现状在修复阶段 0 的越权后基本够用；如果目标是"企业级多租户 SaaS / 多法律实体"，必须引入 `tenant_id` 体系。

---

## 8. 未验证与后续确认

以下事项在本次静态分析中**未验证**，落地前需要补充确认：

1. **运行时行为**：未启动服务、未执行测试，所有结论来自静态代码与配置；拦截器在真实 SQL 解析（复杂 JOIN/CTE/子查询）下的行为需以测试验证。
2. **实际租户规模与商业模式**：未知租户数量、单租户数据量、是否需要 BYOK/独立部署；这直接影响是否需要阶段 4。
3. **OIDC IdP 是否提供 `tenant` claim**：当前配置没有该 claim 的读取；需要与 IdP 确认 claim 名称与语义。
4. **现有生产数据中 `create_by='system'` 的占比**：决定回填默认租户的工作量。
5. **`DataScopeInterceptor` 与 MyBatis-Plus `TenantLineInnerInterceptor` 的兼容性**：两者都改写 SQL，叠加顺序与重复条件需要实测。
6. **RLS 与 Hikari/事务的会话变量传播**：需要验证 `SET LOCAL` 在连接复用、嵌套事务、异步线程下的行为。
7. **前端多租户 UI**：本次未深入前端；租户切换、租户管理页、平台管理员视图均需新增。
8. **备份/恢复工具链**：当前只有 `backups/` 目录与 compose 卷，未验证是否有按库/按表恢复流程。

---

## 9. 附录：证据索引

### 9.1 认证与身份

- [OidcAuthenticationFilter.java#L103-L145](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-auth/src/main/java/com/wgh/rag/auth/security/OidcAuthenticationFilter.java#L103-L145)：外部 token 解析、用户名/角色映射
- [OidcAuthenticationFilter.java#L151-L240](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-auth/src/main/java/com/wgh/rag/auth/security/OidcAuthenticationFilter.java#L151-L240)：本地凭证校验
- [LoginUser.java#L11-L24](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-common/src/main/java/com/wgh/rag/common/security/LoginUser.java#L11-L24)：登录上下文无租户
- [SecurityUtils.java#L23-L36](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-common/src/main/java/com/wgh/rag/common/security/SecurityUtils.java#L23-L36)：用户名派生
- [WghUser.java#L41](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-auth/src/main/java/com/wgh/rag/auth/entity/WghUser.java#L41)：`tenantId` 字段
- [01-schema.sql#L1185-L1213](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/sql/schema/01-schema.sql#L1185-L1213)：用户表

### 9.2 可见性与数据权限

- [DataScopeContext.java#L27-L58](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-data/src/main/java/com/wgh/rag/data/scope/DataScopeContext.java#L27-L58)
- [DataScopeInterceptor.java#L51-L109](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-data/src/main/java/com/wgh/rag/data/scope/DataScopeInterceptor.java#L51-L109)
- [DataScopeGuard.java#L18-L44](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-data/src/main/java/com/wgh/rag/data/scope/DataScopeGuard.java#L18-L44)
- [ResourceVisibility.java#L9-L44](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-data/src/main/java/com/wgh/rag/data/scope/ResourceVisibility.java#L9-L44)
- [MetaObjectHandlerImpl.java#L21-L44](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-data/src/main/java/com/wgh/rag/data/mybatis/MetaObjectHandlerImpl.java#L21-L44)
- [MybatisPlusConfig.java#L20-L26](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-data/src/main/java/com/wgh/rag/data/mybatis/MybatisPlusConfig.java#L20-L26)

### 9.3 资源服务

- [KnowledgeBaseService.java#L42-L88](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KnowledgeBaseService.java#L42-L88)
- [KnowledgeFileService.java#L78-L120](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KnowledgeFileService.java#L78-L120)
- [KnowledgeFileService.java#L240-L346](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/KnowledgeFileService.java#L240-L346)
- [ChunkUploadService.java#L67-L99](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/ChunkUploadService.java#L67-L99)
- [ChunkUploadService.java#L200-L211](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/ChunkUploadService.java#L200-L211)
- [EvalDatasetService.java#L65-L92](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/EvalDatasetService.java#L65-L92)
- [EvalDatasetService.java#L378-L391](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/EvalDatasetService.java#L378-L391)
- [ConnectorService.java#L240-L264](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/connector/ConnectorService.java#L240-L264)
- [ChatWindowService.java#L43-L67](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/service/ChatWindowService.java#L43-L67)
- [ChatWindowService.java#L272-L331](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/service/ChatWindowService.java#L272-L331)
- [WorkflowService.java#L91-L121](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-workflow/src/main/java/com/wgh/rag/workflow/service/WorkflowService.java#L91-L121)
- [RunAccessGuard.java#L26-L40](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-controller/src/main/java/com/wgh/rag/controller/support/RunAccessGuard.java#L26-L40)
- [MultiAgentController.java#L82-L151](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-controller/src/main/java/com/wgh/rag/controller/MultiAgentController.java#L82-L151)

### 9.4 配额、审计、存储、缓存

- [QuotaService.java#L70-L132](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-quota/src/main/java/com/wgh/rag/quota/service/QuotaService.java#L70-L132)
- [DefaultQuotaUserResolver.java#L17-L24](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-quota/src/main/java/com/wgh/rag/quota/service/DefaultQuotaUserResolver.java#L17-L24)
- [QuotaUsageMapper.java#L20-L60](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-quota/src/main/java/com/wgh/rag/quota/mapper/QuotaUsageMapper.java#L20-L60)
- [AuditAspect.java#L34-L72](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-log/src/main/java/com/wgh/rag/log/aspect/AuditAspect.java#L34-L72)
- [AuditLog.java#L14-L49](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-log/src/main/java/com/wgh/rag/log/entity/AuditLog.java#L14-L49)
- [StorageKeys.java#L11-L42](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-storage/src/main/java/com/wgh/rag/storage/service/StorageKeys.java#L11-L42)
- [SemanticCacheService.java#L30-L155](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/SemanticCacheService.java#L30-L155)
- [AgentMemoryService.java#L31-L115](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-kb/src/main/java/com/wgh/rag/kb/service/AgentMemoryService.java#L31-L115)
- [PublicAccessRateLimiter.java#L71-L73](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-public-access/src/main/java/com/wgh/rag/publicaccess/service/PublicAccessRateLimiter.java#L71-L73)

### 9.5 开放 API

- [ApiKeyAuthenticationFilter.java#L47-L105](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-public-access/src/main/java/com/wgh/rag/publicaccess/config/ApiKeyAuthenticationFilter.java#L47-L105)
- [ApiKeyService.java#L185-L220](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-public-access/src/main/java/com/wgh/rag/publicaccess/service/ApiKeyService.java#L185-L220)
- [OpenApiController.java#L76-L143](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-controller/src/main/java/com/wgh/rag/controller/OpenApiController.java#L76-L143)
- [OpenAiCompatController.java#L179-L201](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-controller/src/main/java/com/wgh/rag/controller/openai/OpenAiCompatController.java#L179-L201)
- [ShareLinkService.java#L270-L283](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-public-access/src/main/java/com/wgh/rag/publicaccess/service/ShareLinkService.java#L270-L283)

### 9.6 表结构与部署

- [01-schema.sql](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/sql/schema/01-schema.sql)
- [01_resource_visibility.sql](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/sql/migrations/20261113/01_resource_visibility.sql)
- [application.yml#L13-L28](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-app/src/main/resources/application.yml#L13-L28)
- [application.yml#L116-L143](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-backend/rag-app/src/main/resources/application.yml#L116-L143)
- [docker-compose.yml#L7-L20](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/docker-compose.yml#L7-L20)

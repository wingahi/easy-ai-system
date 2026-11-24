# Easy AI System

> 面向私有化部署的企业知识库问答与 RAG 中台：把文档治理、检索增强、流式问答、Agent / 工作流编排、分享与开放接入收敛到一套可审计、可配额管理的系统。
>
> 当前版本：**1.4.0（2026-11-24）**。本版落地「无产出不计费」与死信人工出口、多副本调度与 SSE 事件重放、用户信息 Redis 缓存、优雅停机与探针分组、K8s K1/K2/K3 清单与观测栈；已完成 / 已交付未验证 / 未实现的区分见 [能力矩阵](docs/reference/capabilities.md) 与 [已知限制](docs/reference/known-limitations.md)。

---

## 1. 定位与价值

**一句话**：把分散在企业内部的文档变成可检索、可引用、可评估、可治理的问答能力，并以聊天窗口、分享链接、Widget 和 API 对外提供。

它解决的是"知识在哪里、答案从哪来、质量怎么衡量、谁能用、用了多少"这一整条链路，而不只是把问题拼进一段 prompt：

- **文档到答案**：上传 → 解析 → 切分 → 向量化 → 混合检索 → 带引用的回答，支持增量维护与重新切分。
- **质量可度量**：SSE 实时输出召回覆盖率与命中率；有标注后可计算 Recall@K / Precision@K / MRR / NDCG，并提供离线评测脚本。
- **多入口复用同一套能力**：管理端调试、免登录分享、第三方站点 Widget、API Key 开放 API 都复用同一套模型、知识库、工具、配额与审计。
- **可治理**：OIDC / 本地账号双认证、RBAC、配额与限流、审计日志、成本统计、可观测性。

### 1.1 与"直接调用大模型"的区别

| 维度 | 直接调用大模型 | 本系统 |
|------|----------------|--------|
| 知识来源 | 依赖模型参数或临时拼 prompt | 文档入库、切分、向量与关键词索引，内容可更新、可删除 |
| 回答可追溯 | 较难说明依据 | 回答附带引用来源，前端按文档去重展示 |
| 质量评估 | 主要靠人工感觉 | 检索侧 Recall@K / Precision@K / MRR / NDCG，生成侧 LLM-as-Judge |
| 权限与审计 | 需自行实现 | OIDC 资源服务器 + RBAC、审计日志、配额与限流 |
| 对外渠道 | 需自行开发 | 免登录分享、iframe Widget、API Key 开放 API |
| 复杂任务 | 单轮问答为主 | 单 Agent、多 Agent、工作流编排与 HITL 人工确认 |

### 1.2 能力边界

- **做**：文档解析与检索增强、流式问答、多轮会话与上下文压缩、Agent 规划执行、多 Agent 协作、工作流编排、分享 / 嵌入 / 开放 API、配额限流、审计与成本统计。
- **不做**：多租户隔离、独立向量数据库 / 搜索引擎（单库 PostgreSQL + pgvector）、图片 OCR、移动端与国际化；应用内任务调度不迁移到 Kubernetes（`deploy/k8s/` 只做应用与依赖的部署编排，K1/K2/K3 清单已交付但未做真实集群验证，见 [已知限制](docs/reference/known-limitations.md)）。

---

## 2. 典型应用场景

| 场景 | 典型用法 | 系统提供的支撑 | 边界 |
|------|----------|----------------|------|
| **企业内部知识库问答与员工自助** | 制度、手册、SOP 入库，员工在聊天窗口提问，回答附带原文出处 | 12 种文档格式解析、9 种切分策略、混合检索、SSE 流式回答、引用来源与检索指标 | 不替代 OA / 工单流程；文档需先上传或经 API 写入 |
| **客服 / 工单坐席辅助** | 坐席把客户问题或工单描述作为查询，检索知识库并生成建议答复；对外可用免登录分享或 Widget 自助问答 | RAG 问答、引用来源、分享链接、Widget 嵌入、开放 API、配额与限流 | 工单流转、客户画像、坐席工作台集成不在本系统范围 |
| **研发文档与规范检索助手** | 接口文档、设计文档、排障记录、编码规范入库，研发在聊天窗口或经开放 API 在内部工具中检索 | 混合检索（向量 + 关键词）、查询变换、Rerank、API Key 开放 API、单页 URL 连接器 | 不直接同步 Git / Jira 等数据源；文档需上传、经 API 写入或由连接器抓取单个页面 |
| **培训 / 考试题库知识问答** | 培训资料、FAQ、题库解析后入库，学员按自然语言提问 | FAQ / 章节 / 结构化 / 语义等多种切分，问答对与知识点检索，引用来源 | 不提供组卷、考试、判分等培训业务功能 |
| **第三方系统集成** | 既有站点嵌入问答窗口，或由外部系统通过 API 调用问答与知识库检索 | 免登录分享链接、iframe Widget、API Key（`chat:write` / `kb:read` / `usage:read`）、Redis 限流与调用日志 | Widget 为 iframe 注入，不含 `postMessage` 双向通信；跨域需宿主配置 CSP |
| **多用户按角色与配额分权使用** | 管理员维护用户/角色/权限，按用户配置日/月配额，审计关键操作 | OIDC 资源服务器 + 本地 admin/体验账号、RBAC、角色与权限管理页面、配额模块、审计日志 | **不做租户隔离**；知识库/文档级权限未全量覆盖 |

> 说明：以上场景均基于当前代码已实现的能力描述；未实现的能力不会作为卖点写入场景。

---

## 3. 能力矩阵

状态口径：**已完成** / **已交付未验证**（代码或清单已合入，缺真实环境或端到端验证）/ **部分完成** / **未实现** / **规划中**（已有成稿规划文档、尚未实施）。状态按代码与文档核对结果判断。

| 能力域 | 覆盖范围 | 状态 |
|--------|----------|------|
| 知识库与检索 | 12 种格式解析、PDF 版面 / 表格 / OCR、9 种切分、查询变换 + 混合检索 + RRF + Rerank、评测与引用溯源、标签 / 版本、URL 连接器、local / OSS 存储、分片上传 | 已完成（独立图片 OCR 未实现） |
| 对话与开放接入 | 多会话流式问答、SSE 重放与自动重连、上下文压缩、多模态、反馈、分享 / Widget / 开放 API、OpenAI 兼容入口、Prompt 模板、语义缓存 | 已完成（跨副本 SSE 重连未验证；反馈 A/B 未实现） |
| Agent 与工作流 | 单 Agent、6 种多 Agent 模式、12 类工作流节点、表达式与单步调试、模型故障转移、任务恢复与长期记忆、代码沙箱 | 已完成（代码沙箱默认关闭） |
| 平台治理 | OIDC / 本地双认证与 RBAC、资源可见性、配额与「无产出不计费」、死信人工退款 / 核销、Redis 共享状态、审计与成本、可观测性、优雅停机与探针 | 已完成（多副本 / 滚动更新相关项未验证） |
| 模型与工程 | 多厂商模型管理、路由与故障转移、JWKS L1/L2 缓存、模块拆分与依赖边界、JDK 21、配置分层 | 已完成 |
| 上下文与工具治理 | 历史 Token 裁剪、检索指标 / 来源 / 上下文同源、工具结果预算、工具重试 / 熔断 / 兜底、工作流 LLM 节点输入预算 | 已完成 |

完整到具体条目（含模块、类名、配置键、提交号）的矩阵见 [能力矩阵](docs/reference/capabilities.md)。各项限制、未验证项与未实现能力见 [已知限制](docs/reference/known-limitations.md)；平台演进四块（调度中心 / 多租户 / 向量库 / K8s）的规划状态见 [路线图](docs/reference/roadmap.md)。

---

## 4. 架构与技术栈

后端为 24 个 Maven 模块的 Spring Boot 应用，核心依赖方向为 `rag-controller → rag-chat → rag-kb → rag-llm`，业务数据与向量共用 PostgreSQL + pgvector；完整模块树、依赖约束、关键设计与技术栈见 [工程结构与技术栈](docs/reference/engineering-structure.md)。

---

## 5. 快速开始

### 5.1 环境要求

- **JDK 21（必需）**：编译目标与测试均固定在 JDK 21；JDK 25 下会在 `validate` 阶段直接失败。
- Maven 3.9+、Node.js 18+（前端）、Docker 24+（可选，用于 PostgreSQL / Redis / Compose）。

```bash
java -version 2>&1 | head -1     # 期望 openjdk version "21.x"
mvn -version | grep "Java version"   # 期望 Java version: 21
# macOS 切换：export JAVA_HOME=$(/usr/libexec/java_home -v 21)
# Linux 切换：export JAVA_HOME=/path/to/jdk-21
```

### 5.2 本地启动

1）启动 PostgreSQL + pgvector 并初始化：

```bash
docker run -d --name postgres-with-vector \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=wgh_rag_dev \
  -p 5432:5432 \
  -v "$(pwd)/rag-backend/sql:/sql:ro" \
  pgvector/pgvector:pg15

# 47 张表 + 索引 + 种子数据：按顺序用 -f 执行（结构脚本幂等，种子含示例数据，不建议对存量库重跑）
docker exec -i postgres-with-vector psql -U postgres -d wgh_rag_dev \
  -f /sql/schema/01-schema.sql -f /sql/seed/02-data.sql
```

> **已有库升级**：不要重跑 schema / seed。按版本顺序执行 `sql/migrations/` 下的增量脚本；完整清单、脚本状态与执行顺序见 [数据库迁移](docs/reference/database-migrations.md)。

2）启动后端：

```bash
cd rag-backend
export WGH_DB_PASSWORD=postgres          # 与上面容器口令一致
export WGH_INIT_ADMIN_PASSWORD='<至少 8 位且含字母与数字>'
mvn install -DskipTests
mvn -f rag-app/pom.xml spring-boot:run
# 业务端口 http://localhost:8080；管理端口 http://localhost:9090
```

> 使用 `-pl` 时必须加 `-am`，否则可能读到 `~/.m2` 中的旧 jar。

3）可选：启动 Redis（公开访问分布式限流 / 密码锁定 / 嵌入授权）：

```bash
docker run -d --name wgh-rag-redis -p 6379:6379 redis:8.10.1-alpine
docker exec wgh-rag-redis redis-cli ping   # PONG
```

4）启动前端：

```bash
cd rag-frontend
npm install
npm run dev          # http://localhost:5173
```

### 5.3 Docker Compose 一键启动

```bash
cp .env.example .env
# 至少填写：
#   POSTGRES_PASSWORD
#   WGH_AUTH_LOCAL_JWT_SECRET   （至少 32 字节）
#   WGH_INIT_ADMIN_PASSWORD
docker compose up -d --build
docker compose ps
```

| 服务 | 默认端口 | 说明 |
|------|----------|------|
| frontend | `8088` → 容器 80 | Nginx 托管 SPA，`/api` 反代 backend，SSE 关闭缓冲 |
| backend | `8080` → 容器 8080 | 业务 API / SSE；**不暴露 actuator** |
| backend 管理端口 | 容器内 `9090`，默认不映射宿主机 | Prometheus 抓取；生产禁止对外发布 |
| prometheus | `9091` → 容器 9090 | 抓取 `backend:9090/actuator/prometheus` |
| grafana | `3000` | 预置数据源与业务总览 / 运行时仪表盘 |
| redis | `6379` | 限流 / 密码锁定 / 嵌入授权 |
| postgres | **默认不映射宿主机** | `pgvector/pgvector:pg15`，仅容器网络内可达 |

> `docker compose down -v` 会删除 `pgdata` 与 Redis / Prometheus / Grafana 数据卷；只停服务请用 `docker compose down`。
> 本地开发若需宿主直连数据库，可启用 `docker-compose.override.yml` 的 `127.0.0.1:5432` 绑定；生产不要启用，更不要把 5432 发布到 `0.0.0.0`。

### 5.4 首次登录

- 初始管理员用户名为 `admin`，口令来自 `WGH_INIT_ADMIN_PASSWORD`；仅当库中不存在 admin 时创建，已存在则不会覆盖。
- 首次登录后必须先调用 `POST /api/auth/password` 改密；改密前写请求会被拒绝（业务码 1006）。
- 不使用本地账号时可只配置 OIDC；外部 IdP 用户由 Bearer token 验签进入。

---

## 6. 配置说明

功能开关与运行参数集中在 `application-features.yml`，由 `application.yml` 以 `optional:classpath` 引入；文件内所有值等于代码默认值，缺失不影响启动，敏感项仍由环境变量注入。`config/migrations/` 存放按日期分目录的增量配置片段，不参与自动加载，合并方式与各片段键位见 [config/migrations/README.md](rag-backend/config/migrations/README.md)。

启动阶段最常改的注入项：

- **认证**：`WGH_INIT_ADMIN_PASSWORD`、`WGH_AUTH_LOCAL_JWT_SECRET`（prod 必填，至少 32 字节）、OIDC 的 `WGH_OIDC_*`。
- **存储**：`WGH_STORAGE_TYPE`（`local` / `oss`）与多副本 `WGH_STORAGE_MODE`（`single` / `shared`）。
- **数据库与 Redis**：`WGH_DB_*` / `POSTGRES_*`、`WGH_REDIS_*`。
- **调度、配额与多副本**：`wgh.scheduler.*`、`wgh.rag.quota.*`、`wgh.rag.stream.replay.*`、`wgh.auth.user-cache.*`，以及优雅停机与探针分组。

按认证、存储、数据库、检索评测、连接器、上下文预算、调度与配额、链路追踪、多副本等主题拆分后的完整配置表见 [配置说明](docs/reference/configuration.md)。限流覆盖、可信代理、配额、Agent 与上下文压缩的完整设计说明另见 `docs/design/features/**/*.md`；接口侧的限流与错误码行为见 [docs/api/api.md](docs/api/api.md)。

---

## 7. 接口概览

对外接口分为鉴权、知识库、聊天、Agent、工作流、管理端与公开访问等 17 组，完整分组与前缀见 [接口概览](docs/reference/api-overview.md)，请求体、响应示例与 SSE 事件协议见 [docs/api/api.md](docs/api/api.md)。

---

## 8. 已知限制

已知限制按权限、多副本与集群、配额计费、存储、检索评测、接入交互 6 类汇总、共 45 条逐项说明，详见 [已知限制](docs/reference/known-limitations.md)。

---

## 9. 路线图 / 后续计划

| 方向 | 状态 | 摘要 |
|------|------|------|
| 上下文与工具治理（P0–P2） | 已完成 | 15 项落地清单全部完成：Token 裁剪、指标同源、工具预算 / 兜底、统一 `ContextAssembler`、结构化状态块、检索式记忆、共享黑板、模型能力画像等。 |
| 缺口开发计划（T01–T13） | 大部分已完成 | T01、T03–T06、T08–T13 已完成；T02 低覆盖自动补偿、T07 资源 ID 审计明细为部分完成。 |
| 多副本调度与 SSE 重放 | 已完成，未验证 | 实例心跳 / 失联回收、作业对账、SSE 事件重放已交付；真实双副本回收、对账与跨副本重连未验证。 |
| Kubernetes 编排 | 已交付未验证 | K1/K2/K3 清单与观测栈已交付，仅做离线渲染与 schema 校验，未在真实集群部署。 |
| 平台演进四块 | 部分完成 / 规划中 | 调度中心 S1 已落地、S3 基础能力未验证；K8s 清单已交付；多租户隔离与独立向量库仍在规划。 |
| 其他方向 | 未实现 / 部分完成 | 整站爬取与多源接入、反馈 A/B 对比未实现；独立图片 OCR 未接入（扫描版 PDF 已支持）。 |

完整清单（P0/P1/P2 逐项、T01–T13 状态、其他方向、平台演进四块阶段与前置关系）见 [路线图与后续计划](docs/reference/roadmap.md)。

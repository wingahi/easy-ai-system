# WGH RAG System

> 面向私有化部署的企业知识库问答与 RAG 中台：把文档治理、检索增强、流式问答、Agent / 工作流编排、分享与开放接入收敛到一套可审计、可配额管理的系统。

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
- **不做**：多租户隔离、独立向量数据库 / 搜索引擎（单库 PostgreSQL + pgvector）、图片 OCR、移动端与国际化、Kubernetes 级别的水平扩展编排。

---

## 2. 典型应用场景

| 场景 | 典型用法 | 系统提供的支撑 | 边界 |
|------|----------|----------------|------|
| **企业内部知识库问答与员工自助** | 制度、手册、SOP 入库，员工在聊天窗口提问，回答附带原文出处 | 12 种文档格式解析、9 种切分策略、混合检索、SSE 流式回答、引用来源与检索指标 | 不替代 OA / 工单流程；文档需先上传或经 API 写入 |
| **客服 / 工单坐席辅助** | 坐席把客户问题或工单描述作为查询，检索知识库并生成建议答复；对外可用免登录分享或 Widget 自助问答 | RAG 问答、引用来源、分享链接、Widget 嵌入、开放 API、配额与限流 | 工单流转、客户画像、坐席工作台集成不在本系统范围 |
| **研发文档与规范检索助手** | 接口文档、设计文档、排障记录、编码规范入库，研发在聊天窗口或经开放 API 在内部工具中检索 | 混合检索（向量 + 关键词）、查询变换、Rerank、API Key 开放 API | 不直接同步 Git / Jira 等数据源；文档需上传或经 API 写入 |
| **培训 / 考试题库知识问答** | 培训资料、FAQ、题库解析后入库，学员按自然语言提问 | FAQ / 章节 / 结构化 / 语义等多种切分，问答对与知识点检索，引用来源 | 不提供组卷、考试、判分等培训业务功能 |
| **第三方系统集成** | 既有站点嵌入问答窗口，或由外部系统通过 API 调用问答与知识库检索 | 免登录分享链接、iframe Widget、API Key（`chat:write` / `kb:read` / `usage:read`）、Redis 限流与调用日志 | Widget 为 iframe 注入，不含 `postMessage` 双向通信；跨域需宿主配置 CSP |
| **多用户按角色与配额分权使用** | 管理员维护用户/角色/权限，按用户配置日/月配额，审计关键操作 | OIDC 资源服务器 + 本地 admin/体验账号、RBAC、角色与权限管理页面、配额模块、审计日志 | **不做租户隔离**；知识库/文档级权限未全量覆盖 |

> 说明：以上场景均基于当前代码已实现的能力描述；未实现的能力不会作为卖点写入场景。

---

## 3. 能力矩阵

状态口径：**已完成** / **部分完成** / **未实现**。状态判断以代码与提交为准，详细限制见 §8。

### 3.1 知识库与检索

| 能力 | 状态 | 说明 |
|------|------|------|
| 文档解析（12 种格式） | 已完成 | PDF / DOC / DOCX / XLS / XLSX / CSV / TXT / MD / PPTX / HTML / JSON / EML；统一由 `DocumentParserDispatcher` 分发并抽取元数据 |
| 新增文档格式（PPTX / HTML / JSON / EML） | 已完成 | `PptxParser` / `HtmlParser` / `JsonParser` / `EmlParser` + 统一元数据与大小上限 |
| 文本切分（9 种策略） | 已完成 | AUTO / RECURSIVE / CHAPTER / PARAGRAPH / FAQ / LENGTH / CUSTOM / STRUCTURED（标题感知）/ SEMANTIC（语义断句） |
| 查询变换 / 混合检索 / RRF / Rerank | 已完成 | REWRITE / MULTI_QUERY / HYDE；向量余弦 + pg_trgm 关键词召回；RRF(k=60) 融合；可选 Rerank |
| 检索指标与离线评测 | 已完成 | 覆盖率 / 命中率；ground truth 标注后计算 Recall@K / Precision@K / MRR / NDCG；`eval/eval_recall.py` 复用同一标注 |
| 知识库标签 | 已完成 | `TagService` / `TagController`；知识库与文件多标签绑定及管理界面 |
| 文档版本 | 已完成 | `FileVersionService` + 版本列表 / 回滚；回滚生成新版本，不覆盖旧版本 |
| 可插拔文件存储（local / OSS） | 已完成 | 独立 `rag-storage` 模块，`FileStorageService` + 本地 / 阿里云 OSS 实现 |
| 大文件分片上传 | **未实现** | OSS 仅单次流式 `putObject`，无 multipart / 断点续传；业务侧默认上限 50MB |
| `local` ↔ `oss` 历史对象自动迁移 | **未实现** | 切换本身已支持，但旧对象仍留在原存储后端，需手工迁移或重新上传并重建向量 |
| OSS 删除失败后的孤儿对象清理 | **未实现** | 删除失败仅记录 WARN，数据库仍完成逻辑删除，对象可能残留 |
| 图片 OCR | **未实现** | 仅支持文本型 PDF / 文档；扫描件与图片型 PDF 无法直接提取文字 |

### 3.2 对话与开放接入

| 能力 | 状态 | 说明 |
|------|------|------|
| 多会话与流式问答 | 已完成 | 多会话管理、首条消息自动生成标题、历史滑动加载；SSE 输出 Markdown、引用来源与检索指标 |
| 三层上下文压缩 | 已完成 | 超过 `max-history-messages`（默认 20）触发：近期原文 / 中期摘要 / 远期关键事实；模型缺失或超时降级为截断 |
| 多模态图片问答 | 已完成 | 视觉模型（`supports_vision`）支持图片 + 文本提问，附件地址落 `wgh_chat_message.attachments` |
| 反馈闭环 | 已完成 | 消息点赞 / 点踩 + 备注，窗口满意度统计与差评运营列表 |
| 反馈 A/B 与数据集版本管理 | **未实现** | 无实验对比与数据集版本管理能力 |
| 免登录分享 / Widget 嵌入 / 开放 API | 已完成 | 分享链接（有效期、访问密码、只读范围、吊销）；`widget.js` iframe 注入；API Key 开放 API 与调用日志 |
| Prompt 模板中心与输入框前缀联想 | 已完成 | 模板 CRUD、变量提取、渲染预览；输入框前缀触发候选、模糊过滤、变量逐个填写；`GET /api/prompt-templates/suggest` |
| 语义缓存 | 已完成 | 窗口级开关，相似度阈值命中后直接返回缓存答案，跳过检索与 LLM |

### 3.3 Agent 与工作流

| 能力 | 状态 | 说明 |
|------|------|------|
| 单 Agent | 已完成 | Planner 拆解 → StepExecutor 执行 → Reflection 评分重试 → 汇总答复；计划 / 步骤落库；支持 HITL |
| 多 Agent 协作 | 已完成 | 6 种模式：CREW_SEQUENTIAL / CREW_HIERARCHICAL / CREW_PARALLEL / GRAPH_ROUTED / GROUP_CHAT_ROUND_ROBIN / GROUP_CHAT_DYNAMIC_ROUTER |
| 工作流编排 | 已完成 | 11 类节点：START / END / LLM / RETRIEVAL / TOOL / CONDITION / LOOP / HITL / VARIABLE / WORKFLOW / AGENT；并行 fan-out/fan-in、版本、触发器、模板市场 |
| 工作流 AGENT 节点 | 已完成 | `AgentNodeExecutor` 内置轻量 Plan-and-Execute；编辑器支持 `goal` / `maxSteps` / `timeoutMs` / `kbIds` |

### 3.4 平台治理

| 能力 | 状态 | 说明 |
|------|------|------|
| OIDC 资源服务器形态验签 | 已完成 | `OidcTokenVerifier` / `JwksProvider`；仅接受非对称签名，拒绝 HS256 与 `none`，校验 iss / aud / exp / nbf |
| 本地 admin / 体验账号登录 + `password_version` | 已完成 | `LocalLoginService` / `LocalTokenService`；仅 `source=local` 的 admin / 体验账号可登录；`password_version` 校验会话失效 |
| 多用户与权限体系 | 已完成 | OIDC + 本地账号双认证，RBAC（`@RequiresPermission` / `PermissionAspect`）；租户隔离与知识库/文档级全量权限不在范围内 |
| 角色与权限管理页面 | 已完成 | `AdminRoleController`（`/api/admin/roles`、`/api/admin/permissions`）+ `RoleManagement.vue`、`UserManagement.vue` |
| 配额模块与前后端页面 | 已完成 | 独立 `rag-quota` 模块；全局默认 + 按用户日/月配置，超限 429；`QuotaManagement.vue` / `MyQuota.vue` |
| Redis 8.10.1 接入 | 已完成 | 分布式限流、分享密码失败锁定（成功清零）、嵌入授权、API Key IP 白名单；不可用时降级为单机内存 |
| 审计与成本统计 | 已完成 | `@Audit` 异步记录写操作；模型价格表 + 消息成本 / 模型名回填；管理端聚合统计 |
| 安全整改 | 已完成 | 首登强制改密（`password_must_change`）、审计日志敏感信息脱敏、409 映射、`WGH_INIT_ADMIN_PASSWORD` 注入 |
| 可观测性（Prometheus + 独立 9090） | 已完成 | `RagMetrics` 业务指标 + `/actuator/prometheus`；管理端口默认 `9090`，业务端口不暴露 `/actuator` |
| Compose 内置 PostgreSQL(pgvector) | 已完成 | `docker-compose.yml` 内置 `pgvector/pgvector:pg15`，空卷首启执行 `schema.sql` / `data.sql` |

### 3.5 模型与工程

| 能力 | 状态 | 说明 |
|------|------|------|
| 模型管理 | 已完成 | DB 动态模型 + OpenAI / DASHSCOPE / DeepSeek / 自建 OpenAI 兼容端点；缓存热更新 |
| 模型路由与故障转移 | **部分完成（存在已知缺陷）** | `ModelRouter#resolveChain` + `RagChatService#runWithFailover` 支持普通 RAG 按 `route_group` 切链；但流式回调 `onError`（含首个 token 前的异步连接失败）只发送 `error` 事件并结束，不会回到切链循环；Agent / 工作流仍只用主模型 |
| 多实例 JWKS 缓存共享 | **未实现** | `JwksProvider` 为进程内缓存，密钥轮换后各实例刷新时间不一致 |
| `rag-public-access` 独立模块 + Enforcer 禁反向依赖 | 已完成 | 分享 / Widget / API Key 实现层独立；Controller 留在 `rag-controller`，依赖方向受 Enforcer 约束 |
| `rag-storage` 独立模块（local / OSS 可插拔） | 已完成 | `FileStorageService` + `LocalFileStorageService` / `AliyunOssFileStorageService` |
| `rag-agent-multi` Controller 迁入 `rag-controller` | 已完成 | `rag-agent-multi` 已无 Controller，`MultiAgentController` 位于 `rag-controller` |
| 常量统一抽取 | 已完成 | 各模块 `constant` 包；权限码、角色码、状态值、SSE 事件名、存储 key 前缀等改引用常量 |
| JDK 21 统一 | 已完成 | `maven.compiler.release=21` + Enforcer 强制 JDK 21，CI 与本地一致 |
| 测试用例与设计文档补全 | 已完成 | JUnit 5 + Mockito；`scripts/` 提供 e2e / 冒烟 / 压测；`docs/test-cases.md`、`docs/design-*.md` 已补齐 |

---

## 4. 架构与技术栈

### 4.1 模块结构

```text
wgh-rag-system/
├── rag-backend/                  # Spring Boot 多模块（Java 21）
│   ├── rag-common/               # 纯契约层：Result / 异常 / 指标 / 安全契约
│   ├── rag-data/                 # BaseEntity / MyBatis-Plus 配置 / 类型处理器
│   ├── rag-log/                  # @Audit 切面与审计日志
│   ├── rag-auth/                 # OIDC 资源服务器 + 本地登录 + 用户/角色/权限
│   ├── rag-quota/                # 配额模块（全局默认 + 按用户日/月）
│   ├── rag-config/               # 配置属性映射
│   ├── rag-storage/              # 可插拔文件存储：local / 阿里云 OSS
│   ├── rag-llm/                  # 动态模型、厂商工厂、缓存热更新、模型路由
│   ├── rag-kb/                   # 上传 → 解析 → 切分 → 向量化 → 检索增强 → 评测
│   ├── rag-chat/                 # 窗口/会话、上下文压缩、流式 RAG、分享、反馈
│   ├── rag-tool/                 # HTTP / MCP / Skill / 函数调用工具
│   ├── rag-agent/                # 单 Agent：Planner / Executor / Reflection / HITL
│   ├── rag-agent-multi/          # 多 Agent：Crew / Graph / GroupChat / runtime
│   ├── rag-workflow/             # 工作流引擎：节点执行器、触发器、模板
│   ├── rag-public-access/        # 分享 / Widget / 开放 API 实现层（无 Controller）
│   ├── rag-controller/           # REST 层（含 /api/public、/api/open/v1）
│   ├── rag-app/                  # 启动模块 + application*.yml + 集成测试
│   ├── sql/                      # schema.sql（37 张表）/ data.sql / init.sql
│   ├── eval/                     # eval_recall.py（Recall@K）、eval_rag.py（含 LLM Judge）
│   └── scripts/                  # e2e / 冒烟 / 压测脚本
├── rag-frontend/                 # Vue 3 前端（含 public/widget.js）
└── docs/                         # 设计与测试文档
```

关键依赖约束：

- `rag-common` 不依赖任何内部模块，也不含实体 / Mapper / MyBatis 配置；
- `rag-public-access` 依赖方向固定为 `rag-controller → rag-public-access`，并由 Enforcer 禁止反向依赖；
- `rag-storage` 仅依赖 `rag-common`，上层业务通过 `FileStorageService` 抽象使用；
- `rag-auth` 持有用户 / 角色 / 权限实体与 Mapper，供 Controller 的 RBAC 管理接口使用。

更完整的模块依赖与边界说明见 [docs/design-overview.md](docs/design-overview.md) 与 [docs/design-detailed.md](docs/design-detailed.md)。

### 4.2 关键设计

- **单库承载业务与向量**：PostgreSQL + pgvector 省去独立向量数据库；pgvector 不可用时不阻断应用启动，但向量检索相关能力不可用。
- **Redis 可选**：限流、分享密码锁定、嵌入授权可集中到 Redis；不可用时自动降级为单机内存，不阻断启动。
- **SSE 统一协议**：聊天、Agent、工作流、多 Agent 共用事件风格；`content` 事件使用 JSON 包装文本，避免换行丢失，前端状态复位以事件驱动。
- **先落库后推送**：Agent / 工作流执行结果先持久化再发送 SSE，刷新页面后仍可恢复。
- **多环境配置**：dev / test / prod 分离；生产敏感配置通过环境变量注入。
- **逻辑删除**：所有删除走 `deleted` 字段，便于审计与恢复。

### 4.3 技术栈

| 层次 | 技术 |
|------|------|
| 前端 | Vue 3 · Vite · Pinia · Vue Router · Element Plus · SCSS · marked |
| 后端 | Spring Boot 3.4 · Java 21 · Maven 多模块 · Spring Security |
| 数据 | PostgreSQL 15+ · pgvector · MyBatis-Plus（逻辑删除 + 分页） |
| LLM | LangChain4j · OpenAI / DASHSCOPE / DeepSeek / 自建 OpenAI 兼容端点 |
| 工具 | LangChain4j Tool Calling · MCP（stdio / Streamable HTTP）· HTTP · 本地反射 · Skill |
| Agent | Planner / StepExecutor / Reflection / HITL；多 Agent 含 Crew / Graph / GroupChat |
| 工作流 | 自研 DAG 引擎：11 类节点、并行 fan-out/fan-in、版本快照、CRON / Webhook |
| 鉴权 | 外部 OIDC token（JWKS 非对称公钥验签）+ 本地会话凭证 + RBAC + API Key（SHA-256） |
| 评估 | 检索侧 Recall@K / Precision@K / MRR / NDCG；生成侧 LLM-as-Judge |
| 测试 | JUnit 5 + Mockito；shell e2e / 冒烟 / 压测；离线召回率评测脚本 |

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
  -e POSTGRES_DB=wgh_rag \
  -p 5432:5432 \
  pgvector/pgvector:pg16

# 37 张表 + 索引 + 种子数据（幂等，可重复执行）
docker exec -i postgres-with-vector psql -U postgres -d wgh_rag < rag-backend/sql/init.sql
```

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
| grafana | `3000` | 预置数据源与业务总览仪表盘 |
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

### 6.1 认证与初始化管理员

| 配置 | 默认 | 说明 |
|------|------|------|
| `WGH_INIT_ADMIN_PASSWORD` | 空 | 初始管理员口令；未注入且库中无 admin 时不创建管理员，启动日志输出 ERROR；**禁止使用默认口令** |
| `wgh.auth.local.jwt-secret` / `WGH_AUTH_LOCAL_JWT_SECRET` | 空 | 本地会话签名密钥；`prod` / `production` 下未配置会启动失败（fail-closed）；开发环境未配置会生成随机密钥，重启后旧会话失效 |
| `WGH_JWT_SECRET` | 无 | 兼容旧 prod 配置项，建议与 `WGH_AUTH_LOCAL_JWT_SECRET` 同值 |
| `wgh.auth.init-admin.password` | 空 | `WGH_INIT_ADMIN_PASSWORD` 的 YAML 等价配置项 |

本地账号仅允许 `wgh_user.source=local` 的 admin 或体验账号（`trial=1`）登录；外部 OIDC 用户即使 `password_hash` 为空也不能走本地密码登录。

### 6.2 外部 OIDC（资源服务器）

| 配置 | 说明 |
|------|------|
| `WGH_OIDC_ISSUER` / `wgh.auth.oidc.issuer` | IdP issuer，token `iss` 必须一致 |
| `WGH_OIDC_AUDIENCE` / `wgh.auth.oidc.audience` | 期望 audience，token `aud` 必须包含 |
| `WGH_OIDC_JWKS_URI` / `wgh.auth.oidc.jwks-uri` | JWKS 端点；留空时按 issuer 自动发现 |
| `WGH_OIDC_ROLES_CLAIM` / `wgh.auth.oidc.roles-claim` | 承载角色的 claim，默认 `roles`，常用 `groups` |
| `WGH_OIDC_USERNAME_CLAIM` / `wgh.auth.oidc.username-claim` | 承载用户名的 claim，默认 `sub` |
| `wgh.auth.oidc.role-mapping` | claim 值 → `wgh_role.role_code` 映射表 |
| `wgh.auth.oidc.default-role` | token 无角色或映射不到数据库角色时的默认角色，默认 `viewer` |

验签仅接受 RS / PS / ES 非对称算法，拒绝 HS256 与 `none`；JWKS 为进程内缓存，多实例不共享（见 §8）。

### 6.3 文件存储（local / OSS）

| 配置 | 默认 | 说明 |
|------|------|------|
| `WGH_STORAGE_TYPE` / `wgh.rag.storage.type` | `local` | `local` 本地磁盘 / `oss` 阿里云 OSS |
| `WGH_UPLOAD_DIR` / `wgh.rag.storage.local.base-dir` | `./uploads` | 本地存储根目录 |
| `WGH_STORAGE_URL_PREFIX` / `wgh.rag.storage.local.url-prefix` | `/uploads` | 本地 `resolveUrl` 逻辑前缀（需静态服务配合） |
| `WGH_STORAGE_MAX_FILE_MB` | 50 | 存储层单文件大小上限 |
| `WGH_OSS_ENDPOINT` / `wgh.rag.storage.oss.endpoint` | - | OSS 公网 endpoint |
| `WGH_OSS_INTERNAL_ENDPOINT` | - | OSS 内网 endpoint（可选） |
| `WGH_OSS_BUCKET` / `wgh.rag.storage.oss.bucket` | - | OSS bucket |
| `WGH_OSS_ACCESS_KEY_ID` / `WGH_OSS_ACCESS_KEY_SECRET` | - | OSS 凭证，生产必须环境变量注入 |
| `WGH_OSS_KEY_PREFIX` | 空 | 对象 key 前缀 |
| `WGH_OSS_PUBLIC_READ` | `false` | `true` 返回公网直链；`false` 返回签名 URL |
| `WGH_OSS_SIGNED_URL_EXPIRE_MINUTES` | 60 | 签名 URL 有效期 |
| `WGH_OSS_MAX_RETRIES` | 3 | 上传失败重试次数（含首次） |

OSS 配置不完整时不阻断启动，仅在真正调用存储操作时返回明确错误。存储切换、迁移与孤儿对象限制见 §8；完整设计见 [docs/design-file-storage.md](docs/design-file-storage.md)。

### 6.4 数据库、Redis 与其他关键配置

| 配置 | 默认 | 说明 |
|------|------|------|
| `WGH_PROFILE` | `dev` | Spring Profile：`dev` / `test` / `prod` |
| `WGH_DB_HOST` / `PORT` / `NAME` / `USERNAME` / `PASSWORD` | localhost / 5432 / wgh_rag / postgres / 123456 | 本地 dev 数据源 |
| `POSTGRES_*` | - | Compose 内置数据库与 backend 数据源的唯一真相，密码必填 |
| `PROD_DB_*` | - | 非 Compose（裸机 `prod`）或外部库；优先级低于 `SPRING_DATASOURCE_*` |
| `WGH_REDIS_ENABLED` / `HOST` / `PORT` / `PASSWORD` | true / localhost / 6379 / 空 | Redis 连接；不可用时降级为单机内存 |
| `WGH_MANAGEMENT_PORT` | 9090 | 管理端口；业务端口不暴露 actuator |
| `WGH_MANAGEMENT_ADDRESS` | 0.0.0.0 | 管理端口监听地址，生产建议内网网卡 |
| `wgh.public-access.share-default-expire-days` | 7 | 分享链接默认有效期（上限 30 天） |
| `wgh.public-access.share-password.max-failures` | 5 | 密码失败阈值 |
| `wgh.public-access.share-password.lock-minutes` | 15 | 锁定时长（分钟） |
| `wgh.rag.cache.similarity-threshold` | 0.95 | 语义缓存命中阈值 |
| `wgh.file.max-single-file-mb` | 50 | 单文件上传 / 解析大小上限 |

> 限流覆盖、可信代理、配额、Agent 与上下文压缩等完整配置与设计说明见 `docs/design-*.md`；接口侧的限流与错误码行为见 [docs/api.md](docs/api.md)。
> `wgh.public-access.trusted-proxies` 默认空，只信任 TCP 对端；部署在 Nginx / 网关后需显式配置可信 CIDR，**不要配置为 `0.0.0.0/0`**。

---

## 7. 接口概览

完整请求体与 SSE 事件协议见 [docs/api.md](docs/api.md)；这里只列分组。

| 模块 | 前缀 | 说明 |
|------|------|------|
| 鉴权 / OIDC | `/api/auth` | 本地登录、当前用户、改密、OIDC 状态 |
| 知识库 | `/api/kb` | 知识库 / 文件 CRUD、重切、检索测试、ground truth、召回评测 |
| 模型 | `/api/models` | Chat / Embedding / Rerank 模型 CRUD |
| 聊天 | `/api/chat/windows` | 窗口 CRUD、会话、消息、SSE 对话、反馈 |
| 分享（登录态旧入口） | `/api/chat/share` | 窗口分享开关与 token 管理 |
| 工具 | `/api/chat/tools` | 工具 CRUD、在线调试、Skill 目录管理 |
| Agent | `/api/agent` | 计划状态、HITL 确认、取消 / 重试 / 跳过 |
| 工作流 | `/api/workflows` | CRUD、校验、运行、HITL、版本、触发器、模板 |
| 多 Agent | `/api/agent-multi` | Crew CRUD / 校验、运行、SSE、state、HITL |
| Prompt 模板 | `/api/prompt-templates` | CRUD、渲染预览、前缀联想候选（`/suggest`） |
| 标签 / 版本 | `/api/tags`、`/api/kb/files/{id}/versions` | 标签 CRUD 与绑定、版本列表与回滚 |
| 管理端 | `/api/admin` | 审计日志、成本 / 配额统计、差评、用户配额、角色权限、分享链接、API Key |
| 免登录分享 | `/api/public/share/{token}` | 信息 / 解锁 / 会话 / 消息 / SSE 问答 / 知识库检索 |
| 开放 API | `/api/open/v1` | `/me`、`/chat/stream`、`/kb/search`、`/usage`（`X-API-Key`） |

---

## 8. 已知限制

| 类别 | 限制 | 影响 / 建议 |
|------|------|-------------|
| 权限体系 | 已提供角色 / 权限管理页面与接口；无租户隔离，资源级权限仅覆盖部分 Controller | 多团队共用需在网关或后续迭代补足 |
| 可观测性 | 已提供 Prometheus 指标与管理端口（9090）、`X-Request-Id` 日志关联；未接入分布式链路追踪 | 跨服务调用链仍需日志 / 审计辅助 |
| 部署编排 | 已提供前后端 Dockerfile 与全栈 docker-compose；无 Kubernetes 级编排 | 大规模水平扩展需自行补编排 |
| 限流 | Redis Lua 分布式滑动窗口，故障时降级单机内存 | Redis 故障期间多实例计数不共享、重启清零；建议 Redis 高可用 |
| Widget | 仅 iframe 注入，无 `postMessage` 双向通信；`frame-ancestors` 需网关 / 静态服务配置 | 宿主页无法读取对话事件；手工 iframe 仍可能绕过 `widget.js`，需 CSP 兜底 |
| 分享密码 | 失败 5 次锁定 15 分钟（可配置），无验证码 | 换 IP 可继续尝试；强防护可叠加网关限流 / 验证码 |
| API Key | 无自动轮换；已有可选 IP 白名单 | 泄露后需人工吊销；建议始终配置 IP 白名单 |
| 真实 IP | 默认只信任 TCP 对端，XFF 仅在可信代理下生效 | 代理拓扑变化时需同步 `trusted-proxies`，配置过宽会引入伪造风险 |
| 反馈与评测 | 无数据集版本管理、无 A/B 对比 | 离线评测只能脚本化执行 |
| 多模态 | 仅视觉模型图片输入，无 OCR | 扫描件 / 图片型 PDF 无法直接提取文字 |
| 检索 | 单库 PostgreSQL + pgvector，无独立向量库 / 搜索引擎 | 超大规模语料需评估性能上限 |
| **模型故障转移** | **仅普通 RAG 在发起调用前的同步异常会切链；流式 `onError`（含首个 token 前的异步连接失败）不回切链；Agent / 工作流只用主模型** | **已知缺陷，后续优先修复（见 §9）** |
| 存储切换 | `local` ↔ `oss` 切换不自动迁移历史对象 | 需手工迁移或重新上传并重建向量 |
| OSS 删除 | 删除失败仅 WARN，数据库仍逻辑删除，可能残留孤儿对象 | 需离线清理脚本 |
| 大文件上传 | 未实现分片上传 / 断点续传，OSS 仅单次 `putObject`；默认上限 50MB | 超大文件与弱网场景需另行接入 |
| 多实例 JWKS | `JwksProvider` 缓存为进程内，多实例不共享 | 单实例部署假设；多实例需接受轮换后的刷新时间差 |
| 移动端 / 国际化 | 仅桌面端布局，无 i18n | — |
| 扩展性 | 无 URL 抓取与第三方数据源连接器，无增量同步 | 文档需手工上传或经 API 写入 |

---

## 9. 路线图 / 后续计划

以下为候选方向，**未排期、不代表交付承诺**；优先级按当前风险与使用频率排序。

| 优先级 | 方向 | 当前状态 | 说明 |
|--------|------|----------|------|
| P0 | 模型故障转移补全 | 部分完成 | 让流式 `onError`（含首个 token 前的异步连接失败）可回到切链循环；逐步扩展到 Agent / 工作流 |
| P1 | 大文件分片上传 / 断点续传 | 未实现 | OSS multipart 与失败续传；业务侧分片大小与并发控制 |
| P1 | 存储迁移与孤儿对象清理 | 未实现 | `local` ↔ `oss` 历史对象迁移工具；按 `file_path` 比对清理 OSS 残留对象 |
| P2 | 多实例 JWKS 缓存共享 | 未实现 | 将 JWKS 缓存放至 Redis 或共享缓存，统一密钥轮换后的刷新时机 |
| P2 | 分布式链路追踪 | 未实现 | 在现有 Prometheus + `X-Request-Id` 基础上接入 Tracing |
| P2 | 数据源连接器与增量同步 | 未实现 | URL 抓取、第三方文档源同步、增量更新 |
| P3 | 反馈 A/B 与数据集版本 | 未实现 | 版本化评测集、实验对比与效果回归 |
| P3 | 图片 OCR | 未实现 | 扫描件 / 图片型 PDF 文本提取 |

暂不规划：多租户隔离、独立向量数据库 / 搜索引擎、Kubernetes 级编排。当前设计以单库 PostgreSQL + pgvector 与单实例部署为优先，降低私有化部署与运维成本。

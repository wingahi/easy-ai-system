# WGH RAG System

> 企业知识库问答与 RAG 中台：把文档治理、检索增强、流式问答、Agent 编排与开放接入收敛到一套可私有化部署的系统。

---

## 1. 定位与价值

本系统面向需要在**内网/私有环境**里快速搭建"可治理、可评估、可审计"的智能问答与知识检索平台的团队。它不是一个只做 Demo 的 RAG 脚本，也不是只面向系统集成的自动化工作流工具，而是把两类常见诉求合并：

- **企业知识库问答**：文档入库 → 解析切片 → 向量/关键词混合检索 → 大模型回答，并给出引用来源与检索质量指标；
- **RAG 中台**：同一套模型、知识库、工具、配额与审计能力，向上支撑多个聊天窗口、工作流与第三方系统调用。

### 1.1 典型适用场景

| 场景 | 用法 |
|------|------|
| 内部文档问答 | 制度、产品手册、SOP 入库，员工通过聊天窗口提问，回答附带原文出处 |
| 客服知识辅助 | 坐席侧知识检索 + 建议回答；对外可通过免登录分享或 Widget 嵌入官网/帮助中心 |
| 研发知识检索 | 接口文档、设计文档、排障记录检索；通过开放 API 接入 IDE 插件或内部工具链 |
| 合规审计追溯 | 全量审计日志（谁、何时、对什么资源做了什么）+ 模型调用成本与配额用量留痕 |
| 固定 SOP 自动化 | 工作流编排（含 LLM/检索/工具/AGENT/人工确认节点），按版本发布与回滚 |

### 1.2 能力边界

- **做**：文档解析与检索增强、流式问答、多轮会话与上下文压缩、Agent 规划执行、多 Agent 协作、工作流编排、分享/嵌入/开放 API、配额限流、审计与成本统计。
- **不做**：多租户隔离、独立向量数据库/搜索引擎（单库 PostgreSQL + pgvector）、图片 OCR、移动端与国际化、Kubernetes 级别的水平扩展编排。

### 1.3 与同类产品的差异

| 维度 | 主流产品（Dify / Coze / RAGFlow / FastGPT） | 本系统取舍 |
|------|-------------------------------------------|-----------|
| 部署形态 | SaaS 为主或依赖多中间件 | **单库 + 可选 Redis**：PostgreSQL + pgvector 承载业务与向量，Redis 承载分布式限流 / 密码锁定 / 嵌入授权；两者不可用均不阻断启动（Redis 自动降级为单机内存） |
| 检索质量 | 多数只给相似度分数 | 对话实时输出召回覆盖率/命中率；ground truth 标注后可算真实 Recall@K，并支持离线评测脚本 |
| 长对话成本 | 依赖外部缓存或简单截断 | 三层上下文压缩（近期原文/中期摘要/远期事实），压缩结果持久化与 Prompt 版本失效重算 |
| 编排一致性 | 对话、Agent、工作流常是两套体系 | 聊天窗口、Agent、工作流、多 Agent 共用 SSE 风格、HITL、落库与刷新恢复约定 |
| 对外接入 | 渠道发布能力完善 | 提供免登录分享、iframe Widget 与 API Key 开放 API，并统一纳入配额、限流与审计 |

---

## 2. 核心能力

### 2.1 知识库与检索增强

- **12 种文档格式**：PDF / DOC / DOCX / XLS / XLSX / CSV / TXT / MD / PPTX / HTML / JSON / EML。统一由 `DocumentParserDispatcher` 分发，抽取标题、页数/工作表数、编码、字符数等元数据；解析前做存在性、空文件与大小上限检查，损坏文件与缺少依赖返回友好错误（图片 OCR 未支持）。
- **9 种切分策略**：AUTO / RECURSIVE / CHAPTER / PARAGRAPH / FAQ / LENGTH / CUSTOM / STRUCTURED（标题感知）/ SEMANTIC（语义断句）。文件级幂等，重新切分/删除会同步清理旧向量。
- **检索链路**：查询变换（REWRITE / MULTI_QUERY / HYDE）→ 多路召回（向量余弦 + pg_trgm 关键词）→ RRF(k=60) 融合 → 可选 Rerank → topK。每一步失败独立降级，不阻断问答。
- **可度量**：SSE 随消息下发召回覆盖率（返回条数/topK）与命中率（分数达标占比）；ground truth（`wgh_kb_ground_truth`，`fileId:chunkIndex` 为键）标注后可计算 Recall@K / Precision@K，离线脚本 `eval/eval_recall.py` 可复用同一标注。
- **标签与文档版本**：知识库/文件多标签；文件历史版本快照与回滚（回滚生成新版本，不覆盖旧版本）。

### 2.2 对话、分享与多模态

- 多会话管理、首条消息自动生成标题、历史滑动加载；SSE 流式输出 Markdown、引用来源、检索指标与工具/Agent 事件。
- **分层上下文压缩**：超过 `max-history-messages`（默认 20）触发三层压缩：近期 4 轮原文、中期 6 轮摘要、远期 ≤8 条关键事实；模型缺失或超时自动降级为截断。
- **多模态图片问答**：视觉模型（`supports_vision`）支持图片 + 文本提问，附件地址落 `wgh_chat_message.attachments`。
- **反馈闭环**：消息点赞/点踩 + 备注，窗口满意度统计与差评运营列表（A/B 对比与数据集版本管理未实现）。
- **免登录分享**：带 token 链接，支持有效期（默认 7 天、上限 30 天）、可选访问密码（失败 5 次锁定 15 分钟，可配置）、只读范围（`WINDOW` 匿名问答 / `SESSION` 只读会话 / `KB` 只读检索）、使用次数与即时吊销。匿名问答复用完整 RAG 链路并计入配额。
- **嵌入 Widget**：`widget.js` 一行脚本在第三方页面注入 iframe；服务端先按域名（支持 `*.example.com` 通配）与 IP/CIDR 白名单校验来源并签发短期 grant（`?embed=1&grant=...`），未授权来源返回可读拒绝原因；样式靠 iframe 文档隔离；提供本地可打开的 `widget-demo.html`。
- **开放 API**：API Key（仅存 SHA-256，明文创建时返回一次）、`chat:write` / `kb:read` / `usage:read` 权限范围、可选 IP/CIDR 白名单（不命中返回 403）、独立鉴权过滤链、按 Key + IP + 路径的 Redis 分布式限流、调用日志（谁/接口/状态码/耗时/IP），并复用配额校验。

### 2.3 Agent 与工作流编排

- **单 Agent**：窗口级 `agent_enabled`。Planner 拆解 → StepExecutor 执行（可调工具与检索）→ Reflection 评分重试 → 汇总答复；计划/步骤全量落库；有副作用操作可经 HITL 人工确认。
- **多 Agent 协作**：6 种模式——串行/层级/并行 Crew、状态图 Graph、轮询/动态路由 GroupChat；每个 Agent 独立模型/知识库/工具/记忆/HITL 配置。
- **工作流**：11 类节点（START / END / LLM / RETRIEVAL / TOOL / CONDITION / LOOP / HITL / VARIABLE / WORKFLOW / **AGENT**），支持并行 fan-out/fan-in、变量引用、子流程（最大嵌套 3 层）、版本快照与回滚、运行历史、CRON/Webhook 触发器与模板市场。AGENT 节点内置轻量 Plan-and-Execute（`AgentNodeExecutor`，支持 `goal`/`maxSteps`/`timeoutMs`/`kbIds`）。

### 2.4 平台治理

- **账号与权限**：本系统**只验证外部 OIDC IdP 签发的 token，不签发任何 token**（JWKS 公钥验签 + iss/aud/exp/nbf 校验）；角色取自 token 的 `roles`/`groups` claim，经映射表匹配数据库 `wgh_role.role_code` 后加载权限，`@RequiresPermission` 做资源级校验。token 无角色时按默认 `viewer` 处理。尚未提供角色管理界面/接口，也不支持租户隔离与知识库/文档级权限的全量覆盖。
- **审计与成本**：`@Audit` 切面异步记录写操作（含成功/失败、操作人、资源、IP，失败不阻断主流程）；模型价格表 + 消息成本/模型名回填，管理端聚合统计。
- **配额与限流**：配额由独立 `rag-quota` 模块提供（全局默认 + 按用户日/月配置，超限 429，用量原子累加）；分享与开放 API 另有每分钟滑动窗口限流。
- **语义缓存**：窗口级开关，相似度阈值命中后直接返回缓存答案，跳过检索与 LLM；每窗口条数上限，超出淘汰最旧。
- **Prompt 模板中心**：模板 CRUD、变量提取、渲染预览；窗口系统提示可用 `template:{id}` 引用。输入框支持**前缀联想**：在聊天调试页输入框或工作流 LLM 节点的 User Prompt 中输入前缀（默认 `/`）即弹出模板候选，继续输入按名称/简述/分类模糊过滤，↑↓ 选择、Enter 填入、Esc 关闭。选中含 `{{变量}}` 的模板会进入变量填写态（占位符高亮、Tab/Enter 逐个填写，全部替换后才可发送；也可「跳过变量直接发送」，未填变量按后端 render 规则替换为空串）。前缀字符、最大候选数、是否启用可在「Prompt 模板中心」页顶部配置，保存在浏览器 localStorage（`wgh_prompt_trigger_config`，默认启用、前缀 `/`、最多 10 个）。
- **模型路由与故障转移**：窗口可指定 `model_route_group`，同组模型按 `route_priority` 升序形成故障转移链；未配置时回退 `llm_model_id`。
- **可观测性（未完成）**：暂无 Prometheus 指标与链路追踪，仓库不含 Dockerfile / docker-compose。

---

## 3. 架构与技术栈

### 3.1 模块结构

```
wgh-rag-system/
├── rag-backend/                  # Spring Boot 多模块（Java 21）
│   ├── rag-common/               # Result / RBAC 模型 / 审计切面 / 全局异常
│   ├── rag-auth/                 # 认证能力归属：外部 OIDC token 资源服务器（JWKS 验签 + 角色映射）
│   ├── rag-quota/                # 配额模块（全局默认 + 用户日/月配置）
│   ├── rag-config/               # RagProperties（RAG / PgVector / Agent / Context 配置映射）
│   ├── rag-llm/                  # DB 动态模型 + 多厂商工厂 + 缓存热更新 + 模型路由
│   ├── rag-kb/                   # 上传 → 解析 → 切分 → 向量化 → 检索增强 → 评测
│   ├── rag-chat/                 # 窗口/会话、上下文压缩、流式 RAG、分享、图片、反馈
│   ├── rag-tool/                 # ToolExecutor SPI + HTTP / MCP / Skill / 函数调用
│   ├── rag-agent/                # 单 Agent：Planner / Executor / Reflection / HITL
│   ├── rag-agent-multi/          # 多 Agent：Crew / Graph / GroupChat / runtime
│   ├── rag-workflow/             # 工作流引擎：调度、节点执行器（含 AGENT）、触发器、模板
│   ├── rag-controller/           # REST 层 + publicaccess（分享 / 开放 API / Widget 鉴权）
│   ├── rag-app/                  # 启动模块 + application*.yml + 集成测试
│   ├── sql/                      # schema.sql（37 张表 + 索引 + COMMENT）/ data.sql（种子）
│   ├── eval/                     # eval_recall.py（零依赖 Recall@K）、eval_rag.py（含 LLM Judge）
│   └── scripts/                  # e2e / 冒烟 / 压测 / 测试代码规范检查
├── rag-frontend/                 # Vue 3 前端（含 public/widget.js、widget-demo.html）
└── docs/                         # 设计文档、API 参考、测试用例与报告
```

### 3.2 技术栈

| 层次 | 技术 |
|------|------|
| 前端 | Vue 3 · Vite · Pinia · Vue Router · Element Plus · SCSS · marked |
| 后端 | Spring Boot 3.4 · Java 21 · Maven 多模块 · Spring Security（外部 OIDC 资源服务器 + 独立开放 API 过滤链） |
| 数据 | PostgreSQL 16+ · pgvector · MyBatis-Plus（逻辑删除 + 分页） |
| LLM | LangChain4j · OpenAI / DASHSCOPE / DeepSeek / 自建 OpenAI 兼容端点 |
| 工具 | LangChain4j Tool Calling · MCP（stdio / Streamable HTTP）· HTTP · 本地反射 · Skill |
| Agent | Planner / StepExecutor / Reflection / HITL；多 Agent 另含 Crew / Graph / GroupChat 协调器 |
| 工作流 | 自研 DAG 引擎：11 类节点、并行 fan-out/fan-in、版本快照、CRON/Webhook |
| 鉴权 | 外部 OIDC token（JWKS 非对称公钥验签）+ RBAC 注解校验 + API Key（SHA-256） |
| 评估 | 检索侧 Recall@K / Precision@K / MRR / NDCG；生成侧 LLM-as-Judge |
| 测试 | JUnit 5 + Mockito（75 个测试类 / 557 个 `@Test` 方法，以当前工作区统计）+ shell e2e/冒烟/压测脚本 |

### 3.3 工程约定

- 多模块启动：`mvn -f rag-app/pom.xml spring-boot:run`；用 `-pl` 时必须加 `-am`，避免读到 `~/.m2` 旧 jar。
- 所有删除走逻辑删除（`deleted` 字段）。
- SSE `content` 事件必须 JSON 包装文本，避免换行丢失；状态复位必须事件驱动。
- Agent 先落库后推 SSE；HITL 暂停前不发 `step_start`，前端按 `plan_paused` 补建步骤。
- surefire 排除 `**/*IntegrationTest.java`，集成测试用 `*ApiTest` / `*EngineTest` / `*Test` 命名。

---

## 4. 快速开始 / 部署

### 4.1 启动 PostgreSQL + pgvector

```bash
docker run -d --name postgres-with-vector \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=wgh_rag \
  -p 5432:5432 \
  pgvector/pgvector:pg16

# 初始化：37 张表 + 业务索引 + 种子数据（幂等，可重复执行）
docker exec -i postgres-with-vector psql -U postgres -d wgh_rag < rag-backend/sql/init.sql
```

### 4.2 后端（含可选 Redis）

```bash
# 可选：启动 Redis（公开访问的分布式限流 / 密码锁定 / 嵌入授权使用）
docker compose up -d redis       # redis:8.10.1-alpine，默认 localhost:6379
docker exec wgh-rag-redis redis-cli ping   # PONG

cd rag-backend
mvn install -DskipTests          # 多模块先安装，避免读到旧 jar
mvn -f rag-app/pom.xml spring-boot:run
# http://localhost:8080
```

> Redis 未启动也能正常启动应用：首次公开访问请求失败后自动降级为**单机内存**限流/计数，并每 30 秒最多输出一次 WARN。恢复 Redis 后自动回到 Redis（详见 [docs/design-public-access.md](docs/design-public-access.md) §4.3）。
> 关闭 Redis 接入：`WGH_REDIS_ENABLED=false`。

> 双认证并存：
> - **本地管理员 / 体验账号**：登录页输入账号密码，后端 `POST /api/auth/login` 校验 BCrypt 密码并签发内部会话凭证（HS256，独立 `wgh.auth.local.jwt-secret`，有效期默认 2 小时）。仅 `wgh_user.source=local` 且为 admin 角色或体验账号（`trial=1`）可登录。
> - **外部 OIDC 用户**：由 IdP 签发 Bearer token，后端按 JWKS 公钥验签；启动前需配置 `WGH_OIDC_ISSUER` / `WGH_OIDC_AUDIENCE` / `WGH_OIDC_JWKS_URI`。
> 两种凭证都经统一认证过滤器识别，角色/权限统一查数据库角色表与权限表。
>
> **生产部署不得使用默认口令**：`sql/data.sql` 只包含角色/权限等幂等种子，不再硬编码任何可登录账号。
> 初始管理员由启动参数注入后创建：
>
> ```bash
> export WGH_INIT_ADMIN_PASSWORD='<至少 8 位且含字母与数字>'
> mvn -f rag-app/pom.xml spring-boot:run
> ```
>
> 未注入该变量时**不会创建默认管理员**（启动日志输出 ERROR 提示）；首次启动创建的 admin 账号带
> `password_must_change=1`，登录后必须先调用 `POST /api/auth/password` 改密，改密前除只读接口、
> 登出与改密接口外的写请求都会被拒绝（业务码 1006）。已存在的 admin 账号不会被覆盖口令。
> 本地会话凭证密钥 `wgh.auth.local.jwt-secret` 在生产 profile（`prod`/`production`）下必须显式配置，
> 否则启动失败（fail-closed）；开发环境未配置时会生成随机密钥并打印 WARN（重启后旧会话全部失效）。

### 4.3 前端

```bash
cd rag-frontend
npm install
npm run dev                      # http://localhost:5173
npm run build                    # 产物 dist/，由 Nginx 或后端静态托管
```

### 4.4 配置模型

登录后进入 **模型管理**，至少配置一个 Embedding 模型和一个 Chat 模型；可选标记 `compression_model=true` 作为压缩专用模型。

- DASHSCOPE 走 OpenAI 兼容端点时，provider 选 `CUSTOM_OPENAI`（否则 SDK 路径拼接 404）
- 通义 / DeepSeek 在 LangChain4j 的 `community` 包下，不在核心包

### 4.5 体验路径

1. **知识库**：新建知识库 → 上传文件（含 PPTX/HTML/JSON/EML）→ 检索测试看三率指标
2. **RAG 对话**：新建聊天窗口 → 绑定知识库/工具 → 调试对话
3. **Agent**：窗口开启「Agent 自动规划执行」→ 配置模型与反思阈值 → 发送多步目标
4. **工作流**：模板市场选模板 → 画布编辑（可拖入 AGENT 节点）→ 运行调试（含 HITL 弹窗）
5. **多 Agent**：Crew 管理配置角色 → 运行调试查看执行面板
6. **开放能力**：开放平台 → 创建分享链接 / API Key；浏览器打开 `/share/{token}` 或 `rag-frontend/public/widget-demo.html` 验证

### 4.6 运行测试

```bash
cd rag-backend
mvn test                                  # 单元测试
bash scripts/test-agent-e2e.sh            # Agent 主链路
bash scripts/test-agent-hitl-e2e.sh       # Agent HITL
bash scripts/test-workflow-e2e.sh         # 工作流节点 / 并行 / 版本 / 触发器
bash scripts/test-multi-agent-smoke.sh    # 多 Agent 冒烟
bash scripts/test-stress.sh               # 压力测试（依赖 ab）
python3 eval/eval_recall.py --kb 2 --k 5  # 离线召回率评测
```

脚本依赖后端 8080 运行中、`postgres-with-vector` 可访问（默认库 `wgh_rag`）。
测试用例与覆盖说明见 [docs/test-cases.md](docs/test-cases.md)。

### 4.7 生产部署要点

- 仓库提供 `docker-compose.yml` 用于本地启动 **Redis 8.10.1**；应用本体仍为 **PostgreSQL + Spring Boot 进程 + Nginx 托管前端**（未提供应用 Dockerfile）。
- Redis 推荐独立部署或托管服务，开启持久化与访问密码，通过 `WGH_REDIS_HOST/PORT/DATABASE/PASSWORD/TIMEOUT` 注入；生产需监控连接与内存。
- 生产必须通过环境变量注入 `WGH_OIDC_ISSUER`、`WGH_OIDC_AUDIENCE`、`WGH_OIDC_JWKS_URI`、数据库口令与模型 Key；`WGH_PROFILE=prod`。
- 分享 / 开放 API 的限流、密码锁定、嵌入 grant 已集中到 Redis，多实例共享；Redis 故障时自动降级为单机内存，此时各实例计数不共享。
- 客户端真实 IP：默认只信任 TCP 对端地址。若部署在 Nginx / 网关后，需配置 `wgh.public-access.trusted-proxies`（如 `10.0.0.10/32`、`172.17.0.0/16`），**不要配置为 `0.0.0.0/0`**，否则客户端可伪造 `X-Forwarded-For` 绕过 IP 白名单与限流。
- 第三方页面嵌入 Widget 时，需在宿主 CSP 中放行 `script-src` 与 `frame-src` 指向本系统域名；如需禁止任意站点手工 iframe 分享页，请在网关/静态服务为分享页下发 `CSP frame-ancestors <白名单域名>`。

### 4.8 对接外部 IdP（OIDC 资源服务器）

本系统**不签发 token**，只验证外部 OIDC IdP 签发的 Bearer token：

```yaml
wgh:
  auth:
    oidc:
      issuer: https://idp.example.com/realms/wgh
      audience: wgh-rag
      jwks-uri: https://idp.example.com/realms/wgh/protocol/openid-connect/certs  # 可留空自动发现
      roles-claim: groups          # 也支持 roles；按 IdP 实际 claim 配置
      username-claim: preferred_username
      default-role: viewer
      jwks-cache-ttl-seconds: 300
      jwks-refresh-min-interval-seconds: 30
      clock-skew-seconds: 30
      role-mapping:
        "wgh-admins": admin
        "wgh-editors": editor
```

**验签与角色链路**：JWKS 取公钥（仅 RS/PS/ES，拒绝 HS256 与 none）→ 按 kid 选 key → 校验 iss/aud/exp/nbf →
`roles-claim` 值经 `role-mapping` 得到 `role_code` → 匹配数据库 `wgh_role` → 查 `wgh_role_permission` 权限。
token 无角色或映射后数据库不存在时按 `default-role`（viewer）处理。

**宿主系统注入 token**：IdP 登录后将 token 写入 `localStorage.wgh_token`（可由宿主页面 `localStorage.setItem('wgh_token', idToken)`
完成），或打开 `http://<host>/login?token=<token>`；前端会调用 `/api/auth/me` 拉取角色/权限。

```bash
curl -H "Authorization: Bearer $IDP_TOKEN" http://localhost:8080/api/auth/me
```

---

## 5. 配置

### 5.1 环境变量

| 变量 | 默认 | 说明 |
|------|------|------|
| `WGH_PROFILE` | dev | Spring Profile（dev / test / prod） |
| `WGH_DB_HOST` | localhost | PostgreSQL 主机 |
| `WGH_DB_PORT` | 5432 | PostgreSQL 端口 |
| `WGH_DB_NAME` | wgh_rag | 数据库名 |
| `WGH_DB_USERNAME` | postgres | DB 用户 |
| `WGH_DB_PASSWORD` | postgres | DB 密码 |
| `WGH_OIDC_ISSUER` | - | 外部 IdP issuer（token `iss` 必须一致，生产必填） |
| `WGH_OIDC_AUDIENCE` | - | 期望 audience（token `aud` 必须包含，生产必填） |
| `WGH_OIDC_JWKS_URI` | - | JWKS 端点；留空则由 issuer 自动发现 |
| `WGH_OIDC_ROLES_CLAIM` | roles | token 中承载角色的 claim 名 |
| `WGH_OIDC_USERNAME_CLAIM` | sub | token 中承载用户名的 claim 名 |
| `WGH_REDIS_ENABLED` | true | 是否启用 Redis；false 时公开访问直接使用单机内存 |
| `WGH_REDIS_HOST` | localhost | Redis 主机 |
| `WGH_REDIS_PORT` | 6379 | Redis 端口 |
| `WGH_REDIS_DATABASE` | 0 | Redis 逻辑库 |
| `WGH_REDIS_TIMEOUT` | 2s | Redis 连接 / 命令超时（影响故障降级延迟） |
| `WGH_REDIS_PASSWORD` | - | Redis 访问密码 |
| `WGH_RL_DEFAULT` | 60 | 限流默认每分钟上限 |
| `WGH_RL_SHARE_TOKEN` | 20 | 分享 token + IP 每分钟上限 |
| `WGH_RL_SHARE_IP` | 60 | 分享单 IP 每分钟上限 |
| `WGH_RL_SHARE_CHAT` | 20 | 分享聊天接口每分钟上限 |
| `WGH_RL_SHARE_KB` | 20 | 分享知识库检索每分钟上限 |
| `WGH_RL_SHARE_EMBED` | 30 | Widget 嵌入授权每分钟上限 |
| `WGH_RL_API_KEY` | 60 | 单个 API Key 每分钟上限 |
| `WGH_RL_API_IP` | 120 | 开放 API 单 IP 每分钟上限 |
| `WGH_SHARE_PWD_MAX_FAILURES` | 5 | 分享密码失败阈值 |
| `WGH_SHARE_PWD_LOCK_MINUTES` | 15 | 分享密码锁定时长（分钟） |
| `WGH_SHARE_PWD_RESET_ON_SUCCESS` | true | 密码验证成功后清零失败计数 |
| `WGH_EMBED_GRANT_TTL_MINUTES` | 30 | Widget 嵌入 grant 有效期（分钟） |
| `WGH_UPLOAD_DIR` | ./uploads | 文件上传目录 |
| `WGH_SKILL_BASE_PATH` | ./skills | Skill 磁盘目录 |
| `OPENAI_API_KEY` | demo | OpenAI 兼容接口 Key |
| `OPENAI_BASE_URL` | https://api.openai.com/v1 | OpenAI 兼容接口地址 |
| `DASHSCOPE_API_KEY` | - | 通义千问 API Key |
| `DEEPSEEK_API_KEY` | - | DeepSeek API Key |

### 5.2 关键配置项（`application.yml`）

| key | 默认值 | 说明 |
|-----|--------|------|
| `wgh.file.max-single-file-mb` | 50 | 单文件上传/解析大小上限 |
| `wgh.rag.cache.enabled` | true | 语义缓存全局开关（窗口级需同时开启） |
| `wgh.rag.cache.similarity-threshold` | 0.95 | 缓存命中相似度阈值 |
| `wgh.rag.cache.max-per-window` | 500 | 每窗口缓存条数上限 |
| `wgh.public-access.share-max-expire-days` | 30 | 分享链接有效期上限（天） |
| `wgh.public-access.share-default-expire-days` | 7 | 分享链接默认有效期（天） |
| `wgh.public-access.max-images-per-request` | 4 | 单次提问图片数量上限 |
| `wgh.rag.redis.enabled` | true | 启用 Redis（分布式限流 / 密码锁定 / 嵌入授权） |
| `wgh.rag.redis.host/port/database/timeout/password` | localhost / 6379 / 0 / 2s / - | Redis 连接参数 |
| `wgh.public-access.rate-limit.default-per-minute` | 60 | 未命中覆盖配置时的限流默认值 |
| `wgh.public-access.rate-limit.overrides.*` | 见下表 | 按维度（`share-token` / `share-ip` / `share-chat` / `share-kb` / `share-embed` / `api-key` / `api-ip`）或 `维度:路径` 覆盖每分钟上限 |
| `wgh.public-access.share-password.max-failures` | 5 | 密码错误阈值 |
| `wgh.public-access.share-password.lock-minutes` | 15 | 锁定分钟数 |
| `wgh.public-access.share-password.reset-on-success` | true | 验证成功后清零失败计数 |
| `wgh.public-access.embed.grant-ttl-minutes` | 30 | Widget 嵌入 grant 有效期（分钟） |
| `wgh.public-access.trusted-proxies` | `[]` | 可信反向代理 CIDR 列表；为空时不采信 `X-Forwarded-For` |
| `wgh.auth.oidc.issuer` | - | 外部 IdP issuer（必填） |
| `wgh.auth.oidc.audience` | - | 期望 audience（必填） |
| `wgh.auth.oidc.jwks-uri` | - | JWKS 端点；留空则按 issuer 自动发现 |
| `wgh.auth.oidc.roles-claim` | roles | 角色 claim 名（也常用 `groups`） |
| `wgh.auth.oidc.role-mapping` | {} | claim 值 → `wgh_role.role_code` 映射表 |
| `wgh.auth.oidc.default-role` | viewer | token 无角色或映射不到数据库角色时的默认角色 |
| `wgh.auth.oidc.jwks-cache-ttl-seconds` | 300 | JWKS 本地缓存 TTL |
| `wgh.auth.oidc.jwks-refresh-min-interval-seconds` | 30 | 未知 kid/网络失败时刷新最小间隔（限频） |
| `wgh.auth.oidc.clock-skew-seconds` | 30 | exp/nbf 时钟偏差容忍 |

> 限流覆盖默认值：`share-token=20`、`share-ip=60`、`share-chat=20`、`share-kb=20`、`share-embed=30`、`api-key=60`、`api-ip=120`；支持 `wgh.public-access.rate-limit.overrides["维度:路径"]` 做更细粒度覆盖。
> 完整规则、原子性实现与降级策略见 [docs/design-public-access.md](docs/design-public-access.md)。
> 配额相关配置（全局默认/用户自定义）由 `rag-quota` 模块提供，见 [docs/quota-design.md](docs/quota-design.md)。

---

## 6. 接口概览

完整请求体与 SSE 事件协议见 [docs/api.md](docs/api.md)；分享/开放 API 细节见 [docs/design-public-access.md](docs/design-public-access.md)。

| 模块 | 前缀 | 说明 |
|------|------|------|
| 鉴权 / OIDC | `/api/auth` | 登录、当前用户、OIDC authorize/callback/status |
| 知识库 | `/api/kb` | 知识库/文件 CRUD、重切、检索测试、ground truth、召回评测 |
| 模型 | `/api/models` | Chat / Embedding / Rerank 模型 CRUD |
| 聊天 | `/api/chat/windows` | 窗口 CRUD、会话、消息、SSE 对话、反馈 |
| 分享（登录态旧入口） | `/api/chat/share` | 窗口分享开关与 token 管理 |
| 工具 | `/api/chat/tools` | 工具 CRUD、在线调试、Skill 目录管理 |
| Agent | `/api/agent` | 计划状态、HITL 确认、取消/重试/跳过 |
| 工作流 | `/api/workflows` | CRUD、校验、运行、HITL、版本、触发器、模板 |
| 多 Agent | `/api/agent-multi` | Crew CRUD/校验、运行、SSE、state、HITL |
| Prompt 模板 | `/api/prompt-templates` | CRUD、渲染预览、输入框前缀联想候选（`/suggest`） |
| 标签 / 版本 | `/api/tags`、`/api/kb/files/{id}/versions` | 标签 CRUD 与绑定、版本列表与回滚 |
| 管理端 | `/api/admin` | 审计日志、成本/配额统计、差评、用户配额、分享链接、API Key |
| 免登录分享 | `/api/public/share/{token}` | 信息/解锁/会话/消息/SSE 问答/知识库检索 |
| 开放 API | `/api/open/v1` | `/me`、`/chat/stream`、`/kb/search`、`/usage`（`X-API-Key`） |

---

## 7. 已知限制

| 类别 | 限制 | 影响 / 建议 |
|------|------|------------|
| 权限体系 | 无角色管理界面/接口，无租户隔离，资源级权限仅覆盖部分 Controller | 多团队共用需在网关或后续迭代补足 |
| 可观测性 | 无 Prometheus 指标、链路追踪 | 生产排障依赖日志与审计表 |
| 部署编排 | 仅提供 Redis 的 docker-compose；应用无 Dockerfile | 需手工部署 PostgreSQL + Redis + 后端进程 + Nginx |
| 限流 | Redis Lua 分布式滑动窗口，故障时降级单机内存 | Redis 故障期间多实例计数不共享、重启清零；建议 Redis 高可用 |
| Widget | 仅 iframe 注入，无 `postMessage` 双向通信；`frame-ancestors` 需网关/静态服务配置 | 宿主页无法读取对话事件；手工 iframe 仍可能绕过 `widget.js`，需 CSP 兜底 |
| 分享密码 | 失败 5 次锁定 15 分钟（可配置），无验证码 | 换 IP 可继续尝试；强防护可叠加网关限流/验证码 |
| API Key | 无自动轮换；已有可选 IP 白名单 | 泄露后需人工吊销；建议始终配置 IP 白名单 |
| 真实 IP | 默认只信任 TCP 对端，XFF 仅在可信代理下生效 | 代理拓扑变化时需同步 `trusted-proxies`，配置过宽会引入伪造风险 |
| 反馈与评测 | 无数据集版本管理、无 A/B 对比 | 离线评测只能脚本化执行 |
| 多模态 | 仅视觉模型图片输入，无 OCR | 扫描件/图片型 PDF 无法直接提取文字 |
| 检索 | 单库 PostgreSQL + pgvector，无独立向量库/搜索引擎 | 超大规模语料需评估性能上限 |
| 移动端 / 国际化 | 仅桌面端布局，无 i18n | — |
| 扩展性 | 无 URL 抓取与第三方数据源连接器，无增量同步 | 文档需手工上传 |

---

## 8. 系统截图

`rag-frontend/image/` 目录下：首页 / 模型管理 / 嵌入模型 / 聊天窗口管理 / 创建聊天窗口 / 调试聊天窗口 / 分享聊天窗口 / 工具管理。

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
| 部署形态 | SaaS 为主或依赖多中间件 | **单库单进程**：PostgreSQL + pgvector 承载业务与向量；pgvector 不可用不阻断启动 |
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
- **免登录分享**：带 token 链接，支持有效期（默认 7 天、上限 30 天）、可选访问密码、只读范围（`WINDOW` 匿名问答 / `SESSION` 只读会话 / `KB` 只读检索）、使用次数与即时吊销。匿名问答复用完整 RAG 链路并计入配额。
- **嵌入 Widget**：`widget.js` 一行脚本在第三方页面注入 iframe（`?embed=1`），样式靠 iframe 文档隔离；提供本地可打开的 `widget-demo.html`。
- **开放 API**：API Key（仅存 SHA-256，明文创建时返回一次）、`chat:write` / `kb:read` / `usage:read` 权限范围、独立鉴权过滤链、按 Key 限流、调用日志（谁/接口/状态码/耗时/IP），并复用配额校验。

### 2.3 Agent 与工作流编排

- **单 Agent**：窗口级 `agent_enabled`。Planner 拆解 → StepExecutor 执行（可调工具与检索）→ Reflection 评分重试 → 汇总答复；计划/步骤全量落库；有副作用操作可经 HITL 人工确认。
- **多 Agent 协作**：6 种模式——串行/层级/并行 Crew、状态图 Graph、轮询/动态路由 GroupChat；每个 Agent 独立模型/知识库/工具/记忆/HITL 配置。
- **工作流**：11 类节点（START / END / LLM / RETRIEVAL / TOOL / CONDITION / LOOP / HITL / VARIABLE / WORKFLOW / **AGENT**），支持并行 fan-out/fan-in、变量引用、子流程（最大嵌套 3 层）、版本快照与回滚、运行历史、CRON/Webhook 触发器与模板市场。AGENT 节点内置轻量 Plan-and-Execute（`AgentNodeExecutor`，支持 `goal`/`maxSteps`/`timeoutMs`/`kbIds`）。

### 2.4 平台治理

- **账号与权限**：本系统**只验证外部 OIDC IdP 签发的 token，不签发任何 token**（JWKS 公钥验签 + iss/aud/exp/nbf 校验）；角色取自 token 的 `roles`/`groups` claim，经映射表匹配数据库 `wgh_role.role_code` 后加载权限，`@RequiresPermission` 做资源级校验。token 无角色时按默认 `viewer` 处理。尚未提供角色管理界面/接口，也不支持租户隔离与知识库/文档级权限的全量覆盖。
- **审计与成本**：`@Audit` 切面异步记录写操作（含成功/失败、操作人、资源、IP，失败不阻断主流程）；模型价格表 + 消息成本/模型名回填，管理端聚合统计。
- **配额与限流**：配额由独立 `rag-quota` 模块提供（全局默认 + 按用户日/月配置，超限 429，用量原子累加）；分享与开放 API 另有每分钟滑动窗口限流。
- **语义缓存**：窗口级开关，相似度阈值命中后直接返回缓存答案，跳过检索与 LLM；每窗口条数上限，超出淘汰最旧。
- **Prompt 模板中心**：模板 CRUD、变量提取、渲染预览；窗口系统提示可用 `template:{id}` 引用。
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

### 4.2 后端

```bash
cd rag-backend
mvn install -DskipTests          # 多模块先安装，避免读到旧 jar
mvn -f rag-app/pom.xml spring-boot:run
# http://localhost:8080
```

> 本系统不再提供本地登录接口。启动前需配置外部 IdP（`WGH_OIDC_ISSUER` / `WGH_OIDC_AUDIENCE` /
> `WGH_OIDC_JWKS_URI`），前端携带 IdP 签发的 Bearer token 访问。

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

- 仓库暂不含 Dockerfile / docker-compose；当前方式为 **PostgreSQL + Spring Boot 进程 + Nginx 托管前端**。
- 生产必须通过环境变量注入 `WGH_OIDC_ISSUER`、`WGH_OIDC_AUDIENCE`、`WGH_OIDC_JWKS_URI`、数据库口令与模型 Key；`WGH_PROFILE=prod`。
- 分享/开放 API 的进程内限流不跨实例共享；多实例部署需在网关层补充限流。
- 第三方页面嵌入 Widget 时，需在宿主 CSP 中放行 `script-src` 与 `frame-src` 指向本系统域名。

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
| `wgh.public-access.share-rate-limit-per-minute` | 20 | 分享入口每分钟请求上限（token + IP） |
| `wgh.public-access.api-rate-limit-per-minute` | 60 | 开放 API 每分钟请求上限（API Key） |
| `wgh.public-access.max-images-per-request` | 4 | 单次提问图片数量上限 |
| `wgh.auth.oidc.issuer` | - | 外部 IdP issuer（必填） |
| `wgh.auth.oidc.audience` | - | 期望 audience（必填） |
| `wgh.auth.oidc.jwks-uri` | - | JWKS 端点；留空则按 issuer 自动发现 |
| `wgh.auth.oidc.roles-claim` | roles | 角色 claim 名（也常用 `groups`） |
| `wgh.auth.oidc.role-mapping` | {} | claim 值 → `wgh_role.role_code` 映射表 |
| `wgh.auth.oidc.default-role` | viewer | token 无角色或映射不到数据库角色时的默认角色 |
| `wgh.auth.oidc.jwks-cache-ttl-seconds` | 300 | JWKS 本地缓存 TTL |
| `wgh.auth.oidc.jwks-refresh-min-interval-seconds` | 30 | 未知 kid/网络失败时刷新最小间隔（限频） |
| `wgh.auth.oidc.clock-skew-seconds` | 30 | exp/nbf 时钟偏差容忍 |

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
| Prompt 模板 | `/api/prompt-templates` | CRUD、渲染预览 |
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
| 部署编排 | 无 Dockerfile / docker-compose | 需手工部署 PostgreSQL + 后端进程 + Nginx |
| 限流 | 分享/开放 API 为进程内滑动窗口 | 多实例不共享计数，重启清零 |
| Widget | 仅 iframe 注入，无 `postMessage` 双向通信；未做 `frame-ancestors` 域名白名单 | 宿主页无法读取对话事件；任意站点可嵌入已授权链接 |
| 分享密码 | 无失败次数限制与验证码 | 暴力破解防护依赖网关层 |
| API Key | 无自动轮换、无 IP 白名单 | 泄露后需人工吊销 |
| 反馈与评测 | 无数据集版本管理、无 A/B 对比 | 离线评测只能脚本化执行 |
| 多模态 | 仅视觉模型图片输入，无 OCR | 扫描件/图片型 PDF 无法直接提取文字 |
| 检索 | 单库 PostgreSQL + pgvector，无独立向量库/搜索引擎 | 超大规模语料需评估性能上限 |
| 移动端 / 国际化 | 仅桌面端布局，无 i18n | — |
| 扩展性 | 无 URL 抓取与第三方数据源连接器，无增量同步 | 文档需手工上传 |

---

## 8. 系统截图

`rag-frontend/image/` 目录下：首页 / 模型管理 / 嵌入模型 / 聊天窗口管理 / 创建聊天窗口 / 调试聊天窗口 / 分享聊天窗口 / 工具管理。

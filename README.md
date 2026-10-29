# Easy AI System

一个开箱即用的 RAG（检索增强生成）+ LLM 工作流引擎 Agent 自动规划执行 + 多 Agent 协作 ，专为构建企业级智能系统而设计。

覆盖 **知识库管理 → 向量检索 → 工具调用 → 分层上下文压缩 → Agent 自动规划执行 → 多 Agent 协作 → LLM 流式对话 → RAG 效果评估 → 工作流编排 → 分享会话** 完整链路，可广泛应用于智能客服、企业内部知识库、数据分析、策划评审等场景。

---

## 1. 项目介绍

系统把"对话"和"流程"统一抽象为**可配置的应用**：聊天窗口可分别装配 RAG 与 Agent 能力；多 Agent 协作通过可复用的 Crew 配置提供独立运行入口；工作流则承担步骤固定、需要审核与复用的业务 SOP。所有编排能力在后台可视化配置，前端以流式对话 + 执行轨迹的方式呈现给最终用户。

四种运行范式：

| 范式 | 适用场景 | 特点 |
|------|---------|------|
| RAG 对话 | 知识问答、客服、文档助手 | 检索 + 生成，三率指标实时可见 |
| Agent 自动规划 | 目标明确、步骤未知的开放任务 | Planner 拆解 → 逐步执行 → 反思重试 |
| 多 Agent 协作 | 多角色分工、需要讨论收敛的任务 | 角色分层 / 状态图 / 对话式三种协调器 |
| 工作流编排 | 步骤固定、需审核、需复用的 SOP | 人工编排 DAG，可复现、可审计、可回滚 |

---

## 2. 技术栈

| 层次 | 技术 |
|------|------|
| 前端 | Vue 3 · Vite · Pinia · Vue Router · Element Plus · SCSS · marked（Markdown 渲染） |
| 后端 | Spring Boot 3.x · Java 21 · Maven 多模块 · Spring Security |
| 数据 | PostgreSQL 16+ · pgvector 向量扩展 · MyBatis-Plus（逻辑删除 + 分页） |
| LLM | LangChain4j 1.19.0 · OpenAI / DASHSCOPE / DeepSeek / 自建 OpenAI 兼容端点 |
| 向量 | pgvector（复用 Spring HikariCP 连接池，无需独立向量库客户端） |
| 工具 | LangChain4j Tool Calling · MCP（stdio / Streamable HTTP）· HTTP · 本地反射调用 · Skill |
| Agent | 规划-执行-反思循环（Planner / StepExecutor / ReflectionEngine / AgentOrchestrator）+ HITL |
| 多 Agent | CrewAI 风格角色分层 + 自研状态图（DAG）协调器 + AutoGen 风格对话式协调器 |
| 工作流 | 自研 DAG 引擎：9 类节点 + 子流程、并行 fan-out/fan-in、版本快照、CRON/Webhook 触发器、模板市场 |
| 鉴权 | JWT（HS512），预留 OIDC 扩展 |
| 评估 | MRR / NDCG / Hit Rate / Recall + LLM-as-Judge（Faithfulness / Answer Relevancy） |
| 测试 | JUnit 5 + Mockito + shell e2e/冒烟/压测脚本 + CI 静态规范检查 |

---

## 3. 解决痛点

### 3.1 市面产品的共性短板

| 产品/方案 | 强项 | 典型短板 |
|-----------|------|---------|
| Dify | 应用编排体验好、发布渠道多 | 知识库治理与效果评估偏薄，检索质量缺量化指标；长对话上下文策略需自建 |
| Coze | 对话 Bot 生态、渠道丰富 | SaaS 形态为主，私有化与数据不出域受限，工具生态绑定平台 |
| RAGFlow | 文档解析深度、可视化引用 | Agent 编排与多轮会话工程较弱，缺少反思/HITL/版本化工作流 |
| FastGPT | 轻量问答、上手快 | 复杂编排、审批流、运行审计能力有限 |
| LangChain / LlamaIndex（库） | 灵活、生态广 | 只是库，鉴权、持久化、SSE、评估、可观测都要自己搭 |
| n8n 等工作流工具 | 自动化强 | 面向系统集成，缺少面向最终用户的对话与知识问答体验 |

### 3.2 本系统的针对性解决

| 痛点 | 本系统做法 |
|------|-----------|
| 检索效果不可度量 | 对话实时输出**召回率 / 命中率 / 正确率**；评估模块提供 MRR、NDCG、Hit Rate、Recall + LLM-as-Judge 双轨评估 |
| 长对话 token 爆炸、成本失控 | **三层上下文压缩**（近期原文 / 中期摘要 / 远期事实），持久化复用，Prompt 版本升级自动失效重算，全链路可降级 |
| 编排能力割裂，"问答"与"流程"两套系统 | 统一为窗口级能力 + 独立工作流引擎，SSE 事件风格、HITL、持久化、刷新恢复刻意对齐 |
| Agent 不可控、结果不可审计 | 计划/步骤全量落库，反思评分可追溯，HITL 对**有副作用**操作强制人工确认，支持取消/重试/跳过 |
| 模型厂商绑定 | 模型中立：DB 动态配置 + 多厂商 OpenAI 兼容协议，聊天/压缩/反思/Judge/路由模型可分别指定 |
| 部署成本高（Milvus / ES / Redis 全家桶） | **单库单进程**：PostgreSQL + pgvector 承载业务与向量，pgvector 不可用不阻断启动（降级开关 + 连接复用） |
| 工具接不进现有系统 | 四类执行器统一 SPI：HTTP / MCP / Skill / Function Calling，窗口级独立工具配置，互不干扰 |
| 分享出去的内容不可信/无法隔离 | 分享 token 鉴权 + 按登录用户隔离历史会话；重新生成 token 旧链接立即失效，历史保留 |

---

## 4. 核心特性

### 4.1 知识库与 RAG 链路
多格式解析（PDF / DOCX / DOC / XLSX / XLS / CSV / TXT / MD）；7 种切分策略（AUTO / RECURSIVE / CHAPTER / PARAGRAPH / FAQ / LENGTH / CUSTOM）；pgvector 检索默认 `min_similarity=0.55`，按 embedding 模型 ID 分组避免维度混用；文件级幂等，重新分割/删除同步清理旧向量；三率指标随 SSE 下发并落库。

### 4.2 工具调用（Tool Calling）
`ToolExecutor` SPI + `ToolExecutionEngine` 按类型分发：

| 类型 | 执行器 | 关键配置 | 场景 |
|------|--------|---------|------|
| HTTP | `HttpToolExecutor` | `url` / `method` / `headers` / `timeoutMs` | 调用任意 REST API（`{param}` 占位符 + `${ENV}` 环境变量） |
| MCP | `McpToolExecutor` + `McpClientManager` | `transport=http`（`serverUrl`）/ `stdio`（`command`+`args`） | 接入 Model Context Protocol 服务器 |
| SKILL | `SkillToolExecutor` | `mode=inline`（`promptTemplate`）/ `mode=directory`（`skillPath`）+ `subModelId` | 可复用 Prompt 技能，支持独立子模型 |
| FUNCTION_CALLING | `FunctionCallingToolExecutor` | `handlerClass` / `method` | 本地 Java 方法反射调用 |

工具结果 > 4000 字符自动截断、单次对话工具循环最多 5 轮、窗口级按 `toolIds` 隔离；工具管理页可按 definition 自动生成动态表单在线调试。

### 4.3 Skill 双源管理
磁盘目录（扫描 `SKILL.md` + YAML front matter + `references/`）与数据库记录（inline promptTemplate）统一列表管理，tag 区分来源；同名导入**不覆盖**并返回 `duplicated`；子模型优先级：`subModelId` > `subModelDefault`（继承窗口模型）> Skill 默认。

### 4.4 分层上下文压缩
超过 `max-history-messages`（默认 20）触发三层压缩：近期 4 轮保留原文、中期 6 轮生成 3-5 句摘要、远期提取 ≤8 条关键事实。Prompt 显式定义保留优先级（用户需求/业务规则/实体名/精确数值 > 背景闲聊）。压缩结果持久化（来源消息范围、模型、Prompt 版本、时间戳），命中范围直接复用；无压缩模型、LLM 超时/异常时自动降级为简单截断，不阻塞主流程。

### 4.5 RAG 评估双轨
检索侧：MRR / NDCG@K / Hit Rate / Recall（需 ground truth）；生成侧：Faithfulness / Answer Relevancy（LLM-as-Judge，不需要 ground truth）。窗口可选 `METRICS` / `LLM_JUDGE` / `BOTH` / `NONE`；Judge 未指定时自动选 2 个不同提供商的模型并排除当前聊天模型；评估异步执行不阻塞 SSE。

### 4.6 会话与分享
多会话、首条消息自动生成标题、滑动加载历史；窗口级分享开关与 token 管理；分享页需登录，按登录用户名隔离历史会话，与调试页渲染规则完全一致（Markdown、引用去重、指标展示、Agent 计划树）。

### 4.7 Agent 自动规划执行
窗口级开关 `agent_enabled`。链路：Planner 拆解（步骤/依赖/所需工具/HITL 标记）→ StepExecutor 逐步执行（可调工具 + 检索）→ ReflectionEngine 评分（低于阈值重试）→ 汇总答复 → 先落库后推送。
事件：`plan_created` / `plan_updated` / `plan_paused` / `plan_resumed` / `plan_cancelled` / `plan_complete` / `plan_failed` / `step_start` / `step_success` / `step_failed` / `step_reflection` / `step_hitl_pending`。
HITL：有副作用或不可逆操作（删除数据、外发消息、下单支付、权限变更）由 Planner 标记，窗口开启 `agent_hitl_enabled` 时执行前暂停，人工确认后继续；只读查询不暂停。

### 4.8 多 Agent 协作
6 种模式，窗口/调试入口统一：

| 模式 | 协调器 | 说明 |
|------|--------|------|
| `CREW_SEQUENTIAL` / `CREW_HIERARCHICAL` / `CREW_PARALLEL` | `CrewCoordinator` | 角色分层，串行 / 层级 / 并行 |
| `GRAPH_ROUTED` | `GraphCoordinator` | 状态图调度：START / AGENT / CONDITION / LOOP / HITL / END |
| `GROUP_CHAT_ROUND_ROBIN` / `GROUP_CHAT_DYNAMIC_ROUTER` | `GroupChatCoordinator` + `MessageBus` | 对话式轮询 / LLM 动态选择下一个发言者，支持汇总 Agent |

每个 Agent 独立配置：角色/目标/系统提示/模型/知识库/工具/记忆/反思阈值/HITL。运行事件 14 类（`multi_agent_*`、`agent_*`、`agent_turn_*`），步骤与运行全量落库，支持 HITL 暂停续跑、取消、刷新轮询恢复。

### 4.9 工作流可视化编排
9 类基础节点 + 子流程节点（共 10 类）：START / END / LLM / RETRIEVAL / TOOL / CONDITION / LOOP / HITL / VARIABLE / WORKFLOW。
引擎：`frontier` 就绪队列线程池并发 + `pendingInEdges` 入边计数器实现 fan-out/fan-in 汇聚；CONDITION/LOOP 按 `sourceHandle` 选分支，未走分支下游自然跳过；子流程最大嵌套 3 层。
配套：版本快照与回滚（回滚生成新版本）、运行历史与节点级回放、CRON（6 段表达式）/ Webhook 触发器、3 个内置模板（知识库问答 / 人工审批流 / 定期巡检流）。
变量语法统一 `{{input}}` / `{{vars.x}}` / `{{n_llm_1.text}}` / `{{n_rag_1.sources.0.fileName}}` / `{{loop.iteration}}`。

### 4.10 安全与工程化
JWT 鉴权 + 路由守卫；所有删除逻辑删除；多环境配置（dev/test/prod，生产敏感项环境变量注入）；SSE 流式响应统一 JSON 包装避免换行丢失；页面刷新/会话切换主动中断未完成请求，SSE 状态复位全部事件驱动。

---

## 5. 模块结构

```
wgh-rag-system/
├── rag-backend/                  # 后端（Maven 多模块）
│   ├── rag-common/               # Result / PageResult / JWT / 全局异常 / MyBatisPlus / SecurityConfig
│   ├── rag-config/               # RagProperties（RAG / PgVector / Agent / Context 配置映射）
│   ├── rag-llm/                  # 模型管理：DB 动态配置 + 多厂商工厂 + ModelCacheManager 热更新
│   ├── rag-kb/                   # 知识库：上传 → 解析 → 切分 → 向量化 → 检索
│   ├── rag-chat/                 # 对话：窗口/会话、分层上下文压缩、流式 RAG、评估、分享
│   ├── rag-tool/                 # 工具框架：ToolExecutor SPI + 四类执行器 + Skill 文件服务
│   ├── rag-agent/                # 单 Agent：planner / reflection / service（编排、SSE、HITL）
│   ├── rag-agent-multi/          # 多 Agent：crew / graph / groupchat / runtime / support
│   ├── rag-workflow/             # 工作流：engine（frontier 调度 + executor）、trigger、template、service
│   ├── rag-controller/           # 统一 REST 层：Auth / Chat / KnowledgeBase / LlmModel / Agent / Workflow
│   ├── rag-app/                  # 启动模块 + application*.yml + 集成测试 + skills
│   ├── sql/                      # init.sql → schema.sql（18 张表 + 索引 + COMMENT）+ data.sql（种子）
│   └── scripts/                  # e2e / 冒烟 / 压测 / 测试代码规范检查
├── rag-frontend/                 # Vue 3 前端
│   └── src/
│       ├── api/index.js          # axios 封装 + SSE（GET/POST 流式通用实现）
│       ├── components/           # AgentPlanTree（计划树：状态 / 反思分数 / HITL）
│       ├── router/               # 路由 + 守卫（/share/:token 需登录）
│       └── views/
│           ├── login/            # 登录（redirect 回跳）
│           ├── kb/               # 知识库管理 + 检索测试
│           ├── chat/             # 调试对话 + 分享对话
│           ├── chat-admin/       # 聊天窗口管理（RAG / Agent / 工具 / 评估 / 分享）
│           ├── models/           # Chat / Embedding 模型配置
│           ├── tools/            # 工具管理（4 类 + Skill 双源 + 在线调试）
│           ├── workflow/         # 工作流列表（版本/触发器/运行历史/模板）+ 画布编辑器
│           └── multi-agent/      # 多 Agent：Crew 管理 + 运行调试
└── docs/                         # 设计文档、API 参考、测试用例与报告
```

---

## 6. 关键 API

完整接口清单、请求体与 SSE 事件协议见 **[docs/api.md](docs/api.md)**。

模块速览：

| 模块 | 前缀 | 说明 |
|------|------|------|
| 鉴权 | `/api/auth` | 登录、当前用户 |
| 知识库 | `/api/kb` | 知识库/文件 CRUD、重整、检索测试 |
| 模型 | `/api/models` | Chat / Embedding 模型 CRUD |
| 聊天 | `/api/chat/windows` | 窗口 CRUD、会话、消息、SSE 流式对话 |
| 分享 | `/api/chat/share` | token 校验、SSE 对话、会话与消息 |
| 工具 | `/api/chat/tools` | 工具 CRUD、在线调试、Skill 目录管理 |
| Agent | `/api/agent` | 计划状态、HITL 确认、取消 / 重试 / 跳过 |
| 工作流 | `/api/workflows` | CRUD、校验、运行、HITL、版本、触发器、模板 |
| 多 Agent | `/api/agent-multi` | Crew CRUD / 校验、运行、SSE、state、HITL、取消 |

---

## 7. 功能完成清单

### 7.1 后端模块

| 模块 | 状态 | 说明 |
|------|------|------|
| rag-common | ✅ 完成 | Result / JWT / 异常 / MyBatisPlus / Security |
| rag-config | ✅ 完成 | 多 profile 配置映射（RAG / PgVector / Agent / Context） |
| rag-llm | ✅ 完成 | DB 动态模型 + DASHSCOPE / DEEPSEEK / CUSTOM_OPENAI + 缓存热更新 |
| rag-kb | ✅ 完成 | 8 种格式解析、7 种切分、pgvector 存储与检索 |
| rag-chat | ✅ 完成 | 窗口/会话、三层压缩、流式 RAG、双轨评估、分享 |
| rag-tool | ✅ 完成 | 4 类执行器 + Skill 双源 + 在线调试 |
| rag-agent | ✅ 完成 | Planner → Executor → Reflection → HITL 全链路 |
| rag-agent-multi | ✅ 完成 | 6 种协作模式、14 类 SSE 事件、HITL 续跑、步骤落库 |
| rag-workflow | ✅ 完成 | 9 类节点 + 子流程、并行 fan-in、版本、触发器、模板 |
| rag-controller | ✅ 完成 | 9 组 REST API 全覆盖 |
| 单元测试 | ✅ 完成 | 374 项全绿（common 11 / llm 36 / kb 25 / agent 23 / chat 24 / workflow 63 / agent-multi 56 / app 136） |
| e2e / 冒烟 | ✅ 完成 | Agent 18 + HITL 16 + 工作流 67 + 多 Agent 12 |
| 压力测试 | ✅ 完成 | 7 场景 8 项断言通过（登录 3091 RPS、SSE 首字节 24-38ms） |
| CI 静态检查 | ✅ 完成 | 测试方法英文命名 + 中文注释，GitHub Actions / Gitee Go 复用 |

### 7.2 前端页面

| 页面 | 状态 | 说明 |
|------|------|------|
| 登录 | ✅ 完成 | JWT + 路由守卫 + redirect |
| 知识库管理 / 检索测试 | ✅ 完成 | 上传、状态、实时检索与三率指标 |
| 对话调试 | ✅ 完成 | SSE 流式、来源、指标、工具事件、会话侧栏、Agent 计划树 |
| 分享对话 | ✅ 完成 | 与调试页一致的渲染 + Agent 计划树 + 刷新恢复 |
| 聊天窗口管理 | ✅ 完成 | 知识库 / 工具分组选择 / 评估方案 / Agent 配置 / 分享 |
| 模型管理 | ✅ 完成 | Chat / Embedding 多厂商配置 |
| 工具管理 | ✅ 完成 | 4 类 tab + Skill 双源 + 调试弹窗 |
| 工作流列表 / 编辑器 | ✅ 完成 | 模板市场、启停、版本回滚、触发器、运行历史；DAG 画布 + 节点配置 + 运行轨迹 + HITL |
| 多 Agent Crew / 运行调试 | ✅ 完成 | 6 种模式配置与校验；14 类事件渲染、执行面板、HITL 弹窗、取消、刷新恢复 |

### 7.3 数据层

| 项目 | 状态 | 说明 |
|------|------|------|
| schema.sql | ✅ 完成 | 18 张表 + 业务索引，幂等 DDL（`IF NOT EXISTS`） |
| data.sql | ✅ 完成 | 种子数据 + 老库兼容修复，可重复执行 |
| pgvector | ✅ 完成 | 自动建表、可选索引、连接复用、不可用不阻断启动 |

### 7.4 计划中（主流能力尚未实现）

以下功能是市面主流产品（Dify / Coze / RAGFlow / FastGPT）普遍具备、本系统尚未落地的能力，按优先级排列：

| # | 功能 | 主流对标 | 当前状态 | 说明 |
|---|------|---------|---------|------|
| 1 | **Rerank 重排序** | Dify / RAGFlow / FastGPT | 📋 计划中 | 配置项 `wgh.pgvector.retrieve.enable-rerank` 已预留（默认 false），尚未接入任何 Rerank 模型调用 |
| 2 | **混合检索（向量 + 关键词）** | RAGFlow / Dify | 📋 计划中 | 现仅向量检索；可基于 PostgreSQL `tsvector` + 中文分词实现 BM25 与向量加权融合 |
| 3 | **查询改写 / Multi-Query / HyDE** | RAGFlow / LlamaIndex | 📋 计划中 | 现直接使用原始 query 检索，缺少同义改写与多路召回 |
| 4 | **语义切分 / 结构化切分** | RAGFlow / Dify | 📋 计划中 | 现为规则切分；语义切分依赖 embedding 聚类，结构化切分依赖 Excel/JSON 结构 |
| 5 | **更多文档格式** | Dify / RAGFlow | 📋 计划中 | HTML / JSON / PPTX / 图片 OCR 未支持（PDF/DOCX/DOC/XLSX/XLS/CSV/TXT/MD 已完成） |
| 6 | **URL 抓取与数据源连接器** | Dify / FastGPT | 📋 计划中 | 无网页爬取、Notion/飞书文档等第三方数据源同步、增量更新 |
| 7 | **工作流作为聊天窗口执行模式** | Dify（应用绑定工作流） | 📋 计划中 | 已完成方案设计（窗口加 `workflow_id` + 聊天 SSE 分流），待实现 |
| 8 | **工作流独立发布 / 分享页** | Dify Web App / Coze 渠道 | 📋 计划中 | 工作流目前仅管理端调试与 CRON/Webhook 触发，无对外发布入口 |
| 9 | **工作流 AGENT 节点** | Dify / Coze | 📋 计划中 | 节点白名单暂无 `AGENT`，工作流内无法直接复用 Agent 能力 |
| 10 | **免登录分享 / 嵌入 Widget / 开放 API** | Dify / Coze / FastGPT | 📋 计划中 | 现分享页需登录；无 iframe 嵌入、JS SDK、API Key 开放调用 |
| 11 | **多用户与权限体系** | Dify / FastGPT | 📋 计划中 | 当前仅 `admin` 单账号 JWT，无租户、RBAC、知识库/文档级权限 |
| 12 | **审计日志 / 配额限流 / 成本统计** | 企业级产品通用 | 📋 计划中 | 无操作审计、租户配额、Token 用量与费用报表 |
| 13 | **语义缓存** | Dify（Redis 缓存） | 📋 计划中 | 无相似问题命中缓存，重复提问仍会全量调用 LLM |
| 14 | **可观测性与部署编排** | 企业级产品通用 | 📋 计划中 | 无 Prometheus 指标 / 链路追踪；仓库无 Dockerfile 与 docker-compose |
| 15 | **反馈闭环与离线评测平台** | Dify / RAGFlow | 📋 计划中 | 无点赞点踩与人工标注；评估以脚本为主，缺数据集管理与 A/B 对比 |
| 16 | **多模态** | Coze / Dify | 📋 计划中 | 无图片问答、语音输入、TTS 输出 |
| 17 | **模型路由与故障转移** | Dify | 📋 计划中 | 按模型 ID 直连，无多模型负载均衡、失败重试与降级链 |
| 18 | **Prompt 模板中心** | Dify | 📋 计划中 | Prompt 分散在各编排配置中，无统一模板库与版本管理 |
| 19 | **移动端与国际化** | Coze / Dify | 📋 计划中 | 仅桌面端布局，无 i18n |
| 20 | **知识库标签 / 文档版本** | FastGPT / RAGFlow | 📋 计划中 | 知识库与文件无标签体系、无文档版本与差异对比 |

> 已明确不做的方向：引入 Milvus / Elasticsearch 等独立向量或检索中间件（当前以 PostgreSQL + pgvector 单库承载，降低部署与运维成本）。

---

## 8. 快速开始

### 8.1 启动 PostgreSQL + pgvector

```bash
docker run -d --name postgres-with-vector \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=wgh_rag \
  -p 5432:5432 \
  pgvector/pgvector:pg16

# 初始化：18 张表 + 业务索引 + 种子数据（幂等，可重复执行）
docker exec -i postgres-with-vector psql -U postgres -d wgh_rag < rag-backend/sql/init.sql
```

### 8.2 后端

```bash
cd rag-backend
mvn install -DskipTests          # 多模块先安装，避免读到 ~/.m2 旧 jar
mvn -f rag-app/pom.xml spring-boot:run
# http://localhost:8080 ，默认账号 admin / admin
```

### 8.3 前端

```bash
cd rag-frontend
npm install
npm run dev                      # http://localhost:5173
```

### 8.4 配置模型

登录后进入 **模型管理**，至少配置一个 Embedding 模型和一个 Chat 模型；可选标记 `compression_model=true` 作为压缩专用模型。

- DASHSCOPE 走 OpenAI 兼容端点时，provider 需选 `CUSTOM_OPENAI`（否则 SDK 路径拼接 404）
- 通义 / DeepSeek 在 LangChain4j 1.19.0 的 `community` 包下，不在核心包

### 8.5 体验路径

1. **知识库**：新建知识库 → 上传文件 → 检索测试看三率指标
2. **RAG 对话**：聊天窗口管理新建窗口 → 绑定知识库/工具 → 调试对话
3. **Agent**：窗口开启「Agent 自动规划执行」→ 配置模型与反思阈值 → 发送多步目标
4. **工作流**：工作流编排 → 模板市场选模板 → 画布编辑 → 运行调试（含 HITL 弹窗）
5. **多 Agent**：多 Agent → Crew 管理配置角色 → 运行调试查看执行面板

### 8.6 运行测试

```bash
cd rag-backend
mvn test                                  # 单元测试
bash scripts/test-agent-e2e.sh            # Agent 主链路
bash scripts/test-agent-hitl-e2e.sh       # Agent HITL
bash scripts/test-workflow-e2e.sh         # 工作流 9 类节点 / 并行 / 版本 / 触发器
bash scripts/test-multi-agent-smoke.sh    # 多 Agent 冒烟
bash scripts/test-stress.sh               # 压力测试（依赖 ab）
```

脚本依赖后端 8080 运行中、`postgres-with-vector` 可访问（默认库 `wgh_rag`）。
测试代码规范检查：`python3 scripts/check-test-conventions.py`（测试方法英文命名 + 中文注释）。

---

## 9. 环境变量

| 变量 | 默认 | 说明 |
|------|------|------|
| `WGH_PROFILE` | dev | Spring Profile（dev / test / prod） |
| `WGH_DB_HOST` | localhost | PostgreSQL 主机 |
| `WGH_DB_PORT` | 5432 | PostgreSQL 端口 |
| `WGH_DB_NAME` | wgh_rag | 数据库名 |
| `WGH_DB_USERNAME` | postgres | DB 用户 |
| `WGH_DB_PASSWORD` | postgres | DB 密码 |
| `WGH_JWT_SECRET` | (内置) | JWT 签名密钥（生产必须环境变量注入） |
| `WGH_UPLOAD_DIR` | ./uploads | 文件上传目录 |
| `WGH_SKILL_BASE_PATH` | ./skills | Skill 磁盘目录 |
| `OPENAI_API_KEY` | demo | OpenAI 兼容接口 Key |
| `OPENAI_BASE_URL` | https://api.openai.com/v1 | OpenAI 兼容接口地址 |
| `DASHSCOPE_API_KEY` | - | 通义千问 API Key |
| `DEEPSEEK_API_KEY` | - | DeepSeek API Key |

## 10. 工程约定（重要）

- **多模块启动**：`mvn -f rag-app/pom.xml spring-boot:run`；若用 `-pl` 需同时加 `-am`，否则读 `~/.m2` 旧 jar
- **逻辑删除**：所有删除走 `deleted` 字段，物理删除需明确需求
- **SSE 换行**：`content` 事件必须 JSON 包装传输文本
- **SSE 复位**：状态复位必须事件驱动，不能依赖 `await doStream()` resolve（vite dev 代理不转发终止帧）
- **Agent 落库顺序**：先落库后推 SSE，客户端断连不影响最终答复
- **HITL 时序**：暂停前不发 `step_start`，前端收到 `step_hitl_pending` 需按 payload 补建步骤
- **HITL 弹窗**：`hitlVisible = true` 需包在 `nextTick()` 中，避免 el-dialog teleport 渲染竞争
- **pgvector**：`PgVectorEmbeddingStore` 不要显式配置空 metadata，使用默认 `COMBINED_JSON`
- **JSONB 列**：`input_context` / `crew_config_json` 等 JSONB 列写入必须是合法 JSON
- **并发 Map**：`ConcurrentHashMap` 不允许 null 值，清空标记用空字符串
- **测试命名**：surefire 排除 `**/*IntegrationTest.java`，集成测试用 `*ApiTest` / `*EngineTest` / `*Test`

## 11. 目录约定

| 路径 | 说明 |
|------|------|
| `rag-backend/sql/` | `init.sql` → `schema.sql`（DDL + 索引 + COMMENT）/ `data.sql`（种子 + 兼容修复） |
| `rag-backend/rag-app/skills/` | Skill 磁盘目录（`wgh.skill.base-path`） |
| `rag-backend/uploads/` | 按知识库 ID 分目录 |
| `rag-backend/scripts/` | e2e / 冒烟 / 压测 / 测试规范检查 |
| `docs/` | 设计文档、[API 参考](docs/api.md)、测试用例与报告 |

## 12. 系统截图

`rag-frontend/image/` 目录下：首页 / 模型管理 / 嵌入模型 / 聊天窗口管理 / 创建聊天窗口 / 调试聊天窗口 / 分享聊天窗口 / 工具管理。

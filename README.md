# WGH RAG System

一套可独立部署的 **Retrieval-Augmented Generation (RAG) 问答系统**，覆盖 **知识库管理 → 向量检索 → 工具调用 → 分层上下文压缩 → LLM 流式对话 → RAG 效果评估 → 分享会话** 完整链路。

## 技术栈

| 层次 | 技术 |
|------|------|
| 前端 | Vue 3 + Vite + Pinia + Vue Router + Element Plus + SCSS |
| 后端 | Spring Boot 3.x · Java 21 · Maven 多模块 |
| 数据 | PostgreSQL 16+ + pgvector 向量扩展 · MyBatis-Plus（逻辑删除 + 分页） |
| LLM | LangChain4j 1.19.0 · OpenAI / DASHSCOPE / DeepSeek / 通义 等（OpenAI 兼容协议） |
| 向量 | pgvector（PostgreSQL 内置向量扩展，复用 HikariCP 连接池） |
| 工具 | LangChain4j Tool Calling · MCP（stdio / Streamable HTTP）· HTTP · 本地反射调用 · Skill |
| 鉴权 | JWT (HS256)，预留 OIDC 扩展 |
| 评估 | MRR / NDCG 检索指标 + LLM-as-Judge (faithfulness / answer_relevancy) |

## 模块结构

```
wgh-rag-system/
├── rag-backend/              # 后端（Maven 多模块）
│   ├── rag-app/              # 启动模块 + application*.yml + 集成测试
│   ├── rag-common/           # Result/PageResult、JWT、异常、MyBatisPlus、SecurityConfig
│   ├── rag-config/           # 自定义配置：RagProperties、PgVectorProperties
│   ├── rag-controller/       # REST API 层（Auth / Chat / KnowledgeBase / LlmModel）
│   ├── rag-kb/               # 知识库：上传 → 解析 → 切分 → 向量化 → 检索
│   ├── rag-llm/              # LLM 模型管理：DB 动态配置 + 多厂商兼容 + 缓存工厂
│   ├── rag-chat/             # 对话：会话/窗口管理、分层上下文压缩、流式 RAG、评估、分享
│   ├── rag-tool/             # 工具框架：ToolExecutor SPI + 四类执行器 + ToolDefinitionParser
│   │   ├── executor/         #   HttpToolExecutor / McpToolExecutor / SkillToolExecutor / FunctionCallingToolExecutor
│   │   ├── definition/       #   ToolDefinitionParser（OpenAI function JSON → ToolSpecification）
│   │   ├── demo/             #   OrderQueryHandler（FUNCTION_CALLING 演示 handler）
│   │   └── service/          #   ToolConfigService / SkillFileService
│   ├── sql/                  # init.sql + migration_YYYYMMDD_*.sql
│   └── pom.xml
└── rag-frontend/             # Vue 3 前端
    └── src/
        ├── api/index.js      # axios 封装 + SSE 流式
        ├── router/           # 路由 + 守卫（/share/:token 需登录）
        ├── views/login/      # 登录（支持 redirect 回跳）
        ├── views/kb/         # 知识库管理 + 检索测试
        ├── views/chat/       # 调试对话 + 分享对话
        ├── views/chat-admin/ # 对话窗口管理
        ├── views/models/     # LLM / Embedding 模型配置
        └── views/tools/      # 工具管理（含 SKILL 目录扫描 + 在线编辑 + 调试）
```

## 核心特性

### 1. 知识库与 RAG 链路

- 多格式文件解析：PDF / DOCX / DOC / XLSX / XLS / CSV / TXT / MD
- 多种切分策略：AUTO（自动路由）/ RECURSIVE / CHAPTER / PARAGRAPH / FAQ / LENGTH / CUSTOM
- pgvector 向量检索：默认 `min_similarity=0.55`，按 embedding 模型 ID 分组避免维度混用
- 文件级幂等：重新分割 / 删除文件时同步清理对应向量，避免重复数据
- 实时计算 **召回率（recall_rate）/ 命中率（hit_rate）/ 正确率（accuracy_rate）** 三率指标，写入 `wgh_chat_message` 并随 SSE 下发
- 未关联知识库的窗口自动跳过三率指标计算，避免误导

### 2. 工具调用（Tool Calling）

四类工具统一抽象为 `ToolExecutor` SPI，通过 `ToolExecutionEngine` 按类型分发：

| 类型 | 执行器 | executorConfig 关键字段 | 典型场景 |
|------|--------|------------------------|---------|
| **HTTP** | `HttpToolExecutor` | `url` / `method` / `headers` / `timeoutMs` | 调用任意 REST API（占位符 `{param}` 渲染、环境变量 `${ENV}`） |
| **MCP** | `McpToolExecutor` + `McpClientManager` | `transport=http` 配 `serverUrl` / `transport=stdio` 配 `command+args` / `timeoutMs` | 接入 Model Context Protocol 服务器（如 `@modelcontextprotocol/server-everything`） |
| **SKILL** | `SkillToolExecutor` | `mode=inline`（promptTemplate）/ `mode=directory`（skillPath 运行时读盘）/ `subModelId` / `subModelDefault` | 可复用的 Prompt 模板技能，支持子模型独立配置 |
| **FUNCTION_CALLING** | `FunctionCallingToolExecutor` | `handlerClass` / `method`（默认 `execute`） | 本地反射调用 Java 静态/实例方法（演示见 `OrderQueryHandler`） |

**通用规则**：
- `definition` 必须是 OpenAI function 格式 `{"type":"function","function":{name,description,parameters}}`，否则解析失败会被静默跳过
- 工具结果 > 4000 字符自动截断，避免上下文溢出
- 工具调用循环最多 5 轮，防死循环
- SSE 实时事件：`tool_call_start` / `tool_call_result`（含 success / durationMs / resultText）
- 窗口级独立配置：每个聊天窗口通过 `toolIds` 关联全局工具池，互不影响
- 工具调试：工具管理页面任意工具可在线调试，按 definition 自动生成动态表单（enum 下拉 / number / boolean / string），并提供「一键填入示例参数」

### 3. Skill 管理（数据库 + 磁盘目录双源）

- **磁盘目录**：扫描 `${wgh.skill.base-path}`（默认 `./skills`）下的子目录，识别 `SKILL.md` + YAML front matter（`description` / `subModelId`），支持 `references/` 资源目录
- **数据库记录**：`toolType=SKILL` 的 ToolConfig，executorConfig 内嵌 `promptTemplate`（inline 模式）或 `skillPath`（directory 模式）
- **统一管理页**：左侧合并列表（目录 → 绿色 tag；数据库 → 蓝色 tag；目录已导入显示勾选图标），右侧编辑器按来源切换操作（保存到磁盘 / 保存到数据库 / 导入数据库）
- **同名防重复**：导入时若数据库已存在同名 SKILL，**不创建/不更新**，返回 `duplicated=true` 给前端黄色提示
- **子模型**：`subModelId` 显式指定 > `subModelDefault=true` 继承窗口模型 > 都不设置走 skill 默认

### 4. 分层上下文压缩

对话历史超过 `max-history-messages`（默认 20 条）时触发三层压缩，避免长对话 token 爆炸：

```
全部历史 → 按窗口切分
            ├─ 近期 recent-window-rounds (4)   → 原文保留
            ├─ 中期 mid-term-window-rounds (6)  → LLM 生成 3-5 句摘要
            └─ 远期 剩余所有                     → LLM 提取 ≤8 条关键事实
组装结果  [System, 远期事实, 中期摘要, ...近期原文, 当前用户输入]
```

**Prompt 显式保留优先级**（中期和远期 prompt 均包含）：

1. 用户明确的需求、决策、指令
2. 已确认的业务规则或事实
3. 实体名称（人名、产品名、文件名、配置项）
4. 时间、数字、ID 等精确数据
5. 讨论中的假设、未解决问题
6. 背景闲聊、礼貌用语、重复表述（可删除）

**持久化与复用**：摘要和事实会保存到 `wgh_chat_session`（含来源轮次范围、模型名、Prompt 版本、时间戳），后续请求如果窗口未滑动出已压缩范围则直接复用，避免重复 LLM 调用。Prompt 版本 bump 后旧压缩自动失效。

**配置项**（`wgh.rag.context`）：

| 参数 | 默认 | 说明 |
|------|------|------|
| `max-history-messages` | 20 | 超过此条数触发分层压缩 |
| `max-history-tokens` | 4000 | 单轮输入 token 上限 |
| `enable-compression` | true | 总开关 |
| `recent-window-rounds` | 4 | 近期窗口（轮，保留原文） |
| `mid-term-window-rounds` | 6 | 中期窗口（轮，LLM 摘要） |
| `long-term-facts-tokens` | 400 | 远期对话 Token 预算（关键事实） |
| `summary-max-tokens` | 600 | LLM 摘要最大输出 Token |
| `summary-timeout-seconds` | 20 | 摘要 LLM 调用超时（秒） |

### 5. RAG 评估双轨

| 轨道 | 指标 | 说明 |
|------|------|------|
| 检索指标（METRICS） | MRR、NDCG@K、Hit Rate、Recall | 需要 ground truth（相关文档列表） |
| LLM-as-Judge | Faithfulness、Answer Relevancy | 用独立 LLM 对生成答案打分，不需要 ground truth |

- 评估结果实时写入 `wgh_chat_message`
- 对话窗口可配置评估方案（METRICS / LLM_JUDGE / BOTH / NONE）和 Judge 模型
- 未指定 Judge 时自动选择 2 个不同提供商的模型，且排除当前聊天使用的模型
- LLM-as-Judge 异步执行，不阻塞 SSE 流式响应

### 6. 会话与分享

- **会话管理**：每个窗口下支持多会话，左侧侧边栏滑动加载历史（按 update_time DESC，LIMIT 50），首条消息自动生成会话标题（取前 20 字），支持单会话删除（连带逻辑删所有消息）
- **分享链路**：窗口可开启 / 关闭分享，重新生成分享 token 旧链接立即失效；分享页要求登录（JWT），`userIdentifier` 取当前用户名，多用户共用一个分享链接时各自看到独立历史
- **会话隔离**：管理端按 `default_user`、分享端按登录用户名隔离 `userIdentifier`，跨身份读不到对方会话

## 快速开始

### 1. 启动 PostgreSQL + pgvector

```bash
docker run -d --name postgres-with-vector \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=wgh_rag \
  -p 5432:5432 \
  pgvector/pgvector:pg16

# 初始化（会创建全部 8 张表 + 种子数据）
docker exec -i postgres-with-vector psql -U postgres -d wgh_rag < rag-backend/sql/init.sql

# 已存在库需要执行的增量 migration（按日期顺序）
docker exec -i postgres-with-vector psql -U postgres -d wgh_rag < rag-backend/sql/migration_20261023_chat_eval.sql
docker exec -i postgres-with-vector psql -U postgres -d wgh_rag < rag-backend/sql/migration_20261023_context_compression_persist.sql
docker exec -i postgres-with-vector psql -U postgres -d wgh_rag < rag-backend/sql/migration_20261027_tool_skill_executor_config.sql
docker exec -i postgres-with-vector psql -U postgres -d wgh_rag < rag-backend/sql/migration_20261027_tool_types_executor_config.sql
```

### 2. 后端

```bash
cd rag-backend
# 注意：多模块项目必须用 -am 才会使用本仓库代码，否则会读 ~/.m2 里的旧 jar
mvn install -pl rag-app -am -DskipTests
mvn spring-boot:run -pl rag-app
# 默认 http://localhost:8080 / 默认账号 admin / admin
```

### 3. 前端

```bash
cd rag-frontend
npm install
npm run dev   # http://localhost:5173
```

### 4. 配置 LLM 模型

登录后进入 **模型管理** 页面，添加至少一个：
- **Embedding 模型**：用于向量化（如 text-embedding-3-small / text-embedding-v3）
- **Chat 模型**：用于对话生成
- 可选标记 `compression_model=true`：独立的压缩专用模型，避开对话模型计费

**注意**：
- DASHSCOPE 提供商使用 OpenAI 兼容端点时需把 provider 改为 `CUSTOM_OPENAI`（SDK 路径拼接兼容问题）
- 通义 / DeepSeek 在 LangChain4j 1.19.0 的 `community` 包下，不在核心包

### 5. 体验工具调用

打开 **聊天窗口管理** → 编辑窗口 → 关联工具（按 SKILL / MCP / HTTP / Function Calling 分组显示）→ 保存 → 调试对话，发送一条会触发工具的提问（例如「查一下我的订单」「调用 echo 说 hi」「现在系统健康状况怎么样」），即可看到 `tool_call_start` / `tool_call_result` SSE 事件。

## 关键 API

### 鉴权
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/login` | 登录返回 JWT |

### 知识库
| 方法 | 路径 | 说明 |
|------|------|------|
| GET  | `/api/kb` | 知识库列表 |
| POST | `/api/kb/{id}/files` | 上传文件（触发解析+切分+向量化） |
| GET  | `/api/kb/{id}/search?query=xxx` | 知识库检索测试（含召回率/命中率） |

### 对话
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/chat/windows/{id}/stream` | SSE 流式 RAG 对话（事件：start / retrieve / content / sources / accuracy / tool_call_start / tool_call_result / done） |
| GET  | `/api/chat/windows/{id}/sessions` | 当前用户在窗口下的会话列表 |
| POST | `/api/chat/windows/{id}/sessions` | 显式新建会话 |
| DELETE | `/api/chat/windows/{id}/sessions/{sessionId}` | 删除会话（连带逻辑删消息） |
| GET  | `/api/chat/sessions/{sessionId}/messages` | 分页拉取会话历史消息 |

### 分享
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/chat/windows/{id}/share/enable` | 开启分享返回 token |
| POST | `/api/chat/windows/{id}/share/disable` | 关闭分享 |
| POST | `/api/chat/windows/{id}/share/regenerate` | 重新生成分享 token（旧链接失效，历史会话保留） |
| GET  | `/api/chat/share/{token}/window` | 通过 token 获取窗口信息（需登录） |
| POST | `/api/chat/share/{token}/stream` | 分享侧 SSE 对话 |
| GET  | `/api/chat/share/{token}/sessions` | 当前登录用户在分享窗口下的会话列表 |

### 工具
| 方法 | 路径 | 说明 |
|------|------|------|
| GET  | `/api/chat/tools/page` | 工具分页（支持 toolType 过滤） |
| POST | `/api/chat/tools` | 新增工具 |
| PUT  | `/api/chat/tools/{id}` | 更新工具 |
| DELETE | `/api/chat/tools/{id}` | 逻辑删除工具 |
| POST | `/api/chat/tools/{id}/debug` | 在线调试工具（按 definition 解析参数） |
| GET  | `/api/chat/tools/skill/list-dirs` | 扫描磁盘 skill 目录 |
| POST | `/api/chat/tools/skill/import` | 导入磁盘 skill 到数据库（同名时跳过并返回 duplicated=true） |
| POST | `/api/chat/tools/skill/save` | 保存 skill 内容（更新 front matter：subModelId / description） |

### 模型
| 方法 | 路径 | 说明 |
|------|------|------|
| GET  | `/api/models/chat` | Chat 模型列表 |
| GET  | `/api/models/embedding` | Embedding 模型列表 |

## 环境变量

| 变量 | 默认 | 说明 |
|------|------|------|
| `WGH_PROFILE` | dev | Spring Profile（dev / test / prod） |
| `WGH_DB_HOST` | localhost | PostgreSQL 主机 |
| `WGH_DB_PORT` | 5432 | PostgreSQL 端口 |
| `WGH_DB_NAME` | wgh_rag | 数据库名 |
| `WGH_DB_USERNAME` | postgres | DB 用户 |
| `WGH_DB_PASSWORD` | postgres | DB 密码 |
| `WGH_JWT_SECRET` | (内置) | JWT 签名密钥（生产必须改为环境变量注入） |
| `WGH_UPLOAD_DIR` | ./uploads | 文件上传目录 |
| `WGH_SKILL_BASE_PATH` | ./skills | Skill 磁盘目录基础路径 |
| `OPENAI_API_KEY` | demo | OpenAI 兼容接口 Key |
| `OPENAI_BASE_URL` | https://api.openai.com/v1 | OpenAI 兼容接口地址 |
| `DASHSCOPE_API_KEY` | - | 通义千问 API Key |
| `DEEPSEEK_API_KEY` | - | DeepSeek API Key |

## 目录约定

| 路径 | 说明 |
|------|------|
| `rag-backend/sql/` | init + 增量 migration，按 `migration_YYYYMMDD_<topic>.sql` 命名 |
| `rag-backend/rag-app/skills/` | Skill 磁盘目录（由 `wgh.skill.base-path` 解析而来） |
| `rag-backend/uploads/` | 按知识库 ID 分子目录 |
| `rag-backend/logs/` | 运行日志（SizeAndTimeBasedRollingPolicy） |
| `rag-backend/eval/` | RAG 评估脚本和报告 |

## 工程约定（重要）

- **多模块编译**：`mvn spring-boot:run -pl rag-app` 必须加 `-am`，否则会读 `~/.m2` 旧 jar
- **测试命名**：surefire excludes `**/*IntegrationTest.java`，集成测试用 `*ApiTest` / `*EngineTest` / `*Test` 命名
- **Java 25 + Mockito**：`-Dnet.bytebuddy.experimental=true`
- **逻辑删除**：所有删除操作走 `deleted` 字段，物理删除需明确需求
- **变量遮蔽**：前端避免使用 `window`、`location` 等全局变量名作 ref 名（已在 `ChatPlayground.vue` 中踩过坑，访问全局对象请用 `globalThis.xxx`）
- **SSE 换行**：`content` 事件必须用 JSON 包装传输文本，避免换行符丢失
- **pgvector 配置**：`PgVectorEmbeddingStore` 不要显式配置空的 metadata，使用默认 COMBINED_JSON 模式

## 功能完成清单

### 后端模块

| 模块 | 状态 | 说明 |
|------|------|------|
| rag-common（Result/JWT/异常/MyBatisPlus/Security） | ✅ 完成 | 通用基础设施，全局可用 |
| rag-config（RagProperties + PgVectorProperties） | ✅ 完成 | 多 profile 配置映射 |
| rag-llm（模型管理 + 多厂商工厂 + 缓存） | ✅ 完成 | DB 动态配置 + DASHSCOPE / DEEPSEEK / CUSTOM_OPENAI 兼容 + ModelCacheManager 热更新 |
| rag-kb（知识库 CRUD + 上传 + 解析 + 切分 + 向量化 + 检索） | ✅ 完成 | PDF/DOCX/DOC/XLSX/XLS/CSV/TXT/MD 解析，7 种切分策略，pgvector 向量存储 |
| rag-chat（会话/窗口 + 分层上下文压缩 + 流式 RAG + 评估 + 分享） | ✅ 完成 | 三层压缩含持久化复用；MRR/NDCG + LLM-as-Judge 双轨评估；会话隔离 + 分享 token |
| rag-controller（REST API 层） | ✅ 完成 | Auth / Chat / KnowledgeBase / LlmModel / 分享 / 工具调试 全覆盖 |
| rag-tool（工具框架 + 4 类执行器 + Skill 文件服务） | ✅ 完成 | ToolExecutor SPI + HTTP/MCP/SKILL/FUNCTION_CALLING + 工具调试 + 目录扫描 + 同名判重 |
| Agent（自动规划） | 📋 计划中 | 当前为单次 Tool Calling 循环（最多 5 轮），未引入复杂规划 |

### 前端页面

| 页面 | 状态 | 说明 |
|------|------|------|
| 登录 Login | ✅ 完成 | JWT 存储 + 路由守卫 + redirect 回跳 |
| 首页 | ✅ 完成 | 系统概览 |
| 知识库管理 | ✅ 完成 | 列表 + 新建 + 文件上传 + 处理状态 |
| 知识库搜索测试 | ✅ 完成 | 实时向量检索 + 召回/命中率指标 + 结果展示 |
| 对话 Playground | ✅ 完成 | SSE 流式 + 来源展示 + 三率指标 + 工具调用事件 + 历史会话侧边栏 + 新建/删除会话 + 复制分享链接 |
| 对话窗口管理 | ✅ 完成 | 评估方案 / Judge 模型 / 知识库绑定 / 工具按类型分组选择 / 分享开关联复制+重新生成 |
| 分享对话 ShareChat | ✅ 完成 | 登录后访问 / 历史会话侧边栏 / 新建/删除会话 / 与 Playground 渲染一致 |
| Chat 模型配置 | ✅ 完成 | 多厂商动态添加 + 启用/禁用 |
| Embedding 模型配置 | ✅ 完成 | 默认模型唯一约束 + 多厂商 |
| 工具管理 | ✅ 完成 | 5 个 tab（全部 / Skill / MCP / HTTP / Function Calling）+ SKILL 目录合并列表 + 在线编辑 + 任意工具调试弹窗 |

### 知识库文件格式

| 格式 | 状态 | 解析方式 |
|------|------|----------|
| PDF | ✅ 完成 | PDFBox，按双换行分段；段落级抽取 |
| DOCX (Office Open XML) | ✅ 完成 | POI XWPF，段落 + 表格均抽取（`row1col1 \| row1col2` 格式） |
| DOC (旧版 Word) | ✅ 完成 | POI HWPF + WordExtractor |
| XLSX / XLS Excel | ✅ 完成 | POI XSSF/HSSF，逐行逐格合并为 `cell1 \| cell2 \| ...` 格式 |
| CSV | ✅ 完成 | BufferedReader 逐行读取 |
| TXT / MD | ✅ 完成 | `Files.readString`，按双换行分段 |
| HTML | 📋 计划中 | 引入 Jsoup 剥离标签，保留 `<h1-6>` 作为章节锚点 |
| JSON | 📋 计划中 | 递归展平 key-path:value，支持 array 逐项展开 |
| PPTX / PPT | 📋 计划中 | POI XSLF，按 slide 一页一个 chunk |
| 图片（PNG/JPG） | 📋 计划中 | 接入 OCR（PaddleOCR / Tesseract）识别后入库 |

### 文档分割策略

| 策略 | 状态 | 说明 |
|------|------|------|
| **AUTO**（自动路由） | ✅ 完成 | 按内容特征检测：FAQ 密度 >15% → FAQ；章节标题占比 >10% → CHAPTER；默认 → RECURSIVE |
| **RECURSIVE**（递归字符分块） | ✅ 完成 | 分隔符层级 `\n\n → \n → 。！？； → , → ` 逐级降级切割；相邻 chunk overlap 重叠 |
| **CHAPTER**（按章节） | ✅ 完成 | 识别 `1. / 1.1 / # ## / 第X章` 三种章节标题；识别失败回退 PARAGRAPH |
| **PARAGRAPH**（按段落滑窗） | ✅ 完成 | 段落为单位滑窗合并，超长段落内部按 LENGTH 切 |
| **FAQ**（问答对） | ✅ 完成 | 识别 `问N：...答：...` 模式，一问一答一个 chunk；识别失败回退 RECURSIVE |
| **LENGTH**（固定长度） | ✅ 完成 | 句子边界优先切分 + overlap；带死循环保护 |
| **CUSTOM**（自定义正则） | ⚠️ 占位 | 接口已预留（通过 `split_config` JSON 传正则），当前 fallback 到 PARAGRAPH |
| **SEMANTIC**（语义切分） | 📋 计划中 | 基于嵌入向量相似度的句子聚类切分（需要额外 embedding 开销） |
| **STRUCTURE_AWARE**（结构化切分） | 📋 计划中 | 对 Excel/CSV 按行/按列切，对 JSON 按 key-path 切，保留数据结构 |

### 数据层

| 项目 | 状态 | 说明 |
|------|------|------|
| SQL init（10 张表） | ✅ 完成 | 含 wgh_chat_session / wgh_chat_window / wgh_tool / wgh_llm_model / wgh_embedding_model / wgh_knowledge_base / wgh_knowledge_file / wgh_chat_message / wgh_kb_* |
| pgvector 向量扩展 | ✅ 完成 | 自动建表 + 可选 IVF 索引 |
| Migration（评估 + 压缩持久化 + Skill 迁移 + 工具类型） | ✅ 完成 | 增量脚本按日期命名，幂等可重复执行 |

## 系统截图

`rag-frontend/image/` 目录下：

- 首页 / 模型管理 / 嵌入模型 / 聊天窗口管理 / 创建聊天窗口 / 调试聊天窗口 / 分享聊天窗口 / 工具管理

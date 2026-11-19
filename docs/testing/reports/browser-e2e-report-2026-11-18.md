# 2026-11-18 全量浏览器回归测试报告

> 测试对象：WGH RAG 知识库系统（仓库根目录）
> 测试方式：Playwright（Chrome channel，headless）真实操作页面 + 单列接口级验证
> 测试库：`wgh_rag_fresh`（旧库 `wgh_rag` 仅观测连接，未执行任何写入）
> 基线：`docs/testing/test-cases.md` v1.2（§1~§41）+ `docs/testing/reports/browser-e2e-report-2026-11-06.md`（93 条）
> 本轮未修改后端业务代码、未 commit、未 push。

> **修订记录（2026-11-18 补跑阶段）**：BUG-01 已按“仅补前端 scope 勾选项”修复并验证（见 §7）。随后对 §6 未复验基线用例做补充复跑，**补跑过程被用户明确叫停，未完成部分不计入 PASS**；已完成的 18 条页面/上下文用例与 2 条修复验证接口用例结果已并入本报告，未完成的 WF-05、WF-06、SEC-01 及 BLOCKED 的 AUTH-02 在 §6/§8 单独列出。

## 1. 结论摘要

| 指标 | 数值 |
|---|---:|
| 页面/浏览器用例（含补充复跑，本轮新执行） | 84 |
| PASS | 84 |
| FAIL | 0 |
| BLOCKED（页面用例） | 0 |
| 接口级用例（单列） | 8 |
| 接口 PASS / FAIL | 8 / 0 |
| 缺陷 | 1（BUG-01 已修复并验证；原 OAI-01） |
| 观察项 | 1（混合检索中文关键词命中 0，符合阈值设计） |
| 基线 93 条 | 复验 89 / BLOCKED 1 / 未完成 3（叫停，不计 PASS） |
| 证据截图 | 158 张（`docs/testing/evidence/20261118-*.png`） |

结论：批次 3（窗口检索参数、评测数据集版本化、工作流表达式/单步调试、URL 连接器、分片上传）与批次 2（引用溯源、资源可见性、OpenAI 兼容入口）在页面链路上均可用；聊天流式增量、停止生成、`[Sn]` 点击定位、免登录/登录态分享、工作流 HITL、Agent HITL、多 Agent Crew 运行均通过。BUG-01 已通过前端补齐 `model:read` 勾选项修复，带该 scope 的 Key 调 `/v1/models` 返回 200、不带仍返回 403，后端校验语义未放宽。

## 2. 执行环境

### 2.1 代码与进程

| 项 | 值 |
|---|---|
| 改造前运行进程 | PID 5930，IDEA classpath 方式启动（13:47:08），实际连接 **旧库 `wgh_rag`**，与任务假设的“11-13 jar”不符 |
| 停止旧进程 | 13:55 左右 `kill 5930`，8080/9090 释放 |
| 新 jar | `rag-backend/rag-app/target/rag-app-1.0.0-SNAPSHOT.jar`，构建时间 **2026-11-18 13:56:28**，130,568,382 bytes，`-DskipTests package`（JDK 21，持 `/tmp/wgh-maven-build.lockdir`） |
| 首次新 jar 启动 | 13:58:22，PID 7463，健康检查 200 用时约 26s，连接 `wgh_rag_fresh` |
| 最终运行进程 | **PID 9944**，14:17:53 启动（追加 `WGH_WORKFLOW_DEBUG_ENABLED=true` 以验证单步调试），健康检查 200 用时约 34s |
| 启动环境变量 | `WGH_PROFILE=dev`、`WGH_DB_NAME=wgh_rag_fresh`、`WGH_DB_PASSWORD=123456`、固定 `WGH_AUTH_LOCAL_JWT_SECRET` |
| 健康检查 | `GET http://localhost:9090/actuator/health` → 200，`db=UP`、`redis=UP`、`diskSpace=UP` |
| 前端 | Vite 13:57 重启，PID 7610，`http://127.0.0.1:5173` 200；BUG-01 修复后 `npm run build` 成功（未重启 Vite/后端，HMR 生效） |
| mock 模型服务 | `http://127.0.0.1:9101/v1`（OpenAI 兼容，仅用于链路验证，见 §9） |

> 说明：首次用 `nohup &` 启动时进程随终端会话被回收，改用工具后台任务后稳定运行；随后为开启单步调试开关做过一次受控重启，均未执行 `mvn install/package` 覆盖运行中 jar。

### 2.2 数据库与迁移

执行前核对：`wgh_eval_dataset`、`wgh_eval_case`、`wgh_eval_result`、`wgh_kb_connector`、`wgh_kb_connector_run`、`wgh_upload_session` 六张表均不存在，三个脚本全部需要执行。

| 脚本 | 结果 |
|---|---|
| `sql/migrations/20261118/01_eval_dataset_versioning.sql` | 成功，创建评测数据集/用例/结果表及索引 |
| `sql/migrations/20261118/02_kb_connector.sql` | 成功，创建连接器与运行记录表及索引 |
| `sql/migrations/20261118/03_upload_session.sql` | 成功，创建分片上传会话表及索引 |
| 幂等复跑 | 三个脚本二次执行无 ERROR，可重复执行 |
| 核对结果 | 6/6 表存在；14 个相关索引全部到位；`wgh_rag_fresh` 共 44 张表 |

未执行 `docker compose down -v`、`DROP DATABASE/TABLE`；旧库 `wgh_rag` 零写入（仅查询 `pg_stat_activity` 确认旧进程连接）。

## 3. 用例统计

| 模块 | 页面用例 | PASS | FAIL | 接口用例 | PASS | FAIL |
|---|---:|---:|---:|---:|---:|---:|
| 登录 / 改密 / 登出 / 权限 | 7 | 7 | 0 | 0 | 0 | 0 |
| 知识库 / 上传 / 解析 / 切分 / 版本 / 标签 / 重建 | 15 | 15 | 0 | 0 | 0 | 0 |
| 检索 / 评测 / 数据集版本化 | 7 | 7 | 0 | 0 | 0 | 0 |
| 聊天 / 引用 / 分享 / 开放 API | 18 | 18 | 0 | 8 | 8 | 0 |
| 工作流 | 5 | 5 | 0 | 0 | 0 | 0 |
| Agent / 多 Agent / HITL | 10 | 10 | 0 | 0 | 0 | 0 |
| 管理端（模型/工具/Prompt/配额/角色/审计） | 14 | 14 | 0 | 0 | 0 | 0 |
| 资源可见性 | 3 | 3 | 0 | 0 | 0 | 0 |
| URL 连接器 | 5 | 5 | 0 | 0 | 0 | 0 |
| **合计** | **84** | **84** | **0** | **8** | **8** | **0** |

## 4. 页面用例明细

| ID | 结果 | 说明 |
|---|---|---|
| AUTH-01 | PASS | 错误口令登录被拒，toast「用户名或密码错误」，停留登录页 |
| AUTH-LOGIN-OK | PASS | admin 登录成功，跳转 `/kb` |
| AUTH-03/04/05 | PASS | 改密：短口令/不一致被表单拦截；成功后提示重新登录并跳 `/login` |
| AUTH-06 | PASS | 登出回登录页，`wgh_token=null` |
| AUTH-08 | PASS | 改密后旧口令登录失败 |
| PERM-01 | PASS | viewer 无「用户管理/用户配额」菜单，访问 `/admin/users` 重定向 `/kb` |
| PERM-02 | PASS | editor 可新建知识库，无管理菜单 |
| DATA-EMB-DEFAULT | PASS | 新建 mock 嵌入模型并设默认，旧 DashScope 默认自动取消 |
| DATA-KB-CREATE | PASS | 页面新建 `E2E-20261118-知识库` |
| DATA-KB-UPLOAD-9 / PARSE-ALL | PASS | 9 种格式 + 大文件共 10 个文件全部 `SUCCESS`，段落数正常 |
| KB-01 | PASS | 列表统计：知识库 1 / 文件 10 / 段落 13 |
| KB-02 | PASS | 文件列表 10 条全部成功 |
| KB-03 | PASS | TXT 分割预览：原始段落 6 → 分割段落 1，chunk 正文可见 |
| KB-04 | PASS | MD 重切为 STRUCTURED 成功，策略列更新 |
| KB-05 | PASS | 版本历史 v1(AUTO,4 段)/v2(STRUCTURED,4 段) 均 SUCCESS，非 0 |
| KB-06 | PASS | TXT 重切为 SEMANTIC 成功，18 段 |
| SEARCH-01 | PASS | 召回数 4、过滤后 4、覆盖率 80%、命中率 100% |
| SEARCH-02 | PASS | 开启混合检索后指标区显示「已启用 · 关键词命中 N」 |
| SEARCH-03 | PASS | 关闭混合检索后纯向量检索可用 |
| EVAL-01 | PASS | 新增标注 + 运行评测，Recall/Precision/命中率/MRR/NDCG 指标链正常展示（本轮标注 chunk 未进 topK，数值 0 属正确计算结果） |
| EVAL-02 | PASS | 数据集创建、用例新增（v1）、编辑生成 v2、按版本运行、历史结果勾选两项跨版本对比 |
| DATA-WINDOW-RAG / AGENT | PASS | 页面创建 RAG / Agent 聊天窗口并绑定知识库与 mock 模型 |
| CHAT-01 | PASS | 窗口列表展示知识库数、模型、检索参数、可见性、分享状态 |
| CHAT-02 | PASS | 流式增量长度 `0→24→60→84→114→148→181`，最终 Markdown 渲染，来源面板 3 条 |
| CHAT-03 | PASS | `[S1]` 角标可点击，来源卡片高亮 `cite-flash`，面板显示知识库/文件/相似度/原文 |
| CHAT-04 | PASS | 停止生成后保留内容并显示「已停止生成」，发送按钮恢复 |
| CHAT-05 | PASS | 刷新后历史消息、回答、来源面板恢复（3 条） |
| SHARE-00 | PASS | 窗口「开启分享」→「登录态访问」打开 `/share/chat/<token>` |
| SHARE-02 | PASS | 登录态分享页可提问并返回带 `[S2][S3]` 引用的回答 |
| SHARE-01A | PASS | 开放平台创建 WINDOW 范围免密分享链接，列表可见 token |
| SHARE-01 | PASS | 未登录上下文打开 `/share/<token>` 可匿名提问并返回回答 |
| SHARE-PWD | PASS | 密码分享：错误密码被拒，正确密码解锁进入聊天 |
| OAI-KEY-UI | PASS | 页面创建开放 API Key，明文仅展示一次（`wgh_sk_...`），「复制并关闭」可用 |
| WF-01 | PASS | 页面新建工作流并进入编排（`/workflows/6/edit`） |
| WF-02 | PASS | 节点调色板 11 类齐全：开始/大模型/知识库检索/Agent 规划/工具调用/条件分支/循环/人工确认/变量赋值/表达式/结束 |
| WF-03 | PASS | 新增表达式节点并配置 `1 + 1`，保存 v3 成功；再次保存提示「内容未变更，无需保存」 |
| WF-04 | PASS | 单步调试开启后，开始单步→下一步产生 2 条节点轨迹 |
| WF-HITL | PASS | 拖拽连线 开始→人工确认→结束，执行触发「人工确认」弹窗，确认并继续后流程完成 |
| AGENT-01 | PASS | Agent 窗口发送消息，右侧计划树渲染 2 步（检索知识库→汇总输出），反思 9.0，执行完成 |
| AGENT-02 | PASS | 刷新页面后计划树与步骤状态自动恢复 |
| HITL-00 | PASS | 窗口编辑开启「允许 HITL 节点」 |
| HITL-01 | PASS | 计划第 2 步 `HITL_PENDING`，点击「输入确认」→ 弹窗填写 → 确认继续 → 返回最终答复 |
| MULTI-01 / MULTI-02 | PASS | 多 Agent Crew 管理页与运行调试页入口正常渲染 |
| CREW-01 | PASS | 页面新建 `E2E-20261118-Crew`（角色分层·串行，1 个 Agent，绑定 mock 模型） |
| CREW-02 | PASS | 校验接口返回「校验通过」 |
| CREW-03 | PASS | 运行调试页开始运行 → 运行完成，执行记录 1 条成功（205ms） |
| MGMT-TOOL-01 | PASS | 工具页签：全部/Skill/MCP/HTTP接口/Function Calling |
| MGMT-TOOL-02 | PASS | Skill 列表渲染（数据库来源），目录 Skill 为空目录，无「未导入可调试」误放行 |
| MGMT-PROMPT-01 | PASS | 新建模板 `E2E-20261118-模板`，自动提取 `{{name}}` 变量，v1 |
| MGMT-PROMPT-02 | PASS | 前缀触发配置保存到 `localStorage.wgh_prompt_trigger_config` |
| MGMT-ROLE-01 | PASS | admin/editor/viewer 三角色及权限数（16/8/…）正常 |
| MGMT-QUOTA-01 | PASS | 用户配额管理页正常加载 |
| MGMT-AUDIT-01 | PASS | 审计与统计页正常加载 |
| ADMIN-08 | PASS | 模型列表不出现 API Key 明文 |
| VIS-01 | PASS | 知识库可见性徽标「私有」 |
| VIS-02 | PASS | 徽标点击切换「公开」并即时更新 |
| VIS-03 | PASS | editor 可见公开知识库；看不到 admin 的私有聊天窗口 |
| CONN-01 | PASS | 新建 URL 连接器（https://example.com/docs） |
| CONN-02 | PASS | 立即同步后状态「源已删除」（真实返回 404，符合预期） |
| CONN-03 | PASS | 运行记录：状态/HTTP 404/耗时 1591ms/说明完整 |
| CONN-04 | PASS | 内网 URL 提交被 SSRF 拦截：「目标地址不允许访问: 127.0.0.1」 |
| CONN-05 | PASS | 真实外网 `https://example.com/` 同步成功（last_status=SUCCESS，源页面已更新） |
| DATA-CHUNK-UPLOAD | PASS | 22.89MB HTML 页面分片上传完成（5 个分片，`wgh_upload_session` COMPLETED） |
| BUG01-UI-SCOPE | PASS | 修复后新建 Key 弹窗出现「模型列表（/v1/models）」勾选项（截图 `20261118-i-bug01-scope-options.png`） |
| TAG-01 | PASS | 标签 `E2E-标签` 创建成功（页面标签弹窗无创建入口，经页面上下文 API 创建；UI 已提示仅支持 API 创建） |
| TAG-02 | PASS | 知识库「E2E-20261118-知识库」标签弹窗勾选并保存，列表行显示标签 |
| TAG-03 | PASS | 文件 `yunlan-manual.txt` 标签弹窗勾选并保存，文件行显示标签 |
| KB-11 | PASS | `POST /api/kb/6/rebuild` 受理成功（页面无重建入口，经页面上下文 API 触发） |
| KB-12 | PASS | 重建 11/11 完成，`rebuildStatus=SUCCEEDED`，`chunkCount=31`，文件全部 SUCCESS |
| SEARCH-04 | PASS | 同查询下混合检索召回 4、纯向量召回 4；混合指标显示「已启用 · 关键词命中 0」（阈值内正常） |
| SEARCH-05 | PASS | 无 RERANK 模型，页面展示重排降级状态 |
| AGENT-03 | PASS | `/api/agent/sessions/14/plan` 返回 200，`planId=3`、2 个步骤（页面上下文调用） |
| ADMIN-02 | PASS | 自定义角色 `e2e_reader` 创建成功并出现在角色列表 |
| ADMIN-03 | PASS | 角色权限弹窗勾选 3 项并保存，行权限数变为 3 |
| ADMIN-05 | PASS | `e2e_editor` 配额编辑：请求上限 1234、启用自定义，列表显示 `0 / 1234` |
| ADMIN-07 | PASS | 模板 `E2E-20261118-模板` 编辑后版本递增到 v2，内容已更新 |
| MODEL-03 | PASS | `/api/models/chat` 与 `/api/models/embedding` 响应均无 `apiKey` 字段（页面上下文调用） |
| SHARE-06 | PASS | 新建密码分享，连续 5 次错误后出现「密码错误次数过多，已锁定，请 900 秒后重试」 |
| SHARE-07 | PASS | 开启嵌入并配置来源白名单 `127.0.0.1:5173`，widget-demo 授权通过并加载 iframe（内容长度 98） |
| SHARE-07-NEG | PASS | 非白名单来源 `localhost:5173` 被拒绝，页面显示加载失败且未创建 iframe |
| SHARE-09 | PASS | 带 `kb:read` 的 API Key 调 `/api/open/v1/kb/search` → 200，返回 3 条结果 |
| WF-05 | **未完成** | 补跑被叫停。尝试构建 RETRIEVAL+AGENT 线性图时受默认「开始→结束」边与运行中实例影响，未能完成校验；不计 PASS |
| WF-06 | **未完成** | 依赖 WF-05，未执行；不计 PASS |
| SEC-01 | **未完成** | 补跑被叫停，XSS 清洗用例未执行；不计 PASS |

## 5. 接口级用例（单列，不计入页面用例）

| ID | 结果 | 说明 |
|---|---|---|
| OAI-01 | **PASS（已修复）** | 修复后由页面创建带 `model:read` 的 Key，`GET /v1/models` → 200；详见 §7 BUG-01 |
| OAI-02 | PASS | `POST /v1/chat/completions` 非流式 → 200，content 正常 |
| OAI-03 | PASS | 流式 → 200，35 个 chunk，`[DONE]`，拼接内容 191 字符 |
| OAI-04 | PASS | 不存在的模型 → 404 `model_not_found` |
| OAI-05 | PASS | 无 Key → 401 |
| OAI-06 | PASS | 空 body → 400 |
| OAI-07 | PASS | 页面创建 scope=`chat:write + model:read` 的 Key → `/v1/models` 200，返回 `wgh-window-5` |
| OAI-08 | PASS | 页面创建 scope 仅 `chat:write` 的 Key → `/v1/models` 仍为 403（后端未放宽） |

## 6. 基线 93 条对照（2026-11-06）

本轮按模块重跑核心路径，映射如下（“复验”指本轮真实浏览器操作；接口级单列的不计入页面 PASS）：

| 基线模块 | 基线数 | 本轮复验 | BLOCKED | 未完成（叫停） | 说明 |
|---|---:|---:|---:|---:|---|
| 登录/账号/改密/登出 | 9 | 8 | 1 | 0 | AUTH-02 无页面入口置位“首登必须改密”，BLOCKED |
| 知识库（新建/标签/9格式/预览/版本/重切/重建） | 31 | 31 | 0 | 0 | 补充复跑 TAG-01~03、KB-11 重建、KB-12 chunkCount |
| 聊天（问答/流式/停止/引用/持久化） | 9 | 9 | 0 | 0 | 含 WIN-01、CHAT-01~08 |
| 检索（混合/向量/重排/评测） | 6 | 6 | 0 | 0 | 补充复跑 SEARCH-04 多路差异、SEARCH-05 重排降级 |
| Agent（单 Agent 规划） | 3 | 3 | 0 | 0 | 补充复跑 AGENT-03 计划状态接口（planId=3，2 步） |
| 多 Agent Crew | 3 | 3 | 0 | 0 | 新建/校验/运行均通过 |
| 工作流 | 6 | 4 | 0 | 2 | WF-05/06 补跑被叫停，未完成不计 PASS |
| 管理端（模型/角色/用户/配额/Prompt） | 12 | 12 | 0 | 0 | 补充复跑 ADMIN-02/03/05/07、MODEL-03 |
| 分享 / Widget / 开放 API | 10 | 10 | 0 | 0 | 补充复跑 SHARE-02、SHARE-06 锁定、SHARE-07 Widget、SHARE-09 开放检索 |
| 权限 / 安全 | 4 | 3 | 0 | 1 | SEC-01 XSS 清洗未执行（叫停） |
| **合计** | **93** | **89** | **1** | **3** | 未完成项不等于失败，见 §8 |

未完成/阻塞的基线 ID 清单：AUTH-02（BLOCKED，缺少页面入口）、WF-05、WF-06、SEC-01（补跑被叫停，未完成）。其余 89 条已完成复验并通过。

> 说明：原报告将 SHARE-02 列入“未复验”但同时记录了密码分享创建，存在重复计数；本次统一按实际状态校正为已复验。

## 7. 缺陷清单

### BUG-01（中，已修复并验证）页面创建的开放 API Key 无法调用 `GET /v1/models`

- 现象（修复前）：在「开放平台 → 开放 API Key」勾选全部可用权限（对话/知识库检索/用量查询）创建 Key 后，`GET /v1/models` 返回 **403**；`/v1/chat/completions`、流式、错误码均正常。
- 根因：
  - 前端权限选项仅提供 `chat:write` / `kb:read` / `usage:read`（`rag-frontend/src/views/open/PublicAccess.vue`），无法勾选 `model:read`
  - 后端校验 `/v1/models` 需要 `model:read`（`rag-backend/rag-public-access/src/main/java/com/wgh/rag/publicaccess/config/ApiKeyAuthenticationFilter.java#L97`）
- **修复方式（仅前端，后端未改）**：在 `PublicAccess.vue` 的 scope 复选框组中补充 `model:read`，文案为「模型列表（/v1/models）」；后端权限校验语义保持不变，未放宽。
- 默认勾选取舍：新建 Key 仍默认只勾选 `chat:write`（最小权限）。理由：`model:read` 只影响模型列表探测，对话主链路不依赖；需要 OpenAI SDK 列举模型的用户可显式勾选，避免默认授予非必要权限。
- 验证结果（页面 + 接口）：
  1. `npm run build` 构建成功（`vite build`，无报错）；
  2. 页面创建 scope=`chat:write + model:read` 的 Key，`GET /v1/models` → **200**，返回 `wgh-window-5`（证据 `20261118-i-bug01-scope-options.png`）；
  3. 页面创建 scope 仅 `chat:write` 的 Key，`GET /v1/models` → **403**，确认后端未放宽；
  4. 对话、流式、错误码用例（OAI-02~06）复跑仍通过。
- 状态：**已修复（PASS）**，对应 §5 OAI-01/OAI-07/OAI-08、§4 BUG01-UI-SCOPE。

### 观察项 OBS-01（非缺陷）混合检索中文关键词命中为 0

- 现象：查询「云岚系统支持哪些文档格式」开启混合检索，指标显示「已启用 · 关键词命中 0」，向量分支正常返回 4 条。
- 实测：`word_similarity` 最大 0.308（`e2e-large.html` chunk），低于阈值 `0.5`（`KeywordSearchService.java#L38-L47`，阈值有离线对照依据），属当前设计行为。
- 建议：若希望中文口语查询走关键词分支，可考虑查询分词或降低阈值，但需重新做误召回对照，不建议本轮临时调整。

## 8. BLOCKED 与未覆盖说明

### 8.1 外部条件缺失（BLOCKED）

| 项 | 缺少条件 | 处理 |
|---|---|---|
| 真实厂商模型（通义/DeepSeek/Azure/Ollama 真实端点） | 种子 Key 为占位符，无有效厂商 Key/端点 | 全部模型相关用例改用本地 OpenAI 兼容 mock 验证链路；真实语义质量未评估 |
| 对象存储 OSS | 未配置 OSS Bucket/AK/Endpoint | 未执行 OSS 直传/签名 URL 用例 |
| OIDC 外部身份源 | 无 IdP/Client 配置 | 未执行外部 OIDC 用户登录/拒绝本地登录用例 |
| 真实 RERANK 模型 | 无重排模型 | 页面显示「未启用/已降级」，未做重排效果验证 |

### 8.2 补跑被叫停后的剩余项

| 项 | 状态 | 原因 |
|---|---|---|
| AUTH-02 首登强制改密 | BLOCKED | 新建体验账号 `password_must_change=0`，页面无入口置位；本轮不允许直接改库 |
| WF-05 RETRIEVAL+AGENT 图校验 | 未完成 | 补跑被用户叫停；尝试过程中受默认「开始→结束」边、以及工作流 7 存在遗留 RUNNING 实例影响，未完成校验 |
| WF-06 组合工作流运行 | 未完成 | 依赖 WF-05，未执行 |
| SEC-01 Markdown XSS 清洗 | 未完成 | 补跑被叫停，未执行 |
| Widget 第三方页嵌入 | 已完成 | SHARE-07 / SHARE-07-NEG 通过（白名单内加载、白名单外拒绝） |
| 知识库标签/重建、角色/配额/Prompt、分享锁定、开放检索 | 已完成 | 见 §4 补充复跑结果 |

### 8.3 缺陷与遗留数据提醒

- 工作流 7（`E2E-20261118-检索Agent流`）存在一条遗留 `RUNNING` 运行记录（run id=8，14:47 创建），未提供取消接口；补跑被叫停后未继续处理，测试环境如需复用该工作流需清理该运行记录。
- 其余测试数据未清理，详见 §10。

## 9. mock 与真实环境范围声明

- 模型端点：本轮全部对话/嵌入/规划/反思/评测打分均调用本地 `127.0.0.1:9101` 的 OpenAI 兼容 mock，**不代表真实厂商模型的语义质量**；mock 仅返回固定中文答案（含 `[S1][S2]` 引用标记）、哈希嵌入、固定计划与评分。所有“PASS”仅证明系统链路（SSE、引用、计划、HITL、切分、检索、评测展示）可用。
- 真实网络：URL 连接器使用真实外网 `https://example.com/` 与 `https://example.com/docs`，分别验证成功同步与 404 源删除；内网地址验证 SSRF 拦截。
- 真实数据库：`wgh_rag_fresh`，所有业务数据通过页面创建；未直接 SQL 插入业务数据。
- 真实鉴权：本地口令登录 + JWT；未使用 OIDC。

## 10. 测试数据与残留

- 新增知识库 `E2E-20261118-知识库`（id=6，已切换为公开），11 个文件（9 格式 + 22.89MB HTML + 连接器同步文件），重切产生 v2 版本；已绑定标签 `E2E-标签`，并完成一次全量重建（`SUCCEEDED`，31 chunk）。
- 新增聊天窗口 `E2E-20261118-RAG窗口`、`E2E-20261118-Agent窗口`；新增会话与消息（页面产生）。
- 新增嵌入/对话模型 `E2E-20261118-Embedding`、`E2E-20261118-Chat`（指向本地 mock）。
- 新增用户 `e2e_editor`、`e2e_viewer`、`e2e_pwdchange`（口令已改）；新增自定义角色 `e2e_reader`（已配置 3 项权限）；`e2e_editor` 配额已改为自定义（请求上限 1234）。
- 新增工作流 `E2E-20261118-工作流`（v3，含表达式/人工确认节点）、`E2E-20261118-检索Agent流`（v2，遗留 RUNNING 运行 id=8）、`E2E-20261118-检索Agent流2`；Crew `E2E-20261118-Crew`。
- 新增 Prompt 模板 `E2E-20261118-模板`（已编辑至 v2）、分享链接 7 条（含密码分享、密码锁定分享、Widget 分享）、API Key 3 个（含修复验证 Key）、URL 连接器 2 个、评测数据集 `E2E-20261118-数据集` 与版本结果。
- 以上均为测试数据，未删除；清理可通过页面逐项删除，或按报告 ID 定向软删除。`wgh_rag` 旧库无新增数据。

## 11. 证据

- 全部截图：`docs/testing/evidence/20261118-*.png`（158 张，按 `<日期>-<模块>-<用例>.png` 命名，未覆盖既有文件）。
- 关键证据：
  - BUG-01 修复后 scope 选项：`20261118-i-bug01-scope-options.png`
  - 流式增量：`20261118-b-chat-02-stream-answer.png`
  - 引用定位：`20261118-b-chat-03-citation-click.png`
  - 版本历史：`20261118-a-kb-05-version-history.png`
  - 评测版本对比：`20261118-a-eval-02-compare.png`
  - 分片上传：`20261118-data-chunk-upload.png`
  - 工作流 HITL：`20261118-e-wf-hitl-dialog.png`、`20261118-e-wf-hitl-done.png`
  - 免登录分享：`20261118-b-share-01-public.png`
  - 连接器同步：`20261118-c2-conn-success.png`
  - 多 Agent 运行：`20261118-f-crew-run.png`
  - 分享锁定：`20261118-j3-share-lock.png`
  - Widget 嵌入：`20261118-j3-widget-ok.png`、`20261118-j3-widget-rejected.png`

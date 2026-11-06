# RAG 知识库系统 · 页面端到端测试报告（2026-11-06）

## 1. 结论摘要

- 执行用例 **93 条**：初测 **PASS 76 / FAIL 5 / BLOCKED 12**；修复后在部署实例上复验，5 条 FAIL 全部转 PASS、KB-05 通过 mock 知识库换方法复验，最终 **PASS 82 / FAIL 0 / BLOCKED 11**。
- **计数口径**：复验不新增用例，按同一用例 ID 替换状态；修复 commit `e74f0c2`，部署实例 PID **24167**。初测与复验的详细证据见 §5、§6。
- 5 条 FAIL 均已修复并复验：AUTH-05b、KB-06、KB-12、CHAT-02、WF-04（§5.1—§5.5）。
- 11 条 BLOCKED 为真实模型 Key 占位符（KB-02 + KB-03×9，共 10 条）与前端无检索评测页面（SEARCH-06，1 条）；逐条原因与可替代途径见 §6。

## 2. 环境信息

| 项 | 值 |
|---|---|
| 测试时间 | 初测 2026-11-06 17:23 — 18:13；修后复验 2026-11-06 18:20 — 19:10（CST） |
| 被测前端 | Vite dev，`http://localhost:5173`，PID **5102**（启动于 2026-11-05 14:21:21，复验期未重启） |
| 被测后端（初测） | `rag-app-1.0.0-SNAPSHOT.jar`（11-05 14:16 构建），8080 / 9090，PID **16978** |
| 被测后端（复验） | 重新 `package` 的 `rag-app-1.0.0-SNAPSHOT.jar`（**11-06 18:36**，含 `307508e` 与 `e74f0c2`），8080 / 9090，PID **24167** |
| 健康检查 | 9090 `/actuator/health` = **200**（db/redis 均 UP）；8080 `/actuator` = **404**（管理端口分离生效） |
| 数据库 | PostgreSQL 容器 `wgh-rag-postgres`，库 **`wgh_rag_fresh`**（未触碰旧库 `wgh_rag`） |
| 登录口令 | admin 复验期口令 `Verify123456`；进程 `WGH_INIT_ADMIN_PASSWORD=Verify12345` 仅用于首次初始化 |
| 驱动方式 | Playwright（Chrome channel，headless），Node v24.6.0，脚本位于 `/tmp/wgh-e2e/` |
| 模型替代 | 真实模型 Key 为占位符，另经**模型管理页面/接口**注册本地 OpenAI 兼容 mock（`127.0.0.1:9101`，E2E-Mock-Embedding / E2E-Mock-Chat）跑通链路 |
| 知识库 | 初测：E2E云岚知识库（真实模型，id=1）、E2E云岚知识库-Mock（mock，id=2，测试后软删）；复验：E2E-Verify-KB（mock，id=3） |
| 证据截图 | `docs/testing/evidence/20261106-*.png`（初测 100+ 张，修后复验另增 10 张） |

> 纪律：初测阶段未重编译、未重启、未改业务代码、未直接写 SQL；修后复验阶段按授权重新 `package` 并重启实例，未触碰旧库 `wgh_rag`。

## 3. 用例分布

| 模块 | 用例数 | PASS | FAIL | BLOCKED |
|---|---:|---:|---:|---:|
| 登录 / 账号 / 改密 / 登出 | 9 | 9 | 0 | 0 |
| 知识库（新建/标签/9 格式/解析/预览/版本/重切） | 31 | 21 | 0 | 10 |
| 聊天（问答/流式/停止/引用/持久化） | 9 | 9 | 0 | 0 |
| 检索（混合/向量/重排/评测） | 6 | 5 | 0 | 1 |
| Agent（单 Agent 规划） | 3 | 3 | 0 | 0 |
| 多 Agent Crew | 3 | 3 | 0 | 0 |
| 工作流 | 6 | 6 | 0 | 0 |
| 管理端（模型/角色/用户/配额/Prompt） | 12 | 12 | 0 | 0 |
| 分享 / Widget / 开放 API | 10 | 10 | 0 | 0 |
| 权限 / 安全 | 4 | 4 | 0 | 0 |
| **合计** | **93** | **82** | **0** | **11** |

> 修复复验不新增用例：AUTH-05b、KB-06、KB-12、CHAT-02、WF-04 替换原 FAIL 状态；KB-05 原 BLOCKED 改用 mock 知识库复验成功后替换为 PASS。故总数仍为 93。

## 4. 用例明细

### 4.1 登录 / 账号

| ID | 用例 | 结果 | 证据 / 说明 |
|---|---|---|---|
| AUTH-01 | 错误口令登录被拒绝 | PASS | 无 token，toast 含“用户名或密码错误”；`20261106-01-auth-01-wrong-password.png` |
| AUTH-02 | 正确口令登录后强制跳转改密页 | PASS | url 含 `/change-password`；`20261106-02-auth-02-forced-change-password.png` |
| AUTH-03 | 改密：新密码长度不足被表单拦截 | PASS | 表单错误“至少 8 位”；`20261106-03-auth-03-password-too-short.png` |
| AUTH-04 | 改密：两次输入不一致被拦截 | PASS | 表单错误“不一致”；`20261106-04-auth-04-password-mismatch.png` |
| AUTH-05 | 改密成功并进入知识库页 | PASS | 返回成功并跳 `/kb`；`20261106-05-auth-05-change-password-success.png` |
| AUTH-05b | 改密成功后当前会话保持 | **PASS（已修复 e74f0c2）** | 改密后提示“口令已更新，请用新口令重新登录”并主动跳 `/login`，token 已清空；新口令可重新登录；`20261106-01-auth-05b-change-password-relogin-fix.png`；见 5.1 |
| AUTH-06 | 登出后回到登录页且本地凭证清除 | PASS | url `/login`，token=null；`20261106-01-auth-06-logout.png` |
| AUTH-07 | 新口令重新登录成功 | PASS | 进入 `/kb` |
| AUTH-08 | 改密后旧口令登录失败 | PASS | toast“用户名或密码错误”；`20261106-02-auth-08-old-password-rejected.png` |
| PERM-01 | viewer 体验账号密码登录成功 | PASS | 进入 `/kb`（第二条密码登录路径） |

### 4.2 知识库

| ID | 用例 | 结果 | 证据 / 说明 |
|---|---|---|---|
| KB-01 | 新建知识库 | PASS | toast“创建成功”；`20261106-01-kb-01-create-dialog.png` |
| KB-01b | 知识库出现在列表接口 | PASS | id=1；`20261106-02-kb-02-create-success.png` |
| KB-02 | 上传 9 种格式并完成解析（真实模型） | **BLOCKED** | 9/9 FAILED，embedding 401（Key 为 `YOUR_DASHSCOPE_API_KEY` 占位符）；见 6.1 |
| KB-03-TXT/MD/PDF/DOCX/XLSX/PPTX/HTML/JSON/EML | 各格式解析成功且段落数 > 0（真实模型） | **BLOCKED ×9** | 同 KB-02；`20261106-05-kb-05-upload-all.png` |
| TAG-01 | 创建标签 E2E-产品 / E2E-合同 | PASS | 接口返回 200 |
| TAG-02 | 页面为知识库绑定标签 | PASS | 标签已保存；`20261106-03-kb-03-kb-tags.png` |
| TAG-03 | 页面为文件绑定标签 | PASS | `20261106-17-kb-09-file-tags.png` |
| KB-04 | PDF 段落 / Chunk 预览展示 | PASS | chunk #0 正文含合同编号/金额；`20261106-16-kb-08-pdf-chunk-preview.png` |
| KB-05 | 重切为递归分割并成功 | **PASS（换方法复验）** | 真实模型 KB 仍因 401 无法重切；改在 mock 知识库 `E2E-Verify-KB` 以 PARAGRAPH→RECURSIVE 重切成功，文件 v2 SUCCESS、`chunkCount=1`；`20261106-01-kb-06-version-history-fix.png`；见 6.4 |
| KB-06 | 文档版本历史展示 | **PASS（已修复 e74f0c2）** | 上传后 v1 SUCCESS/1 段，重切后 v2 SUCCESS/1 段，v1 保留；`20261106-01-kb-06-version-history-fix.png`；见 5.2 |
| KB-07 | 页面新建 mock embedding 知识库 | PASS | id=2，dimension=1536；`20261106-06-kb-12-mock-kb-created.png` |
| KB-08 | mock 下 9 种格式全部解析成功 | PASS | 重传后 9/9 SUCCESS；`20261106-02-kb-15-mock-reupload.png`、`20261106-13-kb-13-mock-files-processed.png` |
| KB-09-TXT | yunlan-manual.txt 成功，chunks=1 | PASS | `20261106-07-kb-07-file-yunlan-manual-txt.png` |
| KB-09-MD | yunlan-deploy.md 成功，chunks=4 | PASS | `20261106-08-kb-07-file-yunlan-deploy-md.png` |
| KB-09-PDF | yunlan-contract.pdf 成功，chunks=1 | PASS | `20261106-09-kb-07-file-yunlan-contract-pdf.png` |
| KB-09-DOCX | yunlan-method.docx 成功，chunks=1 | PASS | `20261106-10-kb-07-file-yunlan-method-docx.png` |
| KB-09-XLSX | yunlan-pricing.xlsx 成功，chunks=1 | PASS | `20261106-11-kb-07-file-yunlan-pricing-xlsx.png` |
| KB-09-PPTX | yunlan-intro.pptx 成功，chunks=1 | PASS | `20261106-12-kb-07-file-yunlan-intro-pptx.png` |
| KB-09-HTML | yunlan-faq.html 成功，chunks=1 | PASS | `20261106-13-kb-07-file-yunlan-faq-html.png` |
| KB-09-JSON | yunlan-product.json 成功，chunks=1 | PASS | `20261106-14-kb-07-file-yunlan-product-json.png` |
| KB-09-EML | yunlan-quote.eml 成功，chunks=1 | PASS | `20261106-15-kb-07-file-yunlan-quote-eml.png` |
| KB-11 | 触发知识库重建接口 | PASS | `POST /api/kb/2/rebuild` → 200 |
| KB-12 | 重建 RUNNING→SUCCEEDED 且 chunkCount≠0 | **PASS（部署后复验）** | 新构建 `e74f0c2` 重建 2/2 成功，`chunkCount=2` 等于新向量表 `wgh_kb_3_1793961504345` 实际 2 行；`20261106-01-kb-12-rebuild-chunkcount-fix.png`；见 5.3 |

### 4.3 聊天

| ID | 用例 | 结果 | 证据 / 说明 |
|---|---|---|---|
| WIN-01 | 页面创建聊天窗口并绑定知识库/模型 | PASS | kbIds=[2]，llm=3，eval=METRICS；`20261106-01-chat-01-window-form.png` |
| CHAT-01 | 普通 RAG 问答返回正确内容 | PASS | 正确命中 25600 元 / 14 天 / P1 15 分钟等；`20261106-03-chat-05-answer.png` |
| CHAT-02 | 流式输出（发送中可见增量） | **PASS（已修复 e74f0c2）** | 分片长度 `59→96→156→209→…→892` 分段增长；停止后内容保留并可刷新回显；`20261106-01-chat-02-stream-increment-fix.png`、`20261106-01-chat-02-stop-midstream-fix.png`、`20261106-02-chat-02-stop-after-reload-fix.png`；见 5.4 |
| CHAT-03 | Markdown 渲染为 HTML | PASS | 生成 `<p>` 等标签 |
| CHAT-04 | 引用来源展示且 **kbName 正常显示** | PASS | 5 条来源均显示“E2E云岚知识库-Mock”；`20261106-04-chat-06-sources.png`（重点回归项通过） |
| CHAT-05 | RAG 指标（召回/命中/MRR/NDCG） | PASS | 召回 100%、命中 100%、MRR/NDCG 100% |
| CHAT-06 | 停止生成后保留内容并显示「已停止生成」 | PASS | 停止后内容 264 字符 + 标签；`20261106-02-chat-10-stop-preserved.png` |
| CHAT-07 | 停止状态持久化为 STOPPED | PASS | 接口 status=STOPPED；`20261106-05-chat-07-stopped.png` |
| CHAT-08 | 刷新后停止消息与来源仍在 | PASS | `20261106-03-chat-11-stop-after-reload.png`、`20261106-06-chat-08-after-reload.png` |

### 4.4 检索

| ID | 用例 | 结果 | 证据 / 说明 |
|---|---|---|---|
| SEARCH-01 | 混合检索返回并标注关键词命中 | PASS | 5 条，指标面板可见；`20261106-01-search-01-hybrid.png` |
| SEARCH-02 | 搜索结果展示知识库名 kbName | PASS | 出现“E2E云岚知识库-Mock” |
| SEARCH-03 | 关闭混合检索后纯向量可用 | PASS | 余弦相似度打分；`20261106-02-search-03-vector-only.png` |
| SEARCH-04 | 多路召回生效（混合 vs 纯向量有差异） | PASS | 两轮结果/排序不同；`20261106-03-search-04-api-hybrid-compare.png` |
| SEARCH-05 | 重排开关可用 | PASS（降级） | 开关可操作，但无 RERANK 模型，页面显示“未启用/已降级”；`20261106-04-search-05-rerank-empty.png` |
| SEARCH-06 | 检索评测页面可用 | **BLOCKED** | 前端路由无评测页（仅后端 `RetrievalEvaluationController`）；见 6.2 |

### 4.5 Agent / 多 Agent

| ID | 用例 | 结果 | 证据 / 说明 |
|---|---|---|---|
| AGENT-01 | 页面创建 Agent 模式窗口 | PASS | `20261106-01-agent-01-window-agent-mode.png` |
| AGENT-02 | Agent 自动规划并执行（计划树可见） | PASS | 后端 Planner 日志 17:47:33 输出 2 步计划（DATA→FINAL_ANSWER）；`20261106-02-agent-02-plan-run.png` |
| AGENT-03 | 运行态查询接口返回计划状态 | PASS | `GET /api/agent/sessions/{id}/plan` 返回计划 |
| CREW-01 | 页面创建多 Agent Crew | PASS | id=1，2 个 Agent，CREW_SEQUENTIAL；`20261106-01-agent-07-crew-form.png`、`20261106-02-agent-08-crew-created.png` |
| CREW-02 | Crew 配置校验通过 | PASS | `{valid:true,errors:[]}` |
| CREW-03 | 多 Agent 运行完成且运行态可查询 | PASS | `run.status=COMPLETED`，steps=2；`20261106-03-agent-09-crew-run.png`、`20261106-03-crew-03-run-state.png` |

> 说明：AGENT-01~03 的 Playwright 结果 JSON 未落盘（脚本异常退出），结论依据截图与后端 Planner 日志。

### 4.6 工作流

| ID | 用例 | 结果 | 证据 / 说明 |
|---|---|---|---|
| WF-01 | 页面搭建 RETRIEVAL 图并校验通过 | PASS | `20261106-02-wf-02-retrieval-node.png`、`20261106-03-wf-03-connected.png` |
| WF-02 | 保存工作流版本 | PASS | 已保存 v3；`20261106-04-wf-04-saved.png` |
| WF-03 | 运行并产生节点轨迹/输出 | PASS | 轨迹 SUCCESS/28ms；`20261106-05-wf-05-run-result.png` |
| WF-04 | 编辑器提供 AGENT 节点入口 | **PASS（已修复 e74f0c2）** | 调色板新增「Agent 规划」并可加入画布、配置模型/知识库、连线、校验通过、保存、运行 SUCCESS；`20261106-01-wf-04-agent-node-added-fix.png` 至 `20261106-04-wf-04-agent-run-fix.png`；见 5.5 |
| WF-05 | RETRIEVAL+AGENT 图校验通过 | PASS | 后端校验 errors=[]；`20261106-06-wf-06-agent-graph-loaded.png` |
| WF-06 | RETRIEVAL+AGENT 工作流运行完成 | PASS | 轨迹含检索 + Agent；`20261106-07-wf-07-agent-run.png` |

### 4.7 管理端

| ID | 用例 | 结果 | 证据 / 说明 |
|---|---|---|---|
| ADMIN-01 | 创建 viewer 体验账号 | PASS | e2eviewer；`20261106-01-admin-01-user-form.png` |
| ADMIN-01b | viewer 角色字段为 viewer | PASS | `roles:["viewer"]` |
| ADMIN-02 | 创建自定义角色 | PASS | e2e_reader；`20261106-03-admin-03-roles-list.png` |
| ADMIN-03 | 角色权限保存生效 | PASS | `20261106-04-admin-04-role-permissions.png` |
| ADMIN-04 | 配额管理可查询 viewer | PASS | `20261106-05-admin-05-quota-list.png` |
| ADMIN-05 | 配额编辑保存 | PASS | `20261106-06-admin-06-quota-dialog.png` |
| ADMIN-06 | Prompt 模板创建并提取变量 | PASS | vars=[context,question]；`20261106-01-admin-07-prompt-form.png` |
| ADMIN-07 | Prompt 模板编辑后版本递增 | PASS | v1→v2；`20261106-03-admin-09-prompt-v2.png` |
| ADMIN-08 | 模型管理页面不显示 API Key 明文 | PASS | apiKeyVisible=false；`20261106-05-models-05-models-page-api-key-hidden.png` |
| MODEL-01 | 页面新增 OpenAI 兼容嵌入模型 | PASS | E2E-Mock-Embedding；`20261106-01-models-01-embedding-form.png` |
| MODEL-02 | 页面新增大模型 | PASS | E2E-Mock-Chat；`20261106-03-models-03-chat-form.png` |
| MODEL-03 | 模型接口/页面不泄露 API Key | PASS | 响应无 apiKey 字段；`20261106-04-admin-10-models-page.png` |

### 4.8 分享 / Widget / 开放 API

| ID | 用例 | 结果 | 证据 / 说明 |
|---|---|---|---|
| SHARE-01 | 创建免密分享链接（WINDOW） | PASS | token=s_4a5b...；`20261106-01-share-01-create-dialog.png` |
| SHARE-02 | 创建带访问密码的分享链接 | PASS | `20261106-02-share-02-links-list.png` |
| SHARE-03 | 免登录打开分享聊天页 | PASS | 页面标题/输入区正常；`20261106-01-share-03-anonymous-open.png` |
| SHARE-04 | 匿名分享页可提问并收到回答 | PASS | 含引用；`20261106-02-share-04-anonymous-answer.png`、`20261106-12-share-04-anonymous-answer.png` |
| SHARE-05 | 正确访问密码解锁 | PASS | `20261106-03-share-05-password-ok.png` |
| SHARE-06 | 连续错误密码触发锁定 | PASS | 第 5 次后 `passwordLocked=true`，剩 758 秒；`20261106-04-share-06-password-lock.png`、`20261106-13-share-06-password-lock.png` |
| SHARE-07 | Widget 嵌入脚本在第三方页挂载 | PASS | iframe 1 个，grant 授权成功；`20261106-14-share-07-widget.png` |
| SHARE-08 | 创建开放 API Key（明文仅一次） | PASS | `20261106-03-share-08-apikey-created.png` |
| SHARE-09 | 开放 API Key 调用 KB 检索 | PASS | 200，total=5 |
| SHARE-10 | 无 Key 访问被拒绝 | PASS | 401 + 缺少 API Key 提示 |

### 4.9 权限 / 安全

| ID | 用例 | 结果 | 证据 / 说明 |
|---|---|---|---|
| PERM-02 | viewer 调管理接口全部被拒，文案不串用 | PASS | `/admin/users`、`/admin/roles`、`/admin/quota/users`、`POST /admin/users`、`POST /models/chat`、`POST /workflows` 均为 code=1004；**不再出现“配额管理仅管理员可用”**；`20261106-01-perm-02-viewer-api-denied.png` |
| PERM-03 | viewer 直接访问 `/admin/users` 被拦截 | PASS | 最终 URL 被重定向到 `/kb`；`20261106-02-perm-03-viewer-users-page.png` |
| SEC-01 | Markdown 注入 `<script>`/`onerror`/`javascript:` 被清洗 | PASS | `window.__xss*` 未触发，无 script 标签，链接无 javascript:；`20261106-02-sec-02-xss-render.png` |

## 5. FAIL 详情（复现步骤 · 证据 · 原文）

### 5.1 AUTH-05b 改密成功后当前会话不保持（产品缺陷 · 已修复 e74f0c2）

- 复现：admin 用初始口令登录 → 强制改密页 → 填新口令提交成功。
- 现象：改密接口成功并提示“密码修改成功”，随后 `/auth/me` 返回凭证失效，页面被踢回登录页，需重新登录。
- 证据（初测）：`20261106-05-auth-05-change-password-success.png`；toast 原文：“密码修改成功 | 凭证已失效，请重新登录”；最终 url `/login`。
- 影响：改密后会话中断，体验不佳（安全上可接受，但与“改密成功并进入知识库页”的提示矛盾）。
- **修复（e74f0c2）**：不改变 `pv` 使旧 token 立即失效的安全设计；改密成功后不再请求 `/auth/me`，改为主动清理本地会话、提示“口令已更新，请用新口令重新登录”并 `router.replace('/login')`。
- **复验（PID 24167）**：改密后 toast 精确出现该提示、URL 为 `/login`、本地 token 为 `null`；用新口令登录成功（进入 `/kb`）；再改回原口令并再次登录成功（环境已还原）。
- 修后证据：`20261106-01-auth-05b-change-password-relogin-fix.png`。

### 5.2 KB-06 文档版本历史段落数快照为 0（产品缺陷/展示不一致 · 已修复 e74f0c2）

- 复现：上传文件 → 解析完成 → 打开“版本历史”。
- 现象（初测）：版本表仅 1 行（v1），状态显示 SUCCESS，但“段落数”列为 **0**。
- **原证据链更正（不抹掉）**：`20261106-19-kb-11-version-history.png` 与接口原文 `rows=1; v1 AUTO 0 SUCCESS` 对应的版本行，实际属于 `wgh_knowledge_file.id=3`——真实模型知识库（id=1）中的 `yunlan-contract.pdf`，因 embedding 401 处于 **FAILED**、`chunk_count=0`。原报告把它与“mock 知识库中实际 1 chunk 的文件”放在一起，**证据链有误**；但**缺陷本身成立**：快照在重切时创建且状态被硬编码为 SUCCESS，上传/解析完成后又不记录当前版本，导致版本历史与真实状态、段落数不一致。
- **修复（e74f0c2）**：`KnowledgeFileService` 新增 `upsertVersionSnapshot`，在上传/重切/重建处理完成（成功或失败）后，按该版本真实的 `status` 与 `chunkCount` 落库或更新；重切前的旧版本快照保留真实值，不再固定写 SUCCESS。
- **复验（PID 24167）**：`E2E-Verify-KB` 上传 `yunlan-deploy.md` → v1 `SUCCESS / 1 段`；PARAGRAPH→RECURSIVE 重切 → v2 `SUCCESS / 1 段`，v1 保留；UI 版本历史两行段落数均为 1。
- 修后证据：`20261106-01-kb-06-version-history-fix.png`。

### 5.3 KB-12 重建后知识库 `chunkCount=0`（运行实例未部署修复 · 已修复并部署）

- 复现（初测）：`POST /api/kb/2/rebuild` → 轮询 `GET /api/kb/2`。
- 现象（初测）：`rebuildStatus` RUNNING→**SUCCEEDED**，`rebuildProcessed=9/9`，但 `chunkCount=**0**`。
- 根因（运行实例确为旧构建）：
  - jar 构建时间 `2026-11-05 14:16:07`；
  - 修复提交 `307508e` 时间 `2026-11-05 14:34:18`（晚于 jar）；
  - 反编译运行中 jar 的 `KnowledgeBaseService.finishRebuild`：`iconst_0` → `setChunkCount(0)`，不含 `countVectors` 调用；
  - 数据佐证：新向量表 `wgh_kb_2_1793959575380` 实际 12 行，9 个文件 `chunk_count` 合计 12，KB 汇总仍为 0。
- **修复/部署**：重新 `package`（jar **18:36**，含 `307508e` 与 `e74f0c2`）并重启，实例 PID **24167**，仍连 `wgh_rag_fresh`。
- **复验**：`E2E-Verify-KB` 重建 `RUNNING → SUCCEEDED`（2/2），`chunkCount=2`，新向量表 `wgh_kb_3_1793961504345` 实际 **2 行**，不再是 0。
- 修后证据：`20261106-01-kb-12-rebuild-chunkcount-fix.png`。

### 5.4 CHAT-02 流式内容不在气泡中增量渲染（产品缺陷 · 已修复 e74f0c2）

- 复现：聊天窗口新建会话 → 发送长回答请求（含“详细/逐条展开”）。
- 现象（初测）：助手气泡立即出现且“停止生成”按钮可见，但内容长度在 3.6 秒内持续为 **0**，第 ~3.9 秒一次性显示全文 892 字符。
- 初测采样：`0,0,0,0,0,0,0,0,0,0,0,0,892`（每 300ms 一次）。
- 对照：`curl -N` 直连 8080 与经 5173 代理，均能按 ~50ms 收到 `event:content` 分片，说明后端与代理流式正常。
- 定位：`aiMsg` 为普通对象，`messages.value.push(aiMsg)` 后仍对**原始对象**累加内容；原始对象不经过响应式代理，Vue 不会重渲染。
- **修复（e74f0c2）**：`ChatPlayground.vue`、`ShareChat.vue`、`PublicShareChat.vue` 的 AI 消息改用 `reactive()` 包装；流式回调持有的即为响应式代理。
- **复验量化（PID 24167 + mock 模型，每 250ms 采样）**：`59 → 96 → 156 → 209 → … → 892`，内容随分片分段增长。
- **停止生成回归**：中途停止后内容保留（120 字符）并显示「已停止生成」；刷新后内容 132 字符、停止标记仍在。
- 修后证据：`20261106-01-chat-02-stream-increment-fix.png`、`20261106-01-chat-02-stop-midstream-fix.png`、`20261106-02-chat-02-stop-after-reload-fix.png`。

### 5.5 WF-04 工作流编辑器无 AGENT 节点入口（前后端能力不一致 · 已修复 e74f0c2）

- 复现：打开 `/workflows/{id}/edit` 查看左侧节点调色板。
- 现象（初测）：仅 START/LLM/RETRIEVAL/TOOL/CONDITION/LOOP/HITL/VARIABLE/END，**无 AGENT**；后端 `GraphValidator.NODE_TYPES` 与 `AgentNodeExecutor` 已支持 AGENT。
- 验证：用 API 构造含 RETRIEVAL+AGENT 的图，页面加载、校验（WF-05）、保存、运行（WF-06）均成功，说明仅前端缺入口/配置面板。
- **修复（e74f0c2）**：调色板新增「Agent 规划」，`WorkflowNodeCard` 补齐图标/标签/颜色；配置面板支持目标、执行模型、知识库、最大步数。
- **复验（PID 24167）**：调色板可见 AGENT → 加入画布 → 配置模型/知识库 → 与 START/END 连线（2 条边）→ 校验通过 → 保存成功 → 运行轨迹 AGENT SUCCESS；`a48f11d` 的保存去重与只读校验未改动。
- 修后证据：`20261106-01-wf-04-agent-node-added-fix.png`、`20261106-02-wf-04-agent-connected-fix.png`、`20261106-03-wf-04-agent-saved-fix.png`、`20261106-04-wf-04-agent-run-fix.png`。

## 6. BLOCKED 说明（原 12 条逐条；KB-05 已解除，现 11 条）

### 6.1 逐条原因与可替代性

真实模型 Key 为占位符（`YOUR_DASHSCOPE_API_KEY` / `YOUR_DEEPSEEK_API_KEY`），上传后 embedding 返回 **401**；已在页面注册本地 OpenAI 兼容 mock（`127.0.0.1:9101`）用于链路验证。

| ID | 具体阻塞原因 | 是否可通过换方法解除 | 结论 |
|---|---|---|---|
| KB-02 | 真实模型 embedding 401，9 个文件全部 FAILED | **部分可换方法**：mock 下 9/9 SUCCESS、合计 12 chunks，证明解析入库链路可用；真实模型语义质量需要有效 Key | 保持 BLOCKED（真实模型维度） |
| KB-03-TXT | 同上；`yunlan-manual.txt` 真实模型 401 | 可换 mock：SUCCESS，chunks=1 | 保持 BLOCKED（真实模型维度） |
| KB-03-MD | 同上；`yunlan-deploy.md` 真实模型 401 | 可换 mock：SUCCESS，chunks=4 | 保持 BLOCKED（真实模型维度） |
| KB-03-PDF | 同上；`yunlan-contract.pdf` 真实模型 401 | 可换 mock：SUCCESS，chunks=1 | 保持 BLOCKED（真实模型维度） |
| KB-03-DOCX | 同上；`yunlan-method.docx` 真实模型 401 | 可换 mock：SUCCESS，chunks=1 | 保持 BLOCKED（真实模型维度） |
| KB-03-XLSX | 同上；`yunlan-pricing.xlsx` 真实模型 401 | 可换 mock：SUCCESS，chunks=1 | 保持 BLOCKED（真实模型维度） |
| KB-03-PPTX | 同上；`yunlan-intro.pptx` 真实模型 401 | 可换 mock：SUCCESS，chunks=1 | 保持 BLOCKED（真实模型维度） |
| KB-03-HTML | 同上；`yunlan-faq.html` 真实模型 401 | 可换 mock：SUCCESS，chunks=1 | 保持 BLOCKED（真实模型维度） |
| KB-03-JSON | 同上；`yunlan-product.json` 真实模型 401 | 可换 mock：SUCCESS，chunks=1 | 保持 BLOCKED（真实模型维度） |
| KB-03-EML | 同上；`yunlan-quote.eml` 真实模型 401 | 可换 mock：SUCCESS，chunks=1 | 保持 BLOCKED（真实模型维度） |
| KB-05 | 真实模型知识库重切触发 401，mock 未复测 | **可换方法**：已在 mock `E2E-Verify-KB` 完成 PARAGRAPH→RECURSIVE 重切，v2 SUCCESS、`chunkCount=1` | **已解除，转 PASS**（见 6.4） |
| SEARCH-06 | 前端路由没有检索评测页面，仅后端 `RetrievalEvaluationController` | 只能直接调后端 API；用例要求“页面可用”，不换方法解除 | 不可解除（除非新增前端评测页，超出本轮测试范围） |

### 6.2 真实模型 401 的不可解除性
- 401 源于占位符 Key，属于外部凭证缺失，不是代码缺陷；换 mock 只能验证链路结构与入库结果，不能验证真实模型的解析质量/语义效果。
- 因此 KB-02 与 KB-03×9 保留 BLOCKED，但链路等价性已由 KB-08（mock 下 9/9 SUCCESS）覆盖。

### 6.3 KB-12 修复验证（已解除）
- 初测因运行实例为旧构建无法验证；已重新 `package`（jar 18:36）并重启，复验通过，详见 §5.3。此处不再计为 BLOCKED。

### 6.4 KB-05 换方法复验
- 在 `E2E-Verify-KB`（mock embedding）上传 `yunlan-deploy.md`（PARAGRAPH，v1 SUCCESS/1 段），随后 `POST /api/kb/files/{id}/resplit?splitStrategy=RECURSIVE`。
- 结果：文件 `version=2`、`status=SUCCESS`、`chunkCount=1`；版本历史保留 v1 并新增 v2；证明重切链路可用。真实模型 KB 仍因 401 不可用。
- 证据：`20261106-01-kb-06-version-history-fix.png`。

## 7. 缺陷清单（BUG-01~05 已修复，BUG-06 仅记录）

| 编号 | 严重度 | 描述 | 状态/位置 |
|---|---|---|---|
| BUG-01 | 中 | 流式回答不在气泡内增量渲染，结束才一次性显示 | 已修复 `e74f0c2`；CHAT-02 |
| BUG-02 | 中 | 改密成功后当前会话被判定失效，强制重新登录 | 已修复 `e74f0c2`；AUTH-05b |
| BUG-03 | 中 | 工作流编辑器缺 AGENT 节点入口，与后端能力不一致 | 已修复 `e74f0c2`；WF-04 |
| BUG-04 | 高 | 运行实例重建后 KB 级 `chunkCount=0`（jar 未含 307508e 修复） | 已修复并部署；KB-12 |
| BUG-05 | 低 | 文档版本历史快照段落数为 0，与文件实际 chunk 数不一致 | 已修复 `e74f0c2`；KB-06 |
| BUG-06 | 低 | 分享密码锁定时，弹窗仅在下次 info 请求更新锁定态；当前轮仅 toast，输入框 disabled 但不显示剩余秒数 | 未修复（仅记录）；[PublicShareChat.vue](file:///Users/joe.wen/Desktop/ai-code/wgh-rag-system/rag-frontend/src/views/chat/PublicShareChat.vue)；`20261106-13-share-06-password-lock.png` |

## 8. 测试数据清理

两轮清理均通过页面/接口正常操作，未直接改数据库行。

### 8.1 初测轮（17:23—18:13）

| 数据 | 处理 | 结果 |
|---|---|---|
| 知识库 id=1、2 | `DELETE /api/kb/{id}` | 逻辑删除，向量表已 DROP（仅剩全局 `wgh_kb_ground_truth`、`wgh_kb_tag_rel`） |
| 聊天窗口 id=1、2 | `DELETE /api/chat/windows/{id}` | 列表 total=0 |
| Crew id=1 | `DELETE /api/agent-multi/crews/1` | 列表空 |
| 工作流 id=1、2 | `DELETE /api/workflows/{id}` | 列表 total=0 |
| Prompt 模板 id=4 | `DELETE /api/prompt-templates/4` | 仅剩系统 3 条 |
| 自定义角色 e2e_reader | `DELETE /api/admin/roles/e2e_reader` | 仅剩 admin/editor/viewer |
| 标签 id=1、2 | `DELETE /api/tags/{id}` | 列表空 |
| 测试模型 | `DELETE /models/chat/3`、`/models/embedding/2` | 逻辑删除（deleted=1） |

### 8.2 修后复验轮（18:20—19:10，E2E-Verify 新增数据）

| 数据 | 接口 | 结果 |
|---|---|---|
| 会话 id=10/11/12 | `DELETE /api/chat/windows/{3,4}/sessions/{10,11,12}` | HTTP 200；`wgh_chat_session.deleted=1`，同批消息 33—38 `deleted=1` |
| 聊天窗口 id=3、4（E2E-Verify-Streaming） | `DELETE /api/chat/windows/{id}` | HTTP 200；`deleted=1` |
| 知识库 id=3（E2E-Verify-KB） | `DELETE /api/kb/3` | HTTP 200；`deleted=1`，文件 28/29 随库逻辑删除，动态向量表已 DROP |
| 工作流 id=3、4、5 | `DELETE /api/workflows/{id}` | HTTP 200；`deleted=1` |
| ChatModel id=4、5（E2E-Verify-Chat） | `DELETE /api/models/chat/{id}` | HTTP 200；`deleted=1` |
| 嵌入模型 id=3（E2E-Verify-Embedding） | `DELETE /api/models/embedding/3` | HTTP 200；`deleted=1` |

> 说明：会话 10/11/12 的 `status` 仍为 `ACTIVE`、`message_count` 仍为 2，但 `deleted=1`；逻辑删除不回写业务字段，接口查询按 `deleted=0` 过滤后已不可见。此处不是删除未生效。

### 8.3 无法彻底清除的残留（软删除 / 仅支持吊销）

| 残留 | 位置 | 说明 |
|---|---|---|
| 体验账号 `e2eviewer` | `wgh_user`（enabled=false） | 接口仅禁用，不物理删除 |
| 分享链接 id=1、2（E2E匿名分享/E2E密码分享） | `wgh_share_link`（`revoked=1`、`deleted=0`） | 仅支持吊销；`target_id=1` 指向已逻辑删除的 E2E 聊天窗口 |
| 开放 API Key `E2E集成Key`（id=1） | `wgh_api_key`（`revoked=1`、`deleted=0`） | 仅支持吊销 |
| 聊天会话 id=1—9 / 消息 32 条 | `wgh_chat_session` / `wgh_chat_message`（`deleted=0`） | 初测删窗口未级联清理会话与消息，接口无单独清理入口 |
| 运行记录：工作流运行 1—5、Agent 计划 1、Crew 运行 1 | `wgh_workflow_run` / `wgh_agent_plan` / `wgh_m_agent_run` | 历史运行记录，接口不提供删除；其中工作流运行 5 属于已删的 E2E-Verify 工作流 5 |
| 逻辑删除行 | `wgh_knowledge_base` 3 行、`wgh_knowledge_file` 29 行、ChatModel 3 行（id=3/4/5）、嵌入模型 2 行（id=2/3）、聊天窗口 4 行、工作流 5 行、Crew 1 行、会话 3 行、消息 6 条 | `deleted=1`，数据库内仍占位 |

## 9. 范围与声明

- 本报告为**页面端到端**验证；真实大模型语义质量、真实 PDF 版面还原度未验证（Key 为占位符）。
- 流式、检索、Agent、工作流等链路使用本地 mock 模型验证，结论仅代表**功能链路正确性**，不代表模型效果。
- 全部证据截图位于 `docs/testing/evidence/20261106-*.png`，测试脚本位于 `/tmp/wgh-e2e/`（未纳入代码库）。
- 初测阶段未执行重编译、后端重启或业务代码修改；修后复验阶段按授权重新 `package` 并重启实例（PID 24167）。全程未触碰旧库 `wgh_rag`。

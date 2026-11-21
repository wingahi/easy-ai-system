# 2026-11-21 全量浏览器自动化回归测试报告

> 测试对象：WGH RAG 知识库系统（仓库根目录）
> 测试方式：Playwright 1.65.1（Chrome channel，headless，1440×900）真实操作页面，逐页采集控制台错误、页面异常、失败请求与 HTTP 4xx/5xx，并执行关键交互
> 范围：按 `rag-frontend/src/router/index.js` 与 `Layout.vue` 菜单清点的 **29 个可访问页面**，含本轮新增 `/admin/scheduler`
> 环境：复用既有后端（8080 业务 / 9090 管理）与既有 PostgreSQL / Redis，**未重启、未清库**；前端按仓库方式 `npm run dev` 启动到 5173
> 约束：未修改业务代码、未打包/覆盖 jar、未执行 git 写操作；测试数据仅使用本轮自建并在结束后清理

## 1. 结论摘要

| 指标 | 数值 |
|---|---:|
| 可访问页面 | 29 |
| 页面 PASS | 29 |
| 页面 FAIL | 0 |
| 页面 SKIP | 0 |
| 检查项合计 | 202 |
| 检查项 PASS / FAIL | 202 / 0 |
| 非阻断缺陷（控制台未捕获异常） | 3 类（BUG-01 / BUG-02 / BUG-03） |
| 数据 / 环境观察项 | 4 |
| 证据截图 | 44 张（`docs/testing/evidence/`，目录已 gitignore） |

结论：29 个页面均可打开，关键数据加载正常，主要交互（查询、筛选、分页、弹窗、表单校验、二次确认、轮询开关、聊天发送与引用、免登录分享）均可用；`/admin/scheduler` 的概览、筛选分页、详情抽屉、取消二次确认、轮询开关逐项通过。未发现阻断性缺陷或整页不可用。

控制台层面发现 3 类非阻断问题，均为 **未捕获的 Promise 拒绝**（Element Plus 表单校验失败、MessageBox 取消、接口 500 未 catch），界面表现与校验提示正常，不影响主流程；按任务约束仅记录、未修复。

## 2. 执行环境

| 项 | 值 |
|---|---|
| 后端健康 | `GET http://localhost:9090/actuator/health` → `status=UP`，`db` / `redis` / `diskSpace` / `ping` 均 UP |
| 后端进程 | 复用既有进程，本轮未重启、未换库、未执行迁移 |
| 前端 | `npm run dev -- --host 127.0.0.1 --port 5173`，Vite 5.4.21，PID 10672，`http://127.0.0.1:5173` 返回 200 |
| 浏览器 | Playwright 1.65.1，`channel: chrome`，headless |
| 登录凭据 | 运行时通过环境变量注入（`WGH_E2E_USER` / `WGH_E2E_PASSWORD`），未写入脚本或文档 |
| 会话角色 | admin（可见全部管理菜单） |
| 测试数据来源 | 复用既有知识库、文件、聊天窗口、工作流、Crew、模型、配额、调度作业等数据 |

## 3. 页面清单与逐页结果

> 结果口径：页面可打开 + 关键数据加载 + 本轮主要交互可用 = PASS；“控制台”列单独标注本轮捕获的非阻断异常。

### 3.1 认证与分享（5 页）

| # | 路由 | 页面 | 结果 | 检查项 | 控制台 | 证据 |
|---|---|---|---|---:|---|---|
| 1 | `/login` | 登录页 | PASS | 6/6 | 正常 | [login-wrong-password.png](../evidence/login-wrong-password.png) |
| 2 | `/change-password` | 修改密码 | PASS | 5/5 | BUG-01 | [change-password-mismatch.png](../evidence/change-password-mismatch.png) |
| 3 | `/share/workflow/:token` | 登录态分享工作流 | PASS | 6/6 | 正常 | [share-workflow-share-workflow.png](../evidence/share-workflow-share-workflow.png) |
| 4 | `/share/chat/:token` | 登录态分享对话 | PASS | 4/4 | 正常 | [share-chat-login-share-chat.png](../evidence/share-chat-login-share-chat.png) |
| 5 | `/share/:token` | 免登录分享对话 | PASS | 12/12（含匿名提问） | 正常 | [public-share-share-created.png](../evidence/public-share-share-created.png)、[public-share-anon-anon-reply.png](../evidence/public-share-anon-anon-reply.png) |

要点：
- 登录：错误口令被拒并提示「用户名或密码错误」，停留登录页；正确口令跳转 `/kb` 并写入 `wgh_token`。
- 修改密码：三个口令输入框渲染；两次输入不一致、弱口令均被表单规则拦截，未实际改密、仍停留改密页。
- 分享工作流：分享标题/版本渲染，空输入时「运行」按钮禁用，输入后可用；未实际执行工作流（见 §7）。
- 免登录分享：页面内创建 WINDOW 范围链接 → 匿名上下文打开 → 提问收到带 `[S1]` 引用的回复 → 吊销链接并确认状态更新。

### 3.2 知识库（5 页）

| # | 路由 | 页面 | 结果 | 检查项 | 控制台 | 证据 |
|---|---|---|---|---:|---|---|
| 6 | `/kb` | 知识库管理 | PASS | 6/6 | BUG-01 | [kb-list-list.png](../evidence/kb-list-list.png) |
| 7 | `/kb/:id/files` | 文件管理 | PASS | 9/9 | BUG-03（数据缺失行） | [kb-files-preview.png](../evidence/kb-files-preview.png)、[kb-files-versions.png](../evidence/kb-files-versions.png) |
| 8 | `/kb/:id/connectors` | 数据源连接器 | PASS | 4/4 | BUG-01 | [kb-connectors-connectors.png](../evidence/kb-connectors-connectors.png) |
| 9 | `/kb/:id/search` | 知识库搜索 | PASS | 3/3 | 正常 | [kb-search-search-result.png](../evidence/kb-search-search-result.png) |
| 10 | `/kb/:id/evaluation` | 检索评测 | PASS | 6/6 | 正常 | [kb-evaluation-evaluation.png](../evidence/kb-evaluation-evaluation.png) |

要点：
- 知识库：统计卡、列表、名称搜索过滤、分页正常；新建弹窗空名称被校验拦截，取消后关闭。
- 文件：预览弹窗展示原始段落 40 / 分割段落 206 与块内容；版本历史、重新分割弹窗可开可关。文件 `id=1/2` 的存储对象在磁盘缺失，预览返回 500「存储对象不存在」，界面有错误提示（见 BUG-03 与观察项 O2）。
- 检索：输入查询后指标区（召回数/过滤后/覆盖率/命中率）与结果列表正常返回。
- 评测：版本化数据集区块、临时标注区块、历史结果区块渲染；新建数据集/新增标注弹窗可开；临时标注列表加载 1 条。

### 3.3 模型与聊天管理（6 页 + 1 项交互）

| # | 路由 | 页面 | 结果 | 检查项 | 控制台 | 证据 |
|---|---|---|---|---:|---|---|
| 11 | `/models/chat` | 大模型配置 | PASS | 4/4 | BUG-01 | [models-chat-list.png](../evidence/models-chat-list.png) |
| 12 | `/models/embedding` | 嵌入模型 | PASS | 4/4 | BUG-01 | [models-embedding-embedding-dialog.png](../evidence/models-embedding-embedding-dialog.png) |
| 13 | `/models/rerank` | 重排序模型 | PASS | 4/4 | BUG-01 | [models-rerank-list.png](../evidence/models-rerank-list.png) |
| 14 | `/chat/windows` | 聊天窗口 | PASS | 7/7 | BUG-01 | [chat-windows-windows.png](../evidence/chat-windows-windows.png) |
| 15 | `/prompt/templates` | Prompt 模板 | PASS | 5/5 | BUG-01 | [prompt-templates-templates.png](../evidence/prompt-templates-templates.png) |
| 16 | `/chat/windows/:id/playground` | 调试对话 | PASS | 5/5 | 正常 | [chat-playground-playground.png](../evidence/chat-playground-playground.png) |
| 16a | 同上 | 发送消息与引用 | PASS | 5/5 | 正常 | [chat-send-chat-reply.png](../evidence/chat-send-chat-reply.png) |

要点：
- 三个模型页列表均有数据，新增弹窗空表单被校验拦截（同时触发 BUG-01）。
- 聊天窗口：列表展示知识库数/工具数/执行模式/检索增强/分享/可见性；创建与编辑弹窗可开，空名称被拦截，分页存在。
- Prompt 模板：列表 5 条，名称搜索过滤生效，新增弹窗校验正常。
- 调试对话：历史会话侧栏、输入框、发送按钮渲染；发送「你好，请用一句话回答」后 2.5s 内收到助手回复，回复含 `[S1]` 引用角标，来源面板与指标正常。

### 3.4 工具 / 工作流 / 多 Agent（5 页）

| # | 路由 | 页面 | 结果 | 检查项 | 控制台 | 证据 |
|---|---|---|---|---:|---|---|
| 17 | `/tools` | 工具管理 | PASS | 8/8 | 正常 | [tools-tools.png](../evidence/tools-tools.png) |
| 18 | `/workflows` | 工作流编排 | PASS | 8/8 | 正常 | [workflows-templates-market.png](../evidence/workflows-templates-market.png)、[workflows-run-history.png](../evidence/workflows-run-history.png) |
| 19 | `/workflows/:id/edit` | 工作流编辑器 | PASS | 8/8 | 正常 | [workflow-editor-editor.png](../evidence/workflow-editor-editor.png) |
| 20 | `/multi-agent/crews` | 多 Agent Crews | PASS | 4/4 | 正常 | [multi-agent-crews-crews.png](../evidence/multi-agent-crews-crews.png) |
| 21 | `/multi-agent/playground` | 多 Agent 运行调试 | PASS | 4/4 | 正常 | [multi-agent-playground-multi-agent.png](../evidence/multi-agent-playground-multi-agent.png) |

要点：
- 工具：全部 / Skill / MCP / HTTP接口 / Function Calling 标签切换无异常；新增弹窗可开。
- 工作流列表：模板市场弹窗含模板卡片；新建工作流空名称提示「请填写工作流名称」；触发器弹窗、运行历史抽屉可打开。
- 工作流编辑器：本轮通过页面新建工作流 → 自动进入编辑器 → 校验/保存均有结果提示 → 运行调试标签可切换 → 回到列表删除清理。默认开始/结束节点渲染正常。
- 多 Agent：Crew 列表与新建弹窗正常，「校验」返回结果提示；运行调试页 Crew 下拉有选项、运行输入区渲染（未实际运行，见 §7）。

### 3.5 管理与配额（8 页）

| # | 路由 | 页面 | 结果 | 检查项 | 控制台 | 证据 |
|---|---|---|---|---:|---|---|
| 22 | `/admin/stats` | 审计与统计 | PASS | 6/6 | 正常 | [admin-stats-stats.png](../evidence/admin-stats-stats.png) |
| 23 | `/admin/tasks` | 运行中任务 | PASS | 5/5 | 正常 | [admin-tasks-tasks.png](../evidence/admin-tasks-tasks.png) |
| 24 | `/admin/scheduler` | 调度中心（本轮新增） | PASS | 44/44 | 正常 | 见 §4 |
| 25 | `/open-access` | 开放平台 | PASS | 9/9 | 正常 | [public-share-share-created.png](../evidence/public-share-share-created.png) |
| 26 | `/admin/quota` | 用户配额 | PASS | 5/5 | BUG-02 | [admin-quota-quota.png](../evidence/admin-quota-quota.png) |
| 27 | `/admin/users` | 用户管理 | PASS | 5/5 | 正常 | [admin-users-users.png](../evidence/admin-users-users.png) |
| 28 | `/admin/roles` | 角色管理 | PASS | 6/6 | BUG-01 | [admin-roles-permissions.png](../evidence/admin-roles-permissions.png) |
| 29 | `/my/quota` | 我的配额 | PASS | 4/4 | 正常 | [my-quota-my-quota.png](../evidence/my-quota-my-quota.png) |

要点：
- 审计与统计：当日配额卡、成本统计、差评反馈、审计日志均渲染；按操作 `KB_CREATE` 查询有结果。
- 运行中任务：无「接口暂不可用」告警，总数文案渲染，任务类型筛选与刷新可用。
- 开放平台：分享链接/API Key 两个标签渲染；创建分享时未选目标提示「请选择分享目标」，创建成功后列表出现新链接并可解析 token；吊销有二次确认且状态更新为「已吊销」。
- 用户配额：列表有数据，用户名查询生效，编辑弹窗可开，重置用量弹出二次确认（点取消触发 BUG-02）。
- 用户管理：列表与用户名查询正常，新建体验账号空表单被后端提示拦截。
- 角色管理：内置/普通角色列表、权限配置弹窗（权限分组）、新建角色空表单校验均正常。

## 4. 调度中心 `/admin/scheduler` 细测

| 分组 | 检查项 | 结果 | 说明 |
|---|---|---|---|
| 概览 | 6 张统计卡 | PASS | 待执行 / 运行中 / 失败 / 死信 / 超时或租约过期 / 作业总数，数值均为数字 |
| 概览 | 状态分布、车道分布 | PASS | 标签含状态/车道与计数 |
| 概览 | 统计生成时间 | PASS | 展示「统计生成于 …」 |
| 列表 | 默认分页 20 条 | PASS | 当时共 22 个作业 |
| 筛选 | 状态=成功 | PASS | 结果行均含「成功」 |
| 筛选 | 车道=维护作业 | PASS | 结果行均含「维护作业」 |
| 筛选 | 作业类型 `WF_RUN` | PASS | 结果行类型均为「工作流运行」 |
| 筛选 | 业务键包含 `e2e` | PASS | 有匹配结果 |
| 分页 | 第 2 页 | PASS | 余量行正常加载，可回到第 1 页 |
| 详情抽屉 | 打开与字段 | PASS | 执行标识、作业类型、状态版本号、租约与心跳（持有者/过期/心跳/取消标记/原因）、错误信息、负载 JSON、结果 JSON 均渲染 |
| 详情抽屉 | 负载/结果 JSON | PASS | 格式化 JSON 区块有内容 |
| 取消 | 终态行按钮禁用 | PASS | 成功/失败/死信/已取消行「取消」按钮 disabled |
| 取消 | 不可取消悬浮提示 | PASS | 悬浮显示「当前状态不可取消：成功」 |
| 取消 | 可取消行（自建 PENDING 作业） | PASS | 按钮可用；点击弹出「取消作业确认」，含作业类型/短执行标识与取消原因输入框 |
| 取消 | 二次确认点「返回」 | PASS | 不发送请求，行状态仍为「待执行」 |
| 取消 | 二次确认点「确认取消」 | PASS | 发出 `POST /admin/scheduler/jobs/{id}/cancel`，提示成功，行状态变「已取消」，`cancel_requested=1`、原因「管理端手动取消」 |
| 轮询 | 开启自动刷新（5 秒） | PASS | 开关进入选中态，11.5s 内多次请求 `/admin/scheduler/jobs` |
| 轮询 | 页面隐藏时暂停 | PASS | 覆写 `document.hidden=true` 后 8s 内新请求数为 0 |
| 轮询 | 恢复可见后继续 | PASS | 7s 内重新发起请求 |
| 轮询 | 关闭开关后停止 | PASS | 7s 内无新增请求 |
| 轮询 | 离开页面后停止 | PASS | 跳转 `/kb` 后 7s 内无新增请求 |

证据：[概览](../evidence/admin-scheduler-overview.png)、[详情抽屉](../evidence/admin-scheduler-detail-drawer.png)、[取消二次确认](../evidence/admin-scheduler-cancel-confirm.png)、[不可取消提示](../evidence/admin-scheduler-cancel-disabled.png)、[取消后状态](../evidence/admin-scheduler-cancelled.png)、[离开页面](../evidence/admin-scheduler-after-leave.png)。

> 说明：为验证「可取消」链路，本轮向 `wgh_execution_job` 插入 1 条 `PENDING`、计划时间 2030 年的自建作业（`E2E_TEST_JOB`，不会被执行器领取），通过页面取消后删除该行。

## 5. 缺陷记录（仅记录，未修复）

### BUG-01｜空表单提交触发未捕获的 Promise 拒绝（P3，非阻断）

- 现象：在带 `el-form` 校验的弹窗中直接点「确定/保存」，页面正确显示红色校验文案，但控制台出现 `Uncaught (in promise) Array(1): Object`（Playwright 记为 `pageerror`）。
- 原因：`await formRef.value.validate()` 未包 `try/catch`，校验失败时 Element Plus reject 的校验结果对象成为未处理拒绝。
- 影响页面与代码位置：
  - [KnowledgeBase.vue](../../../rag-frontend/src/views/kb/KnowledgeBase.vue#L237) 第 237 行
  - [KBConnectors.vue](../../../rag-frontend/src/views/kb/KBConnectors.vue#L229) 第 229 行
  - [ChatModels.vue](../../../rag-frontend/src/views/models/ChatModels.vue#L180) 第 180 行
  - [EmbeddingModels.vue](../../../rag-frontend/src/views/models/EmbeddingModels.vue#L133) 第 133 行
  - [RerankModels.vue](../../../rag-frontend/src/views/models/RerankModels.vue#L122) 第 122 行
  - [ChatWindows.vue](../../../rag-frontend/src/views/chat-admin/ChatWindows.vue#L426) 第 426 行
  - [PromptTemplates.vue](../../../rag-frontend/src/views/prompt/PromptTemplates.vue#L254) 第 254 行
  - [ToolManagement.vue](../../../rag-frontend/src/views/tools/ToolManagement.vue#L421) 第 421 行
  - [ChangePassword.vue](../../../rag-frontend/src/views/login/ChangePassword.vue#L73) 第 73 行
  - [Login.vue](../../../rag-frontend/src/views/login/Login.vue#L71) 第 71 行
- 复现步骤：
  1. admin 登录，进入 `/kb`；
  2. 点「新建知识库」，名称留空，点「确定」；
  3. 表单出现「请输入知识库名称」，同时 DevTools Console 出现未捕获拒绝（`Array(1): Object`）。
- 预期：校验失败仅展示表单错误，不产生未捕获异常。
- 实际影响：界面无可见异常，但会污染控制台、可能触发前端错误监控告警。

### BUG-02｜配额「重置用量」点取消触发未捕获拒绝（P3，非阻断）

- 现象：`/admin/quota` 点「重置用量」→ 二次确认框点「取消」→ 控制台 `Uncaught (in promise) cancel`。
- 原因：[QuotaManagement.vue](../../../rag-frontend/src/views/admin/QuotaManagement.vue#L203) 中 `await ElMessageBox.confirm(...)` 未 `try/catch`。
- 复现：admin 登录 → `/admin/quota` → 任一行「重置用量」→ 「取消」→ 查看控制台。
- 预期：取消是正常分支，不应产生未捕获异常（同文件 `restoreDefault` 有相同写法）。

### BUG-03｜文件预览接口 500 时未捕获拒绝（P3，非阻断）

- 现象：知识库 1 的前两条文件存储对象在磁盘缺失，点「预览」后端返回 500「存储对象不存在」，界面弹出错误提示，但控制台出现 `Uncaught (in promise) Error: 存储对象不存在…`。
- 原因：[KBFiles.vue](../../../rag-frontend/src/views/kb/KBFiles.vue#L313) 的 `preview()` 未 `catch`；`kbPreviewSplit` 失败后拒绝未被处理。
- 复现：admin 登录 → `/kb/1/files` → 对「java-10年-文观海-简历.pdf」点「预览」→ 观察错误提示与控制台。
- 预期：失败仅提示错误，不产生未捕获异常；同时应修正该文件的存储对象（见 O2）。

## 6. 数据与环境观察项（非代码缺陷）

| 编号 | 观察 | 说明 |
|---|---|---|
| O1 | 全站控制台 404 | `index.html` 未声明 favicon，浏览器请求 `/favicon.ico` 404；属非业务资源噪声，未计入失败。 |
| O2 | 知识库 1 文件对象缺失 | `wgh_knowledge_file.id=1/2` 的 `file_path` 指向的 PDF 不在 `rag-backend/uploads/1/` 下；`id=13` 正常可预览。属历史数据/存储不一致。 |
| O3 | 工作流 5 自动恢复旧 HITL | `/workflows/5/edit` 恢复 2026-10-28 的 `PAUSED` 运行并自动弹出「人工确认」弹窗，按现有设计阻止其余点击。本轮改用新建工作流完成编辑器用例，未按缺陷计。 |
| O4 | 分享链接有效期 | 库内既有 4 条分享链接均已过期（11-08~11-19）；公开分享链路改用本轮新建链接验证，结束后吊销并清理。 |

## 7. 未执行项与限制

| 项目 | 原因 |
|---|---|
| 工作流实际运行、分享工作流运行 | 会触发 LLM / 工具 / 写库；本轮只验证页面、输入态与按钮可用性。 |
| 多 Agent 运行、HITL 继续 | 依赖模型与人工输入，耗时且会产生运行数据；页面与选择器已验证。 |
| 评测实际运行（版本化 / 临时） | 依赖嵌入与检索计算，且会新增结果数据；页面、弹窗、列表已验证。 |
| OpenAI 兼容 API Key 创建与调用 | 涉及密钥落库与外部调用；本轮仅验证开放平台页面与弹窗，未创建 Key。 |
| 密码保护分享 | 本轮未创建带密码链接；免登录、登录态分享均已覆盖。 |
| 非 admin 角色菜单与 403 拦截 | 本轮仅有 admin 凭据；viewer/editor 权限矩阵沿用既有报告结论，未重复验证。 |

## 8. 测试数据与清理范围

| 数据 | 操作 | 清理 |
|---|---|---|
| 调度作业 `e2e-sched-1795251459` | 直接插入 `PENDING`（计划 2030 年，避免被领取），页面取消 | 已删除该行 |
| 工作流 `e2e-浏览器回归-1795251226979` | 页面新建 → 校验/保存 → 页面删除 | 已页面删除并硬删除行（id=134） |
| 分享链接 2 条（`e2e-浏览器公开分享-*`） | 页面创建 → 匿名访问 → 页面吊销 | 已硬删除行（id=7/8） |
| 匿名会话 2 个（id 116/117）及消息 4 条 | 公开分享匿名提问生成 | 已删除会话与消息 |
| 调试对话消息 6 条（session 113） | 发送测试问题产生 | 已删除消息，并重算该会话 `message_count` / `total_tokens` |
| 截图 44 张 | `docs/testing/evidence/` | 保留本地，目录已 gitignore，不入库 |

未触碰其他用户的会话、知识库、模型、配额、角色与既有调度作业；未执行 `DROP` / `TRUNCATE` / 全表 `DELETE`。

## 9. 复现方式

自动化脚本按页面拆分为多段 Playwright 脚本在本地临时目录执行，未提交入库；复现时按以下约定即可：

```bash
# 前置：后端已运行（9090 health UP），前端已启动
cd rag-frontend && npm run dev -- --host 127.0.0.1 --port 5173

# 运行环境变量（值从仓库外注入，不写入脚本）
export BASE_URL=http://127.0.0.1:5173
export WGH_E2E_USER=<管理员账号>
export WGH_E2E_PASSWORD=<管理员口令>

# 浏览器：Playwright + Chrome channel + headless，1440x900
# 截图输出：docs/testing/evidence/（已 gitignore）
```

关键断言均可在页面上人工复核：筛选后表格文本、详情抽屉字段、取消确认框与状态变化、自动刷新请求次数、聊天回复中的 `[S1]` 角标等。

## 10. 结论

- 29/29 页面通过功能回归，202/202 检查项通过；无阻断性缺陷、无整页不可用。
- 新增调度中心页面的 44 项细测全部通过，包含轮询在页面隐藏与离开后的停止行为。
- 3 类控制台未捕获 Promise 拒绝为唯一的代码层面问题，均不影响界面主流程，建议统一在表单校验、MessageBox 取消与预览请求处补 `try/catch`。
- 知识库 1 两条文件的存储对象缺失属数据问题，建议补齐对象或标记失效，避免预览反复 500。

# RAG 知识库系统浏览器端到端测试报告（2026-11-03）

## 一、测试环境

| 项 | 值 |
| --- | --- |
| 前端 | http://localhost:5173（Vite dev server，`/api` 代理到 8080），进程 node PID 90478 |
| 后端 | http://localhost:8080，测试时段进程 java PID 19927；测试结束后已重新打包并重启为 PID 41603（jar 22:05:05，见 §八-3） |
| 管理端口 | http://localhost:9090（`/actuator/health`、`/actuator/prometheus`） |
| 账号 | `admin` / `admin`（本地测试库） |
| 测试方式 | 浏览器自动化真实操作页面（导航、点击、输入、上传/下载、网络与控制台观测、截图） |
| 测试时间 | 2026-11-03（复验与重新打包同为当日） |

本次浏览器用例执行期间未执行 `mvn install` / `package`，未重启 8080 / 5173 进程；测试结束后的重新打包与后端重启见 §八-3。

## 二、用例总览

- 用例总数：**46**
- PASS：**43**
- FAIL：**1**
- BLOCKED：**2**

> 统计口径说明：§八 的修复与复验结果**未新增或删除**本报告用例，故 46 / 43 / 1 / 2 保持不变。用例级状态变化如下：
>
> | 用例 | 当前状态 | 是否变化 | 说明 |
> | --- | --- | --- | --- |
> | C3 文件预览 | FAIL | 未变 | 用户已决定暂不处理，见 §八-4 |
> | C2 文件上传 | BLOCKED | 未变 | 工具限制，未新增证据 |
> | A7 首登强制改密 | BLOCKED | 未变 | 无可用体验账号密码 |
> | F4 工作流保存 | PASS | 未变 | 原「版本跳号 / 疑似重复提交」备注已被推翻，根因与修复见 §八-1 |
>
> 模型故障转移 P0 的修复属于后端能力修复，不在本报告 46 条浏览器用例内，且真实模型 SSE 切链未验证，见 §八-2。

## 三、用例明细

| # | 场景 | 结果 | 证据文件 | 备注 |
| --- | --- | --- | --- | --- |
| A1 | 登录页加载与表单展示 | PASS | 2026-11-03-01-login-page.png | 用户名/密码输入框、登录按钮正常渲染 |
| A2 | admin/admin 正常登录 | PASS | 2026-11-03-02-kb-list.png | 登录后跳转知识库列表，token 写入 localStorage `wgh_token` |
| A3 | 错误密码登录提示 | PASS | 2026-11-03-01b-login-wrong-password.png、2026-11-03-43-login-wrong-password-verified.png | 提示「用户名或密码错误」；HTTP 200 + 业务码 1005（非 HTTP 401，属约定实现） |
| A4 | 未登录访问受保护 API 返回 401 | PASS | 无截图（控制台/网络记录） | 登出后裸 `fetch('/api/kb')` 返回 401 |
| A5 | 修改密码页可达与表单展示 | PASS | 2026-11-03-33-change-password.png | 页面正常，未实际提交改密（避免影响 admin 凭据） |
| A6 | 登出后回到登录页并清空凭据 | PASS | 2026-11-03-34-logout-login.png | 跳转 `/login`，localStorage token 已清空 |
| A7 | 首次登录强制改密（真实体验账号场景） | BLOCKED | 无 | 无可用体验账号密码；直接改写 localStorage `wgh_pwd_must_change=1` 不生效（store 已 hydrate，路由守卫不读最新值）。已将该键重置为 0，未污染环境 |
| B1 | 知识库列表 | PASS | 2026-11-03-02-kb-list.png | 列表、分页、卡片信息正常 |
| B2 | 新建知识库 | PASS | 2026-11-03-03-kb-create.png | 创建 id=5「浏览器验证-20261103」；表单要求选择嵌入模型，校验生效 |
| B3 | 编辑知识库 | PASS | 2026-11-03-04-kb-edit.png | 修改名称/描述保存成功 |
| B4 | 知识库标签管理 | PASS | 2026-11-03-05-kb-tags.png | 标签绑定/展示正常 |
| B5 | 删除知识库（确认弹窗） | PASS | 2026-11-03-02-kb-list.png | 二次确认弹窗正确弹出，点击取消未实际删除 |
| C1 | 文件列表与解析状态 | PASS | 2026-11-03-06-files-upload-blocked.png | 既有 3 个 PDF 解析状态显示「成功」 |
| C2 | 文件上传（docx/pdf/txt） | BLOCKED | 2026-11-03-06-files-upload-blocked.png | Element Plus `el-upload` 的文件 input 为隐藏元素，浏览器工具无法 attach 本地文件；页面本身无报错 |
| C3 | 文件预览 | **FAIL** | 2026-11-03-07-file-preview-fail.png | 见「失败项根因初判」第 1 条 |
| C4 | 文件删除（确认弹窗） | PASS | 2026-11-03-08-file-delete-confirm.png、2026-11-03-08b-file-delete-confirm.png | 确认弹窗正常，取消未实际删除 |
| D1 | 创建对话窗口（Playground） | PASS | 2026-11-03-08c-chat-window-created.png | 窗口 id=24 创建成功，绑定知识库 |
| D2 | 流式输出问答 | PASS | 2026-11-03-09-chat-streaming.png | SSE 逐字输出正常；召回率 100% / 命中率 100% / 正确率 80%；控制台有 `net::ERR_ABORTED /api/chat/windows/24/stream`，答案完整，判定为前端流结束主动 abort，非故障 |
| D3 | 引用来源展示 | PASS | 2026-11-03-10-chat-answer-sources.png | 答案下方展示来源，同一 PDF 去重为 2 篇 |
| E1 | 提示词模板列表 | PASS | 2026-11-03-11-prompt-templates-list.png | 列表与变量展示正常 |
| E2 | 创建提示词模板 | PASS | 2026-11-03-12-prompt-template-create.png、2026-11-03-13-prompt-template-created.png | 新增 id=5「浏览器验证模板二」，自动识别变量 `{{question}}`、`{{result}}` |
| E3 | 聊天输入框 `/` 前缀联想与变量填写 | PASS | 2026-11-03-14-prompt-slash-suggest.png、2026-11-03-15-prompt-template-varfill.png | 输入 `/` 弹出 5 个候选，键盘选中后插入模板并进入变量填写面板 |
| F1 | 工作流列表 | PASS | 2026-11-03-16-workflow-list.png | 列表、状态、入口正常 |
| F2 | 打开工作流编辑器 | PASS | 2026-11-03-17-workflow-editor-agent.png | `/workflows/129/edit` 打开「浏览器验证-AGENT工作流」 |
| F3 | AGENT 节点配置 | PASS | 2026-11-03-18-workflow-agent-config.png | Start → Agent → End 连线完整，节点配置面板可编辑 |
| F4 | 校验并保存工作流 | PASS | 2026-11-03-19-workflow-saved-v5.png | 校验通过、保存成功。原备注「版本号跳号 / 疑似重复提交」已被推翻：实际为「校验」「运行」内部的隐式保存，见 §八-1 |
| G1 | 多 Agent Crew 列表 | PASS | 2026-11-03-20-multiagent-crew-list.png | 8 个 Crew 正常展示 |
| G2 | 创建 Crew | PASS | 2026-11-03-21-multiagent-create-dialog.png、2026-11-03-22-multiagent-crew-created.png | 新建「浏览器验证-Crew-20261103」，Agent 1（验证员/verifier）保存成功 |
| G3 | Crew 运行入口 | PASS | 2026-11-03-23-multiagent-playground.png | `/multi-agent/playground` 正常加载所选 Crew、Agents(1) 与开始运行入口 |
| H1 | 用户管理（含体验账号） | PASS | 2026-11-03-24-admin-users.png | 用户列表正常，含多个 `trial_*` 体验账号 |
| H2 | 角色与权限页 | PASS | 2026-11-03-25-admin-roles.png | 角色列表与权限勾选正常 |
| H3 | 配额管理页 | PASS | 2026-11-03-26-admin-quota.png | 配额配置正常 |
| H4 | 我的配额页 | PASS | 2026-11-03-27-my-quota.png | `/my/quota` 用量与上限展示正常 |
| H5 | 统计页 | PASS | 2026-11-03-28-admin-stats.png | 统计卡片/图表正常 |
| I1 | 开放访问页/分享列表 | PASS | 2026-11-03-29-open-access-tab.png | `/open-access` 分享 tab 正常 |
| I2 | 创建分享链接 | PASS | 2026-11-03-30-share-create-dialog.png、2026-11-03-31-share-created.png | 分享 id=5「浏览器验证分享-20261103」，token `s_a0be9e14125f4420bbd83071bdc4a898`，密码 `test123test123`，有效期 7 天；创建后自动复制链接 |
| I3 | 免登录访问分享密码页 | PASS | 2026-11-03-35-share-password-gate.png | 登出后打开 `/share/:token` 出现密码弹窗，未登录可到达 |
| I4 | 分享错误密码处理 | PASS | 2026-11-03-36-share-wrong-password.png | 提示「访问密码错误，还可尝试 4 次」；同一错误出现两条 toast（见备注第 3 条） |
| I5 | 分享匿名问答 | PASS | 2026-11-03-37-share-chat-anonymous.png、2026-11-03-38-share-anonymous-answer.png | 输入正确密码进入，匿名提问得到带来源的答案 |
| I6 | 开放 API Key 管理 | PASS | 2026-11-03-32-api-key-tab.png | API Key tab 正常，已有 Key 列表可见 |
| I7 | Widget 嵌入 demo 页面 | PASS | 2026-11-03-40-widget-demo.png | `widget-demo.html` 正常打开 |
| I8 | Widget 未授权时拒绝嵌入 | PASS | 2026-11-03-41-widget-embed-denied.png | 关闭「允许嵌入」时给出可读拒绝原因「该分享链接未开启 Widget 嵌入授权…」 |
| I9 | Widget 授权后 iframe 加载 | PASS | 2026-11-03-39-widget-embed-config.png、2026-11-03-42-widget-iframe-loaded.png | 开启嵌入授权并将 `localhost` 加入白名单后，demo 生成 iframe，src 含 `?embed=1&grant=eg_33ab...` |
| J1 | 管理端口 health | PASS | 无截图（命令行验证） | `9090/actuator/health` HTTP 200，db/redis/disk/ping/ssl 全部 UP |
| J2 | 管理端口 prometheus | PASS | 无截图（命令行验证） | `9090/actuator/prometheus` HTTP 200，约 103 KB 指标 |
| J3 | 业务端口 actuator 不可访问 | PASS | 无截图（命令行验证） | `8080/actuator`、`8080/actuator/health` 均返回 404，管理端点未暴露在业务端口 |

## 四、失败项根因初判

### 1. 文件预览失败（FAIL，C3）

- 页面 URL：知识库文件页 → 点击「预览」
- 操作步骤：登录 → 进入知识库（id=1 存量数据）→ 文件列表 → 点击预览
- 实际表现：未打开预览，弹出 toast「存储对象不存在: ./uploads/1/1793688741615_Wen Guanhai_….pdf」，控制台同文案
- 初判：数据库 `storage_path` 记录为相对路径 `./uploads/1/...`，但当前运行实例的本地存储目录中该文件已不存在。与仓库中 `rag-storage` 模块正被他人重构（`LocalFileStorageService` 等文件处于删除状态）相印证，怀疑为存量数据文件缺失或存储根目录与 DB 记录不一致（例如 `rag.storage.local.root` 指向变化）。属**数据/环境问题**，不是本次前端改动引入，未修改业务代码。

### 2. 工作流保存版本跳号 / 疑似重复提交（备注，F4）——**原初判已被推翻**

- 现象：保存后版本由 v3 变为 v5；`/workflows/129/versions` 中 v4（18:48:58.818）与 v5（18:49:03.742）的 `graphJson` 长度均为 630，间隔约 5 秒
- **原初判（已推翻）**：「一次保存可能触发了两次提交（保存按钮重复点击或编辑器内部二次触发）」。
- 复验结论：点一次「保存」只发 1 条 `PUT`，版本只 +1，不存在一次点击发两次请求。真实原因是保存入口有三个（手动保存、`校验` 内部 `save(true)`、`运行` 内部 `save(true)`），后两者无提示写库并递增版本；数据库中工作流 129 的 v1~v5 快照 md5 完全相同，印证为隐式保存造成的版本空转。已由 commit `a48f11d` 修复，详见 §八-1。

### 3. 分享错误密码提示重复（Minor UI）

- 现象：输入错误密码后同时出现两条相同的 toast
- 初判：错误处理链路上重复调用了一次消息提示（如 catch 中调用 + 全局拦截器调用）。不影响功能，判定 PASS 并记录。

### 4. 聊天流式请求 `net::ERR_ABORTED`（备注，D2）

- 现象：控制台出现 `net::ERR_ABORTED /api/chat/windows/24/stream`
- 初判：前端在 SSE 完成/组件卸载时主动 abort 连接，属预期行为；回答内容完整、统计指标正常，不影响功能。

## 五、环境与工具限制说明

1. **文件上传 BLOCKED**：Element Plus `el-upload` 使用隐藏的 `<input type=file>`，浏览器自动化工具无法选择本地文件，页面侧无任何报错。建议后续通过接口层或用可测试的可见 input 覆盖该用例。
2. **首登强制改密 BLOCKED**：管理端存在 `trial_*` 体验账号，但无可用密码；直接改写 localStorage `wgh_pwd_must_change=1` 不触发跳转（userStore 已 hydrate，守卫读取内存态）。测试后已重置该键，未污染环境。
3. **浏览器工具限制**：
   - `browser_take_screenshot` 的 `filename` 不生效，实际输出 jpg 到工具缓存目录，已用 `sips` 转为 png 归档；
   - 部分 Element Plus 组件（`el-switch`、`el-select` 选项）无稳定 ref，改用 DOM `evaluate` 设置值与派发事件；
   - `el-dropdown` 菜单需 hover 后才可点击。
4. **测试数据**：本次新增知识库 id=5、对话窗口 id=24、提示词模板 id=5、工作流版本 v5、Crew「浏览器验证-Crew-20261103」、分享 id=5。删除类用例均停留在二次确认弹窗并取消，未实际删除存量数据。

## 六、服务存活确认

| 服务 | 端口 | 状态 |
| --- | --- | --- |
| 后端 jar（测试时段 PID 19927；当前 PID 41603，jar 22:05:05） | 8080 / 9090 | 测试全程监听；测试后重新打包重启，见 §八-3 |
| 前端 Vite（PID 90478） | 5173 | 全程监听，未重启 |

测试结束后 `9090/actuator/health` 仍返回 UP，8080 业务接口正常响应。重新打包重启后复验：`9090/actuator/health` = 200，`8080/actuator/health` = 404（管理端点未暴露在业务端口，与 J3 一致）。

## 七、证据目录

所有截图位于 `docs/browser-evidence/`，统一前缀 `2026-11-03-`，共 43 个文件（01 ~ 43）。既有旧截图未修改、未恢复、未 checkout。

> 说明：当前工作区中该目录的旧截图已被他人删除（未提交改动），不在本次文档提交范围内；本报告仅记录测试时的证据文件名。

## 八、修复与复验（2026-11-03 晚）

本节记录测试报告产出后的三项修复与复验，证据来源为对应 commit 与实际验收输出；**不新增、不删除** §三 的 46 条用例。

### 1. 工作流保存「重复提交」已查明并修复（commit `a48f11d`，对应 F4）

- **结论：推翻 §四-2 的猜测。** 不是一次点击发两次请求——复验时点一次「保存」只发 1 条 `PUT /api/workflows/{id}`，版本只 +1。
- **真实原因**：保存入口有三个——手动保存、`校验` 内部 `save(true)`、`运行` 内部 `save(true)`；后两者无提示写库并递增版本。数据库中工作流 129 的 v1~v5 快照 md5 完全相同，印证是隐式保存造成的版本空转。
- **附带修复**：`@click="save"` 把 MouseEvent 当作 `silent` 参数传入，导致保存成功没有 toast。
- **修法**：保存收敛为单一入口 + 内容指纹（`graphKey` / `savedGraphKey`）去重 + `saving` 互斥；内容未变不发请求并提示「内容未变更，无需保存」。「校验」改为只读：前端 `POST /api/workflows/{id}/validate` 携带画布草稿，后端新增可选 body，不写库、不递增版本，不传 body 保持旧行为（`WorkflowController#validate` → `WorkflowService#validateGraph`）。
- **验证**：`rag-workflow` 83 项测试全过（含新增 `WorkflowServiceSaveVersionTest`），`npm run build` 通过；浏览器复验「不改内容点保存 → 0 条 `PUT`、版本不动」「点校验 → 0 条 `PUT` / 1 条 `POST`、版本不动」。

### 2. 模型故障转移 P0 已修复（commit `e496f71`）

- **根因**：LangChain4j 的 `StreamingChatModel.chat()` 是异步的，请求提交后立即返回；旧的 `runWithFailover` 把「`chat()` 正常返回」当作成功，第一轮就 `return`，导致首 token 前的连接失败 / 超时 / 4xx / 5xx 走 `onError` 时切链循环早已退出，只能发 `error` 结束。
- **修法**：改为回调驱动的故障转移。`StreamAttempt` 记录每次尝试的 started / active 状态与独立输出缓冲；`onError` 统一进入 `handleAttemptFailure`；`active` + CAS 防止重复 `onError` 触发多次切链；每次尝试独立缓冲，避免内容污染。
- **部分输出后失败策略**：不切链、不回滚、不重发、不拼接第二个模型输出，发 `error` 如实结束，不产生第二个 `done`。
- **全部候选失败**：返回明确业务错误「所有候选模型均调用失败（共 N 个，最后错误: …）」，不空响应、不挂起。
- **测试**：新增 4 项（切链成功 / 全部失败 / 部分输出后失败 / 重复 `onError` 只切一次），`rag-chat` 59 项全过。
- **仍保留的边界**：Agent / 工作流链路仍只用主模型，未纳入切链。
- **未验证**：真实模型连通性与浏览器端 SSE 切链未测（本地无可用 key），故本节不声明浏览器用例通过。

### 3. 后端已重新打包重启，两处修复生效（无回滚）

- 旧 jar 备份为 `rag-app-1.0.0-SNAPSHOT.old-20261103.jar`；新 jar 时间 `22:05:05`；`mvn -o -pl rag-app -am package -DskipTests` BUILD SUCCESS（63s，16 模块）。
- 新进程 PID **41603**；8080 中断约 4 分 31 秒；复验 `9090/actuator/health` = 200、`8080/actuator/health` = 404。
- **新 validate 逻辑生效证据**：工作流 130 草稿校验，不传 body → `data: []`；传含悬空连线草稿 → `data: ["连线目标节点不存在：missing_node"]`（旧 jar 忽略 body，不可能返回该错误）。校验后 `version` 仍为 1、`updateTime` 未变、日志中 `version=?` 的 UPDATE 计数为 0。
- jar 内嵌 `rag-chat` / `rag-workflow` lib 时间戳 `22:04`；`javap` 确认包含 `RagChatService$FailoverContext` 与 `RagChatService$StreamAttempt`。
- `admin/admin` 登录返回 200，响应与 JWT 均含 `roles=['admin']`。

### 4. 保持原状的项（未修复，不做美化）

- **C3 文件预览仍为 FAIL**：`./uploads/1/...pdf` 存储对象不存在；**用户已决定暂不处理**，本次重新打包与重启未改变该数据/环境问题。
- **C2 文件上传、A7 首登强制改密仍为 BLOCKED**：维持原判定，未新增证据。
- **分享错误密码双 toast**：仍为已知 minor 现象，未修复。

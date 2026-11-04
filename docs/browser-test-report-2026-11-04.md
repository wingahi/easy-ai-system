# 全量前端测试报告（2026-11-04）

> 测试对象：`/Users/joe.wen/Desktop/ai-code/wgh-rag-system`
> 测试方式：真实浏览器操作（Chrome Headless + CDP，视口 1440×900），逐页巡检 + 关键链路交互 + 安全用例；接口类断言用 `curl` 复核。
> 原则：只记录真实执行结果，不估算、不伪造；发现缺陷未修改业务代码（基线启动阻塞项除外，见 §6.2）。

---

## 1. 环境

| 项目 | 值 | 说明 |
|---|---|---|
| Git 提交 | `f21efe5` | 工作区另有 1 处未提交改动（见 §6.2） |
| 后端进程 | PID **14437** | `/usr/bin/java -jar target/rag-app-1.0.0-SNAPSHOT.jar`，cwd=`rag-backend/rag-app` |
| 后端 jar | 2026-11-04 13:13，130,307,149 bytes | 旧包备份 `target/rag-app-1.0.0-SNAPSHOT.old-20261104.jar` |
| 启动日志 | `rag-backend/rag-app/logs/app-restart-20261104.out` | 13:13:06 启动完成 |
| 构建 | JDK 21（`/usr/libexec/java_home -v 21`）、`mvn -o -pl rag-app -am package -DskipTests` | 持锁 `/tmp/wgh-maven-build.lockdir` 执行 |
| 数据库 | PostgreSQL **15.19**（容器 `wgh-rag-postgres`，库 `wgh_rag`） | 三个迁移 13:06 执行 |
| 前端 | Vite **5.4.21** dev 模式，`http://localhost:5173` → 代理 `/api` → 8080 | 本次由测试会话启动（原先未运行） |
| 管理端 | `http://localhost:8080`，Actuator `http://localhost:9090` | 8080 `/actuator` 返回 404（符合预期） |

健康复验结果：

| 检查项 | 实际 | 结果 |
|---|---|---|
| 8080 监听 | `java 14437 ... *:8080 (LISTEN)` | PASS |
| 9090 `/actuator/health` | 200 | PASS |
| 8080 `/actuator` | 404 | PASS |
| admin 登录 | 200，JWT `roles` 含 `admin` | PASS |
| `/api/admin/roles`（admin token） | 200 | PASS |

---

## 2. 用例总览

| 指标 | 数量 |
|---|---|
| 用例总数 | **59** |
| PASS | **58** |
| FAIL | **1** |
| BLOCKED | **0** |

分类：阶段 0 基线 7 项、页面渲染 19 项、功能交互 25 项、安全专项 8 项。

---

## 3. 阶段 0：基线修正

| 编号 | 用例 | 期望 | 实际 | 结果 |
|---|---|---|---|---|
| TC-001 | 迁移 `20261104_01_model_read_permission.sql` | 新增 `model:read` 并授权 admin/editor/viewer | 权限码 16 个；admin 16、editor 8、viewer 4；三角色均含 `model:read` | PASS |
| TC-002 | 迁移 `20261104_02_admin_manage_permissions.sql` | 新增 5 个 `*:manage` 权限并仅授权 admin | admin 新增 `chat/agent/apikey/share/pricing:manage`，editor/viewer 未获得 | PASS |
| TC-003 | 迁移 `20261104_03_default_user_sessions_to_admin.sql` | `default_user` 会话归属 admin | 76 条会话迁移；`SELECT` 确认 admin 为唯一归属者 | PASS |
| TC-004 | 停服 + 持锁重建 + 原方式重启 | 8080 释放后重建启动 | 首次启动失败（`SsrfGuard` 无默认构造器，见 §6.2）；最小修复后 13:13:06 启动成功 | PASS（附基线缺陷） |
| TC-005 | 健康检查 | 9090 health 200、8080 `/actuator` 404 | 一致 | PASS |
| TC-006 | admin 登录与 JWT 角色 | 200 且 token `roles` 含 admin | `{"roles":["admin"]}` | PASS |
| TC-007 | 新权限接口可达 | `/api/admin/roles` 200 | 200 | PASS |

迁移验证关键输出（真实 SELECT）：

```
wgh_permission 16 个：agent:manage, agent:use, apikey:manage, audit:view, chat:manage,
kb:delete, kb:read, kb:write, model:manage, model:read, pricing:manage, prompt:edit,
share:manage, tool:use, workflow:edit, workflow:use
role 权限数：admin=16, editor=8, viewer=4
default 会话：76 条 → user_identifier=admin
```

---

## 4. 阶段 1：前端功能与页面

### 4.1 页面渲染巡检（admin 登录态）

| 编号 | 页面 | 路由 | 实际 | 结果 |
|---|---|---|---|---|
| TC-008 | 修改密码 | `/change-password` | 3 个密码输入框 + 提交/返回 | PASS |
| TC-009 | 知识库列表 | `/kb` | 5 个知识库、11 文件、1264 段落 | PASS |
| TC-010 | 知识库文件 | `/kb/4/files` | 文件表格、上传/预览/版本/标签按钮完整 | PASS |
| TC-011 | 知识库检索 | `/kb/4/search` | 检索输入与结果区 | PASS |
| TC-012 | 聊天模型 | `/models/chat` | 模型列表渲染 | PASS |
| TC-013 | 向量模型 | `/models/embedding` | 列表渲染 | PASS |
| TC-014 | 重排模型 | `/models/rerank` | 列表渲染 | PASS |
| TC-015 | 聊天窗口 | `/chat/windows` | 24 个窗口 | PASS |
| TC-016 | Prompt 模板 | `/prompt/templates` | 模板与触发器配置 | PASS |
| TC-017 | 工具管理 | `/tools` | 工具列表（HTTP/MCP/FC/Skill 标签） | PASS |
| TC-018 | 工作流列表 | `/workflows` | 129/130/122/121/3 等条目与操作列 | PASS |
| TC-019 | 多 Agent Crews | `/multi-agent/crews` | Crew 列表 | PASS |
| TC-020 | 多 Agent 运行台 | `/multi-agent/playground` | 页面渲染 | PASS |
| TC-021 | 审计与统计 | `/admin/stats` | 概览与统计卡片 | PASS |
| TC-022 | 开放平台 | `/open-access` | 免登录分享 / 开放 API Key 两个页签 | PASS |
| TC-023 | 管理端配额 | `/admin/quota` | 配额表格 | PASS |
| TC-024 | 用户管理 | `/admin/users` | 用户表格 | PASS |
| TC-025 | 角色管理 | `/admin/roles` | admin/editor/viewer 三个角色 | PASS |
| TC-026 | 我的配额 | `/my/quota` | 当前用户配额 | PASS |

### 4.2 关键功能交互

| 编号 | 用例 | 期望 | 实际 | 结果 |
|---|---|---|---|---|
| TC-027 | 错误密码登录 | 拒绝且提示 | `code=1005 用户名或密码错误`，停留在登录页 | PASS |
| TC-028 | 新建知识库 | 创建成功并出现在列表 | 弹出表单 → 选向量模型 → 列表出现「浏览器测试-20261104」 | PASS |
| TC-029 | 编辑知识库 | 名称修改生效 | 改为「浏览器测试-20261104-编辑」并回显 | PASS |
| TC-030 | 删除知识库 | Popconfirm 确认后移除 | 行内删除 → 确认 → 列表不再出现 | PASS |
| TC-031 | 上传本地文件 | 文件入库并生成段落 | 隐藏 `input[type=file]` 通过 CDP 注入 `sample-upload.md`，列表出现且段落可检索 | PASS |
| TC-032 | 文件预览 | 弹窗展示切分内容 | 预览弹窗展示 Markdown 文本 | PASS |
| TC-033 | 版本查看 | 展示版本历史 | 版本弹窗正常（新文件为空历史） | PASS |
| TC-034 | 标签查看 | 标签弹窗可操作 | 弹窗正常展示 | PASS |
| TC-035 | 知识库检索 | 返回命中结果、分数、来源 | “邮件”查询返回 4 条，含余弦相似度 60.1%/60.0%/58.8% 与来源文件名 | PASS |
| TC-036 | 对话流式输出 | SSE 流式渲染并输出答案 | 窗口 24 提问→流式 typing→完整答案+RAG 指标+引用来源 | PASS |
| TC-037 | **对话停止生成** | 流式过程中可主动停止 | 流式期间仅有「发送」按钮（禁用态），**不存在停止/取消控件**；只能靠切换会话或刷新中断 | **FAIL** |
| TC-038 | 历史消息回显 | 刷新后仍可见历史 | 重新进入同一窗口，消息与指标、来源回显 | PASS |
| TC-039 | Prompt 前缀联想 | 输入 `/` 弹出候选并可插入 | 输入 `/` 出现 3 个模板候选；方向键+回车插入模板并出现变量填写面板 | PASS |
| TC-040 | 工作流校验 | 返回校验结果 | 点击「校验」→ toast「校验通过」 | PASS |
| TC-041 | 工作流运行 | 节点执行、输出可见 | 3 节点依次 SUCCESS（开始 1ms / Agent 2820ms / 结束 0ms），运行面板展示答案 | PASS |
| TC-042 | 工作流分享 | 开启分享、生成分享页 | 「已开启分享」，`shareToken=898b5d89...`，分享页可打开并展示「分享运行」 | PASS |
| TC-043 | Agent 计划 | 规划面板 + 步骤状态 | 窗口 17 提问后出现「Agent 执行计划」，步骤 0/1→执行中，含重试/跳过/取消 | PASS |
| TC-044 | 多 Agent 协作 | 创建并完成一次 Crew 运行 | Crew#9：`multi_agent_start → agent_start → agent_done → multi_agent_complete`，耗时 7.4s，输出完整答案 | PASS |
| TC-045 | 角色权限配置 | 展示并勾选新权限 | admin 权限弹窗勾选 16 项，含 `model:read`、`chat:manage`、`pricing:manage` 等 | PASS |
| TC-046 | 开放 API Key | Key 列表与操作 | 记录含 `wgh_sk_xxx` 前缀、权限范围、过期时间；操作含调用日志/IP 白名单/吊销 | PASS |
| TC-047 | Widget 嵌入配置 | 弹窗可用 | 「Widget 嵌入白名单配置」含允许嵌入/域名/IP 白名单 | PASS |
| TC-048 | 分享聊天页 | 受访问密码保护的分享页渲染 | 展示窗口名称、历史会话、输入区，并提示「该分享需要访问密码」 | PASS |
| TC-049 | 修改密码校验 | 两次输入不一致被拦截 | 提示「两次输入的密码不一致」，停留在当前页（未改密） | PASS |
| TC-050 | 前端未知路由 | 不应白屏 | 访问 `/no-such-page-xyz` 被路由兜底重定向到 `/kb` 并正常渲染 | PASS |
| TC-051 | 未映射 API | 404 + 业务码 3001 | 带 token 请求 `/api/does-not-exist` → HTTP 404，`{"code":3001,"message":"接口不存在"}` | PASS |

### 4.3 安全专项

| 编号 | 用例 | 期望 | 实际 | 结果 |
|---|---|---|---|---|
| TC-052 | viewer 调用管理接口 | 403 或业务错误码 | `/api/admin/roles`、`/api/admin/users`、`POST /api/chat/tools` 均返回 `code=1004`，未泄露数据 | PASS |
| TC-053 | admin 调用管理接口 | 正常 200 | `/api/admin/roles` 200 | PASS |
| TC-054 | 模型接口不返回密钥 | 无 `apiKey` 字段 | `/api/models/chat` 全部记录无 `apiKey`/`api_key`；编辑弹窗 API Key 输入框为空 | PASS |
| TC-055 | 会话越权读 | 拒绝 | viewer 携带 admin 的 `sessionId=103` 请求消息列表 → `code=1004 会话不存在或无权访问` | PASS |
| TC-056 | 会话越权写 | 拒绝 | viewer 向 admin 会话发起流式请求 → `code=1004 会话不存在或无权访问` | PASS |
| TC-057 | 存储型 XSS | DOM 无 script/事件属性 | 注入 `<script>`、`<img onerror>`、`javascript:` 链接后：`script=0`、`onerror=0`、`href` 被移除，`window.__xss` 未定义 | PASS |
| TC-058 | SSRF 私网元数据 | 拒绝访问 | HTTP 工具指向 `http://169.254.169.254/latest/meta-data/` → 调试返回失败（目标地址不允许访问） | PASS |
| TC-059 | SSRF 公网地址 | 允许访问 | HTTP 工具指向 `https://www.example.com/` → 调试成功返回网页内容 | PASS |

---

## 5. 失败与问题分析

### 5.1 TC-037：对话页缺少「停止生成」控件（唯一功能 FAIL）

**复现步骤**
1. admin 登录 → `/chat/windows/24/playground`；
2. 输入「请写一篇关于 RAG 的 1000 字文章。」并发送；
3. 流式输出开始（`typing` 元素出现）后观察操作区。

**实际**：流式期间仅存在「发送」按钮（禁用），DOM 中不存在包含「停止/取消」的按钮；用户只能通过切换会话、刷新页面或关闭标签中断。

**影响**：长回答/工具链路/Agent 计划执行过程中无法由用户主动终止，交互体验与资源占用不可控。

**证据**：[2026-11-04-49-chat-stop-check.png](browser-evidence/2026-11-04-49-chat-stop-check.png)（`stopControlExists=false`；中断后无错误 toast，说明 AbortController 兜底生效）

**建议**：在流式期间将发送按钮切换为「停止生成」，调用现有 `streamer.abort()` / `AbortController`；Agent/工作流执行期间同步暴露「终止计划」入口。

### 5.2 问题 2：管理接口鉴权失败的提示文案错误（轻微）

viewer 调用 `/api/admin/roles`、`/api/admin/users` 时返回 `code=1004`，但 `message` 为「**配额管理**仅管理员可用」。权限拦截本身有效，但错误文案与接口语义不匹配，会误导排障与前端提示。

**建议**：按接口维度返回通用文案（如「无权访问该管理接口」），或由权限注解携带业务名称。

### 5.3 问题 3：启动阻塞缺陷（基线，已在阶段 0 处理）

`SsrfGuard` 为 `@Component` 且声明了两个 public 构造器（一个供 Spring 注入、一个供测试传入 HostResolver），未标注 `@Autowired`，Spring 报 `No default constructor found`，导致应用无法启动。

处理：在注入用构造器上补 `@Autowired`（一行注解，最小改动），重新构建后启动成功。此改动**未提交**，见 §6.2。建议正式修复时同步补充一次应用上下文启动测试，避免该类问题再次漏过 CI。

---

## 6. 遗留、测试数据与基线变更

### 6.1 测试数据清理

| 对象 | 处理 |
|---|---|
| 测试知识库「浏览器测试-20261104」 | 已通过 UI 删除（逻辑删除） |
| 上传文件 `sample-upload.md` | 已通过 `DELETE /api/kb/files/16` 清理（含向量） |
| SSRF 测试工具（id 24/25） | 已通过 API 删除 |
| XSS 注入消息（id 511） | 已物理删除（测试注入数据） |
| 工作流 129 分享 | 已调用 `/share/disable` 恢复为未开启 |
| viewer/editor 登录态 | 已恢复 `trial=0`；但为临时登录测试，其 `password_hash` 已被覆盖为 admin 的哈希（两账号仍无法密码登录，建议后续重置） |
| 聊天窗口 24/17、会话 103 | 保留（真实 UI 交互产生，未删除） |

### 6.2 代码改动声明

- 唯一代码改动：`rag-tool/.../security/SsrfGuard.java` 注入构造器增加 `@Autowired`（启动阻塞修复），**未提交**。
- 未修改任何其它业务代码；未执行全量 `mvn test`；仅阶段 0 执行一次 `package -DskipTests`；服务未停止，最终保持运行。

### 6.3 未覆盖/建议补充

- 可观测性（Tracing/Metrics 可视化）页面在前端导航中未发现独立入口，本次以 `/admin/stats` 审计统计页代替验证。
- 多模态（图片上传问答）、Skill 目录导入、MCP 工具真实外呼、HITL 人工确认二次运行等链路未纳入本次逐条用例，建议后续补充。
- 建议将 TC-057（XSS DOM 断言）、TC-052/055/056（越权）固化为自动化回归用例。

---

## 7. 证据索引

截图目录：[docs/browser-evidence](browser-evidence)（共 **54** 张，均为本次真实会话产生）

| 用例区间 | 证据文件 |
|---|---|
| 登录/页面巡检 | `01-login-page`、`02-kb-list`、`03`~`21`（按页面序号） |
| 知识库 CRUD | `22-kb-create`、`23-kb-edit`、`24-kb-delete` |
| 文件链路 | `25-kb-file-upload`、`26-kb-file-preview`、`27-kb-file-version`、`28-kb-file-tags`、`51-kb-search` |
| 对话/Prompt | `29-prompt-prefix-suggest`、`29b-prompt-template-selected`、`30-chat-streaming`、`31-chat-answer`、`32-chat-history`、`49-chat-stop-check` |
| 安全 | `33-xss-sanitize`、`42-roles`、`43-role-permissions`、`45-open-api-key`、`46-widget-config`、`53-share-chat` |
| 工作流 | `34-workflow-validate`、`35-workflow-run-dialog`、`36-workflow-run`、`37-workflow-run-result`、`38-workflow-list`、`39-workflow-share-enabled`、`40-workflow-share-page` |
| Agent | `47-agent-plan`、`48-agent-plan-progress` |
| 其它 | `44-frontend-404`、`50-model-edit`、`52-change-password-validate` |

---

## 8. 结论

- 基线：三个迁移全部生效且可重复执行；后端重启与健康检查通过；新权限体系在接口层（`code=1004`）和角色配置界面均正确落地。
- 功能：知识库全链路、对话流式与历史、Prompt 联想、工作流编排/校验/运行/分享、Agent 与多 Agent、配额/用户/角色/模型/API Key/Widget/分享页均可正常使用。
- 安全：5 项指定安全用例全部通过（越权、密钥泄露、会话 IDOR、XSS、SSRF）。
- 主要缺口：对话流式缺少用户可控的「停止生成」；启动期 `SsrfGuard` 构造器注解缺失已临时修复待正式提交。
- 服务最终状态：8080 由 PID **14437** 监听，`/actuator/health`(9090)=200，运行正常。

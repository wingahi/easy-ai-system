# 接口概览

> 本文是 [README](../../README.md) 接口概览章节的完整版，只列接口分组与前缀；完整请求体、响应示例与 SSE 事件协议见 [api.md](../api/api.md)。

| 模块 | 前缀 | 说明 |
|------|------|------|
| 鉴权 / OIDC | `/api/auth` | 本地登录、当前用户、改密、OIDC 状态 |
| 知识库 | `/api/kb` | 知识库 / 文件 CRUD、分片上传（`files/chunk/init` → `chunk/{sessionId}` → `complete`）、重切、检索测试、ground truth、召回评测 |
| 评测数据集 | `/api/kb/{kbId}/eval-datasets` | 数据集 / 用例 CRUD、版本列表、按版本评测归档、历史结果与跨版本对比 |
| 数据源连接器 | `/api/kb/{kbId}/connectors` | URL 连接器 CRUD、立即同步、运行记录 |
| 模型 | `/api/models` | Chat / Embedding / Rerank 模型 CRUD |
| 聊天 | `/api/chat/windows` | 窗口 CRUD、会话、消息、SSE 对话（支持 `Last-Event-ID` 重连）、反馈 |
| 分享（登录态旧入口） | `/api/chat/share` | 窗口分享开关与 token 管理 |
| 工具 | `/api/chat/tools` | 工具 CRUD、在线调试、Skill 目录管理 |
| Agent | `/api/agent` | 计划状态、HITL 确认、取消 / 重试 / 跳过 |
| 工作流 | `/api/workflows` | CRUD、校验、运行、HITL、版本、触发器、模板；`POST /{id}/debug/step` 单步调试（需开启调试开关） |
| 多 Agent | `/api/agent-multi` | Crew CRUD / 校验、运行、SSE、state、HITL |
| Prompt 模板 | `/api/prompt-templates` | CRUD、渲染预览、前缀联想候选（`/suggest`） |
| 标签 / 版本 | `/api/tags`、`/api/kb/files/{id}/versions` | 标签 CRUD 与绑定、版本列表与回滚 |
| 管理端 | `/api/admin` | 审计日志、成本 / 配额统计、差评、用户配额、角色权限、分享链接、API Key；调度实例 / 对账（`/scheduler/instances`、`/scheduler/reconcile`）；配额退款异常流水与人工退款 / 核销（`/quota/refunds/{chargeId}`） |
| 免登录分享 | `/api/public/share/{token}` | 信息 / 解锁 / 会话 / 消息 / SSE 问答 / 知识库检索 |
| 开放 API | `/api/open/v1` | `/me`、`/chat/stream`、`/kb/search`、`/usage`（`X-API-Key`） |
| OpenAI 兼容 | `/v1` | `/models`、`/chat/completions`（Bearer API Key，支持 SSE） |

> 流式端点（普通对话、分享对话、聊天窗口 HITL、分享 HITL、开放 API、公开分享）均支持 `Last-Event-ID`（请求头，或请求体 `lastEventId`）重连；重连只补齐事件，不重发消息、不重复确认 HITL。事件协议与信令见 [api.md](../api/api.md)。

---

[返回 README](../../README.md)

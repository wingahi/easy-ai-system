# 关键 API 参考

> 所有接口统一前缀 `/api`，响应体为 `{ code, message, data, timestamp }`（`code=200` 表示成功）。
> 除 `/api/auth/login`、`/api/workflows/webhook/{token}` 外，均需请求头 `Authorization: Bearer <JWT>`。
> 标注 SSE 的接口返回 `text/event-stream`，事件格式为 `event: <事件名>` + `data: <JSON>`。

## 1. 鉴权

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/auth/login` | 登录，返回 `token` / `expiresIn` / `username` |
| GET | `/auth/me` | 获取当前登录用户信息 |

## 2. 知识库

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/kb` | 知识库分页列表（`page` / `size` / `keyword`） |
| GET | `/kb/list` | 全量知识库（下拉选择用） |
| GET | `/kb/{id}` | 知识库详情 |
| POST | `/kb` | 新建知识库 |
| PUT | `/kb` | 更新知识库 |
| DELETE | `/kb/{id}` | 逻辑删除知识库 |
| POST | `/kb/{kbId}/files` | 上传文件（multipart：`file` / `splitStrategy` / `splitConfig`），异步解析+切分+向量化 |
| GET | `/kb/{kbId}/files` | 文件列表（含处理状态、chunk 数） |
| DELETE | `/kb/files/{fileId}` | 删除文件（同步清理 pgvector 中的对应向量） |
| POST | `/kb/files/{fileId}/resplit` | 重新分割（`splitStrategy` / `splitConfig`，旧向量同步清理） |
| GET | `/kb/files/{fileId}/preview` | 预览分割结果 |
| GET | `/kb/{kbId}/search` | 检索测试（`query`），返回 `results` / `totalMatches` / `filteredMatches` / `recallRate` / `hitRate` |

## 3. 模型管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/models/chat` | Chat 模型分页列表 |
| GET | `/models/chat/list` | 启用的 Chat 模型（下拉选择用） |
| GET | `/models/chat/{id}` | Chat 模型详情 |
| POST | `/models/chat` | 新增 Chat 模型 |
| PUT | `/models/chat` | 更新 Chat 模型（含启用/禁用、压缩模型标记） |
| DELETE | `/models/chat/{id}` | 逻辑删除 |
| GET | `/models/embedding` | Embedding 模型分页列表 |
| GET | `/models/embedding/list` | 启用的 Embedding 模型 |
| POST | `/models/embedding` | 新增 Embedding 模型 |
| PUT | `/models/embedding` | 更新 Embedding 模型 |
| DELETE | `/models/embedding/{id}` | 逻辑删除 |

## 4. 聊天窗口与会话

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/chat/windows` | 窗口分页列表 |
| GET | `/chat/windows/{id}` | 窗口详情（含 RAG / Agent / 评估 / 分享配置） |
| POST | `/chat/windows` | 新建窗口 |
| PUT | `/chat/windows` | 更新窗口 |
| DELETE | `/chat/windows/{id}` | 逻辑删除 |
| POST | `/chat/windows/{id}/enable` | 启用 |
| POST | `/chat/windows/{id}/disable` | 停用 |
| GET | `/chat/windows/{windowId}/sessions` | 当前用户在窗口下的会话列表 |
| POST | `/chat/windows/{windowId}/sessions` | 显式新建会话 |
| DELETE | `/chat/windows/{windowId}/sessions/{sessionId}` | 删除会话（连带逻辑删消息） |
| GET | `/chat/windows/{windowId}/messages` | 会话历史消息分页（`sessionId` / `page` / `size`） |
| POST | `/chat/windows/{windowId}/stream` | **SSE** 流式对话（窗口启用 Agent 时走 Agent 分支） |

## 5. 分享

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/chat/windows/{id}/share/enable` | 开启分享，返回 `shareToken` |
| POST | `/chat/windows/{id}/share/disable` | 关闭分享 |
| POST | `/chat/windows/{id}/share/regenerate` | 重新生成 token（旧链接立即失效，历史会话保留） |
| GET | `/chat/share/{shareToken}` | 通过 token 获取窗口信息（需登录） |
| POST | `/chat/share/{shareToken}/stream` | **SSE** 分享侧对话（与调试页同链路，支持 Agent 事件） |
| GET | `/chat/share/{shareToken}/sessions` | 当前登录用户在分享窗口下的会话列表 |
| POST | `/chat/share/{shareToken}/sessions` | 新建会话 |
| DELETE | `/chat/share/{shareToken}/sessions/{sessionId}` | 删除会话 |
| GET | `/chat/share/{shareToken}/messages` | 会话历史消息分页 |

## 6. 工具

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/chat/tools` | 工具分页列表（支持 `toolType` 过滤：`HTTP` / `MCP` / `SKILL` / `FUNCTION_CALLING`） |
| GET | `/chat/tools/list` | 启用工具全量列表 |
| POST | `/chat/tools` | 新增工具 |
| PUT | `/chat/tools` | 更新工具 |
| DELETE | `/chat/tools/{id}` | 逻辑删除 |
| POST | `/chat/tools/{id}/debug` | 在线调试（按 definition 解析参数） |
| GET | `/chat/tools/skill/dirs` | 扫描磁盘 Skill 目录 |
| GET | `/chat/tools/skill/content` | 读取 Skill 内容（`skillPath`） |
| POST | `/chat/tools/skill/save` | 保存 Skill（front matter 含 `subModelId` / `description`） |
| POST | `/chat/tools/skill/create-dir` | 新建 Skill 目录 |
| DELETE | `/chat/tools/skill/dir` | 删除 Skill 目录 |
| POST | `/chat/tools/skill/import` | 导入目录 Skill 到数据库（同名返回 `duplicated=true`，不覆盖） |

## 7. Agent 执行控制

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/agent/plans/{planId}/state` | 计划完整状态（含步骤列表） |
| GET | `/agent/sessions/{sessionId}/plan` | 按会话查最新计划（刷新恢复面板） |
| POST | `/agent/steps/{stepId}/hitl-confirm` | HITL 确认并继续（结果补写回会话） |
| POST | `/agent/plans/{planId}/cancel` | 取消计划（未执行步骤置 `SKIPPED`） |
| POST | `/agent/steps/{stepId}/retry` | 重试失败步骤 |
| POST | `/agent/steps/{stepId}/skip` | 跳过步骤 |

## 8. 工作流编排

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/workflows` | 分页列表（`page` / `size` / `keyword` / `status`） |
| GET | `/workflows/list` | 启用工作流全量列表 |
| GET | `/workflows/{id}` | 详情（含 `graph_json`） |
| POST | `/workflows` | 新建（图校验 + v1 快照） |
| PUT | `/workflows/{id}` | 保存（version+1，图校验 + 自动快照） |
| POST | `/workflows/{id}/validate` | 仅校验图，返回错误列表 |
| DELETE | `/workflows/{id}` | 逻辑删除 |
| POST | `/workflows/{id}/enable` \| `/disable` | 启停 |
| POST | `/workflows/{id}/run` | **SSE** 运行（body：`{ input }`） |
| GET | `/workflows/runs/{runId}/state` | 运行状态 + 节点记录（刷新恢复） |
| POST | `/workflows/runs/{runId}/hitl-confirm` | **SSE** 人工确认后续跑（body：`{ userInput }`） |
| POST | `/workflows/runs/{runId}/cancel` | 取消运行 |
| GET | `/workflows/{id}/runs` | 运行历史分页 |
| GET | `/workflows/runs/{runId}/nodes` | 节点级记录回放 |
| GET | `/workflows/{id}/versions` | 版本列表 |
| GET | `/workflows/{id}/versions/{version}` | 版本详情（含 `graph_json`） |
| POST | `/workflows/{id}/rollback/{version}` | 回滚（基于旧版本生成新版本） |
| GET | `/workflows/templates` | 内置模板列表 |
| POST | `/workflows/templates/{templateId}/instantiate` | 模板实例化（自动填充占位 `modelId`） |

### 触发器

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/workflows/{id}/triggers` | 触发器列表 |
| POST | `/workflows/{id}/triggers` | 新增（`CRON` 6 段表达式 / `WEBHOOK`） |
| PUT | `/workflows/triggers/{triggerId}` | 更新触发器 |
| POST | `/workflows/triggers/{triggerId}/enable` \| `/disable` | 启停 |
| DELETE | `/workflows/triggers/{triggerId}` | 删除 |
| POST | `/workflows/webhook/{token}` | Webhook 免密触发（系统生成的 token） |

## 9. 多 Agent 协作

### Crew（编排模板）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/agent-multi/crews` | Crew 列表 |
| GET | `/agent-multi/crews/{id}` | Crew 详情（含 `agentsJson` / `crewConfigJson` / `graphJson` / `groupChatConfigJson`） |
| POST | `/agent-multi/crews` | 新建 Crew |
| PUT | `/agent-multi/crews/{id}` | 更新 Crew |
| DELETE | `/agent-multi/crews/{id}` | 逻辑删除 |
| POST | `/agent-multi/crews/{id}/validate` | 校验编排配置（Agent 引用 / 图结构 / GroupChat 配置） |

### 运行

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/agent-multi/runs` | 创建并启动运行（body 见下） |
| GET | `/agent-multi/runs/{runId}/stream` | **SSE** 运行事件流 |
| GET | `/agent-multi/runs/{runId}/state` | 运行状态 + 步骤列表（刷新恢复 / 轮询） |
| GET | `/agent-multi/runs/{runId}/steps` | 步骤列表 |
| POST | `/agent-multi/runs/{runId}/hitl-confirm` | **SSE** HITL 确认后续跑（body：`{ userInput }`） |
| POST | `/agent-multi/runs/{runId}/cancel` | 取消运行 |

`POST /agent-multi/runs` 请求体：

```json
{
  "crewId": 1,
  "executionMode": "CREW_SEQUENTIAL | CREW_HIERARCHICAL | CREW_PARALLEL | GRAPH_ROUTED | GROUP_CHAT_ROUND_ROBIN | GROUP_CHAT_DYNAMIC_ROUTER",
  "input": "任务输入",
  "userGoal": "任务目标",
  "chatWindowId": 0,
  "sessionId": 0,
  "maxRounds": 3,
  "terminationCriteria": "终止条件（GroupChat 可选）",
  "dynamicRouterModelId": 4,
  "dynamicRouterPrompt": "路由 Prompt（可选）",
  "summarizerAgentId": "汇总 Agent（可选）"
}
```

## 10. SSE 事件协议

### 聊天（含 Agent 分支）

| 事件 | 关键字段 |
|------|---------|
| `start` | `sessionId` |
| `retrieve` / `sources` | `sources[]`（fileName / kbName / score / content） |
| `content` | `content`（**JSON 包装，避免换行丢失**） |
| `accuracy` | `recallRate` / `hitRate` / `accuracyRate` |
| `tool_call_start` / `tool_call_result` | `toolName` / `arguments` / `success` / `durationMs` |
| `done` | `messageId` / `durationMs` |

Agent 开启后额外事件：`plan_created`、`plan_updated`、`plan_paused`、`plan_resumed`、`plan_cancelled`、`plan_complete`、`plan_failed`、`step_start`、`step_success`、`step_failed`、`step_reflection`、`step_hitl_pending`。

### 工作流

| 事件 | 关键字段 |
|------|---------|
| `workflow_start` | `runId` / `workflowId` / `totalNodes` |
| `node_start` | `nodeId` / `nodeType` / `nodeName` / `iteration` |
| `node_output` | `nodeId` / `output` / `durationMs` |
| `node_error` | `nodeId` / `error` |
| `node_hitl_pending` | `nodeRunId` / `nodeId` / `prompt` |
| `workflow_complete` | `output` / `durationMs` / `executedNodes` |
| `workflow_failed` | `nodeId` / `error` |
| `workflow_cancelled` | `runId` |

### 多 Agent

| 事件 | 关键字段 |
|------|---------|
| `multi_agent_start` / `multi_agent_resume` / `multi_agent_complete` | `runId` / `output` / `rounds` / `durationMs` |
| `multi_agent_error` | `error` |
| `agent_start` / `agent_done` | `agentId` / `agentName` / `role` / `input` / `output` / `toolCount` / `ragCount` / `durationMs` |
| `agent_rag` | `kbCount` |
| `agent_tool_call_start` / `agent_tool_call_result` | `toolName` / `arguments` / `result` / `success` / `latencyMs` |
| `agent_reflection` | `phase`（start/done）/ `score` / `feedback` / `pass` |
| `agent_turn_start` / `agent_turn_end` | `round` / `output` |
| `agent_message` | `fromAgentId` / `content` |
| `agent_hitl_pending` | `agentId` 或 `nodeId` / `prompt` |

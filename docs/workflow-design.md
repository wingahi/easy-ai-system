# 工作流编排（Workflow Orchestration）系统设计

> 版本：v1.0　｜　适用代码：`rag-workflow` 模块 + `WorkflowController` + 前端 `views/workflow`
> 关联设计：[agent-design.md](./agent-design.md)

---

## 1. 需求分析

### 1.1 背景

当前系统已支持三种对话形态：

| 形态 | 入口 | 编排能力 |
| --- | --- | --- |
| 普通 RAG 对话 | `RagChatService.streamChat` | 固定链路：检索 → 组装 Prompt → LLM，不可编排 |
| 工具调用对话 | `ToolExecutionEngine` + ReAct 循环 | 由 LLM 自主决定调用哪个工具，链路不可控 |
| Agent 自动规划 | `AgentOrchestrator` | 运行时由 Planner 动态生成步骤，链路不确定、不可复用 |

Agent 适合"目标明确、步骤未知"的开放任务，但对**步骤固定、需要审核、需要复用**的业务流程（如：合同审查、工单分流、报告生成）不合适——每次都要重新规划，结果不稳定，也无法由业务人员在界面上调整。

因此需要补充第四种形态：**可视化工作流编排**，把"先做什么、再做什么、什么条件下走哪条分支"从 LLM 手里收回来，变成可编辑、可版本化、可审计的资产。

### 1.2 目标用户与核心诉求

| 角色 | 诉求 |
| --- | --- |
| 业务配置人员 | 在画布上拖拽节点、连线、填写参数，不写代码即可搭出流程 |
| 开发者 | 提供可复用的 LLM / 知识库 / 工具节点，支持变量在节点间传递 |
| 运维/审核人员 | 查看每次运行的节点级耗时、输入输出、失败原因；敏感节点支持人工确认 |

### 1.3 功能需求（本期）

1. **工作流 CRUD**：列表、新建、编辑、启停、逻辑删除。
2. **可视化 DAG 画布**：节点拖拽、连线、缩放、小地图、自动布局。
3. **节点类型**：开始、结束、LLM、知识库检索、工具调用、条件分支、循环、人工确认、变量赋值。
4. **变量引用**：`{{input}}`、`{{nodeId.text}}`、`{{vars.x}}`、`{{loop.iteration}}`。
5. **运行调试**：SSE 实时推送节点状态，右侧面板展示每个节点的输入/输出/耗时/错误。
6. **人工确认（HITL）**：运行到 HITL 节点暂停，前端确认后从断点续跑。
7. **运行历史**：按工作流查询历史运行，可回放节点级记录。
8. **失败策略**：节点失败默认终止运行；可配置"忽略错误继续"。

### 1.4 非目标（本期不做）

- 多用户协作编辑 / 草稿冲突合并（用乐观锁版本号 `version` 兜底）
- 运行时并行分支（本期串行调度，为后续并行预留）
- 定时触发 / Webhook 触发 / 事件触发（仅手动触发起步）
- 子工作流嵌套调用（节点协议已预留 `WORKFLOW` 类型扩展位）

---

## 2. 主流实现调研与设计取舍

| 产品 | 编排模型 | 变量传递 | 运行追踪 | 可借鉴点 | 不适用点 |
| --- | --- | --- | --- | --- | --- |
| **Dify** | 可视化 DAG，节点 = LLM / 知识检索 / 代码 / 条件分支 / 迭代 | `{{#nodeId.field#}}` | 节点级 trace + 变量池 | 变量池 + 节点级 trace 的 UI 表达 | DSL 过于厚重，依赖其 runtime |
| **Coze** | 画布 DAG + 插件节点 | `{{node.output}}` | 逐节点日志 | 节点分类清晰、调试面板友好 | SaaS 闭源，无法照搬引擎 |
| **n8n** | 节点 + 连线，强调集成 | `$json` / `$node["x"].json` | 执行列表 + 单步重放 | 执行记录模型（execution + node execution）| 以 HTTP/集成节点为核心，AI 节点弱 |
| **Flowise / LangFlow** | LangChain 节点图 | 链式输入输出 | 节点级日志 | 基于 LangChain 的节点复用方式 | 面向开发者，无业务化配置面板 |

**结论**：采用 **Dify / Coze 式「可视化 DAG + 变量引用 + 节点级 trace」** 作为产品形态，执行内核**自研轻量引擎**（不引入 Dify DSL / Temporal 等外部依赖），节点能力全部复用系统现有组件：

- LLM 节点 → `ModelCacheManager#getChatModel`
- 知识库节点 → `RagRetrieveService#searchMultiple`
- 工具节点 → `ToolExecutionEngine#execute`
- 追踪/流式 → 复用项目既有 SSE 事件风格（与 `AgentOrchestrator` 一致）
- 异步执行 → 独立线程池（与知识库文件处理一致，禁止裸线程）

---

## 3. 总体架构

```
┌──────────────────────────────────────────────────────────────┐
│  前端 (Vue3 + Element Plus + @vue-flow/core)                 │
│  WorkflowList.vue        列表 / 新建 / 运行历史                 │
│  WorkflowEditor.vue      画布 + 节点配置抽屉 + 运行调试面板      │
└───────────────┬──────────────────────────────────────────────┘
                │ REST (Result 包装) + SSE (fetch)
┌───────────────▼──────────────────────────────────────────────┐
│  rag-controller  →  WorkflowController                        │
│      /api/workflows/**            CRUD + 运行 + 状态查询       │
└───────────────┬──────────────────────────────────────────────┘
┌───────────────▼──────────────────────────────────────────────┐
│  rag-workflow                                                 │
│  ┌────────────────┐  ┌────────────────┐  ┌─────────────────┐ │
│  │ WorkflowService│→ │ WorkflowEngine │→ │ NodeExecutor    │ │
│  │ CRUD / 调度    │  │ 图遍历 + 状态机 │  │ 9 类节点执行     │ │
│  └────────────────┘  └────────────────┘  └─────────────────┘ │
│         │                    │                   │           │
│         │              VariableResolver    ConditionEvaluator│
│         ▼                    ▼                   ▼           │
│   MyBatis-Plus          run.context_json   表达式求值         │
└───────────────┬──────────────────────────────────────────────┘
                │ 复用
   rag-llm / rag-kb / rag-tool / rag-common / rag-config
```

**分层职责**

| 层 | 职责 | 不做的事 |
| --- | --- | --- |
| Controller | 参数校验、SSE 通道建立、`Result` 包装 | 不写业务逻辑 |
| Service | 事务边界、运行生命周期（创建/暂停/恢复/取消）、线程池提交 | 不做节点执行 |
| Engine | 图校验、拓扑推进、节点调度、上下文变量维护、节点级持久化 | 不感知 HTTP |
| NodeExecutor | 单一节点类型的执行，返回结构化 `NodeResult` | 不感知图结构 |
| Mapper | 数据访问 | 不含逻辑 |

---

## 4. 数据模型

工作流图采用 **`graph_json` 反范式存储**（节点数组 + 边数组），而非拆成 `node` / `edge` 两张表：

- 画布编辑是"整图保存"语义，拆表需要全量 diff，收益低；
- 图规模小（典型 < 50 节点），JSONB 单行读取即可；
- PostgreSQL JSONB 便于按节点类型做统计查询。

运行数据则拆成 `run` 与 `node_run` 两级，便于节点级查询与回放。

### 4.1 `wgh_workflow` 工作流定义

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | BIGSERIAL | 主键 |
| name | VARCHAR(100) | 名称 |
| description | VARCHAR(500) | 描述 |
| status | SMALLINT | 1 启用 / 0 停用 |
| version | INT | 版本号，每次保存 +1（乐观锁提示用） |
| graph_json | JSONB | `{ "nodes": [...], "edges": [...] }` |
| create_time / update_time / create_by / update_by / deleted | — | 公共字段（逻辑删除） |

### 4.2 `wgh_workflow_run` 运行实例

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | BIGSERIAL | 主键 |
| workflow_id | BIGINT | 所属工作流 |
| workflow_version | INT | 运行时的版本快照 |
| input_text | TEXT | 运行输入 |
| status | VARCHAR(20) | RUNNING / PAUSED / COMPLETED / FAILED / CANCELLED |
| output_text | TEXT | 最终输出 |
| context_json | JSONB | 运行上下文（变量池 / 节点输出 / 待恢复节点） |
| error_message | TEXT | 失败原因 |
| executed_nodes / total_nodes | INT | 进度 |
| start_time / finish_time | TIMESTAMP | 时间 |
| duration_ms | BIGINT | 总耗时 |

### 4.3 `wgh_workflow_node_run` 节点级执行记录

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | BIGSERIAL | 主键 |
| run_id | BIGINT | 所属运行 |
| node_id | VARCHAR(64) | 图中节点 ID |
| node_type | VARCHAR(30) | 节点类型 |
| node_name | VARCHAR(100) | 节点显示名 |
| status | VARCHAR(20) | PENDING / RUNNING / SUCCESS / FAILED / SKIPPED / HITL_PENDING |
| iteration | INT | 循环体第几次执行（非循环节点为 0） |
| input_json / output_json | JSONB | 渲染后的入参 / 出参 |
| error_message | TEXT | 失败原因 |
| start_time / end_time / duration_ms | — | 耗时 |

索引：`(run_id, node_id)`、`(workflow_id, start_time)`、`status`。

---

## 5. 节点协议

`graph_json.nodes[]` 为 `GraphNode`：

```json
{
  "id": "n_llm_1",
  "type": "LLM",
  "name": "生成答复",
  "position": { "x": 320, "y": 160 },
  "config": { },
  "onError": "STOP"
}
```

`graph_json.edges[]` 为 `GraphEdge`：

```json
{ "id": "e_1", "source": "n_start", "target": "n_llm_1", "sourceHandle": "default" }
```

`sourceHandle` 用于分支选择：条件节点为 `true` / `false`，循环节点为 `loop` / `done`，普通节点为 `default`。

### 5.1 节点类型与配置

| type | 中文 | config 字段 | 输出（写入变量池） |
| --- | --- | --- | --- |
| `START` | 开始 | — | `{ text: 运行输入 }`，同时 `vars.input` |
| `END` | 结束 | `output`：模板字符串 | 运行最终输出 |
| `LLM` | 大模型 | `modelId`、`systemPrompt`、`prompt`、`maxToolRounds`(预留) | `{ text }` |
| `RETRIEVAL` | 知识库检索 | `kbIds: number[]`、`query`、`topK`(默认 5) | `{ text, sources: [{fileName,chunkIndex,score}] }` |
| `TOOL` | 工具调用 | `toolId`、`arguments: object`（值为模板字符串） | `{ text, success }` |
| `CONDITION` | 条件分支 | `expression` | `{ result: true/false }` |
| `LOOP` | 循环 | `maxIterations`(默认 3)、`breakCondition`(可选) | `{ iteration }` |
| `HITL` | 人工确认 | `prompt` | `{ text: 用户输入 }` |
| `VARIABLE` | 变量赋值 | `assignments: [{name, value}]` | 写入 `vars.<name>` |

### 5.2 变量引用语法

统一使用 `{{ path }}`（允许两侧空格），渲染发生在**节点执行前**：

| 表达式 | 含义 |
| --- | --- |
| `{{input}}` | 本次运行输入 |
| `{{vars.orderId}}` | 变量池中的变量 |
| `{{n_llm_1.text}}` | 某节点输出的 `text` 字段 |
| `{{n_rag_1.sources.0.fileName}}` | 数组下标访问 |
| `{{loop.iteration}}` | 当前循环轮次（从 1 开始，仅循环体内有效） |

未解析到的路径渲染为空串（不抛错），便于流程在缺省数据下继续调试。

### 5.3 条件表达式

条件节点与循环中断条件使用**白名单式极简求值器**（不引入 Groovy/SpEL，避免注入风险）：

1. 先做变量渲染 → 得到字符串 `left op right`；
2. 支持运算符：`==`、`!=`、`>`、`<`、`>=`、`<=`、`contains`、`is_empty`；
3. 两侧若都能解析为数字则按数值比较，否则按字符串比较；
4. 无运算符时按"真值"判断：非空、非 `false`、非 `0` 即为真。

示例：`{{n_llm_1.text}} contains "通过"`、`{{vars.score}} >= 0.8`。

---

## 6. 执行引擎

### 6.1 图校验（保存时 + 运行前）

1. 必须且只能有 1 个 `START`；至少 1 个 `END`；
2. 边的 `source` / `target` 必须存在；
3. `START` 不允许有入边，`END` 不允许有出边；
4. 除"回边（指向 LOOP 节点的边）"外不允许成环——DFS 检测，回边白名单；
5. 条件 / 循环节点的出边 `sourceHandle` 必须落在合法集合内。

校验失败返回 `Result.fail`，运行时不产生脏数据。

### 6.2 调度算法（串行工作队列）

```
frontier ← [startNodeId]
while frontier 非空:
    nodeId ← frontier.pop()
    node = nodes[nodeId]
    if node 是 LOOP 且已执行过（回边进入）:
        break = loopIterations[nodeId] >= maxIterations 或 breakCondition 为真
        if not break:
            iteration = loopIterations[nodeId] + 1     // 首次执行时已初始化为 1
            loopIterations[nodeId] = iteration
        else:
            iteration = loopIterations[nodeId]          // 结束轮次沿用最后一轮
        持久化 node_run（iteration）并 SSE: node_output
        frontier ← (break ? done : loop) 边目标
        continue
    result = NodeExecutor.execute(node, context)         // 见 6.3
    持久化 node_run（RUNNING → SUCCESS / FAILED）
    SSE: node_output
    frontier ← selectNext(node, result)
```

`selectNext` 规则：

| 节点 | 下一跳 |
| --- | --- |
| END | 无（运行结束） |
| CONDITION | `sourceHandle == String.valueOf(result)` 的边 |
| LOOP（首次） | `sourceHandle == "loop"` 的边（maxIterations = 0 时走 `done`） |
| 其他 | 所有出边（本期串行，按数组顺序入队） |

### 6.3 节点执行

每个节点执行统一流程：

1. **渲染入参**：对 config 中所有模板字符串做 `VariableResolver` 替换；
2. **记录 node_run**：插入 RUNNING 记录（含渲染后 input）；
3. **执行**：
   - `LLM` → `ChatRequest.builder().messages(...)` 同步 `model.chat()`；
   - `RETRIEVAL` → `searchMultiple(kbIds, query)`，拼接上下文并保留 sources；
   - `TOOL` → `ToolDefinitionParser.extractFunctionNode` 取函数名，构造 `ToolExecutionRequest`，走 `ToolExecutionEngine.execute(req, registry, null)`；
   - `CONDITION` / `LOOP` → `ConditionEvaluator`；
   - `HITL` → 抛 `PauseSignal`（见 6.4）；
   - `VARIABLE` / `START` / `END` → 纯内存计算。
4. **写回变量池**：`context.nodeOutputs[nodeId] = output`；
5. **更新 node_run**：SUCCESS + output + duration；
6. **推送 SSE** `node_output`。

异常处理：

- 节点异常 → node_run = FAILED + error_message；
- `onError == "CONTINUE"` → 标记失败但继续走下一跳（输出为空对象）；
- 否则 → run = FAILED，推送 `workflow_failed`，终止。

### 6.4 人工确认（HITL）与断点恢复

暂停：

1. node_run 状态置 `HITL_PENDING`，记录 prompt；
2. 运行上下文写入 `context_json`：`pendingNodeIds`（HITL 节点的后继）、`loopIterations` 等；
3. run = `PAUSED`；
4. 抛出 `PauseSignal` 中断工作队列，**不发** `workflow_complete`；
5. SSE 推送 `node_hitl_pending { runId, nodeRunId, nodeId, prompt }`。

恢复（`POST /api/workflows/runs/{runId}/hitl-confirm`）：

1. 校验 run 为 `PAUSED` 且存在 `HITL_PENDING` 节点；
2. node_run → SUCCESS，output = `{ text: 用户输入 }`；
3. run → `RUNNING`，提交线程池，`frontier ← pendingNodeIds` 继续；
4. 后续事件沿用原 SSE（若前端已断开，则以 REST 轮询 `state` 兜底）。

### 6.5 失败 / 取消

- 取消：run → `CANCELLED`，未完成 node_run → `SKIPPED`；工作队列在每次迭代前检查 run 状态，发现非 RUNNING 即退出。
- 前端刷新：SSE 断开不影响后端执行（发送异常仅记 warn），前端通过 `GET /runs/{id}/state` 轮询恢复视图——与 Agent 模块保持一致。

### 6.6 线程模型

- 运行在专用 `ThreadPoolTaskExecutor`（core 2 / max 8 / queue 100），禁止裸线程；
- 每次节点执行结束后立即持久化，避免 JVM 崩溃丢失进度；
- **先落库、再发 SSE**（沿用 Agent 模块教训，防止刷新丢结果）。

---

## 7. 接口设计

### 7.1 REST

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/workflows` | 分页列表（`page`/`size`/`keyword`/`status`） |
| GET | `/api/workflows/list` | 全量启用工作流（下拉选择用） |
| GET | `/api/workflows/{id}` | 详情（含 graph） |
| POST | `/api/workflows` | 新建（校验图） |
| PUT | `/api/workflows/{id}` | 保存（version+1，校验图） |
| POST | `/api/workflows/{id}/validate` | 仅校验图 |
| DELETE | `/api/workflows/{id}` | 逻辑删除 |
| POST | `/api/workflows/{id}/enable` / `/disable` | 启停 |
| POST | `/api/workflows/{id}/run` | SSE 运行（body：`{ input }`） |
| GET | `/api/workflows/runs/{runId}/state` | 运行状态 + 节点记录（刷新恢复） |
| POST | `/api/workflows/runs/{runId}/hitl-confirm` | 人工确认续跑（body：`{ userInput }`） |
| POST | `/api/workflows/runs/{runId}/cancel` | 取消运行 |
| GET | `/api/workflows/{id}/runs` | 运行历史分页 |
| GET | `/api/workflows/runs/{runId}/nodes` | 节点级记录回放 |

### 7.2 SSE 事件

| 事件 | payload |
| --- | --- |
| `workflow_start` | `{ runId, workflowId, workflowName, totalNodes }` |
| `node_start` | `{ runId, nodeId, nodeType, nodeName, iteration }` |
| `node_output` | `{ runId, nodeId, nodeName, output, durationMs }` |
| `node_error` | `{ runId, nodeId, nodeName, error }` |
| `node_hitl_pending` | `{ runId, nodeRunId, nodeId, nodeName, prompt }` |
| `workflow_complete` | `{ runId, output, durationMs, executedNodes }` |
| `workflow_failed` | `{ runId, nodeId, error }` |
| `workflow_cancelled` | `{ runId }` |

所有 payload 均为 JSON（`SseEmitter.event().name(x).data(json)`），避免换行丢失。

---

## 8. 前端原型

### 8.1 页面结构

```
工作流管理 /workflows                     工作流编辑器 /workflows/:id/edit
┌──────────────────────────────┐          ┌────────────────────────────────────────────┐
│ 工作流管理           [新建]   │          │ ← 返回  合同审查流程 v3   [校验][保存][运行] │
├──────────────────────────────┤          ├──────────┬──────────────────────┬──────────────┤
│ ID 名称 节点数 状态 更新时间  │          │ 节点面板  │  画布（Vue Flow）     │ 运行/配置抽屉 │
│ 操作：编辑 运行 历史 删除     │          │ 开始      │  ┌────┐              │ 运行输入      │
│                              │          │ 大模型    │  │开始│              │ [执行]        │
│ [运行历史抽屉]               │          │ 知识库    │  └─┬──┘              │ ── 节点轨迹 ──│
│  run#12 COMPLETED 3.2s       │          │ 工具      │   ▼                 │ 1 开始 12ms   │
│  run#11 FAILED   1.1s        │          │ 条件      │  ┌────┐  ┌────┐     │ 2 检索 320ms  │
└──────────────────────────────┘          │ 循环      │  │检索│→ │LLM │     │ 3 LLM 1.2s    │
                                          │ 人工确认  │  └────┘  └─┬──┘     │ 4 条件 ✔      │
                                          │ 变量      │           ▼        │               │
                                          │           │        ┌────┐      │ [输出结果]     │
                                          │           │        │结束│      │               │
                                          │           │        └────┘      │               │
                                          └──────────┴──────────────────────┴──────────────┘
```

### 8.2 交互要点

1. **节点面板**：点击 / 拖拽添加节点，自动分配 `id`（如 `n_llm_3`）并放在视口中心附近；
2. **节点连线**：从节点右侧 handle 拖到目标节点左侧 handle；条件/循环节点提供多 handle（`true/false`、`loop/done`）；
3. **节点配置**：单击节点 → 右侧抽屉展示按类型动态表单（模型下拉、知识库多选、工具下拉、表达式输入、提示词文本域），实时写回节点 `config`；
4. **运行状态着色**：节点执行中黄色、成功绿色、失败红色、暂停橙色，未执行灰色；
5. **运行面板**：底部输入框填写测试输入 → 点击执行 → 逐条追加节点轨迹（可点击定位到画布节点）；HITL 节点弹出确认框；
6. **保存**：整图序列化 `{nodes, edges}` 提交，后端校验并 version+1；
7. **刷新恢复**：编辑器持有最近一次 `runId`，进入页面时查询状态，若 RUNNING 则 2s 轮询、PAUSED 则弹确认框。

### 8.3 节点视觉规范

| 类型 | 颜色 | 图标 |
| --- | --- | --- |
| START / END | `#67c23a` / `#909399` | 播放 / 停止 |
| LLM | `#409eff` | 魔术棒 |
| RETRIEVAL | `#e6a23c` | 文档 |
| TOOL | `#8e44ad` | 扳手 |
| CONDITION | `#f56c6c` | 分支 |
| LOOP | `#16a085` | 刷新 |
| HITL | `#d46b08` | 用户 |
| VARIABLE | `#606266` | 标签 |

---

## 9. 与 Agent 模块的边界

| 维度 | Agent | Workflow |
| --- | --- | --- |
| 链路来源 | LLM 运行时生成 | 人工预先编排 |
| 稳定性 | 每次不同 | 固定可复现 |
| 适用 | 开放探索型任务 | 固定 SOP、需审核 |
| 存储 | `wgh_agent_plan` / `wgh_agent_step` | `wgh_workflow` / `wgh_workflow_run` / `_node_run` |
| 共性 | SSE 事件风格、先落库后推送、HITL 暂停续跑、刷新轮询恢复、独立线程池 | 同左（刻意对齐，降低维护成本） |

---

## 10. 演进路线

| 方向 | 状态 | 说明 |
| --- | --- | --- |
| 并行分支 | ✅ 已实现 | `frontier` 并发调度 + `pendingInEdges` 入边等待计数器，支持 fan-out/fan-in |
| 子流程节点（`WORKFLOW`） | ✅ 已实现 | 工作流作为节点被复用，最大嵌套 3 层，`inputTemplate`/`outputTemplate` 变量渲染 |
| 版本管理 | ✅ 已实现 | `wgh_workflow_version` 快照表，保存/回滚均生成新版本（只增不改），版本列表 + 回滚接口 |
| 触发方式 | 🔲 未实现 | 定时（Quartz）/ Webhook / 被 ChatWindow 作为工具调用 |
| 节点市场 | 🔲 未实现 | 把常用节点组合导出为模板 |
| 灰度发布 | 🔲 未实现 | 基于版本快照的流量分配 |

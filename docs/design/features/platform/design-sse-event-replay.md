# SSE 事件重放（多副本断线续传）设计

> 版本：v1.0 ｜ 日期：2026-11-24
> 范围：为 SSE 流式响应建立事件序号与跨副本共享缓冲，支持客户端按 `Last-Event-ID` 重连补齐缺失事件；给出 Ingress Cookie 亲和的去留判断。
> 约束：不改 `sql/`、`deploy/k8s/`、`docker-compose*.yml`、`application.yml`；前端默认不改；新增配置以增量片段记录。
> 关联文档：[design-kubernetes-orchestration.md](design-kubernetes-orchestration.md)、[design-react-task-recovery.md](../agent/design-react-task-recovery.md)。

---

## 0. 结论摘要

1. **事件编号**：每条约 SSE 事件带 `id: <streamId>:<seq>`，`seq` 为流内单调递增（从 1 开始），不新增负载字段，`event`/`data` 结构不变。
2. **共享缓冲**：事件批量写入 Redis List（`{root}:stream:buf:{streamId}`），另有元信息、当前流指针、重连租约三个辅助键；容量、TTL、单流上限与截断策略见 §3。
3. **重连**：沿用原 POST 流接口，带标准 `Last-Event-ID` 头（或请求体 `lastEventId`）即进入「接管」模式——先回放 `seq > last` 的缓冲事件，再按 300ms 轮询持续跟随；**不重新生成、不重复扣费、不重复写用户消息**。
4. **断连不等于失败**：客户端刷新/卸载/网络断开时只停止向下游推送，生成继续、执行状态保持原状，交给重连续看、续跑或最终持久化收口；用户主动停止仍落 `STOPPED`。详见 §4.4。
5. **降级不静默**：Redis 关闭/故障时退回「实时流 + 断连即取消」，上报 `wgh.stream.replay.fallbacks` 与 WARN；重连请求返回明确错误（3001/3003），由客户端回退到会话历史，绝不从头重放或重启生成。
6. **覆盖**：对话流（管理端、登录分享页、公开分享、开放 API）与「聊天窗口绑定的工作流 HITL 流」。**未覆盖**独立工作流运行、多 Agent 运行、OpenAI 兼容入口，原因见 §6。
7. **K8s 亲和**：**现阶段建议保留 Cookie 亲和**。判断依据见 §7：前端尚无自动重连、两条流仍未接入缓冲、且重放无法覆盖「生成实例被杀」之后的尾部事件。

---

## 1. 现状与问题

| 事实 | 位置 | 影响 |
|---|---|---|
| 流式出口统一为 `ExecutionStreamSink`，SSE 适配器只写 `event`/`data` | `rag-backend/rag-execution/src/main/java/com/wgh/rag/execution/event/ExecutionStreamSink.java`、`rag-backend/rag-controller/src/main/java/com/wgh/rag/controller/support/SseExecutionStreamSink.java` | 无事件 id，无法表达断点 |
| 对话断连即取消生成并落库 `STOPPED` | `rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/service/stream/ChatStreamHandle.java` | 跨副本重连时「断点之后」没有事件可补 |
| 事件只在当前实例内存中流转 | `rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/service/stream/ChatEventPublisher.java` | 副本一换即丢 |
| Ingress 仅关闭缓冲 + Cookie 亲和 | `deploy/k8s/base/ingress.yaml`、`deploy/k8s/overlays/k2-multi/` | 亲和只覆盖「同一浏览器落在同一 Pod」，Pod 驱逐后失效 |

---

## 2. 事件编号与写出链路

```
业务执行体
  └─ ExecutionStreamSink.emit(type, payload)
       └─ ReplayableStreamSink  ← 新增：分配 seq、写 Redis 缓冲、断连吞掉写出异常
            └─ SseExecutionStreamSink  ← 增加：SseEmitter.event().id("<streamId>:<seq>")
```

- `ExecutionStreamSink` 新增默认方法 `emit(type, payload, eventId)`；未覆盖的实现自动忽略 `eventId`，既有调用方零改动。
- 事件 id 的前缀 `streamId` 形如 `chat-<32 位 uuid>` / `hitl-<32 位 uuid>`，不含冒号，便于用最后一个 `:` 拆出序号。
- 流内序号来自 `ReplayableStreamSink` 的本地计数器：单条流只有一个生成实例写入，无需 Redis 计数器；
  批量写入只影响「入缓冲的时机」，不影响 id 与顺序。
- 前端现有解析器只识别 `event:`/`data:` 行，`id:` 行会被自然忽略——**不配 ID 也不破坏现有契约**。

---

## 3. 缓冲结构、容量与清理

### 3.1 Redis 键

| 键 | 类型 | 内容 | TTL |
|---|---|---|---|
| `{root}:stream:buf:{streamId}` | List | 每元素为 `{"seq":n,"type":"...","data":...}` | 活跃 35m，每次写入续期；终态切 5m |
| `{root}:stream:meta:{streamId}` | String | `kind:subjectId:status:lastSeq` | 同上 |
| `{root}:stream:cur:{kind}:{subjectId}` | String | 当前流 id，新一次生成覆盖 | 同上 |

`root` 复用 `wgh.rag.redis.key-prefix`（默认 `wgh`），与限流/取消等既有共享状态同一命名空间。

### 3.2 容量与上限口径

| 项 | 默认值 | 依据 |
|---|---|---|
| 单流事件条数 | 5000 | token 级 `content` 事件为主；单条约 50~200B，单流约 250KB~1MB。按 50 路并发流估算，Redis 增量约 12~50MB |
| 活跃 TTL | 35 分钟 | 覆盖聊天 30 分钟上限 + 余量；与 SSE 超时对齐 |
| 完成后 TTL | 5 分钟 | 只服务「刚结束就重连」；长期保留没有价值 |
| 刷盘间隔 | 200ms / 批 | 把「每 token 一次 Redis 往返」降为每秒 ≤5 次/流 |
| 截断 | LTRIM 丢头 | 超出上限保留最新事件；缺口由 `stream_replay_gap` 显式告知 |

清理：**不需要后台清扫任务**。所有键都有 TTL；终态时把缓冲 TTL 从 35m 降为 5m；截断在写入 Lua 脚本内原子完成。

### 3.3 原子性

`appendBoundedList` 用一段 Lua 原子完成 `RPUSH`（批量）+ `LLEN/LTRIM` + `EXPIRE`，避免重连方读到「写了一半/刚截断」的中间态。
序号与事件写入顺序由生成侧的 `synchronized` 保证；同一流的写入方唯一。

---

## 4. 重连协议

### 4.1 请求

沿用原有流式接口，不改 URL/Method：

| 场景 | 接口 | 所属对象 `subjectId` |
|---|---|---|
| 管理端对话 | `POST /api/chat/windows/{windowId}/stream` | `sessionId` |
| 登录分享页对话 | `POST /api/chat/share/{shareToken}/stream` | `sessionId` |
| 公开分享对话 | `POST /api/public/share/{token}/stream` | `sessionId` |
| 开放 API 对话 | `POST /api/open/v1/chat/stream` | `sessionId` |
| 聊天窗口 HITL | `POST /api/chat/windows/{windowId}/workflow-runs/{runId}/hitl-confirm` | `runId` |
| 分享页 HITL | `POST /api/chat/share/{shareToken}/workflow-runs/{runId}/hitl-confirm` | `runId` |

重连时携带 `Last-Event-ID: <streamId>:<seq>`；不便设置请求头的客户端可在 JSON 体加 `lastEventId`（请求头优先）。
对话重连必须同时带 `sessionId`，HITL 的 `runId` 在路径上。

### 4.2 服务端校验与行为

1. 解析出 `streamId + seq`；格式非法 → 2001。
2. 校验元信息存在、`kind` 与 `subjectId` 与本次请求一致；不一致 → 3003（防止拿别人的 id 拖流）。
3. 校验 `{root}:stream:cur:{kind}:{subjectId}` 仍指向该流；已被新一次生成覆盖 → 3003。
4. 流不存在/已过期（TTL 到期）→ 3001。此时**不会**退化为「重新生成」。
5. 通过后：回放 `seq > last` 的事件（保持原事件名、负载、id）→ 每 300ms 读尾部 → 直到终态/空闲/超时。

### 4.3 新增信令事件（仅重连时出现）

| 事件 | 触发 | data |
|---|---|---|
| `stream_replay_gap` | 客户端游标早于缓冲最老事件（超上限被截断） | `streamId`、`requestedFrom`、`oldestAvailable` |
| `stream_replay_incomplete` | 生成实例消失、缓冲降级、空闲 120s、跟随 29m 超时 | `streamId`、`lastSeq`、`reason`（`idle-timeout` / `buffer-degraded` / `follow-timeout` / `stream-expired` / `replay-error`） |

两者都是**增量事件**：旧客户端不认识可直接忽略（现有解析器只会把它们当未知事件类型）；建议新客户端据此回退到
`GET /api/chat/windows/{windowId}/workflow-state`、会话消息分页或运行状态接口，而不是无限重试。

### 4.4 状态语义：断开 ≠ 失败

**调查结论（2026-11-24 复核）**

| 场景 | 原行为 | 根因位置 |
|---|---|---|
| 对话流刷新/关闭页面 | 断连后取消并落 `STOPPED` | `ChatStreamHandle.onCompletion/onError/onTimeout` → `ChatCancellationStore` 本地取消 → `persistStopped` |
| 多 Agent 运行刷新/关闭页面 | Run 可能被误标 `FAILED` | `SseEventSender.send` 只捕获 `IOException`，失败后调用 `completeWithError`；对已完成 emitter 再发送/再 complete 会抛 `IllegalStateException`，冒泡到 `MultiAgentRuntime` 的 catch 分支 → `finishRun(FAILED)` |
| 独立工作流运行 | 不受影响 | `WorkflowService` 的 sink 已捕获 `Exception`，写失败被吞掉 |
| 前端卸载 | 只 abort 本地流，不调用停止接口 | 各页 `onUnmounted/beforeUnload` 仅 `streamer?.abort()` |

**修正后的语义**

- **意外断开**（刷新/卸载/网络）：`ReplayableStreamSink` 停止向下游推送并继续生成（Redis 可用时写缓冲）；
  `ChatStreamHandle` 不再调用 `cancel()`，执行状态保持不变。生成自然结束 → `COMPLETED`；真实模型错误 → `FAILED`。
- **用户主动停止**：`POST .../stop` 写入会话级共享取消标记（`source=explicit`），生成实例轮询消费后
  `persistStopped` → `STOPPED`，语义不变。
- **服务端进程被回收**：进程消失后不再产生事件，重连方以 `stream_replay_incomplete{reason=idle-timeout}`
  收口；多 Agent / 独立工作流的 Run 仍由实例失联回收逻辑处理，不会被本改动吞掉。
- **映射到多 Agent**：`SseEventSender` 首次写失败后把该 emitter 标记为关闭，后续事件直接跳过，
  不再抛异常/调用 `completeWithError`；序列化失败只跳过单个事件，不关闭连接。

只有总开关 `wgh.rag.stream.replay.enabled=false` 时，对话流才回退为「断连即取消」的旧语义。

---

## 5. 降级语义与可观测

| 故障 | 行为 | 指标 / 日志 |
|---|---|---|
| 重放开关关闭 | 只走实时流；事件 id 仍下发 | `wgh.stream.replay.fallbacks{kind,reason=disabled}`；启动后首次 WARN |
| Redis 未启用/连接失败（初始化） | 该流不缓冲；仍保持「断连不等于失败」，实时流继续；重连请求显式失败 | 同上 + WARN（30s 节流） |
| Redis 写失败（流进行中） | 标记 `degraded`、丢未刷盘批次、实时流继续 | `fallbacks{reason=error}` + WARN；重连侧回放已有事件后发 `stream_replay_incomplete{reason=buffer-degraded}` |
| 缓冲被截断 | 重连从最老可用事件继续 | `wgh.stream.replay.events{result=gap}` + WARN |
| 生成实例消失 | 重连回放已有事件后 `incomplete{reason=idle-timeout}` 并关闭 | `events{result=incomplete}` |
| 重连流过期/被取代 | 控制器返回 JSON 业务错误（3001/3003） | 不产生指标尖刺，按业务失败计 |

指标定义见 `rag-backend/rag-common/src/main/java/com/wgh/rag/common/metrics/RagMetrics.java`：
- `wgh.stream.replay.events{kind,result}`：`append`（条数）、`detached`、`gap`、`incomplete`、`detach_cancel`；
- `wgh.stream.replay.fallbacks{kind,reason}`：`disabled` / `error`。

标签均为有限枚举，不写 `streamId`/`sessionId`/`runId`，避免 Prometheus 序列爆炸。

---

## 6. 覆盖范围与不适用原因

| 流 | 是否覆盖 | 说明 |
|---|---|---|
| 对话流（管理端/分享页/公开分享/开放 API） | ✅ | 均汇聚到 `RagChatService.streamChat`，在会话解析后统一包装 |
| 聊天窗口绑定的工作流流（含 HITL、分享 HITL） | ✅ | 经 `ChatEventPublisher` → `ExecutionStreamSink` 输出 |
| 独立工作流运行 `POST /api/workflows/{id}/run` | ❌ | `WorkflowService` 直接操作进程内 `emitters` Map，不经过执行流 sink；重连需先改造成 sink 抽象。当前兜底为 `GET /api/workflows/runs/{runId}/state` + 节点记录 |
| 多 Agent 运行 `GET /api/.../runs/{runId}/stream` | ❌ | 控制器把 `SseEmitter` 直接传入 `MultiAgentRuntime`，事件走 `SseEventSender`；该接口本身是「再次执行」语义，重连会重复触发。兜底为 `GET .../runs/{runId}/state` 与步骤列表 |
| OpenAI 兼容入口 `POST /api/open/v1/chat/completions` | ❌ | 上游 `SseEmitter` 是内部转码通道，对外按 OpenAI chunk 输出，OpenAI 客户端没有 `Last-Event-ID` 语义；重放无消费方 |

后三类若要做，建议分别：把 `WorkflowService.run` 的 emitter 改为 sink、给多 Agent 增加独立「订阅」接口、OpenAI 入口不提供断点续传。

---

## 7. K8s / Ingress 侧能否去掉 Cookie 亲和

**结论：现阶段不去掉，保留 `affinity: cookie`。**

判断依据：

1. **前端尚未实现重连**：现有 `rag-frontend/src/api/index.js` 的 SSE 客户端用 `fetch + ReadableStream`，出错只回调 `onError`，不会重试，也不会记录最后一个 `id`。去掉亲和不会带来任何重连收益，反而在 Ingress 重载时更容易把连接换到陌生 Pod。
2. **覆盖不完整**：本方案只覆盖对话流与聊天窗口 HITL；独立工作流与多 Agent 流仍依赖进程内 emitter，去掉亲和会直接断流。这两个流完成改造前，亲和仍是它们的唯一保障。
3. **重放无法覆盖「生成实例被杀」**：重放补的是已写入缓冲的事件；生成 Pod 被杀后没有后续事件，只能以 `stream_replay_incomplete` 收口。此时客户端仍需状态接口兜底，亲和并不解决、但去掉亲和也不会更好。
4. **亲和的收益有限**：它只保证「同一浏览器落在同一 Pod」，Pod 驱逐/滚动更新后同样失效；它不能替代事件重放，但可以在前端未改造前减少无谓的跨副本切换。

可以去掉亲和的**前置条件**（同时满足）：
- 前端（及开放 API 的示例客户端）在断线时带 `Last-Event-ID` 重连，并能处理 `stream_replay_gap` / `stream_replay_incomplete`；
- 独立工作流与多 Agent 流接入共享缓冲或明确下线其 SSE（改为状态轮询）；
- 有跨副本重连的实测（给 Pod 打标签做定向断连，验证回放条数与最终状态一致）。

达到上述条件后，对话与 HITL 可以先去掉亲和，工作流/多 Agent 再跟进。

---

## 8. 前端是否需要配合

- **不配合同样可运行**：`id:` 行被忽略，事件名与负载不变；客户端断开后执行继续，最终结果写入历史。
- **要真正用上重放**，客户端需要：
  1. 解析并记住最后一个 `id:` 值；
  2. 重连时对同一 URL 发同样的 POST，并带 `Last-Event-ID`（或 JSON 体 `lastEventId`），对话需带 `sessionId`；
  3. 处理两个新增信令事件，必要时回退到消息分页/状态接口。
- **当前状态（2026-11-24）**：前端已接入上述三步——`rag-frontend/src/utils/sseStream.js` 解析 `id:`、
  按 `Last-Event-ID` 重连（指数退避 5 次、1s–15s，带抖动），重连请求体只带 `sessionId` + `lastEventId`，
  不重发消息；`rag-frontend/src/api/index.js` 的五个流式接口已统一走该客户端；
  `ChatPlayground.vue` / `ShareChat.vue` / `PublicShareChat.vue` 已消费 `stream_replay_gap` /
  `stream_replay_incomplete` 并展示提示。
- **前端仍需处理**：两个信令事件的**页面级兜底**尚未自动化——当前只展示文案并结束流式态，
  不会自动回退到消息分页/运行状态接口拉取完整结果，需要用户刷新会话；`stream_replay_gap` 时应主动补拉历史，
  这一动作留给后续迭代。

---

## 9. 配置

新增键：`wgh.rag.stream.replay.*`，共 9 个，默认值写在 `StreamReplayProperties`，增量片段：
`rag-backend/config/migrations/20261124/04_stream-replay.yml`。9 个键已写入主配置
`rag-backend/rag-app/src/main/resources/application.yml` 的 `wgh.rag.stream.replay` 段
（支持 `WGH_STREAM_REPLAY_*` 环境变量覆盖，值与片段一致）；片段保留为变更记录，不参与自动加载。

部署侧只需保证所有副本连同一 Redis；Redis 不可用时实时流不受影响，但重放能力降级（见 §5）。

---

## 10. 验证与未验证

已验证：
- JDK 21、持 `/tmp/wgh-maven-build.lockdir`，`mvn -o -DskipTests -pl rag-app -am compile` 全模块 BUILD SUCCESS；
- `rag-chat`、`rag-controller` 的 `test-compile` 通过（仅修正 `RagChatService` 构造参数，未新增测试）。

未验证（无可用多副本环境，且按要求不重启服务）：
1. 真实跨副本重连（Pod A 生成、Pod B 接管）的端到端时延与事件完整性；
2. Redis 故障注入下的降级路径与指标上报；
3. 刷新页面后对话保持 `RUNNING`/完成、多 Agent Run 不再误标 `FAILED` 的端到端验证；
4. Ingress 去掉亲和后的实际表现（见 §7 前置条件）。

---

## 附录：证据索引（相对仓库根目录）

| 结论 | 文件 |
|---|---|
| 事件出口契约 | `rag-backend/rag-execution/src/main/java/com/wgh/rag/execution/event/ExecutionStreamSink.java` |
| SSE 适配与 id 下发 | `rag-backend/rag-controller/src/main/java/com/wgh/rag/controller/support/SseExecutionStreamSink.java` |
| 可重放出口 | `rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/service/stream/replay/ReplayableStreamSink.java` |
| 缓冲与键结构 | `rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/service/stream/replay/StreamReplayBuffer.java` |
| 重连编排 | `rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/service/stream/replay/StreamReplayService.java` |
| 配置默认值 | `rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/service/stream/replay/StreamReplayProperties.java` |
| 对话接入点 | `rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/service/RagChatService.java` |
| 断连不取消 | `rag-backend/rag-chat/src/main/java/com/wgh/rag/chat/service/stream/ChatStreamHandle.java` |
| 多 Agent 断连不误判失败 | `rag-backend/rag-agent-multi/src/main/java/com/wgh/rag/agentmulti/support/SseEventSender.java` |
| 重连接口 | `rag-backend/rag-controller/src/main/java/com/wgh/rag/controller/chat/ChatController.java`、`rag-backend/rag-controller/src/main/java/com/wgh/rag/controller/publicaccess/PublicShareController.java`、`rag-backend/rag-controller/src/main/java/com/wgh/rag/controller/publicaccess/OpenApiController.java` |
| 指标 | `rag-backend/rag-common/src/main/java/com/wgh/rag/common/metrics/RagMetrics.java` |
| Redis 原子列表 | `rag-backend/rag-config/src/main/java/com/wgh/rag/config/redis/RedisCommandPort.java`、`SpringRedisCommandPort.java` |
| 增量配置 | `rag-backend/config/migrations/20261124/04_stream-replay.yml`（已合入 `application.yml`） |
| 前端重连客户端 | `rag-frontend/src/utils/sseStream.js` |
| 前端信令提示 | `rag-frontend/src/utils/chatStream.js`（`describeReplayNotice`） |

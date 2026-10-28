# WGH RAG System 全面测试用例

> 版本：v1.0　｜　日期：2026-10-28　｜　测试方法：黑盒 + 灰盒（已知 API 面）
> 分类：正常功能 / 边界条件 / 异常场景
> 覆盖模块：鉴权 · 知识库 · 模型 · 聊天 · Agent · 工作流 · 工具 · 上下文压缩 · 评估 · 分享 · 触发器 · 多 Agent 协作

---

## 目录

1. [鉴权与安全模块](#1-鉴权与安全模块)
2. [知识库与 RAG 链路](#2-知识库与-rag-链路)
3. [LLM / Embedding 模型管理](#3-llm--embedding-模型管理)
4. [聊天窗口 / 会话 / 分享](#4-聊天窗口--会话--分享)
5. [分层上下文压缩](#5-分层上下文压缩)
6. [RAG 评估双轨](#6-rag-评估双轨)
7. [Agent 自动规划执行](#7-agent-自动规划执行)
8. [工作流可视化编排](#8-工作流可视化编排)
9. [工具框架（4 类执行器 + Skill）](#9-工具框架4-类执行器--skill)
10. [触发器（CRON / Webhook）](#10-触发器cron--webhook)
11. [端到端集成场景](#11-端到端集成场景)
12. [多 Agent 协作框架（rag-agent-multi）](#12-多-agent-协作框架rag-agent-multi)

---

## 1. 鉴权与安全模块

| 编号 | 分类 | 用例 | 操作步骤 | 预期结果 |
|------|------|------|---------|---------|
| Auth-01 | 正常 | 正确账号登录 | POST /api/auth/login `{"username":"admin","password":"admin"}` | code=200，data.token 非空，expiresIn=86400 |
| Auth-02 | 边界 | 空用户名登录 | username="" | code≠200，不返回 token |
| Auth-03 | 边界 | 空密码登录 | password="" | code≠200 |
| Auth-04 | 异常 | 不存在的用户名 | username="nobody" | code≠200 |
| Auth-05 | 异常 | 错误密码 | 正确 username + 错误 password | code≠200 |
| Auth-06 | 边界 | JWT 过期后访问 | 篡改 expired 或等待超时 → 访问 `/api/workflows` | 返回 401，前端跳 `/login?redirect=原路径` |
| Auth-07 | 边界 | 未携带 Authorization header | 无 header 访问任意受保护 API | 返回 401 |
| Auth-08 | 边界 | 伪造 JWT（篡改 payload） | 用 jwt.io 修改 payload → 签名校验失败 | 返回 401 或 403 |
| Auth-09 | 边界 | 空 Bearer Token | `Authorization: Bearer `（Bearer 后空串） | 返回 401 |
| Auth-10 | 安全 | SQL 注入 username | username=`admin' OR '1'='1` | 登录失败，无异常堆栈泄露 |
| Auth-11 | 安全 | XSS password | password=`<script>alert(1)</script>` | 登录失败，无 script 执行 |
| Auth-12 | 安全 | 超长 username/password | 1000 字符 → 验证通过或返回友好错误（无 OOM） |
| Auth-13 | 正常 | 登录后访问 API 响应 401 重登录 | 正常流程，验证 redirect 回跳 | 跳回登录页 → 自动回跳原页面 |

---

## 2. 知识库与 RAG 链路

### 2.1 CRUD

| 编号 | 分类 | 用例 | 操作步骤 | 预期结果 |
|------|------|------|---------|---------|
| KB-01 | 正常 | 新建知识库 | POST /api/kb name=testkb，description=test | 返回新 ID，list 可见 |
| KB-02 | 正常 | 列出知识库 | GET /api/kb | 分页返回全部 |
| KB-03 | 边界 | 空名称知识库 | name="" | 校验失败或默认名 |
| KB-04 | 边界 | 超长名称（200 字符） | name=200 字符 | 截断或拒绝（无 crash） |
| KB-05 | 异常 | 删除不存在的 KB | DELETE /api/kb/99999 | 返回 404 或幂等成功（逻辑删除） |
| KB-06 | 正常 | 逻辑删除后 list 不可见 | 删除 KB → GET list | 已删除的 KB 不出现 |
| KB-07 | 边界 | 删除已删除的 KB | 重复 DELETE | 幂等（返回成功，第二次无效果） |

### 2.2 文件上传与解析

| 编号 | 分类 | 用例 | 操作步骤 | 预期结果 |
|------|------|------|---------|---------|
| KB-08 | 正常 | 上传 PDF 解析 | POST `/api/kb/{id}/files` multipart PDF | 返回 fileId，processing_status=PROCESSING → 最终 SUCCESS，chunk 数>0 |
| KB-09 | 正常 | 上传 DOCX | 同上 DOCX | 同上 |
| KB-10 | 正常 | 上传 XLSX | 同上 XLSX | 逐行逐格合并为 `cell1\|cell2\|...` 格式 chunk |
| KB-11 | 正常 | 上传 CSV | 同上 CSV | 每行一个 chunk |
| KB-12 | 正常 | 上传 TXT/MD | 同上 TXT/MD | 按双换行分段 |
| KB-13 | 异常 | 上传非支持格式 | .exe / .json / .png | 返回友好错误，解析不启动 |
| KB-14 | 异常 | 上传损坏文件 | 改扩展名伪装的 PDF | processing 状态 FAILED，error_message 可读 |
| KB-15 | 边界 | 上传空文件 | 0 字节 PDF | 返回错误，不 crash |
| KB-16 | 边界 | 上传超大文件 | > 50MB | 前端大小限制或后端超时保护 |
| KB-17 | 边界 | 同一文件重复上传 | 同一 KB 连续上传同 PDF 两次 | 两份独立 file 记录（重分割时各自清自己的向量） |

### 2.3 切分策略

| 编号 | 分类 | 用例 | 操作步骤 | 预期结果 |
|------|------|------|---------|---------|
| KB-18 | 正常 | AUTO 自动路由 FAQ | 上传 FAQ 格式内容（"问1...答..."） | 自动切为 FAQ 策略，一问一答一个 chunk |
| KB-19 | 正常 | AUTO 自动路由 CHAPTER | 上传含 `1. / 1.1 / # ##` 的文档 | 自动切为 CHAPTER 策略 |
| KB-20 | 正常 | RECURSIVE 递归切分 | 纯长段落文档 | 按分隔符层级降级切割，相邻 chunk 有 overlap |
| KB-21 | 正常 | CHAPTER 切分失败回退 PARAGRAPH | 上传无章节标记的纯正文文档 | 回退到 PARAGRAPH |
| KB-22 | 正常 | FAQ 切分失败回退 RECURSIVE | 上传无问答标记的正文 | 回退到 RECURSIVE |
| KB-23 | 边界 | 超长段落（1 万字符） | PARAGRAPH 策略 | 段落内部自动按 LENGTH 切分 |

### 2.4 向量检索

| 编号 | 分类 | 用例 | 操作步骤 | 预期结果 |
|------|------|------|---------|---------|
| KB-24 | 正常 | 检索测试命中 | GET `/api/kb/{id}/search?query=xxx`，query 命中文档 | 返回 hits>0，score≥min_similarity(0.55)，召回率/命中率指标计算正确 |
| KB-25 | 正常 | 检索测试不命中 | query 完全无关的内容 | hits=0，score 全 <0.55，metrics 可计算 |
| KB-26 | 边界 | 空 query 检索 | query="" | 返回空或友好错误（不 crash） |
| KB-27 | 边界 | 跨 KB 检索（不同 embedding 模型） | 2 个 KB 用不同 embedding 模型，同时检索 | 按模型 ID 分组检索，结果不混合错位 |
| KB-28 | 边界 | TopK 边界 | topK=0 / topK=1000 | 0 返回空、1000 返回受 DB 限制或截断 |
| KB-29 | 正常 | min_similarity 过滤生效 | query 相关度低 | 低于 0.55 的 chunk 被过滤，不返回 |
| KB-30 | 异常 | pgvector 不可用 | 停止 pgvector 扩展或 DB 断连 | 返回降级错误，不阻断应用启动（系统降级开关生效） |

---

## 3. LLM / Embedding 模型管理

| 编号 | 分类 | 用例 | 操作步骤 | 预期结果 |
|------|------|------|---------|---------|
| LM-01 | 正常 | 新增 Chat 模型（OpenAI 兼容） | POST /api/models/chat name=test, baseUrl, apiKey, provider=CUSTOM_OPENAI | 返回新 ID，list 可见 |
| LM-02 | 正常 | 新增 Embedding 模型 | POST /api/models/embedding | 同上 |
| LM-03 | 边界 | DASHSCOPE 提供商使用 OpenAI 兼容端点 | provider=DASHSCOPE 但用兼容 baseUrl | 返回警告或验证失败（应改为 CUSTOM_OPENAI） |
| LM-04 | 边界 | 缺失 API Key 的模型调用 | 新增模型 API Key=空 → 聊天触发 | 返回 401 InvalidApiKey 可读错误 |
| LM-05 | 边界 | 禁用模型后引用 | 禁用模型 → 聊天窗口引用该模型 | 返回友好错误或降级到默认模型 |
| LM-06 | 正常 | compression_model 标记独立模型 | 新增模型设 compression_model=true | 压缩时优先使用该模型，避开对话模型计费 |
| LM-07 | 边界 | 通义 SDK 路径兼容 | provider=DASHSCOPE 但未改 provider 为 CUSTOM_OPENAI | 调用返回 404（SDK 路径拼接问题），需修正 |
| LM-08 | 正常 | 模型热更新 | 编辑模型配置保存 → 下次 LLM 调用 | ModelCacheManager 热更新生效，无需重启 |
| LM-09 | 边界 | 默认 Embedding 模型唯一约束 | POST 第二个 default=true 的 Embedding 模型 | 返回唯一约束错误或自动切换 |

---

## 4. 聊天窗口 / 会话 / 分享

### 4.1 聊天窗口管理

| 编号 | 分类 | 用例 | 操作步骤 | 预期结果 |
|------|------|------|---------|---------|
| Chat-01 | 正常 | 新建聊天窗口 | POST /api/chat/windows name=test, chatModelId, knowledgeBaseIds | 返回新 ID |
| Chat-02 | 正常 | 编辑窗口 | PUT /api/chat/windows/{id} | 更新成功 |
| Chat-03 | 正常 | 停用/启用窗口 | POST /api/chat/windows/{id}/disable / /enable | 状态切换 |
| Chat-04 | 边界 | 停用窗口后对话 | 停用 → 发送消息 | 返回友好错误 |
| Chat-05 | 边界 | 窗口未选模型就保存 | chatModelId=NULL | 返回校验错误 |
| Chat-06 | 边界 | 关联禁用模型的窗口引用被禁用后行为 | 窗口 chatModelId 指向禁用模型 → 发送消息 | 401/403 + 可读错误 |

### 4.2 会话与 SSE 流式对话

| 编号 | 分类 | 用例 | 操作步骤 | 预期结果 |
|------|------|------|---------|---------|
| Chat-07 | 正常 | 新建会话 | POST /api/chat/windows/{id}/sessions | 返回 sessionId |
| Chat-08 | 正常 | SSE 流式发送消息 | POST /api/chat/windows/{id}/stream（body: sessionId, message） | SSE 事件流：start → retrieve（如关联 KB）→ content（多次）→ sources（如 RAG）→ accuracy → done |
| Chat-09 | 边界 | SSE content 事件换行 | content 事件内文本含 `\n` 换行符 | JSON 包装传输，前端渲染换行正确 |
| Chat-10 | 正常 | 历史会话加载 | GET /api/chat/sessions/{id}/messages?page=1&size=50 | 分页返回按 update_time DESC |
| Chat-11 | 边界 | 空会话发送消息 | 创建会话后还没发过消息 → 拉历史 | 返回空列表 |
| Chat-12 | 边界 | 会话滑动加载（第 2 页） | page=2 | 翻页正确，按 update_time DESC 排序 |
| Chat-13 | 正常 | 删除会话 | DELETE /api/chat/windows/{id}/sessions/{sid} | 该会话所有消息被逻辑删除 |
| Chat-14 | 边界 | 删除不存在的会话 | DELETE sessions/99999 | 返回 404 或幂等成功 |
| Chat-15 | 异常 | LLM API Key 过期/欠费时发送消息 | DASHSCOPE 欠费 → 发送消息 | SSE 中断或 done 事件带 error，不返回错误横幅 |
| Chat-16 | 边界 | 超长消息（10k+ 字符） | 发送超长 message | LLM 截断或返回错误（无 crash/OOM） |

### 4.3 分享链路

| 编号 | 分类 | 用例 | 操作步骤 | 预期结果 |
|------|------|------|---------|---------|
| Share-01 | 正常 | 开启分享 | POST /api/chat/windows/{id}/share/enable | 返回 shareToken |
| Share-02 | 正常 | 关闭分享 | POST /api/chat/windows/{id}/share/disable | 旧 token 立即失效 |
| Share-03 | 正常 | 重新生成分享 token | POST regenerate | 新 token，旧 token 失效，历史会话保留 |
| Share-04 | 安全 | 未登录访问分享页 | GET /share/{token}/window 无 Authorization | 返回 401（需登录），跳登录页 |
| Share-05 | 安全 | 伪造 token 访问 | 篡改 token → GET share/{token}/window | 返回 404 |
| Share-06 | 安全 | 分享页 userIdentifier 隔离 | 两个用户各登录同一分享 token → 各自发送消息 | 各自看到独立历史，不串会话 |
| Share-07 | 边界 | 分享关闭后继续访问 | 关闭分享 → 刷新分享页 | 返回 404 或友好提示 |
| Share-08 | 正常 | 分享页支持 Agent 事件 | 分享页 → 窗口开启 Agent → 发送多步目标 | SSE 正常下发 plan_* / step_* 事件 |
| Share-09 | 正常 | 分享页刷新恢复 | Agent 运行中刷新分享页 | 恢复计划面板 + 2 秒轮询 RUNNING 状态 |

---

## 5. 分层上下文压缩

| 编号 | 分类 | 用例 | 操作步骤 | 预期结果 |
|------|------|------|---------|---------|
| Cmp-01 | 正常 | 触发分层压缩（>20 条） | 同一窗口同一会话发 21 条消息 | 超过阈值触发压缩 |
| Cmp-02 | 正常 | 压缩持久化存储 | 触发压缩 → DB 查 wgh_chat_session | 中期摘要/远期事实落库，含来源轮次范围、模型、prompt 版本 |
| Cmp-03 | 正常 | 压缩结果复用 | 未滑动出已压缩范围 → 继续发消息 | 直接复用已压缩记录，不重复调 LLM |
| Cmp-04 | 边界 |  exactly 20 条不触发 | 发第 20 条 | 不触发 |
| Cmp-05 | 正常 |  Prompt 版本 bump 后旧压缩失效 | 修改 `compression.prompt.version` 后发消息 | 旧压缩标记失效，重新生成 |
| Cmp-06 | 边界 | 无压缩模型配置 | 清空 compression_model → 触发压缩 | 降级为截断或跳过，不 crash |
| Cmp-07 | 边界 | LLM API 异常时压缩 | 压缩时 LLM 超时/401 | 降级为简单截断，不阻塞主流程 |
| Cmp-08 | 正常 |  Prompt 显式保留优先级生效 | 中期/远期 prompt 中列出 6 级优先级 | 压缩结果中用户明确需求、实体名称优先保留 |

---

## 6. RAG 评估双轨

| 编号 | 分类 | 用例 | 操作步骤 | 预期结果 |
|------|------|------|---------|---------|
| Eval-01 | 正常 | 配置 METRICS 方案 | 窗口 evaluation_scheme=METRICS + 提供 ground truth | 返回 MRR、NDCG@K、Hit Rate、Recall |
| Eval-02 | 正常 | 配置 LLM_JUDGE 方案 | 窗口 evaluation_scheme=LLM_JUDGE | 异步执行，返回 Faithfulness、Answer Relevancy |
| Eval-03 | 正常 | 配置 BOTH 方案 | 两者都开 | 双轨指标都返回 |
| Eval-04 | 正常 | 配置 NONE 方案 | 不开评估 | 不返回评估字段 |
| Eval-05 | 边界 | Judge 模型自动选择 | 未指定 critic_model_ids | 自动选 2 个不同 provider 的模型，且排除当前聊天模型 |
| Eval-06 | 边界 | Judge 模型都被禁用 | 禁用所有模型 → LLM_JUDGE 执行 | 降级跳过，不 crash |
| Eval-07 | 边界 | Judge 模型只有 1 个可用 | 只 1 个模型（与当前聊天模型同 provider） | 降级为只用这 1 个，或跳过（按实现） |
| Eval-08 | 正常 | LLM-as-Judge 异步不阻塞 | LLM_JUDGE 执行中 | SSE done 事件已发送，评估结果通过后续查询拉取 |
| Eval-09 | 安全 | 单次 LLM judge 打分不被信任 | - | 系统设计上支持多 judge 重复评估 + 一致性报告（待实现） |

---

## 7. Agent 自动规划执行

### 7.1 Planner / Plan 生命周期

| 编号 | 分类 | 用例 | 操作步骤 | 预期结果 |
|------|------|------|---------|---------|
| Agt-01 | 正常 | 启用 Agent 的窗口发消息 | window.agent_enabled=true, agent_max_steps=15 → 发送多步目标 | SSE 下发 plan_created、plan_complete 事件 |
| Agt-02 | 正常 | 关闭 Agent 的窗口发消息 | agent_enabled=false | 走普通 RAG/工具调用链路，无 plan_* 事件 |
| Agt-03 | 边界 | Planner LLM 返回非法 JSON | 让 Planner prompt 构造缺陷 → 测试 | 降级为普通 Tool Calling，SSE 发送 plan_create_failed（如实现） |
| Agt-04 | 边界 | Planner 超时 | 模拟 LLM 超时 → 发送消息 | 降级或超时保护，不 crash |
| Agt-05 | 正常 | 执行中动态重规划 | 步骤失败 → Planner 重新生成剩余计划 | SSE 下发 plan_updated 事件 |
| Agt-06 | 边界 | 计划步骤数超过 agent_max_steps | 让 Planner 返回 20 步 | 截断或拒绝，不超出上限 |

### 7.2 StepExecutor / Reflection

| 编号 | 分类 | 用例 | 操作步骤 | 预期结果 |
|------|------|------|---------|---------|
| Agt-07 | 正常 | 单步骤执行成功 | 步骤无工具依赖 | step_success SSE + reflection 评分 ≥ threshold |
| Agt-08 | 正常 | 反思低于阈值触发重试 | 设定低 reflection_threshold=5.0 → 让 Critic 打分 4.5 | step_reflection（retryCount+1）→ 重试 → 最终 success 或 retry 耗尽 |
| Agt-09 | 边界 | 反思重试耗尽 | 连续 N 次评分 < threshold | step_failed + retryCount=max |
| Agt-10 | 边界 | 关闭反思（reflection_enabled=false） | Agent 全局关反思 | 步骤执行完直接 SUCCESS，无 reflection 事件 |
| Agt-11 | 边界 | Critic 模型不可用 | 禁用所有模型 → 反思执行 | 降级跳过反思，步骤直接 SUCCESS |
| Agt-12 | 正常 | 步骤内工具调用（ReAct 循环） | 步骤 requiresTools=true | 内部最多 5 轮 tool calling，SSE 下发 tool_call_start/result |
| Agt-13 | 边界 | 工具循环死锁保护 | 连续 5 轮工具调用无终止 | 第 6 轮强制终止，步骤 FAILED |

### 7.3 HITL 人工确认

| 编号 | 分类 | 用例 | 操作步骤 | 预期结果 |
|------|------|------|---------|---------|
| Agt-14 | 正常 | HITL 暂停 → 确认续跑 | Planner 标记 hitl=true + agent_hitl_enabled=true → 执行到该步 | SSE step_hitl_pending → plan_paused → 弹窗 → 输入确认 → 后续步骤继续 → plan_complete |
| Agt-15 | 正常 | HITL 暂停 → 稍后处理按钮 | 点击「稍后处理」→ 关闭弹窗 → 刷新页面 | 再进恢复 PAUSED 状态，HITL 弹窗再次出现（nextTick 时机正确） |
| Agt-16 | 正常 | HITL 暂停 → 取消计划 | 暂停中 → POST /api/agent/plans/{id}/cancel | plan CANCELLED，未执行步骤 SKIPPED |
| Agt-17 | 边界 | 窗口关闭 HITL 开关 | agent_hitl_enabled=false → Planner 标记 hitl=true | HITL 步骤跳过暂停（或执行），不阻塞 |
| Agt-18 | 正常 | HITL 步骤确认后结果落库 | hitl-confirm API 返回 | assistant message 补写回 chat_message 表，前端刷新可见 |
| Agt-19 | 边界 | 刷新恢复 RUNNING 状态 | Agent 运行中刷新页面 | 恢复计划面板，2 秒轮询直到终态 → 自动补拉消息 |
| Agt-20 | 边界 | 分享页刷新恢复 | 同上在 /share/{token} 页面 | 同调试页行为 |
| Agt-21 | 边界 | step_hitl_pending 事件无 DB 主键 id | 前端收到事件 → payload 缺 id | 补建步骤时 ID 缺失，confirm 接口 404 |

### 7.4 计划级 REST 控制

| 编号 | 分类 | 用例 | 操作步骤 | 预期结果 |
|------|------|------|---------|---------|
| Agt-22 | 正常 | GET plans/{planId}/state | 执行中查询 | 返回完整 plan + steps 列表 + 状态 |
| Agt-23 | 正常 | GET sessions/{sessionId}/plan | 刷新恢复用 | 返回该会话最新 plan |
| Agt-24 | 正常 | POST plans/{planId}/cancel | 执行中取消 | plan CANCELLED，step SKIPPED |
| Agt-25 | 正常 | POST steps/{stepId}/retry | 步骤 FAILED → 重试 | 该 step 重新执行 |
| Agt-26 | 正常 | POST steps/{stepId}/skip | 步骤 FAILED → 跳过 | 标记 SKIPPED，后续步骤依赖检查 |
| Agt-27 | 边界 | 重试未 FAILED 的步骤 | SUCCESS 步骤重试 | 返回错误或幂等成功（按设计） |
| Agt-28 | 边界 | 跳过 HITL_PENDING 步骤 | 非 FAILED 跳过 | 返回错误（只有 FAILED 能跳过） |
| Agt-29 | 边界 | 取消已 COMPLETED 的计划 | CANCELLED 终态 | 返回幂等成功 |

---

## 8. 工作流可视化编排

### 8.1 图校验

| 编号 | 分类 | 用例 | 操作步骤 | 预期结果 |
|------|------|------|---------|---------|
| WF-01 | 正常 | 合法图通过校验 | 1 START → LLM → END | 校验通过 |
| WF-02 | 边界 | 无 START 节点 | 图无 START | 校验失败 |
| WF-03 | 边界 | 2 个 START 节点 | 图有两个 START | 校验失败 |
| WF-04 | 边界 | 无 END 节点 | 图无 END | 校验失败（至少 1 个 END） |
| WF-05 | 边界 | START 有入边 | START 连入边 | 校验失败 |
| WF-06 | 边界 | END 有出边 | END 连出边 | 校验失败 |
| WF-07 | 边界 | 无关节点 | LLM 节点孤立无连线 | 校验警告或拒绝（按实现） |
| WF-08 | 边界 | 循环回边（合法） | LOOP → 循环体 → 回 LOOP | 校验通过 |
| WF-09 | 边界 | 循环嵌套层数超限 | WORKFLOW 节点嵌套 4 层 | 校验失败（最大 3 层） |
| WF-10 | 边界 | 有向环（非法） | A→B→C→A 形成环（非回边白名单） | 校验失败，DFS 检测 |

### 8.2 CRUD 与版本

| 编号 | 分类 | 用例 | 操作步骤 | 预期结果 |
|------|------|------|---------|---------|
| WF-11 | 正常 | 新建工作流 | POST /api/workflows name + 合法 graph_json | 返回 ID，version=1 |
| WF-12 | 正常 | 保存自动版本 + 快照 | PUT /api/workflows/{id} → 保存 | version+1，wgh_workflow_version 新增一条 |
| WF-13 | 正常 | 版本列表 | GET /api/workflows/{id}/versions | 返回所有版本（v1, v2, ...） |
| WF-14 | 正常 | 回滚到旧版本 | POST /api/workflows/{id}/rollback/{version} | 基于旧版本生成新版本（如 v1 回滚 → 生成 v5） |
| WF-15 | 边界 | 回滚当前版本 | POST rollback/{当前版本号} | 返回错误或被禁用（前端按钮已禁用） |
| WF-16 | 边界 | 回滚不存在的版本 | POST rollback/999 | 返回 404 |
| WF-17 | 正常 | 逻辑删除工作流 | DELETE /api/workflows/{id} | list 不可见，数据库 deleted=1 |
| WF-18 | 边界 | 删除已删除的工作流 | 重复 DELETE | 幂等成功 |
| WF-19 | 正常 | 启停工作流 | enable / disable | status 切换 |
| WF-20 | 边界 | 停用工作流后 Webhook 触发 | 停用 → POST webhook | 返回友好错误 |

### 8.3 运行时：9 类节点

| 编号 | 分类 | 用例 | 操作步骤 | 预期结果 |
|------|------|------|---------|---------|
| WF-21 | 正常 | LLM 节点执行 | LLM modelId=有效模型 → 运行 | SUCCESS，输出非空 text |
| WF-22 | 边界 | LLM modelId 无效 | modelId 指向禁用/不存在的模型 | FAILED + error_message |
| WF-23 | 正常 | RETRIEVAL 节点执行 | kbIds=[有效 KB], query="RAG 是什么" | SUCCESS，返回 sources + score ≥ 0.55 |
| WF-24 | 边界 | RETRIEVAL 空 kbIds | kbIds=[] | FAILED（无 KB 可检索） |
| WF-25 | 正常 | TOOL 节点执行 | toolId=echo, arguments={"input":"hi"} | SUCCESS，text 含 "hi" |
| WF-26 | 边界 | TOOL toolId 无效 | toolId=99999 | FAILED |
| WF-27 | 正常 | CONDITION 节点 true 分支 | expression=`{{n_llm_1.text}} contains "通过"`，LLM 输出含"通过" | 走 true 分支，false 分支下节点不执行 |
| WF-28 | 正常 | CONDITION 节点 false 分支 | expression 同上，LLM 输出不含"通过" | 走 false 分支，true 分支下节点 pendingInEdges 不为 0 不执行 |
| WF-29 | 正常 | LOOP 节点循环 maxIterations | maxIterations=3 → 运行 | 循环体执行 3 次，输出含 loopDone:3/3 |
| WF-30 | 边界 | LOOP 节点 breakCondition 提前终止 | breakCondition=`{{loop.iteration}} >= 2` | 第 2 轮终止，走 done 出口 |
| WF-31 | 正常 | HITL 节点暂停 | HITL prompt="请审批..." → 运行到该节点 | SUCCESS（用户输入 → text 输出） |
| WF-32 | 正常 | VARIABLE 节点赋值 | assignments=[{name:"x", value:"{{input}}"}] → 后续节点引用 `{{vars.x}}` | 渲染正确 |
| WF-33 | 正常 | WORKFLOW 子流程节点 | WORKFLOW nodeId 指向有效父流程 → 运行 | SUCCESS，子流程内部节点静默执行（无 SSE） |
| WF-34 | 边界 | WORKFLOW 节点嵌套超限 | 父流程→子流程→孙流程→曾孙流程（4 层） | 校验阶段拒绝 |

### 8.4 并行 fan-out/fan-in

| 编号 | 分类 | 用例 | 操作步骤 | 预期结果 |
|------|------|------|---------|---------|
| WF-35 | 正常 | fan-out 并行分支 | START → LLM1（分支1）+ LLM2（分支2）→ fan-in 节点 | 分支1 和分支2 并发执行，fan-in 等待两者都 SUCCESS 后执行 |
| WF-36 | 正常 | fan-in 顺序无关 | 分支1 执行 3s，分支2 执行 1s → fan-in 等待 3s | fan-in 在两个都完成后才入队 |
| WF-37 | 边界 | fan-out 其中一个分支失败 | 分支1 SUCCESS，分支2 FAILED → fan-in | fan-in pendingInEdges 包含分支2（FAILED 也会减计数）→ fan-in 执行但依赖 FAILED 分支输出为空 |
| WF-38 | 边界 | CONDITION 只走一个分支 → fan-in | 条件分支走 true，false 分支下游节点 pendingInEdges 不减 | fan-in 因 false 分支 pending>0 不入队（按实现：未走分支不触发减计数） |

### 8.5 变量引用

| 编号 | 分类 | 用例 | 操作步骤 | 预期结果 |
|------|------|------|---------|---------|
| WF-39 | 正常 | `{{input}}` 渲染 | END output=`输入是 {{input}}` | 运行输入正确渲染 |
| WF-40 | 正常 | `{{n_llm_1.text}}` 节点输出引用 | 后续节点 prompt 引用前置 LLM 输出 | 渲染为前置节点 text |
| WF-41 | 正常 | `{{vars.x}}` 变量池引用 | VARIABLE 节点赋值 x=value → 后续引用 | 渲染为 value |
| WF-42 | 正常 | `{{loop.iteration}}` 循环轮次 | 循环体内引用 iteration | 第 1 轮=1、第 2 轮=2 |
| WF-43 | 边界 | 未解析变量路径渲染为空 | `{{vars.undefined}}` | 渲染为空串（不抛错） |
| WF-44 | 边界 | `{{n_rag_1.sources.0.fileName}}` 数组下标 | RETRIEVAL 后引用 sources 第 0 条文件名 | 渲染为第一个文件名 |

### 8.6 HITL 暂停恢复

| 编号 | 分类 | 用例 | 操作步骤 | 预期结果 |
|------|------|------|---------|---------|
| WF-45 | 正常 | HITL 暂停 → 确认续跑 | 运行到 HITL 节点 → PAUSED → 弹窗 → 输入"同意" → 后续步骤继续 | 最终 COMPLETED，run.output 含用户输入 |
| WF-46 | 边界 | 刷新恢复 PAUSED 状态 | HITL 暂停 → 刷新编辑器页面 | syncRunState 检测 PAUSED → nextTick 后弹 HITL 确认框 |
| WF-47 | 边界 | 刷新后 HITL 弹窗可见性 | 同上 | overlay 正常显示，不被 teleport 竞争遮挡（nextTick 修复生效） |
| WF-48 | 边界 | HITL 弹窗 ESC 键不关闭 | 按 ESC | 弹窗不关闭（close-on-press-escape=false） |
| WF-49 | 正常 | HITL 暂停期间取消 | PAUSED → POST runs/{runId}/cancel | CANCELLED，node_run HITL_PENDING 不变，后续 SKIPPED |
| WF-50 | 边界 | HITL 暂停中重复确认 | 已 PAUSED → 重复 hitl-confirm | 第二次返回错误或幂等 |

### 8.7 运行控制与失败

| 编号 | 分类 | 用例 | 操作步骤 | 预期结果 |
|------|------|------|---------|---------|
| WF-51 | 正常 | 运行中取消 | POST runs/{runId}/cancel | run CANCELLED，未执行节点 SKIPPED |
| WF-52 | 正常 | 节点失败终止（onError=STOP） | 节点无配置 onError → 模拟 LLM 失败 | run FAILED，SSE workflow_failed |
| WF-53 | 正常 | 节点失败忽略继续（onError=CONTINUE） | 节点配置 onError=CONTINUE → 模拟失败 | 标记 FAILED 但继续执行后续 |
| WF-54 | 边界 | 运行中页面刷新（SSE 断开） | 运行中刷新 → GET runs/{runId}/state | 后端继续执行，刷新后可查状态，前端恢复视图 |
| WF-55 | 边界 | 节点失败但有重试逻辑 | 步骤 FAILED → 无 retryStep API | 工作流引擎当前无单步重试（与 Agent 不同），需手动重跑整个 workflow |

### 8.8 SSE 事件

| 编号 | 分类 | 用例 | 操作步骤 | 预期结果 |
|------|------|------|---------|---------|
| WF-56 | 正常 | SSE 事件完整序列 | 运行合法 DAG | workflow_start → node_start×N → node_output×N → workflow_complete |
| WF-57 | 边界 | 节点 FAILED 事件 | 模拟节点失败 | node_error → workflow_failed |
| WF-58 | 边界 | workflow_complete 携带 output | 正常完成 | payload.output = END 节点渲染后的输出 |

### 8.9 模板市场

| 编号 | 分类 | 用例 | 操作步骤 | 预期结果 |
|------|------|------|---------|---------|
| WF-59 | 正常 | 模板列表 | GET /api/workflows/templates | 返回 3 个模板（知识库问答 / 人工审批流 / 定期巡检流） |
| WF-60 | 正常 | 模板实例化 | POST /api/workflows/templates/{id}/instantiate | 返回新 workflowId，version=1，LLM 节点占位 modelId 自动填充 |
| WF-61 | 边界 | 模板内 LLM modelId 占位符处理 | 模板 graph_json 含 modelId="" 的 LLM 节点 | 自动填充为第一个启用的模型 |
| WF-62 | 边界 | 模板图本身非法 | 模板 graph_json 有 2 个 START | 实例化时校验失败返回错误 |

---

## 9. 工具框架（4 类执行器 + Skill）

### 9.1 ToolExecutor SPI

| 编号 | 分类 | 用例 | 操作步骤 | 预期结果 |
|------|------|------|---------|---------|
| Tool-01 | 正常 | HTTP 工具执行 | GET 示例 API | SUCCESS，text 含响应内容 |
| Tool-02 | 正常 | HTTP 占位符渲染 | URL 含 `{param}` | 执行前参数替换 |
| Tool-03 | 正常 | HTTP 环境变量 | executorConfig 含 `${ENV}` | 运行时解析环境变量 |
| Tool-04 | 边界 | HTTP 超时 | timeoutMs=1000 → 调用慢接口 | 超时友好错误，不阻塞主流程 |
| Tool-05 | 边界 | HTTP SSL 错误 | 证书无效 | 可读错误 |
| Tool-06 | 边界 | HTTP 连接拒绝 | URL 端口无服务 | 超时或连接拒绝错误 |
| Tool-07 | 正常 | MCP stdio 工具 | 启动本地 MCP server → 调用 | 正常执行 |
| Tool-08 | 正常 | MCP Streamable HTTP 工具 | 远程 MCP server → 调用 | 正常执行 |
| Tool-09 | 边界 | MCP server 启动失败 | 错误 command/args | 超时或启动失败错误 |
| Tool-10 | 边界 | MCP 工具不存在 | MCP 注册表里找不到工具名 | 返回错误 |
| Tool-11 | 正常 | SKILL inline 模式执行 | executorConfig.promptTemplate | 作为 promptTemplate 传给 LLM |
| Tool-12 | 正常 | SKILL directory 模式执行 | executorConfig.skillPath + SKILL.md | 运行时读 SKILL.md → 解析 front matter + 追加 references/ |
| Tool-13 | 边界 | SKILL directory 目录不存在 | skillPath 指向不存在的目录 | 返回错误 |
| Tool-14 | 边界 | SKILL executorConfig 不可变 Map | 构造 executorConfig 传入 ImmutableMap | 内部转为可变 HashMap，避免 UnsupportedOperationException |
| Tool-15 | 正常 | FUNCTION_CALLING 工具执行 | handlerClass + method | 本地反射调用 |
| Tool-16 | 边界 | FUNCTION_CALLING handlerClass 不存在 | handlerClass="nonexistent" | 返回错误 |
| Tool-17 | 边界 | FUNCTION_CALLING method 不存在 | handlerClass 存在但 method 错 | 返回错误 |

### 9.2 工具执行通用规则

| 编号 | 分类 | 用例 | 操作步骤 | 预期结果 |
|------|------|------|---------|---------|
| Tool-18 | 正常 | 工具结果超 4000 字符自动截断 | 模拟返回 5000 字符 | 截断并标注 |
| Tool-19 | 正常 | 工具调用循环最多 5 轮 | 连续调用 | 第 5 轮后强制终止 |
| Tool-20 | 边界 | 工具 definition 非 OpenAI function 格式 | definition 结构错误 | 静默跳过，工具不出现在 LLM 可见列表 |
| Tool-21 | 正常 | 窗口级独立 toolIds | 两个窗口各自配置不同工具 | 互相不干扰 |
| Tool-22 | 正常 | 工具调试弹窗动态表单 | 按 definition 解析 enum/number/boolean/string | 表单渲染正确 |

### 9.3 Skill 管理

| 编号 | 分类 | 用例 | 操作步骤 | 预期结果 |
|------|------|------|---------|---------|
| Tool-23 | 正常 | 扫描磁盘 skill 目录 | GET /api/chat/tools/skill/list-dirs | 返回目录下所有 SKILL.md |
| Tool-24 | 正常 | 同名 SKILL 导入判重 | 数据库已有同名 SKILL → 再次导入 | 返回 duplicated=true，不创建不更新 |
| Tool-25 | 正常 | Skill subModelId 指定 | front matter 含 subModelId | Skill 执行时用该模型而非窗口默认 |
| Tool-26 | 边界 | Skill subModelId=subModelDefault | front matter 含 subModelDefault=true | 继承窗口模型 |

---

## 10. 触发器（CRON / Webhook）

| 编号 | 分类 | 用例 | 操作步骤 | 预期结果 |
|------|------|------|---------|---------|
| Trg-01 | 正常 | 创建 CRON 触发器 | POST /api/workflows/{id}/triggers triggerType=CRON, cronExpression="0 */5 * * * *" | 返回 ID，注册到 scheduler |
| Trg-02 | 边界 | CRON 表达式非法 | "invalid-cron" | 返回校验错误 |
| Trg-03 | 正常 | CRON 秒级触发 | 创建秒级 CRON → 等待触发 | fireCount+1, lastFireTime 更新 |
| Trg-04 | 正常 | 停用 CRON 触发器 | POST disable → 等待触发 | fireCount 不变，触发器从 scheduler 注销 |
| Trg-05 | 正常 | 创建 Webhook 触发器 | POST triggerType=WEBHOOK | 返回 ID + webhookToken |
| Trg-06 | 正常 | Webhook 免密触发 | POST /api/workflows/webhook/{token} inputText="test" | 静默运行，fireCount+1 |
| Trg-07 | 安全 | Webhook 伪造 token | 篡改 token → POST | 返回 404 |
| Trg-08 | 正常 | 删除触发器 | DELETE trigger | 从 scheduler 移除（CRON），Webhook token 失效 |
| Trg-09 | 边界 | 停用工作流后 CRON 触发 | 停用工作流 → CRON 到点 | 返回错误或静默跳过 |
| Trg-10 | 边界 | 高频 CRON 导致运行冲突 | 1 秒一次 CRON → 工作流 10 秒完成 | 并行运行或排队（按设计） |

---

## 11. 端到端集成场景

### 11.1 Agent 全链路

| 编号 | 分类 | 场景 | 操作步骤 | 预期结果 |
|------|------|------|---------|---------|
| E2E-01 | 正常 | Agent 多步工具调用 | 启用 Agent + 工具 → 发送"查订单 → 汇总 → 保存" | plan_created → step_start×N → step_success×N → plan_complete，助手消息含最终答复 |
| E2E-02 | 正常 | Agent + HITL 审批 | Planner 标记步骤 hitl=true → 暂停 → 用户确认 → 后续继续 | plan_paused → step_hitl_pending → hitl-confirm → plan_complete |
| E2E-03 | 边界 | Agent 刷新恢复 | 运行中刷新 → 再发消息 | 恢复计划面板 + 轮询 + 不重复执行步骤 |
| E2E-04 | 异常 | Agent 工具不可用降级 | 清空窗口 toolIds → 发送需要工具的目标 | Planner 跳过 Action 层，直接 RAG + LLM |
| E2E-05 | 边界 | Agent Reflection 评分失败重试 | 设定高阈值 → 让 Critic 打分低 | step_reflection（retryCount=1, pass=false）→ 重试 → retryCount=2 → 最终 success 或 failed |

### 11.2 工作流全链路

| 编号 | 分类 | 场景 | 操作步骤 | 预期结果 |
|------|------|------|---------|---------|
| E2E-06 | 正常 | fan-out → LLM1/LLM2 并行 → fan-in | 两分支各 1 个 LLM → fan-in 汇聚 | 两个 LLM 并发，fan-in 等待两者都 SUCCESS 后执行 |
| E2E-07 | 正常 | 子流程 WORKFLOW 节点 | 父流程 → WORKFLOW 子流程节点 | 父流程 SUCCESS，子流程内部节点静默执行 |
| E2E-08 | 正常 | 循环 + 条件分支嵌套 | LOOP 内含 CONDITION，条件成立走 true | 循环正常迭代，分支正确 |
| E2E-09 | 正常 | Webhook 触发审批流 | Webhook POST → 审批流自动运行 → 输出结果 | fireCount+1，run 记录 COMPLETED |
| E2E-10 | 正常 | CRON 触发巡检流 | 创建巡检流 CRON → 等待触发 | fireCount+1 |
| E2E-11 | 正常 | 模板市场一键创建 → 运行 | 模板 → 人工审批流 → 创建 → 运行 | SUCCESS，变量/模型自动填充 |

### 11.3 Agent 与工作流协同

| 编号 | 分类 | 场景 | 操作步骤 | 预期结果 |
|------|------|------|---------|---------|
| E2E-12 | 正常 | Agent 调用工作流（如 Tool） | 工作流包装为 Tool → Agent 计划里需要调用 | StepExecutor 走 ToolExecutionEngine → ToolExecution 请求正常 |
| E2E-13 | 正常 | 工作流节点里嵌套调用 Agent ？ | 当前不支持（工作流无 Agent 节点） | N/A，记录为功能 gap |

### 11.4 分享 + Agent

| 编号 | 分类 | 场景 | 操作步骤 | 预期结果 |
|------|------|------|---------|---------|
| E2E-14 | 正常 | 分享页 Agent HITL | 分享页 + Agent 启用 + HITL 步骤 | 暂停/确认/恢复全链路正常 |
| E2E-15 | 正常 | 分享页 Agent 刷新恢复 | 运行中刷新 | 恢复计划面板 |

### 11.5 数据库与持久化

| 编号 | 分类 | 场景 | 操作步骤 | 预期结果 |
|------|------|------|---------|---------|
| DB-01 | 边界 | 重启后 Agent 状态恢复 | Agent RUNNING → 重启后端 → 刷新 | RUNNING 步骤标记 FAILED 或待用户操作（按恢复策略） |
| DB-02 | 边界 | 重启后工作流状态恢复 | 工作流 RUNNING → 重启 → GET state | 运行状态可查询（内存态丢失，持久化态保留） |
| DB-03 | 边界 | 进程崩溃不丢最终答复 | Agent 最后一步落库后进程崩溃 → 重启 | assistant message 已写入 DB |
| DB-04 | 边界 | 并发运行同一工作流 10 次 | 10 个浏览器同时跑 | 各自独立 run 记录，无串扰 |

---

## 附录：测试环境速查

### 预置数据

| 项目 | 值 |
|------|-----|
| admin 账号 | admin / admin |
| 测试 KB | 至少 1 个启用的 KB，含向量化文档 |
| 测试模型 | 至少 1 个 Chat + 1 个 Embedding，API Key 有效 |
| 测试工作流 | fan-out(36) / 子流程(38) / 条件(76) / 循环(77) / LLM(79) / 检索(80) / 审批 HITL(87) |
| 测试 Agent 窗口 | 至少 1 个 agent_enabled=true 的窗口 + 工具绑定 |

### 现有 e2e 脚本覆盖情况

| 脚本 | 覆盖 API 面 | 用例数 | 已覆盖重点 | 未覆盖 |
|------|------------|--------|-----------|--------|
| test-agent-e2e.sh | stream + plan state + plan cancel | 18 | SSE 事件 / payload / 降级路径 | 反思重试、动态重规划、sessions/{id}/plan、step retry/skip |
| test-agent-hitl-e2e.sh | stream + hitl-confirm + state | 16 | HITL 暂停续跑、窗口开关对照、取消、消息落库 | 暂停中取消、step_hitl_pending 无 id 补建 |
| test-workflow-e2e.sh | 25 个 API（CRUD/validate/run/state/hitl/version/trigger/template） | 67 | 9 类节点/fan-out fan-in/子流程/版本回滚/CRON&Webhook/HITL/模板实例化 | 节点失败 onError 两种策略、变量引用数组下标、嵌套超限 4 层、fan-in 一个分支 FAILED 场景 |

### 黑盒测试方法映射

| 方法 | 适用章节 |
|------|---------|
| 等价类 | 2.2 文件格式、2.3 切分策略、3 模型 provider |
| 边界值 | 1 Auth-02~04 空字符串、5  exactly 20 条、8 WF-09 嵌套层数 |
| 判定表 | 8 CONDITION 分支选择（expression+LLM output → true/false） |
| 状态迁移 | 7.3 HITL（RUNNING → PAUSED → RUNNING / CANCELLED）、8.6 HITL 恢复 |
| 错误推测 | 1 安全注入、Tool-14 不可变 Map、Cmp-06 LLM 降级 |
| 场景法 | 11 端到端集成（E2E-01 ~ E2E-15） |

---

## 12. 多 Agent 协作框架（rag-agent-multi）

> 覆盖范围：Crew CRUD · Run 生命周期 · Crew 三种模式（Sequential/Hierarchical/Parallel）· Graph 状态图路由（CONDITION/LOOP/HITL）· GroupChat（RoundRobin/DynamicRouter）· SSE 事件 · HITL · 持久化 · 集成边界
> API 前缀：`/api/agent-multi`｜数据表：`wgh_m_agent_run` / `wgh_m_agent_step` / `wgh_m_agent_crew`

### 12.1 Crew 配置 CRUD

| 编号 | 分类 | 用例 | 操作步骤 | 预期结果 |
|------|------|------|---------|---------|
| MA-C-01 | 正常 | 创建 Crew（SEQUENTIAL） | POST /crews 带 name/executionMode/agentsJson/crewConfigJson | code=200，返回 id；list 可见 |
| MA-C-02 | 正常 | 创建 Crew（GROUP_CHAT_DYNAMIC_ROUTER） | executionMode=GROUP_CHAT_DYNAMIC_ROUTER + groupChatConfigJson | code=200 |
| MA-C-03 | 正常 | Crew 分页列表 | GET /crews?page=1&size=10 | 分页结构正确（total/records） |
| MA-C-04 | 正常 | Crew 详情查询 | GET /crews/{id} | 返回完整 JSON 字段 |
| MA-C-05 | 正常 | 更新 Crew | PUT /crews/{id} 改 name | updateTime 更新，版本字段生效 |
| MA-C-06 | 正常 | 删除 Crew（逻辑删除） | DELETE /crews/{id} → list | 列表不再出现；DB deleted=1 |
| MA-C-07 | 边界 | agentsJson 为空数组 | agentsJson="[]" | 校验失败（422）或创建成功但 run 时校验拦截 |
| MA-C-08 | 边界 | 单 Agent Crew | agentsJson 只含 1 个 Agent | 允许创建（退化为单 Agent）或明确拒绝 |
| MA-C-09 | 边界 | 名称超长 200 字符 | name=200 字符 | 截断或友好拒绝，无 DB 异常 |
| MA-C-10 | 异常 | agentsJson 非法 JSON | agentsJson="{invalid" | 400 参数错误，无 500 堆栈 |
| MA-C-11 | 异常 | 删除不存在的 Crew | DELETE /crews/99999 | 404 或幂等成功 |
| MA-C-12 | 异常 | executionMode 非法值 | executionMode="HACK_MODE" | 400 校验失败 |
| MA-C-13 | 安全 | agentsJson 注入 SQL 片段 | role=`x'; DROP TABLE--` | 纯文本存储，无 SQL 执行 |

### 12.2 Run 生命周期

| 编号 | 分类 | 用例 | 操作步骤 | 预期结果 |
|------|------|------|---------|---------|
| MA-R-01 | 正常 | 创建 Run（内联 agentsJson） | POST /runs {sessionId, chatWindowId, executionMode, userGoal} | code=200，返回 runId（mag-xxx），status=PLANNING |
| MA-R-02 | 正常 | 创建 Run（引用 crewId 模板） | POST /runs {crewId} | 自动补齐 agentsJson/crewConfigJson/executionMode |
| MA-R-03 | 正常 | 查询 Run 状态 | GET /runs/{runId}/state | 返回 run + steps 数组 |
| MA-R-04 | 正常 | 取消 RUNNING 的 Run | POST /runs/{runId}/cancel | status→CANCELLED，未执行 steps→SKIPPED，SSE multi_agent_cancelled |
| MA-R-05 | 正常 | Run 完成终态 | 等全部 Agent 执行完 | status=COMPLETED，outputText 非空，durationMs>0 |
| MA-R-06 | 边界 | 空 userGoal 创建 Run | userGoal="" | 400 校验失败或默认取 input |
| MA-R-07 | 边界 | maxRounds=1 | maxRounds=1（GroupChat） | 一轮后即终止 |
| MA-R-08 | 边界 | maxRounds 超上限 50 | maxRounds=100 | 截断为上限或 400 |
| MA-R-09 | 异常 | 查询不存在的 runId | GET /runs/ghost-999/state | 404 |
| MA-R-10 | 异常 | 取消已 COMPLETED 的 Run | POST cancel | 幂等成功（状态不变） |
| MA-R-11 | 异常 | 缺 chatWindowId | 不传 chatWindowId | 400（DB NOT NULL 约束前置校验，不报 500） |
| MA-R-12 | 异常 | 缺 crewConfigJson 且 agentsJson 为空 | 两者都缺 | 400 明确错误信息 |
| MA-R-13 | 状态机 | PLANNING→RUNNING→COMPLETED 迁移 | 全程观察 status 字段 | 不跳变、不倒退 |
| MA-R-14 | 状态机 | PAUSED 状态 hitl-confirm 后 RUNNING | hitl-confirm {userInput} | PAUSED→RUNNING，继续执行 |

### 12.3 Crew — Sequential 串行模式

| 编号 | 分类 | 用例 | 操作步骤 | 预期结果 |
|------|------|------|---------|---------|
| MA-S-01 | 正常 | 2 Agent 串行执行 | researcher → writer 链 | SSE agent_start/done 按序到达；DB steps step_index 递增 |
| MA-S-02 | 正常 | 上游输出传递到下游 | a1.output 含"量子"→ a2 的 prompt 渲染 `{{a1.text}}` | a2 的 input_context 中含 a1 输出文本 |
| MA-S-03 | 正常 | 末节点输出为最终答复 | END 节点前最后一个 AGENT 输出 | run.outputText = 末 Agent 输出 |
| MA-S-04 | 边界 | 某 Agent 无工具无 RAG | toolIds=[], kbIds=null | 纯 LLM 对话，正常返回 |
| MA-S-05 | 异常 | 中间 Agent LLM 失败 | a1 的模型 API Key 失效 | run.status=FAILED，errorMessage 含原因，后续 Agent 标记 SKIPPED |
| MA-S-06 | 异常 | 图中含环（a1→a2→a1） | 非法 DAG | validate 拦截 422 或执行时检测死循环终止 |

### 12.4 Crew — Hierarchical 层级模式

| 编号 | 分类 | 用例 | 操作步骤 | 预期结果 |
|------|------|------|---------|---------|
| MA-H-01 | 正常 | Manager 分配 + Worker 执行 + 汇总 | managerAgentId + 2 worker | manager 先执行产出分配；workers 执行；manager 二次汇总为 output |
| MA-H-02 | 正常 | Manager 输出 JSON 子任务解析 | manager 返回 `[{"agent":"w1","task":"..."}]` | 正确解析并调度对应 worker |
| MA-H-03 | 边界 | Manager 分配不存在的 agentId | manager 返回 agent="ghost" | 该子任务标记 FAILED，不影响其他 worker |
| MA-H-04 | 异常 | managerAgentId 不在 agents 列表 | 配置 managerAgentId="not-exist" | 400 校验失败 |
| MA-H-05 | 异常 | Manager LLM 输出非法 JSON | manager 返回非 JSON | 降级为顺序执行全部 worker 或 FAILED 带明确信息 |

### 12.5 Crew — Parallel 并行模式

| 编号 | 分类 | 用例 | 操作步骤 | 预期结果 |
|------|------|------|---------|---------|
| MA-P-01 | 正常 | 3 Agent fan-out + fan-in 汇聚 | START→{a1,a2,a3}→collector | 三者并发执行（start_time 重叠）；collector 等全部完成后执行 |
| MA-P-02 | 正常 | 汇聚节点拿到全部上游输出 | collector 模板 `{{a1.text}} {{a2.text}} {{a3.text}}` | 三个变量全部渲染 |
| MA-P-03 | 边界 | 1 个并行分支失败 | a2 模型超时 | run 按配置：STOP→整体 FAILED；CONTINUE→collector 仍执行，a2 输出为空串/错误标记 |
| MA-P-04 | 边界 | 并行度超过线程池 | 10 个并行分支（pool max=8） | 排队执行，不丢任务 |
| MA-P-05 | 异常 | 两个 Agent 同时写同名 context key | a1/a2 都输出到 `result` | 后写覆盖或按 agentId 隔离，行为明确且无 ConcurrentModificationException |

### 12.6 Graph — 状态图路由模式

| 编号 | 分类 | 用例 | 操作步骤 | 预期结果 |
|------|------|------|---------|---------|
| MA-G-01 | 正常 | CONDITION true 分支 | expression=`{{coder.text}} contains "PASS"`，coder 输出含 PASS | 走 sourceHandle=true 的边 |
| MA-G-02 | 正常 | CONDITION false 分支 | 输出不含 PASS | 走 false 边到 LOOP |
| MA-G-03 | 正常 | LOOP 循环 3 轮后 done | maxIterations=3 | 循环体执行 3 次，loop.iteration 计数正确 |
| MA-G-04 | 正常 | LOOP breakCondition 提前退出 | breakCondition=`{{tester.text}} contains "PASS"` 第 2 轮满足 | 第 2 轮后走 done 边 |
| MA-G-05 | 正常 | HITL 节点暂停→确认→续跑 | HITL prompt 审批 | SSE agent_hitl_pending → status=PAUSED → confirm 后续跑 |
| MA-G-06 | 边界 | LOOP maxIterations=1 | 最小循环 | 执行 1 次即 done |
| MA-G-07 | 边界 | LOOP maxIterations=20（上限） | 最大循环 | 正常执行 20 轮上限后强制 done |
| MA-G-08 | 边界 | CONDITION 引用不存在的变量 | `{{ghost.text}} contains "x"` | 渲染为空串，按 is_empty/false 处理，不抛 NPE |
| MA-G-09 | 异常 | CONDITION 缺 true 或 false 出边 | 图校验 | validate 返回 422 明确指出 |
| MA-G-10 | 异常 | LOOP 无 loop/done 出口 | 图校验 | 422 拦截 |
| MA-G-11 | 异常 | 表达式语法非法 | expression="{{a} invalid" | ConditionEvaluator 返回 false 或 400，不 crash |
| MA-G-12 | 状态机 | HITL 暂停期间刷新页面后恢复 | 暂停→刷新→GET state | steps 状态完整恢复，前端可继续确认 |

### 12.7 GroupChat — RoundRobin 轮询模式

| 编号 | 分类 | 用例 | 操作步骤 | 预期结果 |
|------|------|------|---------|---------|
| MA-Q-01 | 正常 | 3 Agent 固定轮询 3 轮 | agents=[a1,a2,a3], maxRounds=3 | 发言顺序 a1→a2→a3 重复；SSE agent_turn_start/end 带 round 递增 |
| MA-Q-02 | 正常 | 对话历史隔离视角 | a2 收到的 history | 包含 a1 的发言（broadcast 追加到各 Agent history） |
| MA-Q-03 | 正常 | TERMINATE 提前终止 | 某 Agent 回复含 "TERMINATE" | 当前轮后 shouldTerminate=true，进入汇总 |
| MA-Q-04 | 正常 | Summarizer 汇总最终答复 | 指定 summarizerAgentId | outputText 为 summarizer 输出 |
| MA-Q-05 | 边界 | maxRounds 达到上限终止 | maxRounds=5，无 TERMINATE | 恰执行 5 轮后终止，outputText 非空 |
| MA-Q-06 | 边界 | 无 summarizer 配置 | summarizerAgentId=null | 取最后一条 agent_message 作为 output |
| MA-Q-07 | 异常 | 某轮 Agent LLM 超时 | a2 超时 | 该轮标记 FAILED，继续下一轮或整体 FAILED（按策略明确） |
| MA-Q-08 | 异常 | 连续空回复 | 3 轮所有 Agent 输出为空 | 触发终止条件（防死循环） |

### 12.8 GroupChat — DynamicRouter LLM 动态路由模式

| 编号 | 分类 | 用例 | 操作步骤 | 预期结果 |
|------|------|------|---------|---------|
| MA-D-01 | 正常 | Router 选择合适发言者 | router prompt + 对话历史 | pickNextSpeaker 返回合法 agentId，SSE agent_turn_start 含 routerReason |
| MA-D-02 | 正常 | Router JSON 解析 | router 返回 `{"agentId":"analyst","reason":"..."}` | 正确解析 agentId |
| MA-D-03 | 边界 | Router 返回不存在的 agentId | router 返回 ghost | 降级为 RoundRobin 或重试一次，不 crash |
| MA-D-04 | 边界 | Router 返回非 JSON | router 输出散文 | 正则/降级兜底提取，失败则 RoundRobin |
| MA-D-05 | 异常 | dynamicRouterModelId 为空 | 未配置路由模型 | 回退到第一个 Agent 的模型，或 400 提示 |
| MA-D-06 | 异常 | Router 模型 API 失败 | 路由模型 Key 失效 | 降级 RoundRobin 并完成对话，日志有 WARN |

### 12.9 SSE 事件协议（多 Agent 专属 17 类）

| 编号 | 分类 | 用例 | 操作步骤 | 预期结果 |
|------|------|------|---------|---------|
| MA-E-01 | 正常 | multi_agent_start | 创建 Run 后 | payload 含 runId/executionMode/agentCount/userGoal |
| MA-E-02 | 正常 | agent_start/agent_done 配对 | 每个 Agent 执行 | 每个 agent_start 必有对应 agent_done 或 agent_failed |
| MA-E-03 | 正常 | agent_tool_call_start/result | Agent 内 ReAct 工具调用 | toolName/input/resultText/durationMs 齐全 |
| MA-E-04 | 正常 | agent_rag 事件 | Agent 绑定 kbIds | chunks 数组含 kbName/fileName/score |
| MA-E-05 | 正常 | agent_reflection 事件 | enableReflection=true | score(0-10)/pass/feedback 齐全 |
| MA-E-06 | 正常 | agent_message（GroupChat） | broadcast 时 | fromAgentId/content/timestamp 齐全，前端气泡可渲染 |
| MA-E-07 | 正常 | multi_agent_complete | 全部完成 | finalAnswer/totalRounds/totalDurationMs 齐全 |
| MA-E-08 | 边界 | 事件 payload JSON 单行无换行 | 抓原始 SSE 流 | data: 为单行 JSON（换行在 JSON 内转义） |
| MA-E-09 | 边界 | 客户端中途断开 SSE | 执行中 abort | 后端仅 WARN 日志，主流程不中断、不报错事件 |
| MA-E-10 | 异常 | multi_agent_failed | Agent 全部失败 | payload 含 error 和 failedAgentId |

### 12.10 HITL（多 Agent 统一）

| 编号 | 分类 | 用例 | 操作步骤 | 预期结果 |
|------|------|------|---------|---------|
| MA-T-01 | 正常 | Crew 中 Agent hitlRequired=true 暂停 | 执行到该 Agent | SSE agent_hitl_pending + status=PAUSED + step status=HITL_PENDING |
| MA-T-02 | 正常 | confirm 后续跑 | POST hitl-confirm {userInput:"同意"} | status→RUNNING，userInput 注入该 Agent 上下文 |
| MA-T-03 | 正常 | GroupChat 中 HITL Agent 轮次暂停 | 轮到 hitl Agent | 对话循环退出等待，confirm 后从同一轮继续（round 不重跳） |
| MA-T-04 | 边界 | 暂停中取消 Run | PAUSED + cancel | status→CANCELLED，confirm 接口再调返回 400 |
| MA-T-05 | 边界 | 重复 confirm | 同一 Run 连续 confirm 两次 | 第二次幂等或 400（状态已非 PAUSED） |
| MA-T-06 | 异常 | 非 PAUSED 状态调 confirm | RUNNING 时 confirm | 400「run 状态不是 PAUSED」 |

### 12.11 持久化与恢复

| 编号 | 分类 | 用例 | 操作步骤 | 预期结果 |
|------|------|------|---------|---------|
| MA-B-01 | 正常 | 每个 Agent 执行落库 wgh_m_agent_step | 执行 3 Agent Crew | 3 条 step 记录，字段齐全（output/toolCalls/durationMs/tokenCount） |
| MA-B-02 | 正常 | Run 完成后统计字段 | 查询 run | startTime/finishTime/durationMs 齐全且 durationMs = finish-start |
| MA-B-03 | 正常 | 服务重启后查询历史 Run | 重启 Spring Boot → GET state | 历史数据完整可查 |
| MA-B-04 | 边界 | RUNNING 状态服务宕机重启 | 执行中 kill → 重启 | 该 Run 保持 RUNNING 或自动标记 FAILED（行为明确，不允许幽灵 RUNNING 永久挂起） |
| MA-B-05 | 异常 | steps 表 run_id 外键孤儿 | 手工删 run 留 steps | 查询接口不 crash（JOIN 容错或级联逻辑删除） |

### 12.12 集成边界（与现有模块共存）

| 编号 | 分类 | 用例 | 操作步骤 | 预期结果 |
|------|------|------|---------|---------|
| MA-I-01 | 正常 | 单 Agent AgentOrchestrator 不受影响 | 原 /api/agent/stream 执行 | 行为与引入 rag-agent-multi 前完全一致 |
| MA-I-02 | 正常 | 工作流引擎不受影响 | 原 /api/workflows/{id}/run 执行 | 67 项 workflow e2e 全部仍通过 |
| MA-I-03 | 正常 | 多 Agent 内调用 RAG | Agent kbIds 绑定知识库 | RagRetrieveService 正常召回，agent_rag 事件上报 |
| MA-I-04 | 正常 | 多 Agent 内调用工具 | Agent toolIds 绑定 HTTP 工具 | ToolExecutionEngine 正常执行，tool_call 事件上报 |
| MA-I-05 | 正常 | Agent 独立模型路由 | a1 用 qwen / a2 用 deepseek | 各自走各自模型（ModelCacheManager 隔离），token 计数分开 |
| MA-I-06 | 边界 | Agent 模型被禁用后执行 | 中途禁用 a1 的模型 | 执行到 a1 时 FAILED 带明确信息，不影响已完成步骤数据 |
| MA-I-07 | 异常 | rag-agent-multi 模块 Bean 冲突 | 启动日志检查 | 无 BeanDefinitionOverride / 循环依赖错误 |

### 12.13 性能与并发（基础）

| 编号 | 分类 | 用例 | 操作步骤 | 预期结果 |
|------|------|------|---------|---------|
| MA-N-01 | 性能 | 10 并发创建 Run | 10 线程同时 POST /runs | 全部成功，runId 唯一无冲突 |
| MA-N-02 | 性能 | 单 Run 5 Agent 串行总耗时 | 掐表 | ≈ 各 Agent 耗时之和 + 编排开销 <15% |
| MA-N-03 | 性能 | Parallel 模式 4 分支提速 | 对比串行 | 总耗时 ≈ 最慢分支耗时（±20%），非四者之和 |
| MA-N-04 | 边界 | 同一 Crew 模板并发实例化 | 10 个 Run 引用同一 crewId | 配置只读无脏写 |
| MA-N-05 | 异常 | 线程池满负荷 | 连续提交 20 个 Parallel Run | 排队执行不丢任务，无 RejectedExecutionException 泄露给前端 |

---

### 多 Agent 用例统计

| 分类 | 数量 |
|------|------|
| Crew CRUD | 13 |
| Run 生命周期 | 14 |
| Crew Sequential | 6 |
| Crew Hierarchical | 5 |
| Crew Parallel | 5 |
| Graph 路由 | 12 |
| GroupChat RoundRobin | 8 |
| GroupChat DynamicRouter | 6 |
| SSE 事件 | 10 |
| HITL | 6 |
| 持久化恢复 | 5 |
| 集成边界 | 7 |
| 性能并发 | 5 |
| **合计** | **102** |

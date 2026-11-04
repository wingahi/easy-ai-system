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
13. [更多文档格式（PPTX / HTML / JSON / EML）](#13-更多文档格式pptx--html--json--eml)
14. [配额限流（rag-quota）](#14-配额限流rag-quota)
15. [免登录分享 / 嵌入 Widget / 开放 API](#15-免登录分享--嵌入-widget--开放-api)
16. [审计日志 / 成本统计](#16-审计日志--成本统计)
17. [语义缓存](#17-语义缓存)
18. [反馈闭环与离线评测](#18-反馈闭环与离线评测)
19. [多模态图片问答](#19-多模态图片问答)
20. [Prompt 模板中心](#20-prompt-模板中心)
21. [知识库标签与文档版本](#21-知识库标签与文档版本)
22. [模型路由与故障转移](#22-模型路由与故障转移)
23. [工作流 AGENT 节点](#23-工作流-agent-节点)
24. [多用户与权限（RBAC / OIDC）](#24-多用户与权限rbac--oidc)
25. [浏览器全量测试执行记录（2026-11-02）](#25-浏览器全量测试执行记录2026-11-02)
26. [认证与会话（本地 / OIDC 双认证）](#26-认证与会话本地--oidc-双认证)
27. [角色与权限管理（管理端）](#27-角色与权限管理管理端)
28. [对象存储（local / OSS）](#28-对象存储local--oss)
29. [可观测性（Metrics / Request-Id / Actuator）](#29-可观测性metrics--request-id--actuator)
30. [补充用例（文档元数据 / 工作流 / 路由 / 标签版本）](#30-补充用例文档元数据--工作流--路由--标签版本)
31. [限制与未覆盖](#31-限制与未覆盖)

- [附录 A：测试环境速查](#附录-a测试环境速查)
- [附录 B：测试代码规范与自动化门禁](#附录-b测试代码规范与自动化门禁)

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

## 附录 A：测试环境速查

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
| test-multi-agent-smoke.sh | /api/agent-multi（run + state） | 12 | SSE 事件序列 / 终态 COMPLETED / 步骤持久化 / state 接口 | Crew 三种模式细节、GroupChat 路由、HITL 中断 |

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

---

## 附录 B：测试代码规范与自动化门禁

| 项 | 约定 |
|----|------|
| 方法命名 | `@Test` / `@ParameterizedTest` / `@RepeatedTest` / `@TestFactory` 方法必须为纯 ASCII 英文，推荐 `方法_条件_期望`；禁止中文命名 |
| 方法注释 | 每个测试方法上方必须有中文注释（Javadoc 或 `//`），描述实际验证的行为 |
| 静态检查 | `python3 rag-backend/scripts/check-test-conventions.py`，违规逐条输出 `文件:行号`，退出码 1 |
| CI 门禁 | GitHub Actions `.github/workflows/ci.yml` 在 push / PR 时阻断；Gitee Go 复用同一脚本（接入说明见 README 第 9 节） |
| 当前基线 | 380 个测试方法全部合规；surefire 执行 374 项全绿，功能 e2e 113 项全绿 |

---

## 13. 更多文档格式（PPTX / HTML / JSON / EML）

> 特性组 1。实现：`rag-kb` 模块 `parser` 包；自动化：`DocumentFormatParserTest`、`DocumentIngestChainTest`、
> `DocumentParserDispatcherTest`、`DocumentParserTest`（共 20 项，`mvn -pl rag-kb test` 全绿）。
> 覆盖类型说明：`单元` = 解析器/分发器；`链路` = 上传→解析→切片→检索；`接口` = REST；`仅手工` = 无自动化。

| 编号 | 分类 | 前置条件 | 用例 / 步骤 | 预期结果 | 覆盖类型 | 自动化落点 |
|------|------|---------|------------|---------|---------|-----------|
| FMT-01 | 正常 | 已建知识库；准备含 2 页文本的 pptx | 上传 pptx → 等待处理完成 → 查看切片预览 | 状态 SUCCESS，切片数 > 0，正文含两页文本 | 单元 | `DocumentFormatParserTest#parsePptx` |
| FMT-02 | 正常 | 同上 | 调用 `GET /api/kb/files/{id}/preview` | `documentMetadata.pageCount == 2`，含 `charCount` | 单元 | `DocumentFormatParserTest#parsePptx` |
| FMT-03 | 正常 | 准备含 `<title>报销制度</title>` 与正文的 html | 上传 html → 预览 | 标题=「报销制度」，正文含标题与段落 | 单元 | `DocumentFormatParserTest#parseHtml` |
| FMT-04 | 边界 | html 内含 `<script>alert(1)</script>` 与 `<style>` | 解析 html | 正文不含 `alert`，style 内容不入切片 | 单元 | `DocumentFormatParserTest#parseHtml` |
| FMT-05 | 正常 | 准备嵌套 JSON `{"title":"产品配置","spec":{"topK":5},"tags":["检索"]}` | 解析 json | 标题=「产品配置」，出现 `spec.topK: 5`、`tags[0]: 检索` | 单元 | `DocumentFormatParserTest#parseJson` |
| FMT-06 | 正常 | 准备含主题/发件人/正文的 eml | 解析 eml | 标题=主题，正文含发件人与正文 | 单元 | `DocumentFormatParserTest#parseEml` |
| FMT-07 | 边界 | 准备 GBK 编码的 txt | 解析 txt | `encoding == GBK`，中文无乱码 | 单元 | `DocumentFormatParserTest#parseGbkText` |
| FMT-08 | 异常 | 准备伪装成 pdf 的文本文件 | 解析 pdf | 抛业务错误，提示「文件可能已损坏或格式不正确」 | 单元 | `DocumentFormatParserTest#corruptFileFriendlyError` |
| FMT-09 | 边界 | 准备 2MB 文件，上限调为 1MB | 解析 | 抛业务错误，提示「超过解析上限 1 MB」 | 单元 | `DocumentFormatParserTest#oversizedFileRejected` |
| FMT-10 | 边界 | 准备 0 字节文件 | 解析 | 抛业务错误，提示「文件为空」 | 单元 | `DocumentFormatParserTest#emptyFileRejected` |
| FMT-11 | 异常 | 文件扩展名为未注册格式（如 xyz） | 解析 | 抛业务错误，提示「不支持的文件格式: XYZ」 | 单元 | `DocumentParserTest#parseUnknown` |
| FMT-12 | 正常 | 12 个内置解析器 | 遍历扩展名断言注册 | pdf/txt/md/csv/xlsx/xls/docx/doc/pptx/html/htm/json/eml 均有解析器 | 单元 | `DocumentParserDispatcherTest#builtinParsersSupportAllExtensions` |
| FMT-13 | 正常 | 两个解析器，其中一个支持 pdf | 以大写 `PDF` 分发 | 仅命中 pdf 解析器，扩展名已转小写 | 单元 | `DocumentParserDispatcherTest#dispatchToSupportingParser` |
| FMT-14 | 链路 | 内存 embedding + 内存向量库 | 上传 HTML → 解析 → 递归切片 → 写入向量 → 检索「采购审批金额」 | top1 命中该 HTML 的切片 | 链路 | `DocumentIngestChainTest#uploadParseSplitEmbedAndRetrieve` |
| FMT-15 | 接口 | 后端 8080、已登录、已建知识库 | 依次上传 pptx/html/json/eml → 轮询文件状态 → 预览 | 4 个文件均 SUCCESS 且有切片；预览含 `documentMetadata` | 仅手工 | — |
| FMT-16 | 接口 | 同上 | 上传 GBK txt → 预览 → 重新切片 | 编码 GBK、切片正常；重新切片不报错 | 仅手工 | — |
| FMT-17 | 回归 | 准备 pdf/docx/doc/xls/xlsx/csv/txt/md 各 1 份 | 逐格式上传并预览 | 既有 8 种格式行为与改造前一致 | 单元 | `DocumentParserTest`（8 项） |

---

## 14. 配额限流（rag-quota）

> 特性组：审计日志 / 配额限流 / 成本统计中的配额部分。实现：`rag-quota` 模块；
> 自动化：`QuotaServiceTest`（19 项，`mvn -pl rag-quota -am test` 全绿）。
> 覆盖类型说明：`单元` = 服务/解析规则；`接口` = REST；`端到端` = 配置→校验→统一响应；`仅手工` = 浏览器交互。

| 编号 | 分类 | 前置条件 | 用例 / 步骤 | 预期结果 | 覆盖类型 | 自动化落点 |
|------|------|---------|------------|---------|---------|-----------|
| QTA-01 | 正常 | 无自定义配置，全局默认 200 条/200 万 token | 查询用户生效配额 | 来源 DEFAULT、周期 DAY、上限 200 / 2000000 | 单元 | `QuotaServiceTest#globalDefaultApplies` |
| QTA-02 | 正常 | 无自定义配置，用量 10 条 | 发送一次对话 | 放行并按当日键 `+1 chat / +0 token` | 单元 | `QuotaServiceTest#defaultAllowsUnderLimit` |
| QTA-03 | 正常 | 用户自定义 3 条/5000 token 且启用 | 查询生效配额 | 来源 CUSTOM，自定义值覆盖全局默认 | 单元 | `QuotaServiceTest#userOverrideApplies` |
| QTA-04 | 异常 | 用户自定义上限 3 条，当期已用 3 条 | 发送对话 | 抛 429，提示「今日对话次数已达上限（3 条）」，不累加计数 | 单元 | `QuotaServiceTest#chatLimitExceededReturns429` |
| QTA-05 | 异常 | 用户 token 上限 5000，当期已用 5000 | 发送对话 | 抛 429，提示「token 用量已达上限」 | 单元 | `QuotaServiceTest#tokenLimitExceededReturns429` |
| QTA-06 | 正常 | 用户配置周期 DAY / MONTH | 计算周期键 | DAY=`yyyy-MM-dd`，MONTH=`yyyy-MM`，大小写不敏感 | 单元 | `QuotaServiceTest#periodKeys`、`#periodRolloverProducesNewKeys` |
| QTA-07 | 正常 | 用户周期 MONTH | 发送对话 | 计数写入 `yyyy-MM` 周期键 | 单元 | `QuotaServiceTest#monthPeriodCountsOnMonthKey` |
| QTA-08 | 边界 | 用户自定义配置 enabled=0 | 查询/发送 | 回退全局默认（200 条/日） | 单元 | `QuotaServiceTest#disabledConfigFallsBackToDefault` |
| QTA-09 | 异常 | 提交周期 WEEK、负数上限、空用户名 | PUT 保存配置 | 分别提示「周期仅支持 DAY / MONTH」「不能为负数」「用户名不能为空」 | 单元 | `QuotaServiceTest#saveUserConfigValidates` |
| QTA-10 | 正常 | 用户已有自定义配置 | 再次保存 | 走更新，不新增行 | 单元 | `QuotaServiceTest#saveUserConfigUpsert` |
| QTA-11 | 正常 | 用户已有自定义配置 | 恢复默认后再保存 | 逻辑删除行被复活，不触发 username 唯一键冲突 | 单元 | `QuotaServiceTest#saveAfterRestoreRevivesDeletedRow` |
| QTA-12 | 正常 | 用户有当期用量 | 管理端重置用量 | 当期键计数清零，配置不变 | 单元 | `QuotaServiceTest#resetUsageClearsCurrentPeriod` |
| QTA-13 | 正常 | 用户有自定义配置 | 恢复默认 | 配置逻辑删除，生效配额回退 DEFAULT | 单元 | `QuotaServiceTest#resetUserConfigFallsBackToDefault` |
| QTA-14 | 边界 | 用户无自定义配置 | 恢复默认 | 返回 false（无操作） | 单元 | `QuotaServiceTest#resetUserConfigNoop` |
| QTA-15 | 边界 | 8 线程 × 25 次并发发送 | 并发计数 | 全部落库，总增量 200，无异常 | 单元 | `QuotaServiceTest#concurrentIncrementsAreNotLost` |
| QTA-16 | 正常 | token 增量为 0/负数 | 累加 token | 不写库；正数按生效周期键累加 | 单元 | `QuotaServiceTest#addTokensSkipsNonPositive` |
| QTA-17 | 异常 | DB 不可用 | 发送/累加 token | 不阻断主流程，仅 WARN 日志 | 单元 | `QuotaServiceTest#dbFailureIsSwallowed` |
| QTA-18 | 端到端 | 用户上限 2 条，当期已用 2 条 | 发送对话 → 全局异常处理 | 响应体 `code=429`，message 含「对话次数已达上限」 | 端到端 | `QuotaServiceTest#quotaExhaustedMapsTo429Response` |
| QTA-19 | 接口 | 后端 8080，admin 登录 | 打开「用户配额」→ 搜索/编辑上限与周期/启用开关/保存 → 列表刷新 | 行内来源/周期/上限/用量即时更新 | 仅手工 | `QuotaManagement.vue` |
| QTA-20 | 接口 | 同上 | 点击「重置用量」/「恢复默认」 | 用量归零 / 来源变全局默认，提示成功 | 仅手工 | `QuotaManagement.vue` |
| QTA-21 | 接口 | 普通用户登录 | 用户菜单「我的配额」 | 展示来源、周期、上限、已用、剩余；≥90% 显示接近上限告警 | 仅手工 | `MyQuota.vue` |
| QTA-22 | 接口 | 配额耗尽 | 在调试对话/分享页发送消息 | 页面显示可读的配额提示，而非静默结束 | 仅手工 | `streamChatApi` / `streamShareApi` JSON 错误解析 |

---

## 15. 免登录分享 / 嵌入 Widget / 开放 API

> 特性组：阶段 H。后端实现层 `rag-public-access`（`com.wgh.rag.publicaccess`），Controller 在 `rag-controller`（`com.wgh.rag.controller`）；Redis 基础设施 `com.wgh.rag.config.redis`；前端 `PublicShareChat.vue`、`PublicAccess.vue`、`public/widget.js`。
> 自动化：`PublicAccessUnitTest`（18 项）+ `PublicAccessRedisSecurityTest`（12 项，覆盖 Redis 限流原子性 / 掉线降级 / 嵌入白名单 / 密码锁定 / API Key IP 白名单，**无需 Docker**，使用脚本化 Redis 桩），`mvn -o -pl rag-app -am test -Dtest='PublicAccess*' -Dsurefire.failIfNoSpecifiedTests=false` 全绿。
> 设计说明见 `docs/design-public-access.md`；HTTP 层用例为「仅手工」或「curl 实测」（需后端 8080/8090 + 前端 5173 + Redis）。

### 15.1 分享链接

| 编号 | 分类 | 前置条件 | 用例 / 步骤 | 预期结果 | 覆盖类型 | 自动化落点 |
|------|------|---------|------------|---------|---------|-----------|
| SHARE-01 | 单元 | 数据库无 token 记录 | `resolveActive("not-exist")` | 抛业务异常「分享链接无效」 | 单元 | `PublicAccessUnitTest#invalidToken` |
| SHARE-02 | 单元 | 链接 `revoked=1` | `resolveActive` | 抛「分享链接已被吊销」 | 单元 | `#revokedLink` |
| SHARE-03 | 单元 | `expires_at` 已过去 | `resolveActive` | 抛「分享链接已过期」 | 单元 | `#expiredLink` |
| SHARE-04 | 单元 | `max_uses=5`、`use_count=5` | `resolveActive` | 抛 429「分享链接访问次数已用尽」 | 单元 | `#usesExhausted` |
| SHARE-05 | 单元 | 链接有密码，请求未带密码 | `requirePassword(link, null)` | 抛「该分享链接需要访问密码」 | 单元 | `#passwordRequired` |
| SHARE-06 | 单元 | 链接有密码，密码错误 | `requirePassword(link, "wrong")` | 抛「访问密码错误」 | 单元 | `#passwordWrong` |
| SHARE-07 | 单元 | 链接有密码，密码正确 | `requirePassword` + `matchesPassword` | 通过；错误密码返回 false | 单元 | `#passwordCorrect` |
| SHARE-08 | 单元 | 链接 scope=KB，请求按 WINDOW 访问 | `requireScope` | 抛权限不足，提示「不支持该操作」 | 单元 | `#scopeMismatch` |
| SHARE-09 | 单元 | 有效期超过上限（31 天） | `create` | 拒绝并提示「有效期超过上限」 | 单元 | `#createExpiryTooLong` |
| SHARE-10 | 单元 | scope 小写 `window` | `create` | scope 归一 `WINDOW`；token 以 `s_` 开头；默认有效期≈7 天；`useCount=0`、`revoked=false` | 单元 | `#createWindowLink` |
| SHARE-11 | 单元 | scope=KB、target 不存在 | `create` | 抛「知识库不存在」 | 单元 | `#createKbNotFound` |
| SHARE-12 | 单元 | 并用次数扣减更新影响 0 行（并发竞争） | `consumeUse` | 抛 429 | 单元 | `#consumeUseConflict` |
| SHARE-13 | 接口 | 已登录 admin，存在聊天窗口 | 「开放平台」→ 创建 WINDOW 分享（7 天、无密码）→ 复制链接 → 无痕窗口打开 | 免登录直接进入对话页，可提问并收到 SSE 回复 | 仅手工 | `PublicAccess.vue` / `PublicShareChat.vue` |
| SHARE-14 | 接口 | 存在 SESSION 分享 | 无痕窗口打开链接 | 只展示目标会话历史消息，无输入框，标记「只读分享」 | 仅手工 | `PublicShareChat.vue` |
| SHARE-15 | 接口 | 存在 KB 分享 | 无痕窗口打开链接，输入关键词检索 | 返回命中片段（知识库/文档/内容/score），无问答输入 | 仅手工 | `PublicShareChat.vue` |
| SHARE-16 | 接口 | 创建时填写访问密码 | 打开链接 | 弹「该分享需要访问密码」；错误密码提示剩余尝试次数；达阈值后锁定并展示剩余秒数；锁定期间正确密码仍拒绝；到期后可进入 | 仅手工 | `PublicShareChat.vue#unlock` / `SharePasswordGuard` |
| SHARE-17 | 接口 | 已吊销 / 已过期 / 次数用尽 | 打开链接 | 页面展示对应明确错误（吊销/过期/次数用尽），不进入对话 | 仅手工 | `PublicShareChat.vue#loadError` |
| SHARE-18 | 接口 | WINDOW 分享，配额耗尽 | 匿名发送消息 | 页面提示可读的配额信息而非静默结束 | 仅手工 | `streamPublicShareChat` 错误分支 |
| SHARE-19 | 接口 | 同一分享链接 + 同一 IP | 1 分钟内 >20 次请求 | 业务码 429「请求过于频繁」，`data.dimension=share-token`、`data.store=redis` | curl 实测 | `PublicAccessRateLimiter` / `DefaultPublicAccessStore` |
| SHARE-20 | 回归 | 登录态访问 `/share/{token}` | 直接打开 | 仍按 token 校验，不要求登录、不回跳登录页 | 仅手工 | `router/index.js` 免登录守卫 |

### 15.2 开放 API

| 编号 | 分类 | 前置条件 | 用例 / 步骤 | 预期结果 | 覆盖类型 | 自动化落点 |
|------|------|---------|------------|---------|---------|-----------|
| OPEN-01 | 单元 | 无 scope 列表 | `create(name, [], ...)` | 抛「至少需要指定一个权限范围」 | 单元 | `PublicAccessUnitTest#createApiKeyWithoutScope` |
| OPEN-02 | 单元 | 正常创建 | `create(...)` | 明文以 `wgh_sk_` 开头；`keyPrefix`=明文前 12 位；存储为 SHA-256；scopes 逗号拼接 | 单元 | `#createApiKey` |
| OPEN-03 | 单元 | Key 不存在 / 已吊销 / 已过期 | `verify` | 分别抛「API Key 无效」「已被吊销」、`TOKEN_EXPIRED` | 单元 | `#verifyApiKeyErrors` |
| OPEN-04 | 单元 | scope=`*` / scope=`kb:read` | `requireScope(key, "chat:write")` | `*` 通过；缺失抛权限不足且提示 `chat:write` | 单元 | `#requireScope` |
| OPEN-05 | 单元 | 相同明文 | `sha256` 两次 | 结果稳定且长度 64 | 单元 | `#sha256Stable` |
| OPEN-06 | 接口 | 已登录 admin | 创建 Key（勾选 `chat:write`）→ 复制明文 → 用 curl 调 `/api/open/v1/me` | 返回 Key 信息（无哈希）；列表中无明文 | 仅手工 | `PublicAccess.vue` |
| OPEN-07 | 接口 | 有 `chat:write` 的 Key | `POST /api/open/v1/chat/stream`（有效 windowId，不传 sessionId） | SSE 正常返回；自动创建 `api_<keyId>` 会话；配额计数增加 | 仅手工 | `OpenApiController#stream` |
| OPEN-08 | 接口 | Key 仅 `kb:read` | 调 `/api/open/v1/chat/stream` | 403，提示缺少 `chat:write` | 仅手工 | `ApiKeyAuthenticationFilter` |
| OPEN-09 | 接口 | 任意有效 Key | 调 `/api/open/v1/kb/search` | 返回命中片段；`topK` 生效 | 仅手工 | `OpenApiController#kbSearch` |
| OPEN-10 | 接口 | 任意有效 Key | 调 `/api/open/v1/usage` | 返回当日用量、调用总数、最近 20 条日志 | 仅手工 | `OpenApiController#usage` |
| OPEN-11 | 接口 | 已吊销 Key / 过期 Key | 任意 `/api/open/v1/**` | 401/403 + JSON 错误体，不进入业务 | 仅手工 | `ApiKeyAuthenticationFilter` |
| OPEN-12 | 接口 | 有效 Key | 1 分钟内 >60 次（同一路径） | 业务码 429，`data.dimension=api-key`、`limitPerMinute=60`、`store=redis`；单 IP 阈值 120 不在本用例触发 | curl 实测 | `PublicAccessRateLimiter` / `DefaultPublicAccessStore` |
| OPEN-13 | 接口 | 有效 Key | 调用后查看「调用日志」 | 记录 Key、方法、路径、状态码、耗时、IP | 仅手工 | `ApiCallLogService#record` |
| OPEN-14 | 回归 | 无 Key 直接访问 `/api/open/v1/me` | curl | 401，且不会被 JWT 过滤器二次拦截（返回 JSON 而非空 401） | 仅手工 | `PublicAccessSecurityConfig` |

### 15.3 Widget

| 编号 | 分类 | 前置条件 | 用例 / 步骤 | 预期结果 | 覆盖类型 | 自动化落点 |
|------|------|---------|------------|---------|---------|-----------|
| WID-01 | 接口 | 有效分享 token、前端 5173 | 打开 `/widget-demo.html?token=<token>` | 页面渲染 iframe，可完成匿名问答 | 仅手工 | `public/widget-demo.html` |
| WID-02 | 接口 | 任意第三方页面 | 引入 `widget.js` 并配置 `data-token/data-base/data-container/data-height` | 在指定容器注入 iframe（宽 100%、圆角、阴影）；iframe 内样式不受宿主影响 | 仅手工 | `public/widget.js` |
| WID-03 | 接口 | 宿主页面使用深色背景/自定义 CSS | 同上 | iframe 内分享页样式不被宿主 CSS 覆盖（iframe 独立文档隔离） | 仅手工 | `widget.js` / `PublicShareChat.vue` `embed` 样式 |
| WID-04 | 接口 | 无 token 或 token 无效 | 打开 demo 页 | 提示需填写 token / 分享页展示「分享链接无效」 | 仅手工 | `widget-demo.html` |
| WID-05 | 接口 | `embed_enabled=1`，域名白名单含宿主域 | demo 页加载 Widget | `widget.js` 先请求 `/embed/authorize` 获 grant，再注入 `?embed=1&grant=...` iframe；可正常问答 | curl 实测 | `public/widget.js` / `EmbedAccessService` |
| WID-06 | 接口 | 宿主域不在域名白名单 | 同上 | 不注入 iframe，容器内展示「来源 xxx 不在嵌入白名单内」 | curl 实测 | `EmbedAccessService#authorize` |
| WID-07 | 接口 | 请求缺少 `Origin` 与 `Referer`，IP 未命中白名单 | `GET /embed/authorize` | 拒绝，提示缺少来源信息（默认拒绝） | curl 实测 | `EmbedAccessService` |
| WID-08 | 接口 | 已获 grant 的 iframe | 清空 / 篡改 `X-Embed-Grant` 后调用分享接口 | 拒绝，code=1004 | 单元 | `PublicAccessRedisSecurityTest#embedAuthorizeHitAndReject` |
| WID-09 | 接口 | `embed_enabled=0` | 带 `X-Embed-Mode: 1` 访问分享页 | 拒绝嵌入模式 | 单元 | `PublicAccessRedisSecurityTest#embedDisabled` |

> 实测（2026-11-02）：WID-01 初测失败——`widget-demo.html` 内联脚本字符串含字面量 `</script>` 导致脚本被提前闭合、整段不执行；已修复（`'</scr' + 'ipt>'`）并复测通过（详见 §25 F1）。

### 15.4 Redis 限流 / 嵌入白名单 / 密码锁定 / API Key IP 白名单

| 编号 | 分类 | 前置条件 | 用例 / 步骤 | 预期结果 | 覆盖类型 | 自动化落点 |
|------|------|---------|------------|---------|---------|-----------|
| RL-01 | 单元 | 脚本化 Redis，limit=3 | 连续 3 次 `acquireSliding` | 前 3 次通过，第 4 次返回 -1（超限），窗口内不重复计数 | 单元 | `PublicAccessRedisSecurityTest#redisLuaSlidingWindowOverLimit` |
| RL-02 | 单元 | 限流后 Redis 抛连接异常 | 再次 `acquire` | 自动降级内存，不抛异常；输出 WARN | 单元 | `#redisFailureFallsBackToMemory` |
| RL-03 | 单元 | `enabled=false` | 直接构造 store | `distributed=false`，内存限流生效 | 单元 | `#redisDisabledUsesMemory` |
| RL-04 | curl | Redis 8.10.1 已启动，分享链接有效 | 同一 token + IP 1 分钟内请求 `/api/public/share/{token}` 22 次 | 第 21 次起 `code=429`，`data.store=redis`；Redis ZSET 长度=20 | curl 实测 | `DefaultPublicAccessStore` Lua |
| RL-05 | curl | 有效 API Key | 1 分钟内调用 `/api/open/v1/chat/stream` 61 次（空 body） | 第 61 次 `code=429`，`data.dimension=api-key`；前 60 次为参数错误 | curl 实测 | `PublicAccessRateLimiter` |
| RL-06 | 运维 | Redis 宕机 | 停止容器后访问公开接口 | 应用不重启；限流 / 计数转为内存；恢复后自动回到 Redis | 仅手工 | `DefaultPublicAccessStore` |
| EMB-01 | 单元 | 域名白名单 `*.example.com` | `Origin: https://a.example.com` | 命中 | 单元 | `#domainWhitelistMatching` |
| EMB-02 | 单元 | 同上 | `Origin: https://example.com` / `https://evil.com` | 均不命中（通配仅匹配子域） | 单元 | `#domainWhitelistMatching` |
| EMB-03 | 单元 | IP 白名单 `10.0.0.0/8` | 客户端 `10.1.2.3` / `192.168.1.1` | 前者命中、后者拒绝 | 单元 | `#embedIpWhitelist` |
| EMB-04 | 单元 | IPv6 白名单 | `::1/128` 校验 `0:0:0:0:0:0:0:1` | 命中 | 单元 | `#cidrMatching` |
| PWD-01 | 单元 | 阈值 5，锁定 15 分钟 | 连续 5 次错误 + 1 次正确 | 第 5 次返回 `PUBLIC_ACCESS_LOCKED`（3004）；正确密码仍被拒，`lockRemainingSeconds>0` | 单元 | `#passwordLockAndUnlock` |
| PWD-02 | curl | 锁定 1 分钟（运行参数覆盖） | 5 次错误 → 正确密码 → 等待 61 秒 → 正确密码 | 锁定期间 3004；到期后 200，失败计数清零 | curl 实测 | `SharePasswordGuard` |
| KEYIP-01 | 单元 | Key IP 白名单 `10.0.0.0/8` | `requireIpAllowed(key, "10.1.2.3")` / `"192.168.1.1"` | 前者放行，后者 403 可读文案 | 单元 | `#apiKeyIpWhitelist` |
| KEYIP-02 | 单元 | 创建 Key 时传入 CIDR 列表 | `create(..., ipWhitelist)` | 列表规范化（单 IP 补 `/32`），空值存 NULL | 单元 | `#apiKeyCreateIpWhitelist` |
| KEYIP-03 | curl | 白名单 `::1/128,127.0.0.1/32` / `10.0.0.0/8` | `GET /api/open/v1/me` | 前者 HTTP 200；后者 HTTP 403、`code=1004` | curl 实测 | `ApiKeyAuthenticationFilter` |
| IP-01 | 单元 | `trusted-proxies=10.0.0.0/8`，对端可信 | XFF=`1.2.3.4, 10.0.0.5` | 解析为 `1.2.3.4`（从右向左跳过可信代理） | 单元 | `#trustedProxyOnly` |
| IP-02 | 单元 | `trusted-proxies` 为空 | 伪造 `X-Forwarded-For: 1.2.3.4` | 仍使用 TCP 对端地址，伪造头不生效 | 单元 | `ClientIpResolver` |

---

## 16. 审计日志 / 成本统计

> 实现：`@Audit` + `AuditAspect`（异步落库，失败不阻断主流程，记录 SUCCESS/FAIL）、`ModelPriceService`、`AuditLogService`。
> 接口：`GET /api/admin/audit-logs`、`GET /api/admin/stats/cost`、`GET /api/admin/stats/quota`。
> 表：`wgh_audit_log`、`wgh_model_price`、`wgh_chat_message.cost/model_name`。
> 自动化：`ModelPriceServiceTest`（5 项，rag-llm）；审计切面与统计接口无单测，相关用例标注「仅手工」。

| 编号 | 分类 | 前置条件 | 用例 / 步骤 | 预期结果 | 覆盖类型 | 自动化落点 |
|------|------|---------|------------|---------|---------|-----------|
| AUD-01 | 接口 | admin 登录 | 执行写操作（新建知识库）→ `GET /api/admin/audit-logs` | 新增记录含 username/action/resourceType/resourceId/ip/status=SUCCESS/耗时；分页可查 | 仅手工 | `AdminStats.vue` 审计日志页签 |
| AUD-02 | 异常 | 同上 | 执行失败写操作（如删除不存在资源）→ 查看审计 | 记录 status=FAIL 且 detail 含 ERROR 摘要，主流程仍返回原错误 | 仅手工 | `AuditAspect` |
| AUD-03 | 边界 | 审计落库异常（模拟 DB 不可用） | 触发写操作 | 主流程不受影响，仅 WARN 日志 | 仅手工 | `AuditAspect#finally` |
| AUD-04 | 接口 | 存在多条审计记录 | 按关键字/分页查询 | 过滤与总数正确 | 仅手工 | `AdminStatsController#/audit-logs` |
| COST-01 | 单元 | 配置某模型 input/output 单价 | 估算固定 token 用量成本 | 按单价 × token 计算，缺价格时返回空 | 单元 | `ModelPriceServiceTest`（5 项） |
| COST-02 | 接口 | 已配置模型价格 | 完成一次对话后查消息 | `wgh_chat_message.cost`、`model_name` 有值 | 仅手工 | `RagChatService` 成本回填 |
| COST-03 | 接口 | 未配置模型价格 | 完成一次对话 | `cost` 为空，不影响回复 | 仅手工 | `RagChatService` |
| COST-04 | 接口 | 有多条带成本消息 | `GET /api/admin/stats/cost` | 按模型/日期聚合出金额与 token 用量 | 仅手工 | `AdminStatsController#/stats/cost` |

---

## 17. 语义缓存

> 实现：`SemanticCacheService` + `RagChatService` 前置拦截；窗口字段 `semantic_cache_enabled`；配置 `wgh.rag.cache.enabled/similarity-threshold/max-per-window`；表 `wgh_semantic_cache` + 独立 pgvector 表。
> 自动化：`SemanticCacheServiceTest`（15 项，rag-kb）。注意：该模块正由其他会话调整，用例以当前磁盘代码为准。

| 编号 | 分类 | 前置条件 | 用例 / 步骤 | 预期结果 | 覆盖类型 | 自动化落点 |
|------|------|---------|------------|---------|---------|-----------|
| CACHE-01 | 单元 | 全局与窗口缓存均开启，阈值 0.95 | 写入缓存后以同义问题查询 | 相似度 ≥ 阈值 → 命中，返回缓存答案 | 单元 | `SemanticCacheServiceTest` |
| CACHE-02 | 单元 | 同上 | 以不相关问题查询 | 低于阈值 → 未命中，走正常检索 | 单元 | `SemanticCacheServiceTest` |
| CACHE-03 | 单元 | `max-per-window=500` 且已满 | 再写入新缓存 | 删除最旧记录，条数不超过上限 | 单元 | `SemanticCacheServiceTest` |
| CACHE-04 | 单元 | 全局关闭或窗口关闭 | 查询 | 不读不写缓存，直接走 RAG | 单元 | `SemanticCacheServiceTest` |
| CACHE-05 | 接口 | 窗口开启缓存 | 相同问题连问两次 | 第二次 SSE 带 cacheHit 标识，响应明显加快 | 仅手工 | `ChatWindows.vue` / `ChatPlayground.vue` |
| CACHE-06 | 接口 | 窗口关闭缓存 | 相同问题连问两次 | 两次均完整检索 + LLM，无 cacheHit | 仅手工 | `ChatWindows.vue` |

---

## 18. 反馈闭环与离线评测

> 实现：`FeedbackService` + `ChatController`；接口 `POST /api/chat/messages/{id}/feedback`、`GET /api/chat/windows/{windowId}/feedback/stats`、`GET /api/admin/feedback/dislikes`；字段 `wgh_chat_message.feedback/feedback_comment`；离线评测脚本 `eval_recall.py` + ground truth 标注。
> 自动化：`FeedbackServiceTest`（7 项，rag-chat）。**部分完成**：无数据集版本管理与 A/B 对比。

| 编号 | 分类 | 前置条件 | 用例 / 步骤 | 预期结果 | 覆盖类型 | 自动化落点 |
|------|------|---------|------------|---------|---------|-----------|
| FB-01 | 单元 | 存在助手消息 | 提交 feedback=1（赞） | 落库成功，统计 +1 | 单元 | `FeedbackServiceTest` |
| FB-02 | 单元 | 同上 | 提交 feedback=-1（踩）+ comment | 落库成功，差评列表可见备注 | 单元 | `FeedbackServiceTest` |
| FB-03 | 单元 | 非法 feedback 值 | 提交 | 明确报错或被忽略，不写脏数据 | 单元 | `FeedbackServiceTest` |
| FB-04 | 接口 | 已有若干赞/踩 | `GET /api/chat/windows/{id}/feedback/stats` | 返回总数、好评率/差评数 | 仅手工 | `ChatController` |
| FB-05 | 接口 | 存在差评 | `GET /api/admin/feedback/dislikes` | 分页返回差评消息（含问题、回答、备注） | 仅手工 | `AdminStats.vue` 差评页签 |
| FB-06 | 接口 | 窗口中有对话 | 前端点击 👍 / 👎 | 图标状态切换，刷新后保持 | 仅手工 | `ChatPlayground.vue` |
| FB-07 | 仅手工 | 已标注 ground truth 数据集 | `python eval_recall.py`（离线） | 输出 Recall@K 指标，可用于对比 | 仅手工 | `eval_recall.py`（脚本，未纳入 CI） |
| FB-08 | 仅手工 | 同上 | 管理版本化数据集 / A/B 对比 | **未实现**，无数据集版本与实验对比能力 | 仅手工 | —（缺失） |

---

## 19. 多模态图片问答

> 实现：`ChatImageService` 上传与校验，`RagChatService` 接受 `imageUrls`；`wgh_llm_model.supports_vision` 控制视觉模型；`wgh_chat_message.attachments` 存图片地址。
> 自动化：`ChatImageServiceTest`（9 项，rag-chat）。

| 编号 | 分类 | 前置条件 | 用例 / 步骤 | 预期结果 | 覆盖类型 | 自动化落点 |
|------|------|---------|------------|---------|---------|-----------|
| MM-01 | 单元 | 合法 png/jpg | 上传并校验 | 返回可访问地址（data URL 或静态 URL） | 单元 | `ChatImageServiceTest` |
| MM-02 | 单元 | 超限大小 / 非图片类型 / 超过数量上限 | 上传 | 明确拒绝并提示原因 | 单元 | `ChatImageServiceTest` |
| MM-03 | 接口 | 视觉模型（supports_vision=1） | 发送“图片 + 问题” | SSE 正常返回，回答内容引用图片 | 仅手工 | `ChatPlayground.vue` |
| MM-04 | 接口 | 非视觉模型 | 发送带图片问题 | 明确提示模型不支持图片，不静默丢弃 | 仅手工 | `RagChatService` |
| MM-05 | 接口 | 任一带图对话 | 查询消息 | `attachments` 字段包含图片地址，刷新可回显 | 仅手工 | `wgh_chat_message.attachments` |
| MM-06 | 接口 | 分享/开放 API 入口 | 单次提问图片数 > 配置上限 | 拒绝并提示上限（`wgh.public-access.max-images-per-request`） | 仅手工 | `PublicShareController` / `OpenApiController` |

---

## 20. Prompt 模板中心

> 实现：`PromptTemplateService` + `PromptTemplateController`（`/api/prompt-templates` 增删改查、`/{id}/render`、`/suggest` 前缀联想）；表 `wgh_prompt_template`；窗口 systemPrompt 支持 `template:{id}` 引用。
> 前端：`src/components/PromptTemplateInput.vue`（前缀触发/联想过滤/键盘选择/填入/变量填写），配置项存 localStorage（`wgh_prompt_trigger_config`：启用、前缀字符、最大候选数）。
> 自动化：`PromptTemplateServiceTest`（9 项）+ `PromptTemplateSuggestTest`（6 项，覆盖 name/remark/category 模糊匹配、limit 归一化与上限、无结果、variables 解析回退；rag-chat）；浏览器实测见 `docs/testing/evidence/2026-11-04-29-prompt-prefix-suggest.png`。

| 编号 | 分类 | 前置条件 | 用例 / 步骤 | 预期结果 | 覆盖类型 | 自动化落点 |
|------|------|---------|------------|---------|---------|-----------|
| PT-01 | 单元 | 模板含 `{{question}}` 等变量 | 提取变量 | 返回去重变量列表 | 单元 | `PromptTemplateServiceTest` |
| PT-02 | 单元 | 提供变量值 | 渲染 | 占位符被替换；缺变量时明确报错或保留 | 单元 | `PromptTemplateServiceTest` |
| PT-03 | 接口 | 已登录 | `GET /api/prompt-templates/list` | 返回内置 + 自定义模板 | 仅手工 | `PromptTemplates.vue` |
| PT-04 | 接口 | 同上 | 新建 / 编辑 / 删除模板 | 保存成功，列表刷新 | 仅手工 | `PromptTemplateController` |
| PT-05 | 接口 | 有模板 | `POST /api/prompt-templates/{id}/render` | 返回渲染后的文本，供预览 | 仅手工 | `PromptTemplates.vue` 实时预览 |
| PT-06 | 接口 | 窗口 systemPrompt 设为 `template:{id}` | 发起对话 | 实际系统提示为模板渲染结果；模板不存在时报错 | 仅手工 | `RagChatService` |
| PT-07 | 接口 | 已登录 | `GET /api/prompt-templates/suggest?query=&limit=` | 返回 id/名称/简述/变量/版本/内容；空 query 按更新时间倒序；无匹配返回 `[]` | 单元 + 手工 | `PromptTemplateSuggestTest` |
| PT-08 | 边界 | 已登录 | `limit=0`、`limit=999` | 归一化为 1 / 50（`SUGGEST_MAX_LIMIT`） | 单元 | `PromptTemplateSuggestTest` |
| PT-09 | 安全 | 未携带 token | 请求 `/suggest` | 401，沿用全局登录态，未放宽权限 | 手工（curl） | 全局 `SecurityConfig` |
| PT-10 | 功能 | 调试对话页 | 输入框中输入 `/` | 输入框上方弹出「Prompt 模板」候选面板，展示名称/分类/版本/变量预览 | 手工 | 浏览器实测 |
| PT-11 | 功能 | 候选面板已弹出 | 继续输入「客服」 | 候选按名称/简述/分类模糊过滤（`/suggest` 服务端匹配） | 手工 | 浏览器实测 |
| PT-12 | 交互 | 候选面板已弹出 | ↑↓ 切换、Enter 选中 | 选中模板内容替换「前缀+关键词」，光标落在插入内容末尾 | 手工 | 浏览器实测 |
| PT-13 | 交互 | 候选面板已弹出 | 按 Esc / 删除前缀 / 输入空格 | 面板关闭；无前缀时输入普通文字不弹面板 | 手工 | 浏览器实测 |
| PT-14 | 功能 | 模板含 `{{kbName}}` | 选中后填写变量 | 占位符高亮、逐个 Tab/Enter 填写，变量替换后正文不再含双花括号 | 手工 | 浏览器实测 |
| PT-15 | 边界 | 变量未全部填写 | 点发送 | 拦截并提示；可点「跳过变量直接发送」，未填变量按 render 规则替换为空串后发送 | 手工 | 浏览器实测 |
| PT-16 | 配置 | Prompt 模板中心页 | 修改启用开关/前缀字符/最大候选数 | 保存到 localStorage，输入框按新配置触发 | 手工 | `PromptTemplates.vue` + `promptTrigger.js` |

---

## 21. 知识库标签与文档版本

> 实现：`TagService`、`FileVersionService`、`TagController`；表 `wgh_tag`、`wgh_kb_tag_rel`、`wgh_file_tag_rel`、`wgh_knowledge_file_version`。
> **无自动化单测**（缺 `TagServiceTest` / `FileVersionServiceTest`），本组用例均为「仅手工」。

| 编号 | 分类 | 前置条件 | 用例 / 步骤 | 预期结果 | 覆盖类型 | 自动化落点 |
|------|------|---------|------------|---------|---------|-----------|
| TAG-01 | 接口 | admin 登录 | `/api/tags` 新增/编辑/删除标签 | CRUD 生效，重名给出明确提示 | 仅手工 | `TagController` |
| TAG-02 | 接口 | 已有标签与知识库 | `PUT /api/kb/{kbId}/tags` 绑定标签 | `GET` 返回绑定结果，多标签不重复 | 仅手工 | `TagController` |
| TAG-03 | 接口 | 已有文件 | `PUT /api/kb/files/{fileId}/tags` 绑定 | 文件标签 chip 展示正确 | 仅手工 | `KBFiles.vue` |
| TAG-04 | 接口 | 文件已更新过 | `GET /api/kb/files/{fileId}/versions` | 返回历史版本（版本号、时间、大小） | 仅手工 | `KBFiles.vue` 版本弹窗 |
| VER-01 | 接口 | 存在历史版本 | `POST /api/kb/files/{fileId}/rollback/{version}` | 生成新版本（不覆盖旧版本），切片/向量重建 | 仅手工 | `FileVersionService` |
| VER-02 | 异常 | 回滚不存在版本 | 同上 | 返回明确错误，不产生新版本 | 仅手工 | `TagController#rollback` |

---

## 22. 模型路由与故障转移

> 实现：`ModelRouter#resolveChain`（窗口 `model_route_group` 非空时按 `route_priority` 升序成链，否则回退 `llm_model_id`）、`RagChatService#runWithFailover`；字段 `wgh_llm_model.route_group/route_priority`、`wgh_chat_window.model_route_group`。
> **无自动化单测**（缺 `ModelRouterTest`），本组用例均为「仅手工」。
> 实测（2026-11-02）：ROUTE-01 通过；ROUTE-02 **失败**——首模型连接失败经异步 `onError` 上报，未进入 `runWithFailover` 的切链逻辑，直接向前端返回 `event:error`（详见 §25 F4，遗留）。

| 编号 | 分类 | 前置条件 | 用例 / 步骤 | 预期结果 | 覆盖类型 | 自动化落点 |
|------|------|---------|------------|---------|---------|-----------|
| ROUTE-01 | 接口 | 两个模型同 route_group，优先级 1/2；窗口选择该组 | 发起对话 | 命中优先级 1 的模型 | 仅手工 | `ChatModels.vue` / `ChatWindows.vue` |
| ROUTE-02 | 异常 | 优先级 1 模型不可用（错误 key/超时） | 发起对话 | 自动降级到优先级 2，答复成功且日志记录失败模型 | 仅手工 | `RagChatService#runWithFailover` |
| ROUTE-03 | 异常 | 链路全部失败 | 发起对话 | 返回明确错误，不无限重试 | 仅手工 | `RagChatService` |
| ROUTE-04 | 边界 | 路由组无可用模型 | 发起对话 | 回退窗口 `llmModelId`；仍无则明确报错 | 仅手工 | `ModelRouter#resolveChain` |
| ROUTE-05 | 边界 | 窗口未配置路由组 | 发起对话 | 直接使用 `llm_model_id`（行为与改造前一致） | 仅手工 | `RagChatService` |

---

## 23. 工作流 AGENT 节点

> 实现：`AgentNodeExecutor`（Plan-and-Execute 轻量版：Planner → 步骤执行 → 反思），节点类型 `AGENT`；配置 `goal`/`prompt`、`modelId`、`plannerModelId`、`maxSteps`（默认 5）、`timeoutMs`（默认 60000）、`kbIds`；计划/步骤落 `wgh_agent_plan`、`wgh_agent_step`。
> 自动化：`AgentNodeExecutorTest`（6 项，rag-workflow）。

| 编号 | 分类 | 前置条件 | 用例 / 步骤 | 预期结果 | 覆盖类型 | 自动化落点 |
|------|------|---------|------------|---------|---------|-----------|
| WAG-01 | 单元 | 配置 goal + 模型 | 执行 AGENT 节点 | 生成计划并逐步骤执行，输出文本与 stepCount | 单元 | `AgentNodeExecutorTest` |
| WAG-02 | 单元 | 无 goal/prompt 且无上游 lastOutput | 执行 | 抛「AGENT 节点缺少 goal / prompt 配置」 | 单元 | `AgentNodeExecutorTest` |
| WAG-03 | 单元 | timeoutMs 很小 | 执行 | 返回 success=false、output.status=TIMEOUT | 单元 | `AgentNodeExecutorTest` |
| WAG-04 | 单元 | 步骤执行失败 | 执行 | 记录错误并停止/降级，输出含失败步骤信息 | 单元 | `AgentNodeExecutorTest` |
| WAG-05 | 接口 | 工作流含 AGENT 节点 | `POST /api/workflows/{id}/validate` → run | 校验通过；运行产生 AGENT 步骤事件 | 仅手工 | `WorkflowEditor.vue` |
| WAG-06 | 接口 | 配置 `kbIds` | 运行 AGENT 节点 | 检索增强后生成计划（有 KB 上下文） | 仅手工 | `AgentNodeExecutor` |
| WAG-07 | 接口 | 前端编辑器 | 拖入 AGENT 节点并配置 goal/maxSteps/timeoutMs | 保存后重新打开配置不丢失 | 仅手工 | `WorkflowEditor.vue` |

---

## 24. 多用户与权限（RBAC / OIDC）

> 实现：`AuthRBACService`、`WghUser/WghRole/WghPermission/UserRole/RolePermission`、`@RequiresPermission` + `PermissionAspect`、`OidcService/OidcController`；表 `wgh_user`、`wgh_role`、`wgh_permission`、`wgh_user_role`、`wgh_role_permission`。
> 外部 token 校验：OIDC `id_token`（JWK/HMAC 验签 + iss/aud/exp/nonce/state，支持 PKCE）。
> **部分完成**：无独立角色管理界面/接口，资源级 `@RequiresPermission` 仅覆盖部分 Controller；自动化：`OidcServiceTest`（10 项，rag-common）。
> 实测（2026-11-02）：`POST /api/auth/login` 响应不含 `roles`（`GET /api/auth/me` 才有），前端 `isAdmin` 因此为 false，`requiresAdmin` 路由（`/admin/quota`）被守卫重定向到 `/kb`（详见 §25 F5）。

| 编号 | 分类 | 前置条件 | 用例 / 步骤 | 预期结果 | 覆盖类型 | 自动化落点 |
|------|------|---------|------------|---------|---------|-----------|
| RBA-01 | 单元 | OIDC 已配置 | 生成授权请求 | 返回 authorizeUrl + state + nonce + PKCE codeVerifier | 单元 | `OidcServiceTest` |
| RBA-02 | 单元 | 伪造 id_token（错误签名/iss/aud/过期） | 校验 | 分别抛出对应错误，不建立会话 | 单元 | `OidcServiceTest` |
| RBA-03 | 单元 | state 不匹配 | 回调 | 拒绝（防 CSRF） | 单元 | `OidcServiceTest` |
| RBA-04 | 接口 | OIDC 首次登录 | `/api/auth/oidc/callback` | 自动建用户并按 `default-role`（viewer）分配角色 | 仅手工 | `OidcController` / `AuthRBACService#loginByOidc` |
| RBA-05 | 接口 | 本地账号密码登录 | `POST /api/auth/login` | 返回 JWT，`GET /api/auth/me` 含 roles/permissions | 仅手工 | `AuthController` |
| RBA-06 | 接口 | 无 `model:manage` 权限的用户 | 调用 `LlmModelController` 写接口 | 403，提示缺少权限 | 仅手工 | `PermissionAspect` |
| RBA-07 | 接口 | admin 角色 | 同上 | 放行 | 仅手工 | `PermissionAspect` |
| RBA-08 | 异常 | 用户 status=0（禁用） | 用旧 JWT 访问 | 403「账号已被禁用」，不进入业务 | 仅手工 | `JwtAuthenticationFilter` |
| RBA-09 | 接口 | admin 登录 | 访问用户/角色管理 | **未实现**独立管理页（仅种子数据），标记为遗留项 | 仅手工 | —（缺失） |



---

## 25. 浏览器全量测试执行记录（2026-11-02）

> 环境：后端 `localhost:8080`、前端 `localhost:5173`、PostgreSQL 16 + pgvector（`docker postgres-with-vector`）。
> 工具：TRAE-browseruse 子代理（脚本 + 快照 + 网络/控制台采集）。
> 证据截图统一存放在 `docs/testing/evidence/`（文件名与测试时间一一对应）。
> 说明：后端从干净 HEAD 工作树启动，避免混入其他会话未提交的 auth/quota 改动。

### 25.1 流程结果

| 流程 | 结果 | 关键证据 | 备注 |
|------|------|---------|------|
| 登录与权限校验 | PASS | `page-2026-11-02T11-14-59-196Z.png`、`page-2026-11-02T11-15-53-635Z.png` | admin/admin 登录成功；未登录访问管理页被守卫拦截 |
| 知识库创建 / 上传 / 解析（HTML、JSON、EML、PPTX） | PASS | 同批 `page-2026-11-02T11-15-53-635Z.png` | 4 个新格式文件均解析成功、各生成 1 个切片；文件列表可见 |
| 检索与 RAG 问答（含来源/命中率） | PASS | `rag-full-answer-with-sources.png`、`rag-answer-with-sources.png` | 回答“7 天”，来源含 EML/HTML/JSON/PPTX；coverageRate/hitRate=1.0 |
| Prompt 模板管理（创建 / 渲染） | PASS | `templates-page.png`、`templates-saved.png`、`template-render-preview.png` | 变量渲染结果正确 |
| 标签与文档版本（绑定 / 版本列表 / 回滚） | PASS | `page-2026-11-02T11-24-01-972Z.png`、`page-2026-11-02T11-24-47-143Z.png` | 回滚经 API 验证：version 递增并生成新快照 |
| 工作流 AGENT 节点运行 | PASS（修复后） | `workflow-129-run-result.png`、`workflow-129-final-debug.png` | 修复 `GraphValidator` 缺 `AGENT` 后创建成功；SSE 运行 AGENT 节点 SUCCESS，2 步，输出“7 天” |
| 模型路由与故障转移 | **FAIL** | 后端日志 `[Failover] 使用模型 deepseek-chat (id=2)，原因: 首模型` + `event:error` | ROUTE-01 路由命中正常；ROUTE-02 故障转移未生效（F4） |
| 分享链接免登录访问 | PASS | `share-page-initial.png`、`share-page-answer.png` | 匿名访问与 SSE 问答成功；无效 token/越权错误经 curl 验证 |
| Widget 嵌入 demo | PASS（修复后） | `widget-iframe-evidence.png` | iframe `src=/share/{token}?embed=1`，内部可见问答输入、无登录表单 |
| 开放 API 调用 | PASS | `page-2026-11-02T13-20-08-204Z.png`、`page-2026-11-02T13-20-29-505Z.png` | `/me`、`/kb/search`、`/chat/stream`、`/usage` 均通过；scope 越权 403；限流 429；吊销后 401 |
| 统计与审计页面 | PASS（修复后） | `page-2026-11-02T13-19-41-175Z.png` | 配额 15/200、成本表与审计表均有数据，共 34 条 |
| 配额展示与超限提示 | PASS（部分） | 我的配额页截图（同批） | 我的配额显示 15/200、4,623/2,000,000；限额设为 1 后对话返回 `code=429` 文案；`/admin/quota` UI 被守卫拦截（F5） |

统计：已执行 12 个流程，10 PASS、1 FAIL（模型故障转移）、1 部分（配额管理页 UI 不可达，接口层已验证）。

### 25.2 发现的问题

| 编号 | 问题 | 状态 | 处理 |
|------|------|------|------|
| F1 | `widget-demo.html` 内联脚本字符串含字面量 `</script>`，HTML 提前闭合脚本，demo 完全不执行 | 已修复 | 改为 `'</scr' + 'ipt>'`，复测 iframe 注入成功 |
| F2 | `AdminStats.vue` 对 axios 返回值多做一层 `data.data` 解包，导致 TypeError、统计页全空 | 已修复 | 统一改为 `res.data`，复测数据正常渲染 |
| F3 | `GraphValidator.NODE_TYPES` 缺 `AGENT`，含 AGENT 节点的工作流保存即报“节点类型非法” | 已修复 | 加入 `AGENT`，复测创建与运行通过 |
| F4 | 模型故障转移仅覆盖同步异常：首模型连接失败经异步 `onError` 上报，未进入 `runWithFailover` 切链，直接返回 `event:error` | 未修复（遗留） | 需以 CompletableFuture/CountDownLatch 将首 token 前异步失败纳入切链；涉及核心流式链路，本轮未改动 |
| F5 | `POST /api/auth/login` 响应不含 `roles`，前端 `isAdmin=false`，`requiresAdmin` 路由（`/admin/quota`）被重定向到 `/kb` | 未修复（遗留） | 受“不改 auth/角色”约束；接口层 `/api/admin/quota` 已验证可用，建议登录后补拉 `/auth/me` 或登录响应补 roles |
| F6 | EML 原始 UTF-8 非编码 Subject 显示乱码 | 非缺陷 | 测试数据应使用 RFC2047 编码（`=?UTF-8?B?...?=`）；已用 v2 样例验证解析正确 |
| F7 | 限流/配额业务错误的 HTTP 状态为 200，实际语义码（429）放在响应体 `code` | 待优化 | 前端按 `code` 处理可用；对外开放 API 建议统一为 HTTP 429，便于第三方网关识别 |

---

## 26. 认证与会话（本地 / OIDC 双认证）

> 本章为增量补充，保留 §1 Auth-01~13、§24 RBA-01~09、§25 F4/F5 的既有编号与原文，不修改旧行。
> 代码核对后的差异说明：
> - Auth-01 的 `expiresIn=86400` 已过时；当前默认 `wgh.auth.local.token-ttl-seconds=7200`，签发时取 `max(60, ttl)`。
> - RBA-08 的“禁用后旧 JWT 返回 403”已过时；本地凭证链路在过滤器层返回 HTTP 401 + 业务码 1002，消息为“账号已禁用”。
> - RBA-09 的“无角色管理接口”已过时；`/api/admin/roles`、`/api/admin/permissions`、`/api/admin/users` 均已实现，见 §27。
> - F5 已修复：`POST /api/auth/login` 的 `data.user` 已包含 `roles` 与 `permissions`（Auth-75 回归）。
> 统一约定：过滤器层鉴权失败返回真实 HTTP 401/403；Service 层 `BusinessException` 由全局异常处理返回 HTTP 200 + 响应体 `code`（包含 429、1004、3003、500）。

### 26.1 本地账号密码登录

| 编号 | 分类 | 名称 | 前置条件 | 操作步骤 | 预期结果（HTTP / 业务码） | 覆盖类型 | 自动化落点 |
|------|------|------|---------|---------|--------------------------|---------|-----------|
| Auth-14 | 正常 | 本地管理员登录成功 | 已由初始化器创建 admin 账号 | `POST /api/auth/login`，body `{"username":"admin","password":"admin"}` | HTTP 200 / code 200；`data.token` 非空且为 HS256；`tokenType=local`；`expiresIn=7200`；`passwordMustChange=false`；`user.roles` 含 `admin`；`user.permissions` 含全部 10 个权限码 | 单元 + 接口 | `LocalLoginServiceTest`、`LocalAuthApiTest` |
| Auth-15 | 正常 | 体验账号登录成功 | 用户 `source=local`、`trial=1`、角色 `viewer`、状态启用未过期 | 同上，使用该账号用户名与密码 | HTTP 200 / code 200；`user.roles=[viewer]`；`user.permissions` 为该角色实时权限；token 内 `pv` 等于数据库 `password_version` | 单元 + 接口 | `LocalLoginServiceTest`、`LocalAuthApiTest` |
| Auth-16 | 异常 | 本地登录总开关关闭 | `wgh.auth.local.enabled=false` | 调用登录接口 | HTTP 200 / code 1005，message“本地账号登录已禁用”；不签发 token | 单元 | `LocalLoginServiceTest` |
| Auth-17 | 边界 | 用户名或密码为空 | 分别提交 `username=""`、`password=""` | 调用登录接口 | HTTP 200 / code 2001，message“用户名和密码不能为空”；不签发 token | 单元 + 接口 | `LocalLoginServiceTest` |
| Auth-18 | 异常 | 用户名不存在 | 使用不存在的用户名 | 调用登录接口 | HTTP 200 / code 1005，message“用户名或密码错误”；不泄露用户是否存在 | 单元 | `LocalLoginServiceTest` |
| Auth-19 | 异常 | 密码错误 | 正确用户名 + 错误密码 | 调用登录接口 | HTTP 200 / code 1005，message“用户名或密码错误” | 单元 | `LocalLoginServiceTest` |
| Auth-20 | 异常 | OIDC 账号走本地登录 | 用户 `source=oidc` | 使用该账号用户名与任意密码调用登录 | HTTP 200 / code 1005，message“该账号仅支持外部 OIDC 登录” | 单元 | `LocalLoginServiceTest` |
| Auth-21 | 边界 | source 为 null 不视为本地账号 | 用户 `source` 字段为空 | 调用登录接口 | HTTP 200 / code 1005，message“该账号仅支持外部 OIDC 登录”；仅显式 `local` 才允许本地登录 | 单元 | `LocalLoginServiceTest` |
| Auth-22 | 异常 | 账号已禁用 | `status=0` | 正确用户名密码登录 | HTTP 200 / code 1005，message“账号已禁用，请联系管理员” | 单元 | `LocalLoginServiceTest` |
| Auth-23 | 异常 | 账号已过期 | `expire_at` 为过去时间 | 正确用户名密码登录 | HTTP 200 / code 1005，message 以“账号已过期（有效期至”开头并含到期时间 | 单元 | `LocalLoginServiceTest` |
| Auth-24 | 异常 | 非法本地登录主体 | 用户 `source=local`、`trial=0`、角色不含 admin | 正确用户名密码登录 | HTTP 200 / code 1005，message“仅本地管理员或体验账号支持密码登录” | 单元 | `LocalLoginServiceTest` |
| Auth-25 | 安全 | 登录失败留痕 | 任一无上述拒绝场景 | 触发登录失败后查看账号审计 | 失败记录包含用户名、失败原因与时间；登录成功记录成功动作；不写入明文密码 | 单元 | `LocalLoginServiceTest`、`AccountAuditService` |

### 26.2 本地会话凭证（HS256 / pv 失效）

| 编号 | 分类 | 名称 | 前置条件 | 操作步骤 | 预期结果（HTTP / 业务码） | 覆盖类型 | 自动化落点 |
|------|------|------|---------|---------|--------------------------|---------|-----------|
| Auth-26 | 正常 | token 结构与 claims | 已成功登录 | 解码 token header 与 payload | header `alg=HS256`；payload 含 `sub`、`userId`、`roles`、`pv`、`tokenType=local`、`iss=wgh-local`、`aud=wgh-local`、`iat`、`exp`；`exp-iat` 等于生效 TTL | 单元 | `LocalTokenServiceTest` |
| Auth-27 | 边界 | TTL 下限与默认值 | 分别配置 `token-ttl-seconds=30` 与不配置 | 登录后检查 `expiresIn` 与 `exp` | 配置 30 时 `expiresIn=60`；不配置时 `expiresIn=7200` | 单元 | `LocalTokenServiceTest` |
| Auth-28 | 异常 | 未携带 Authorization | 无认证头 | `GET /api/auth/me` | HTTP 401 / code 1001，message“未授权访问” | 接口 | `LocalAuthApiTest`、`OidcAuthenticationFilter` |
| Auth-29 | 边界 | Bearer 后为空串 | header 为 `Authorization: Bearer ` | 访问受保护接口 | HTTP 401 / code 1002，message“Token已过期或无效” | 接口 | `OidcAuthenticationFilter` |
| Auth-30 | 安全 | 篡改 payload 或签名 | 修改 token payload 或签名后访问 | `GET /api/auth/me` | HTTP 401 / code 1002，message“会话凭证已失效，请重新登录” | 单元 + 接口 | `LocalTokenServiceTest` |
| Auth-31 | 异常 | 本地 token 过期 | token `exp` 已过去 | 访问受保护接口 | HTTP 401 / code 1002，message“会话凭证已失效，请重新登录” | 单元 | `LocalTokenServiceTest` |
| Auth-32 | 异常 | token 格式非法 | 非三段式字符串或 header 无法 Base64 解码 | 访问受保护接口 | HTTP 401 / code 1002，消息“Token已过期或无效”或“会话凭证已失效，请重新登录” | 单元 + 接口 | `LocalTokenService`、`OidcTokenVerifier` |
| Auth-33 | 安全 | issuer / audience 不匹配 | 用其它 issuer 或 audience 签发的 HS256 token | 访问受保护接口 | HTTP 401 / code 1002，本地验签拒绝 | 单元 | `LocalTokenServiceTest` |
| Auth-34 | 安全 | tokenType 非 local | claim `tokenType` 缺失或为其它值 | 访问受保护接口 | HTTP 401 / code 1002，本地验签拒绝 | 单元 | `LocalTokenServiceTest` |
| Auth-35 | 异常 | 口令版本 pv 失效 | 登录后改密、管理员重置或禁用，使 `password_version` 递增 | 用旧 token 访问任意受保护接口 | HTTP 401 / code 1007，message“凭证已失效，请重新登录” | 单元 + 接口 | `LocalTokenService`、`OidcAuthenticationFilter`、`LocalAuthApiTest` |
| Auth-36 | 异常 | 用户被删除 | token 中 `userId` 对应的用户不存在 | 用旧 token 访问 | HTTP 401 / code 1002，message“账号不存在，请重新登录” | 单元 | `OidcAuthenticationFilter` |
| Auth-37 | 异常 | 用户被禁用 | token 有效但用户 `status=0` | 用旧 token 访问 | HTTP 401 / code 1002，message“账号已禁用” | 单元 | `OidcAuthenticationFilter` |
| Auth-38 | 异常 | 用户已过期 | token 有效但 `expire_at` 已过去 | 用旧 token 访问 | HTTP 401 / code 1002，message“账号已过期” | 单元 | `OidcAuthenticationFilter` |
| Auth-39 | 正常 | 角色实时查库 | 已登录用户在线期间被管理员调整角色 | 不重新登录，直接发起下一次请求 | 请求放行；服务端按数据库最新角色判定，`LoginUser.roles` 更新 | 单元 | `OidcAuthenticationFilter` |
| Auth-40 | 边界 | 无角色默认 viewer | 用户未绑定任何角色 | 登录后访问只读接口 | 认证通过；`roles` 缺省为 `viewer`，权限按 viewer 解析 | 单元 | `OidcAuthenticationFilter` |
| Auth-41 | 正常 | 权限实时查库 | 管理员修改角色权限集合 | 该角色用户不重新登录，发起下一次请求 | 权限判定立即按 `wgh_role_permission` 最新结果，无需重新登录 | 单元 + 接口 | `OidcAuthenticationFilter`、§27 RBA-29/30 |
| Auth-42 | 安全 | 生产环境未配置固定密钥 | `spring.profiles.active=prod` 且 `wgh.auth.local.jwt-secret` 为空 | 启动应用 | 启动失败，抛出 IllegalStateException，提示必须配置至少 32 字节密钥（fail-closed） | 单元 | `LocalTokenServiceTest` |
| Auth-43 | 安全 | 密钥长度不足 | 配置 secret 少于 32 字节 | 启动应用 | 启动失败，抛出 IllegalStateException“长度至少 32 字节” | 单元 | `LocalTokenServiceTest` |
| Auth-44 | 边界 | 开发环境随机密钥 | 非 prod 且未配置 secret | 启动后登录，再重启后使用旧 token | 启动仅 WARN；重启后旧 token HTTP 401 / code 1002，需重新登录 | 单元 + 接口 | `LocalTokenServiceTest` |
| Auth-45 | 边界 | 登出为客户端无状态 | 已登录 | `POST /api/auth/logout`，之后重放旧 token | 登出 HTTP 200 / code 200；服务端不维护吊销列表；旧 token 在过期前仍可用（见 §31 L-05） | 接口 | `AuthController` |
| Auth-46 | 正常 | 当前用户信息 | 已登录 | `GET /api/auth/me` | HTTP 200 / code 200；返回 id、username、roles、permissions、passwordMustChange；无 token 时为 HTTP 401 / code 1001 | 接口 | `LocalAuthApiTest` |

### 26.3 首登强制改密与口令变更

| 编号 | 分类 | 名称 | 前置条件 | 操作步骤 | 预期结果（HTTP / 业务码） | 覆盖类型 | 自动化落点 |
|------|------|------|---------|---------|--------------------------|---------|-----------|
| Auth-47 | 异常 | 首登未改密拦截写请求 | 用户 `password_must_change=1`，已登录 | 发起任意非 GET/HEAD/OPTIONS 请求（如新建知识库） | HTTP 403 / code 1006，message“请先修改密码”；不进入业务 | 单元 + 接口 | `OidcAuthenticationFilter`、`LocalAuthApiTest` |
| Auth-48 | 边界 | 首登期间允许只读 | 同上 | `GET /api/kb` 等只读接口 | HTTP 200 / code 200，正常返回 | 单元 | `OidcAuthenticationFilter` |
| Auth-49 | 边界 | 首登期间允许改密与登出 | 同上 | `POST /api/auth/password`、`POST /api/auth/logout` | 不被 1006 拦截；改密按规则返回，登出返回 HTTP 200 / code 200 | 单元 | `OidcAuthenticationFilter` |
| Auth-50 | 正常 | 改密成功并使旧 token 失效 | 已登录，旧密码正确，新密码满足强度且不同于旧密码 | `POST /api/auth/password` → 用旧 token 访问 → 重新登录 | 改密 HTTP 200 / code 200；`password_version+1`、`password_must_change=0`；旧 token 下一次请求 HTTP 401 / code 1007；新登录 token 可用 | 单元 + 接口 | `LocalPasswordService`、`LocalAuthApiTest` |
| Auth-51 | 异常 | 旧密码不正确 | 已登录，提交错误旧密码 | 调用改密接口 | HTTP 200 / code 2001，message“旧密码不正确” | 单元 | `LocalPasswordServiceTest` |
| Auth-52 | 异常 | 新旧密码相同 | 新密码与当前密码一致 | 调用改密接口 | HTTP 200 / code 2001，message“新密码不能与当前密码相同” | 单元 | `LocalPasswordServiceTest` |
| Auth-53 | 异常 | 未登录调用改密 | 无 token | `POST /api/auth/password` | HTTP 401 / code 1001，不进业务 | 接口 | `OidcAuthenticationFilter` |
| Auth-54 | 边界 | 密码强度校验 | 分别提交空、7 位、129 位、纯字母、纯数字 | 调用改密接口 | HTTP 200 / code 2001；消息分别含“密码不能为空”“密码长度至少 8 位”“密码长度不能超过 128 位”“密码必须包含字母”“密码必须包含数字” | 单元 | `PasswordPolicy`、`LocalPasswordServiceTest` |
| Auth-55 | 异常 | OIDC 账号不支持本地改密 | 用户 `source=oidc` 或 `password_hash` 为空 | 已登录后调用改密接口 | HTTP 200 / code 3003，message“外部 OIDC 账号不支持本地改密” | 单元 | `LocalPasswordServiceTest` |
| Auth-56 | 正常 | 管理员重置体验账号口令 | admin 登录，目标为 trial 账号 | `PUT /api/admin/users/{username}/password` 提交新密码 | HTTP 200 / code 200；`password_version+1`、`password_must_change=1`；目标旧 token HTTP 401 / code 1007；目标下次登录 `passwordMustChange=true` | 接口 | `AdminUserController`、`LocalPasswordService` |
| Auth-57 | 正常 | 管理员禁用体验账号 | admin 登录，目标为启用中的 trial 账号 | `DELETE /api/admin/users/{username}` | HTTP 200 / code 200；`status=0`、`password_version+1`；目标旧 token HTTP 401 / code 1002，message“账号已禁用” | 接口 | `AdminUserController` |
| Auth-58 | 异常 | 重置不存在用户口令 | admin 登录 | 对不存在用户名调用重置口令 | HTTP 200 / code 3001，message“用户不存在” | 接口 | `LocalPasswordService` |
| Auth-59 | 异常 | 重置非体验账号口令 | 目标 `source=local` 但 `trial=0` | 调用重置口令 | HTTP 200 / code 3003，message“仅体验账号允许重置口令” | 接口 | `LocalPasswordService` |
| Auth-60 | 异常 | 重置 OIDC 用户口令 | 目标 `source=oidc` | 调用重置口令 | HTTP 200 / code 3003，message“外部 OIDC 用户不允许重置本地口令” | 接口 | `LocalPasswordService` |
| Auth-61 | 边界 | 重置口令强度校验 | 提交不满足策略的新密码 | 调用重置口令 | HTTP 200 / code 2001，消息与改密强度校验一致 | 接口 | `PasswordPolicy` |

### 26.4 外部 OIDC token（JWKS 公钥验签）

| 编号 | 分类 | 名称 | 前置条件 | 操作步骤 | 预期结果（HTTP / 业务码） | 覆盖类型 | 自动化落点 |
|------|------|------|---------|---------|--------------------------|---------|-----------|
| Auth-62 | 正常 | 合法 RS256 token 访问 | IdP 已配置，JWKS 可访问，token 的 kid 命中公钥，iss/aud/exp 正确 | 携带 `Authorization: Bearer <RS256 token>` 访问受保护接口 | HTTP 200 / code 200；按 claim 构建 LoginUser；`username` 取 `username-claim`（默认 sub），角色按映射解析 | 单元 + 接口 | `OidcResourceServerTest`、`OidcTokenVerifier` |
| Auth-63 | 安全 | 外部 HS256 token 被拒 | 用任意密钥构造 header `alg=HS256` 的外部 token | 访问受保护接口 | HTTP 401 / code 1002；算法白名单拒绝 HS*，不落入本地验签分支 | 单元 | `OidcTokenVerifier` |
| Auth-64 | 安全 | alg=none 被拒 | 构造 `alg=none` token | 访问受保护接口 | HTTP 401 / code 1002；白名单拒绝 none | 单元 | `OidcTokenVerifier` |
| Auth-65 | 异常 | 未知 kid | token header 的 kid 不在 JWKS | 访问受保护接口 | HTTP 401 / code 1002，message“Token已过期或无效”；触发限频 JWKS 刷新（最小间隔 30 秒） | 单元 | `JwksProvider`、`OidcTokenVerifier` |
| Auth-66 | 异常 | JWKS 不可达且无缓存 | 启动后首次请求前断开 JWKS，缓存为空 | 使用合法结构 token 访问 | HTTP 401 / code 1002；不建立会话（fail-closed） | 单元 | `JwksProvider` |
| Auth-67 | 边界 | JWKS 不可达但已有缓存 | 先前已成功拉取 JWKS，随后断开网络 | 使用 kid 仍在缓存中的 token 访问 | HTTP 200 / code 200；保留旧缓存继续验签，仅记录 WARN | 单元 | `JwksProvider` |
| Auth-68 | 安全 | issuer 不匹配 | token `iss` 与 `wgh.auth.oidc.issuer` 不同 | 访问受保护接口 | HTTP 401 / code 1002 | 单元 | `OidcTokenVerifier` |
| Auth-69 | 安全 | audience 不匹配 | token `aud` 不包含 `wgh.auth.oidc.audience` | 访问受保护接口 | HTTP 401 / code 1002 | 单元 | `OidcTokenVerifier` |
| Auth-70 | 安全 | issuer 或 audience 未配置 | `wgh.auth.oidc.issuer` 或 `audience` 为空 | 访问受保护接口 | fail-closed：HTTP 401 / code 1002，不跳过关键校验 | 单元 | `OidcTokenVerifier` |
| Auth-71 | 异常 | token 过期或 nbf 未生效 | `exp` 已过去或 `nbf` 在未来且超出 30 秒时钟偏差 | 访问受保护接口 | HTTP 401 / code 1002 | 单元 | `OidcTokenVerifier` |
| Auth-72 | 正常 | 角色 claim 映射到数据库角色 | `role-mapping` 配置 `wgh-editor → editor`，token roles 含 `wgh-editor` | 访问受保护接口 | 认证通过；`roles=[editor]`，权限按 editor 实时解析 | 单元 | `OidcAuthenticationFilter` |
| Auth-73 | 边界 | 角色无法映射 | token 角色 claim 为空或均不存在于 `wgh_role` | 访问只读接口 | 认证通过；使用 `default-role`（默认 viewer），权限按 viewer 解析 | 单元 | `OidcAuthenticationFilter` |
| Auth-74 | 边界 | OIDC 链路不校版本地用户状态 | 本地库存在同名用户且 `status=0`，但外部 token 合法 | 携带外部 token 访问 | 仍认证通过（OIDC 分支不查本地状态）；该行为列为限制 §31 L-11 | 单元 | `OidcAuthenticationFilter` |
| Auth-75 | 回归 | 登录响应包含角色与权限（F5） | admin 登录 | 检查 `POST /api/auth/login` 响应 | HTTP 200 / code 200；`data.user.roles`、`data.user.permissions` 有值，前端 `isAdmin` 无需再补拉 `/auth/me` 即可判定 | 接口 | `LocalAuthApiTest` |

---

## 27. 角色与权限管理（管理端）

> 实现：`AdminRoleController`（`/api/admin/roles`、`/api/admin/permissions`）、`AdminUserController`（`/api/admin/users`）、`QuotaAdminController`（`/api/admin/quota`）。
> 权限校验：全部接口先经 `QuotaAdminGuard.requireAdmin()`；非 admin 返回 HTTP 200 / code 1004（Service 层业务异常），不是 HTTP 403。
> 生效方式：角色与权限写库后立即生效，认证过滤器每次请求实时查询 `wgh_user_role` 与 `wgh_role_permission`；已登录用户无需重新登录。

### 27.1 角色与权限接口

| 编号 | 分类 | 名称 | 前置条件 | 操作步骤 | 预期结果（HTTP / 业务码） | 覆盖类型 | 自动化落点 |
|------|------|------|---------|---------|--------------------------|---------|-----------|
| RBA-10 | 正常 | 角色列表 | admin 登录，存在内置 admin 与自定义角色 | `GET /api/admin/roles` | HTTP 200 / code 200；每行含 roleCode、roleName、description、builtIn、userCount、permissionCount、permCodes；admin 行 `builtIn=true` | 接口 | `AdminRoleController` |
| RBA-11 | 安全 | 非管理员访问角色管理 | 普通 viewer 登录 | 调用任意 `/api/admin/roles` 或 `/api/admin/permissions` | HTTP 200 / code 1004，message“仅管理员可操作配额配置”；不返回角色数据 | 接口 | `QuotaAdminGuard` |
| RBA-12 | 正常 | 创建角色 | admin 登录 | `POST /api/admin/roles`，合法 roleCode、roleName、description | HTTP 200 / code 200；返回新角色行；列表中可见；`permissionCount=0` | 接口 | `AdminRoleController` |
| RBA-13 | 边界 | role_code 非法 | admin 登录 | 提交空、1 位、含大写、含连字符、33 位的 roleCode | HTTP 200 / code 2001，message“role_code 需为 2-32 位，小写字母开头，仅含小写字母、数字、下划线” | 接口 | `AdminRoleController` |
| RBA-14 | 异常 | 角色编码重复 | 已存在同 roleCode | 再次创建 | HTTP 409 / code 3002，message“资源已存在（唯一键冲突）” | 接口 | `GlobalExceptionHandler` |
| RBA-15 | 边界 | 角色名称非法 | admin 登录 | 提交空名称或超过 64 字符 | HTTP 200 / code 2001，消息“角色名称不能为空”或“角色名称不能超过 64 个字符” | 接口 | `AdminRoleController` |
| RBA-16 | 边界 | 角色描述超长 | admin 登录 | 提交超过 500 字符描述 | HTTP 200 / code 2001，message“角色描述不能超过 500 个字符” | 接口 | `AdminRoleController` |
| RBA-17 | 正常 | 修改自定义角色 | 存在非内置角色 | `PUT /api/admin/roles/{roleCode}` 修改名称与描述 | HTTP 200 / code 200；返回更新后的角色行 | 接口 | `AdminRoleController` |
| RBA-18 | 安全 | 内置 admin 角色不可修改 | admin 登录 | `PUT /api/admin/roles/admin` | HTTP 200 / code 3003，message“内置 admin 角色不可修改” | 接口 | `AdminRoleController` |
| RBA-19 | 异常 | 修改不存在角色 | admin 登录 | `PUT /api/admin/roles/not_exist` | HTTP 200 / code 3001，message“角色不存在: not_exist” | 接口 | `AdminRoleController` |
| RBA-20 | 正常 | 删除无绑定自定义角色 | 角色无用户绑定、无权限绑定 | `DELETE /api/admin/roles/{roleCode}` | HTTP 200 / code 200；角色逻辑删除，角色权限关系清理 | 接口 | `AdminRoleController` |
| RBA-21 | 安全 | 内置 admin 角色不可删除 | admin 登录 | `DELETE /api/admin/roles/admin` | HTTP 200 / code 3003，message“内置 admin 角色不可删除” | 接口 | `AdminRoleController` |
| RBA-22 | 异常 | 删除仍有用户绑定的角色 | 角色被至少 1 个用户绑定 | `DELETE /api/admin/roles/{roleCode}` | HTTP 409 / code 3003，message 含“角色仍被 N 个用户绑定，请先调整这些用户的角色”（控制器显式映射 409，状态码以实测为准，待确认） | 接口 | `AdminRoleController` |
| RBA-23 | 异常 | 删除不存在角色 | admin 登录 | `DELETE /api/admin/roles/not_exist` | HTTP 200 / code 3001，message“角色不存在: not_exist” | 接口 | `AdminRoleController` |
| RBA-24 | 正常 | 权限项列表 | admin 登录 | `GET /api/admin/permissions` | HTTP 200 / code 200；按模块分组（KNOWLEDGE_BASE、AGENT、WORKFLOW、TOOL、PROMPT、MODEL、AUDIT），共 10 个权限码：kb:read、kb:write、kb:delete、agent:use、workflow:use、workflow:edit、tool:use、prompt:edit、model:manage、audit:view | 接口 | `AdminRoleController` |
| RBA-25 | 正常 | 整体覆盖角色权限 | admin 登录，存在自定义角色 | `PUT /api/admin/roles/{roleCode}/permissions`，提交权限子集 | HTTP 200 / code 200；旧绑定被清空后重建；`permissionCount` 等于提交集合大小；`permCodes` 正确 | 接口 | `AdminRoleController` |
| RBA-26 | 异常 | 分配未知权限码 | admin 登录 | 提交包含 `not:exist` 的权限码 | HTTP 200 / code 2001，message“未知权限码: not:exist”；不产生部分写入 | 接口 | `AdminRoleController` |
| RBA-27 | 边界 | admin 角色全量权限幂等 | admin 角色当前拥有全部 10 个权限 | 再次提交全部 10 个权限码 | HTTP 200 / code 200；幂等返回，不重复写入，`permissionCount=10` | 接口 | `AdminRoleController` |
| RBA-28 | 安全 | admin 角色不可削减权限 | admin 角色当前全量权限 | 提交缺少任一权限码 | HTTP 200 / code 3003，message“内置 admin 角色必须保留全部权限，不允许削减” | 接口 | `AdminRoleController` |
| RBA-29 | 正常 | 权限即时生效 | 用户 A 已登录，角色 roleA 仅有 `kb:read` | admin 为 roleA 增加 `model:manage`，用户 A 不重新登录直接调用模型管理写接口 | 下一次请求即放行（HTTP 200 / code 200）；无需重新登录 | 接口 | `OidcAuthenticationFilter` |
| RBA-30 | 正常 | 权限回收即时生效 | 用户 A 已登录且 roleA 有 `model:manage` | admin 移除该权限，用户 A 不重新登录直接调用模型管理写接口 | 下一次请求被拒：HTTP 200 / code 1004（`@RequiresPermission` 业务异常），不是 HTTP 403 | 接口 | `PermissionAspect` |

### 27.2 体验账号管理

| 编号 | 分类 | 名称 | 前置条件 | 操作步骤 | 预期结果（HTTP / 业务码） | 覆盖类型 | 自动化落点 |
|------|------|------|---------|---------|--------------------------|---------|-----------|
| RBA-31 | 正常 | 用户列表与筛选 | admin 登录，存在多个体验账号 | `GET /api/admin/users?page=&size=&username=&source=&role=&enabled=` | HTTP 200 / code 200；返回 PageResult（total/page/size/records）；按用户名包含、来源、角色、启用状态过滤；按 username 排序 | 接口 | `AdminUserController` |
| RBA-32 | 正常 | 创建体验账号 | admin 登录 | `POST /api/admin/users`，合法用户名、密码、role、enabled、expireAt、备注 | HTTP 200 / code 200；新用户 `source=local`、`trial=true`、角色为指定非 admin 角色（缺省 viewer）；返回行含 roles/enabled/quota 字段 | 接口 | `AdminUserController` |
| RBA-33 | 安全 | 禁止创建 admin 角色账号 | admin 登录 | `POST /api/admin/users`，role=`admin` | HTTP 200 / code 3003，message“不允许创建或提升为管理员角色” | 接口 | `AdminUserController` |
| RBA-34 | 边界 | 用户名格式非法 | admin 登录 | 提交 2 位、33 位、含中文或空格的用户名 | HTTP 200 / code 2001，message“用户名需为 3-32 位字母、数字、下划线、点或短横线” | 接口 | `AdminUserController` |
| RBA-35 | 异常 | 用户名重复 | 已存在同名用户 | 再次创建 | HTTP 409 / code 3002 | 接口 | `GlobalExceptionHandler` |
| RBA-36 | 正常 | 修改体验账号角色与启用状态 | admin 登录，目标 trial 账号 | `PUT /api/admin/users/{username}` 调整 role、enabled、备注、expireAt | HTTP 200 / code 200；角色关系替换；禁用时 `password_version+1`，旧 token 立即失效；启用恢复 status=1 | 接口 | `AdminUserController` |
| RBA-37 | 安全 | 体验账号接口不可修改管理员 | admin 登录 | 对 admin 账号调用修改接口 | HTTP 200 / code 3003，message“管理员账号不允许通过体验账号接口修改” | 接口 | `AdminUserController` |
| RBA-38 | 异常 | 体验账号接口不可管理 OIDC 用户 | 目标 `source=oidc` | 调用修改、重置口令或禁用接口 | HTTP 200 / code 3003，message“外部 OIDC 用户不允许通过该接口管理” | 接口 | `AdminUserController` |
| RBA-39 | 异常 | 仅 trial 账号可管理 | 目标 `source=local`、`trial=0` | 调用修改接口 | HTTP 200 / code 3003，message“仅体验账号允许通过该接口修改” | 接口 | `AdminUserController` |
| RBA-40 | 正常 | 重置体验账号口令 | admin 登录，目标 trial 账号 | `PUT /api/admin/users/{username}/password` | HTTP 200 / code 200；`password_version+1`、`password_must_change=1`；详见 Auth-56 | 接口 | `AdminUserController` |
| RBA-41 | 正常 | 禁用体验账号 | admin 登录，目标启用中 trial 账号 | `DELETE /api/admin/users/{username}` | HTTP 200 / code 200；返回行 `enabled=false`；`status=0`、`password_version+1`；目标旧 token 失效（Auth-57） | 接口 | `AdminUserController` |
| RBA-42 | 异常 | 操作不存在用户 | admin 登录 | 对不存在用户名调用修改/重置/禁用 | HTTP 200 / code 3001，message“用户不存在” | 接口 | `AdminUserController` |
| RBA-43 | 正常 | 用户行携带配额信息 | 目标用户已有自定义配额 | `GET /api/admin/users` | HTTP 200 / code 200；行内 `period`、`chatLimit`、`tokenLimit`、`quotaEnabled`、`customQuota=true`；无自定义时为 `customQuota=false` 且回退全局默认 | 接口 | `AdminUserController` |
| RBA-44 | 正常 | 创建用户同时写入配额 | admin 登录 | `POST /api/admin/users`，附带 chatLimit/tokenLimit/period/quotaEnabled | HTTP 200 / code 200；`wgh_user_quota_config` 写入该用户配置，后续生效配额为 CUSTOM | 接口 | `AdminUserController`、`QuotaService` |

### 27.3 配额管理接口（补充 §14 的 HTTP 层用例）

| 编号 | 分类 | 名称 | 前置条件 | 操作步骤 | 预期结果（HTTP / 业务码） | 覆盖类型 | 自动化落点 |
|------|------|------|---------|---------|--------------------------|---------|-----------|
| QTA-23 | 正常 | 管理端配额列表 | admin 登录 | `GET /api/admin/quota/users` | HTTP 200 / code 200；返回用户与生效配额（来源、周期、上限、已用） | 接口 | `QuotaAdminController` |
| QTA-24 | 正常 | 查询单个用户配额 | admin 登录，目标用户存在 | `GET /api/admin/quota/users/{username}` | HTTP 200 / code 200；返回自定义配置或 DEFAULT 来源的生效值 | 接口 | `QuotaAdminController` |
| QTA-25 | 正常 | 保存用户配额 | admin 登录 | `PUT /api/admin/quota/users/{username}`，提交 chatLimit、tokenLimit、period、enabled | HTTP 200 / code 200；upsert 不新增重复行；下一次对话按新上限校验 | 接口 | `QuotaAdminController` |
| QTA-26 | 正常 | 恢复默认配额 | 用户已有自定义配置 | `DELETE /api/admin/quota/users/{username}` | HTTP 200 / code 200；配置逻辑删除，生效来源回退 DEFAULT（200 条/日、200 万 token/日） | 接口 | `QuotaAdminController` |
| QTA-27 | 正常 | 重置当期用量 | 用户当期已有对话与 token 计数 | `POST /api/admin/quota/users/{username}/reset-usage` | HTTP 200 / code 200；当前周期键计数清零；配置不变；下一次对话重新计数 | 接口 | `QuotaAdminController` |
| QTA-28 | 安全 | 非管理员操作配额 | 普通用户登录 | 调用任意 `/api/admin/quota/**` | HTTP 200 / code 1004，message“仅管理员可操作配额配置” | 接口 | `QuotaAdminGuard` |
| QTA-29 | 正常 | 我的配额 | 已登录用户 | `GET /api/quota/my` | HTTP 200 / code 200；返回来源、周期、上限、已用、剩余；超限对话返回 HTTP 200 / code 429（`QUOTA_EXCEEDED`） | 接口 | `QuotaUserController` |

---

## 28. 对象存储（local / OSS）

> 实现：`rag-storage`，配置前缀 `wgh.rag.storage`，`type=local`（默认）或 `oss`；`LocalFileStorageService` 与 `AliyunOssFileStorageService` 通过 `@ConditionalOnProperty` 互斥装配。
> 错误语义：存储层使用 `BusinessException(String)`，业务码为 `500`；经 REST 接口暴露时为 HTTP 200 + code 500（不是 4xx）。启动期配置问题只 WARN，不阻断启动。

| 编号 | 分类 | 名称 | 前置条件 | 操作步骤 | 预期结果（HTTP / 业务码） | 覆盖类型 | 自动化落点 |
|------|------|------|---------|---------|--------------------------|---------|-----------|
| STO-01 | 正常 | 默认装配本地存储 | 未配置 `wgh.rag.storage.type` | 启动应用，查看 Bean 与日志 | `LocalFileStorageService` 生效；日志输出绝对化后的 `./uploads` 根目录；OSS Bean 不存在 | 单元 | `LocalFileStorageServiceTest` |
| STO-02 | 正常 | 新对象 key 规则 | 新建知识库 kbId=1，上传原始文件名 `a.pdf`，文件 UUID 已生成 | 调用存储写入 | key 为 `kb/1/{uuid}/a.pdf`；同 KB 不同上传使用不同 UUID 目录，避免同名覆盖 | 单元 | `StorageKeysTest` |
| STO-03 | 边界 | 文件名去除目录 | 原始文件名 `../../etc/passwd` 或 `C:\\tmp\\a.txt` | 计算 key | key 仅保留 basename，目录部分被丢弃；不含 `..` 或路径分隔符 | 单元 | `StorageKeysTest` |
| STO-04 | 边界 | 文件名清洗 | 文件名含空白、`\\`、`/`、`:`、`*`、`?`、双引号、`<`、`>`、竖线，或为 `.`/`..`/空 | 计算 key | 非法字符替换为 `_`；空或 `.`/`..` 归一为 `file` | 单元 | `StorageKeysTest` |
| STO-05 | 正常 | 本地写入成功 | 根目录可写，文件大小在限内 | `store(key, inputStream, size, contentType)` | 返回 `StoredObject`，key 原样、size 为实际字节数、contentType 保留；目标文件存在 | 单元 | `LocalFileStorageServiceTest` |
| STO-06 | 正常 | 本地 URL 解析 | `url-prefix=/uploads` | `resolveUrl("kb/1/u/a.pdf")` | 返回 `/uploads/kb/1/u/a.pdf`；前缀尾部 `/` 被去除；key 中 `\\` 归一为 `/`、首 `/` 去除 | 单元 | `LocalFileStorageServiceTest` |
| STO-07 | 边界 | 原子写入 | 写入过程中观察目标路径 | 调用 `store` | 先写 `.upload-` 临时文件，再 ATOMIC_MOVE 到目标；读取方不会看到半成品；不支持原子移动时降级普通 move | 单元 | `LocalFileStorageServiceTest` |
| STO-08 | 异常 | 声明大小超过上限 | `max-file-size-mb=50`，size 参数 > 50MB | 调用 `store` | 抛出业务异常，消息“文件过大（x MB），超过存储上限 50 MB”，code=500；不创建目标文件 | 单元 | `LocalFileStorageServiceTest` |
| STO-09 | 异常 | 实际内容为空 | 传入 0 字节流 | 调用 `store` | 抛出业务异常“文件内容为空，无法存储”，code=500；临时文件被清理 | 单元 | `LocalFileStorageServiceTest` |
| STO-10 | 安全 | 路径穿越被拒 | key 为 `../outside.txt` 或绝对路径不在根目录下 | 调用任意存储操作 | 抛出业务异常“非法的存储 key（路径穿越）: ...”，code=500；不读写根目录外文件 | 单元 | `LocalFileStorageServiceTest` |
| STO-11 | 兼容 | 历史相对路径可读 | 旧数据 `file_path=./uploads/1/a.pdf`，文件仍位于当前根目录下 | `openStream("./uploads/1/a.pdf")` | 读取成功；历史相对工作目录路径按绝对化后校验根目录 | 单元 | `LocalFileStorageServiceTest` |
| STO-12 | 兼容 | 历史绝对路径可读 | 旧数据为根目录下绝对路径 | `openStream(absolutePath)` | 读取成功；路径必须仍在根目录下，否则按 STO-10 拒绝 | 单元 | `LocalFileStorageServiceTest` |
| STO-13 | 异常 | 读取不存在对象 | key 无对应文件 | `openStream(key)` | 抛出业务异常“存储对象不存在: key”，code=500；不返回 null | 单元 | `LocalFileStorageServiceTest` |
| STO-14 | 边界 | 删除幂等 | 对象存在或不存在 | `delete(key)` 连续调用 | 存在时删除成功；不存在时 `deleteIfExists` 不报错；无返回值 | 单元 | `LocalFileStorageServiceTest` |
| STO-15 | 边界 | 根目录创建失败不阻断启动 | base-dir 指向不可创建路径 | 启动应用 | 启动继续，日志 WARN“本地存储根目录创建失败”；首次写入时抛出明确业务异常 | 单元 | `LocalFileStorageServiceTest` |
| STO-16 | 正常 | 切换 OSS 装配 | `wgh.rag.storage.type=oss` | 启动应用 | `AliyunOssFileStorageService` 生效；本地实现不装配；配置完整时日志输出 bucket/endpoint/keyPrefix | 单元 | `AliyunOssFileStorageServiceTest` |
| STO-17 | 边界 | OSS 配置缺失仍可启动 | `type=oss`，endpoint/bucket/accessKeyId/accessKeySecret 缺失 | 启动应用 | 启动成功；WARN 列出缺失字段并提示补齐 `wgh.rag.storage.oss.*` | 单元 | `AliyunOssFileStorageServiceTest` |
| STO-18 | 异常 | OSS 配置缺失时调用 | 同上 | 调用 store/openStream/delete/resolveUrl | 抛出业务异常“OSS 存储未配置完整，缺少: ...（wgh.rag.storage.oss.*）”，code=500 | 单元 | `AliyunOssFileStorageServiceTest` |
| STO-19 | 正常 | OSS key 前缀归一化 | `key-prefix=wgh/rag/` 或 `\\wgh\\rag` | 调用 store 或 resolveUrl | 对象 key 前缀统一为 `wgh/rag/`；反斜杠转 `/`；首部 `/` 去除；已有尾 `/` 不重复添加 | 单元 | `AliyunOssFileStorageServiceTest` |
| STO-20 | 正常 | 内外 endpoint 分工 | 同时配置 `endpoint` 公网与 `internal-endpoint` 内网 | 上传/读取与生成 URL | 服务端 put/get/delete/exists 使用内网 endpoint；签名/公网 URL 使用公网 endpoint | 单元 | `AliyunOssFileStorageServiceTest` |
| STO-21 | 正常 | public-read 直链 | `public-read=true`，endpoint 含 scheme | `resolveUrl(key)` | 返回 `scheme://{bucket}.{host}/{objectKey}`；不生成签名 | 单元 | `AliyunOssFileStorageServiceTest` |
| STO-22 | 正常 | 私有桶签名 URL | `public-read=false`，`signed-url-expire-minutes=0` | `resolveUrl(key)` | 返回签名 URL；有效期取 `max(1, 配置值)` 分钟，即至少 1 分钟 | 单元 | `AliyunOssFileStorageServiceTest` |
| STO-23 | 异常 | OSS 重试耗尽 | `max-retries=3`，客户端每次抛异常 | 调用 store | 共尝试 3 次；每次 WARN；全部失败后抛“OSS 上传失败: ...”，code=500 | 单元 | `AliyunOssFileStorageServiceTest` |
| STO-24 | 边界 | 上传失败清理 | OSS 上传失败 | 调用 store | 临时 spool 文件被删除；尝试静默删除已写入对象；若清理也失败仅 WARN，可能留下孤儿对象（见 §31 L-03） | 单元 | `AliyunOssFileStorageServiceTest` |
| STO-25 | 异常 | OSS 删除失败 | OSS 不可达或权限不足 | 调用 delete | 重试耗尽后抛出“OSS 删除失败: ...”，code=500；调用方若已删库记录则对象可能成为孤儿（见 §31 L-03） | 单元 | `AliyunOssFileStorageServiceTest` |
| STO-26 | 边界 | local 与 OSS 切换不迁移 | 先以 local 上传对象，再切换 `type=oss` 启动 | 读取旧对象 | 旧对象仍在本地磁盘，不会自动上传到 OSS；通过 OSS 存储读取返回“OSS 读取失败”或对象不存在；需人工迁移（见 §31 L-02） | 仅手工 | —（未实现迁移） |

---

## 29. 可观测性（Metrics / Request-Id / Actuator）

> 实现：`RagMetrics`（Micrometer，指标前缀 `wgh.`）、`RequestIdFilter`（header `X-Request-Id`，MDC key `requestId`）、Spring Boot Actuator。
> 端口：业务 `server.port=8080`；管理 `management.server.port=${WGH_MANAGEMENT_PORT:9090}`、`address=${WGH_MANAGEMENT_ADDRESS:0.0.0.0}`；业务端口不暴露 `/actuator`。

| 编号 | 分类 | 名称 | 前置条件 | 操作步骤 | 预期结果（HTTP / 业务码） | 覆盖类型 | 自动化落点 |
|------|------|------|---------|---------|--------------------------|---------|-----------|
| OBS-01 | 正常 | 管理端口健康检查 | 应用启动，数据库可用 | `GET http://localhost:9090/actuator/health` | HTTP 200；JSON `status=UP`；`show-details=always` 时 components 含 db，db.status=UP | 接口 | `RequestIdFilterTest`、Actuator 配置 |
| OBS-02 | 正常 | Prometheus 抓取 | 应用启动 | `GET http://localhost:9090/actuator/prometheus` | HTTP 200，`Content-Type` 为 Prometheus 文本；包含 `wgh_chat_requests_total`、`wgh_chat_duration_seconds` 等指标；所有指标带公共 `application` 标签（值为 `spring.application.name`） | 接口 | `RagMetrics`、`micrometer-registry-prometheus` |
| OBS-03 | 安全 | 业务端口不暴露 Actuator | 应用启动 | `GET http://localhost:8080/actuator/health` | HTTP 404；管理端点未映射到业务端口；仅 9090 可访问 | 接口 | `application.yml` management.server.port |
| OBS-04 | 正常 | 对话指标 | 完成一次 RAG 对话 | 抓取 `/actuator/prometheus` | `wgh_chat_requests_total{route="rag",share="false"}` 递增；`wgh_chat_duration_seconds` 直方图有样本；`wgh_chat_first_token_seconds` 有样本；错误时 `wgh_chat_errors_total{stage="..."}` 递增 | 单元 + 接口 | `RagMetricsTest` |
| OBS-05 | 正常 | 检索指标 | 完成一次检索 | 抓取指标 | `wgh_retrieval_duration_seconds` 按 hybrid/rerank 维度有样本；`wgh_retrieval_hits_total` 按 hit=true 或 false 递增；检索异常时 `wgh_retrieval_errors_total` 递增 | 单元 | `RagMetricsTest` |
| OBS-06 | 正常 | LLM 指标 | 完成一次 LLM 调用，返回 token 用量 | 抓取指标 | `wgh_llm_calls_total` 按 provider 递增；`wgh_llm_tokens_total` 按 type=input 或 output 仅正数累加；`wgh_llm_cost_total` 仅在 cost>0 时累加 | 单元 | `RagMetricsTest` |
| OBS-07 | 正常 | 配额与限流指标 | 触发一次配额超限与一次限流拒绝 | 抓取指标 | `wgh_quota_exceeded_total` 按 type=chat 或 token 递增；`wgh_rate_limit_rejected_total` 按 dimension（share-token、api-key 等）递增 | 单元 | `RagMetricsTest` |
| OBS-08 | 正常 | 工作流指标 | 运行工作流成功、失败、暂停 | 抓取指标 | `wgh_workflow_runs_total` 按 status=completed、failed、paused 递增；节点失败 `wgh_workflow_node_failures_total` 按 nodeType（LLM、TOOL、RETRIEVAL、AGENT 等）递增 | 单元 | `RagMetricsTest` |
| OBS-09 | 边界 | 指标标签低基数 | 产生多次不同用户/窗口/会话的请求 | 检查 Prometheus 标签 | 标签仅限 route/share/stage/provider/type/dimension/status/nodeType 等有限枚举；不得出现 userId、windowId、sessionId 等高基数标签 | 代码审查 | `RagMetrics` 约定 |
| OBS-10 | 正常 | 透传合法 Request-Id | 客户端携带 `X-Request-Id: abcdef12-3456` | 发起任意 API 请求 | 响应头原样返回该值；日志行包含 `[rid=abcdef12-3456]`；MDC key `requestId` 在请求期间可见 | 单元 | `RequestIdFilterTest` |
| OBS-11 | 正常 | 缺失 Request-Id 自动生成 | 客户端不携带该头 | 发起请求 | 响应头 `X-Request-Id` 有值且为 UUID；日志使用同一值；不返回空 | 单元 | `RequestIdFilterTest` |
| OBS-12 | 安全 | 非法 Request-Id 被替换 | 携带长度 7、长度 65、含空格、含 CRLF 或非 `[A-Za-z0-9._-]` 字符的值 | 发起请求 | 不直接采用非法值；生成新的 UUID；响应头与日志使用新值，避免日志注入 | 单元 | `RequestIdFilterTest` |
| OBS-13 | 边界 | 异步与错误分发沿用同一 ID | 请求触发异步处理或错误分发 | 检查日志 | ASYNC/ERROR dispatch 仍使用请求 attribute 中的同一 requestId，不重复生成 | 单元 | `RequestIdFilterTest` |
| OBS-14 | 边界 | MDC 清理 | 请求结束 | 同一线程处理下一个请求 | 上一个请求的 `requestId` 不残留；filter finally 恢复/清理 MDC | 单元 | `RequestIdFilterTest` |
| OBS-15 | 正常 | 异常日志可关联 | 触发一次业务异常 | 查看应用日志与响应头 | 响应头含 `X-Request-Id`；异常日志行含 `[rid=同一值]`，可按 ID 串联请求 | 接口 | `logback-spring.xml`、`RequestIdFilter` |
| OBS-16 | 运维 | 端口与地址可覆盖 | 设置 `WGH_MANAGEMENT_PORT=19090`、`WGH_MANAGEMENT_ADDRESS=127.0.0.1` | 重启应用并访问 | 管理端点仅监听 `127.0.0.1:19090`；业务 8080 不变；9090 不再监听 | 仅手工 | `application.yml` |

---

## 30. 补充用例（文档元数据 / 工作流 / 路由 / 标签版本）

> 本章仅补充既有章节未覆盖的细节；§12 多 Agent（102 项）、§20 Prompt 模板（PT-01~16）已覆盖的能力不重复展开。
> 多 Agent 权限边界与 Prompt 联想触发沿用 §12、§20 的既有编号；本节只补充文档元数据、工作流图校验、路由已知缺陷、标签/版本自动化落点。

### 30.1 文档解析元数据

> `ParsedDocument.metadata()` 固定键为 `title/pageCount/sheetCount/encoding/charCount`；值为 null 的字段不输出（`charCount` 始终输出）。

| 编号 | 分类 | 名称 | 前置条件 | 操作步骤 | 预期结果（HTTP / 业务码） | 覆盖类型 | 自动化落点 |
|------|------|------|---------|---------|--------------------------|---------|-----------|
| FMT-18 | 正常 | PDF 元数据 | 2 页 PDF | 上传并 `GET /api/kb/files/{id}/preview` | HTTP 200 / code 200；`documentMetadata.pageCount=2`、`encoding=binary`、`charCount>0`；`title` 为推断标题；无 `sheetCount` | 单元 + 接口 | `DocumentFormatParserTest` |
| FMT-19 | 正常 | DOCX 元数据 | 含标题与正文的 docx | 上传并预览 | HTTP 200 / code 200；`documentMetadata` 含 `title`（推断）、`encoding=binary`、`charCount>0`；无 `pageCount`、无 `sheetCount` | 单元 | `DocumentParserTest`、`ParsedDocument.infer` |
| FMT-20 | 正常 | XLSX 元数据 | 含 3 个工作表的 xlsx | 上传并预览 | HTTP 200 / code 200；`documentMetadata.sheetCount=3`、`encoding=binary`、`charCount>0`；无 `pageCount` | 单元 | `DocumentFormatParserTest` |
| FMT-21 | 正常 | JSON / HTML / EML 元数据 | 分别准备含 title 的 JSON、含 `<title>` 的 HTML、含 Subject 的 EML | 逐个上传并预览 | JSON：`title` 取 title 字段，`encoding=UTF-8`；HTML：`title` 取 `<title>`，`encoding` 取文档 charset；EML：`title` 取 Subject，`encoding=UTF-8`；三者均含 `charCount` | 单元 | `DocumentFormatParserTest` |
| FMT-22 | 边界 | 空元数据不输出 | 无标题、无页数、无工作表、无编码的纯文本 | 预览 | HTTP 200 / code 200；`documentMetadata` 仅保留有值字段；`charCount` 始终存在；不输出 null 字段 | 单元 | `ParsedDocumentTest` |
| FMT-23 | 正常 | 预览返回统一元数据 | 已上传且解析成功的文件 | `GET /api/kb/files/{id}/preview` | HTTP 200 / code 200；响应含正文/切片与 `documentMetadata`；解析失败文件返回失败状态与可读错误，不返回伪造元数据 | 接口 | `KnowledgeBaseController` |

### 30.2 工作流 AGENT 节点与图校验

| 编号 | 分类 | 名称 | 前置条件 | 操作步骤 | 预期结果（HTTP / 业务码） | 覆盖类型 | 自动化落点 |
|------|------|------|---------|---------|--------------------------|---------|-----------|
| WAG-08 | 正常 | 图校验接受 AGENT 节点 | 图为 START → AGENT → END | `POST /api/workflows/{id}/validate` 或保存 | HTTP 200 / code 200；校验通过；不再报“节点类型非法”（F3 已修复） | 单元 + 接口 | `GraphValidator`、`WorkflowController` |
| WAG-09 | 边界 | AGENT 保存时不要求 modelId | 图含 AGENT 节点但未配置 modelId | 保存工作流 | HTTP 200 / code 200；保存成功；运行到该节点时由 `AgentNodeExecutor` 校验模型并返回运行时错误 | 单元 | `GraphValidator`、`AgentNodeExecutorTest` |
| WAG-10 | 异常 | 未知节点类型 | 图含 `FOO` 节点 | 保存或校验 | HTTP 200 / code 2001（参数校验失败，具体映射待确认）；消息“节点类型非法”或等价提示；不落库 | 单元 | `GraphValidator` |
| WAG-11 | 异常 | 节点必填项缺失 | LLM 缺 modelId、RETRIEVAL 缺 kbIds、TOOL 缺 toolId、CONDITION 缺 expression、WORKFLOW 缺 workflowId | 保存或校验 | HTTP 200 / code 2001（参数校验失败，具体映射待确认）；按类型返回明确缺失项错误；不落库 | 单元 | `GraphValidator` |
| WAG-12 | 正常 | 保存并运行 AGENT 节点 | AGENT 配置 goal、modelId、maxSteps、timeoutMs、kbIds | 保存 → 运行 | 保存成功；SSE 产生 AGENT 步骤事件；最终 SUCCESS，输出文本与 stepCount 符合 §23 WAG-01~07 | 接口 | `WorkflowEditor.vue`、`AgentNodeExecutor` |

### 30.3 模型路由与故障转移（含已知缺陷）

| 编号 | 分类 | 名称 | 前置条件 | 操作步骤 | 预期结果（HTTP / 业务码） | 覆盖类型 | 自动化落点 |
|------|------|------|---------|---------|--------------------------|---------|-----------|
| ROUTE-06 | 已知缺陷 | 首 token 前异步失败不切链 | 路由组含优先级 1/2 两个模型；优先级 1 在建立流式连接阶段失败 | 发起对话 | 期望自动切到优先级 2 并答复成功；当前实际返回 SSE `event:error`，日志仅记录“使用模型 ... 原因: 首模型...”，未进入 `runWithFailover`。该用例标记 FAIL，缺陷编号 F4（§25）；优先链、回退、全失败等其余路由用例见 §22 ROUTE-01~05 | 仅手工 | `RagChatService#streamChatWithTools`、`ModelRouter` |

### 30.4 标签与文件版本（自动化落点补正）

> §21 原文标注“无自动化单测”已过时：`rag-kb` 已存在 `TagServiceTest`（7 项）与 `FileVersionServiceTest`。既有 TAG-01~04、VER-01~02 保留，本节补充其自动化落点与异常码。

| 编号 | 分类 | 名称 | 前置条件 | 操作步骤 | 预期结果（HTTP / 业务码） | 覆盖类型 | 自动化落点 |
|------|------|------|---------|---------|--------------------------|---------|-----------|
| TAG-05 | 单元 | 标签 CRUD | 已登录 | 创建、更新、删除标签 | 创建成功；更新生效；删除后列表不可见；重名返回明确错误（HTTP 200 / code 2001 或 3002，按实际实现，待确认） | 单元 | `TagServiceTest` |
| TAG-06 | 异常 | 绑定标签时知识库不存在 | kbId 不存在 | `PUT /api/kb/{kbId}/tags` | HTTP 200 / code 3001，message 含“知识库不存在” | 单元 | `TagServiceTest` |
| TAG-07 | 异常 | 绑定标签时文件不存在 | fileId 不存在 | `PUT /api/kb/files/{fileId}/tags` | HTTP 200 / code 3001，message 含“文件不存在” | 单元 | `TagServiceTest` |
| VER-03 | 正常 | 版本列表 | 文件已更新至少一次 | `GET /api/kb/files/{fileId}/versions` | HTTP 200 / code 200；返回版本号、时间、大小；按版本倒序；文件不存在时 3001 | 单元 + 接口 | `FileVersionServiceTest` |
| VER-04 | 异常 | 文件不存在查版本 | fileId 不存在 | `GET /api/kb/files/{fileId}/versions` | HTTP 200 / code 3001，message 含“文件不存在” | 单元 | `FileVersionServiceTest` |

---

## 31. 限制与未覆盖

> 以下能力经代码核对确认**未实现**或属于**已知缺陷/设计边界**，不编写为“应通过”的功能用例；相关测试应标记为 SKIP/FAIL 或按“仅手工 + 限制说明”记录。

| 编号 | 限制 / 未覆盖项 | 现状说明 | 关联用例 | 建议 |
|------|----------------|---------|---------|------|
| L-01 | 大文件分片上传未实现 | 上传为单次 multipart，`max-file-size=50MB`、`max-request-size=50MB`；无分片、断点续传、秒传 | KB-16、STO-08 | 大文件场景只验证拒绝与友好提示，不编写分片成功用例 |
| L-02 | local 与 OSS 切换不迁移旧对象 | 切换 `wgh.rag.storage.type` 仅改变新写入实现；历史本地对象不会自动上传 OSS，历史 OSS 对象也不会回落本地 | STO-26 | 切换前人工迁移；迁移完成前保留旧存储类型或双读 |
| L-03 | OSS 删除失败可能留下孤儿对象 | 上传失败会尝试静默清理、删除调用失败会重试，但清理本身失败仅 WARN；若数据库记录已删除，对象可能残留 | STO-24、STO-25 | 增加对账任务/生命周期规则；删除失败记录补偿队列 |
| L-04 | 多实例 JWKS 缓存不共享 | `JwksProvider` 缓存为进程内，TTL 300 秒；未知 kid 刷新最小间隔 30 秒；各实例独立拉取 | Auth-65~67 | 多实例部署时接受轮换延迟，或引入共享缓存/统一网关验签 |
| L-05 | 无服务端 token 吊销列表 | 登出仅客户端丢弃 token；服务端无黑名单；token 在 `exp` 前仍有效（改密/禁用通过 `pv` 间接失效本地 token） | Auth-45 | 需要即时吊销时缩短 TTL 或引入 jti 黑名单 |
| L-06 | 开发环境随机本地密钥 | 非 prod 且未配置 secret 时每次启动随机生成，重启后所有本地会话失效；多实例之间本地 token 互不认可 | Auth-44 | 开发联调也配置固定 secret；生产必须配置 |
| L-07 | 故障转移不覆盖首 token 前异步失败 | 首模型在流式连接建立阶段失败经异步 `onError` 上报，未进入 `runWithFailover` 切链，直接返回 `event:error`（§25 F4） | ROUTE-06 | 以 CompletableFuture/CountDownLatch 将首 token 前失败纳入切链 |
| L-08 | 业务错误统一 HTTP 200 + body code | 多数 Service 层 `BusinessException`（含 429、1004、3003、500）由全局异常处理返回 HTTP 200；真实 4xx 仅出现在过滤器层（401/403）、唯一键冲突（409/3002）与个别控制器显式映射（如删除绑定角色 409/3003） | QTA-04/05、RBA-11、RBA-22、STO-18 | 对外开放 API 建议单独映射真实 HTTP 状态（§25 F7） |
| L-09 | Actuator 默认监听全部网卡 | `WGH_MANAGEMENT_ADDRESS` 默认 `0.0.0.0`，9090 默认对内网/容器开放 | OBS-01、OBS-16 | 生产绑定内网网卡并用防火墙限制来源 |
| L-10 | 分享密码锁定按 token + IP | 失败计数 key 为 token + 客户端 IP；攻击者更换 IP 可绕过；同一 NAT 出口多个用户共享计数 | SHARE-16、PWD-01/02 | 需要更强防护时叠加账号/全局维度；合理配置可信代理 |
| L-11 | OIDC 链路不校版本地用户状态 | 外部 token 验签通过后不查询本地用户 `status`/`expire_at`；本地禁用同名用户不影响外部 token | Auth-74 | 如需统一封禁，需在 OIDC 分支增加本地映射与状态校验 |
| L-12 | 配额校验 DB 异常静默放行 | 用量查询/累加 DB 异常只 WARN，不阻断对话，可能导致超限期间仍可用 | QTA-17 | 可用性优先的既定设计；监控 `wgh_quota_exceeded_total` 与 DB 告警 |
| L-13 | 权限项只读，无权限 CRUD | `/api/admin/permissions` 仅返回种子权限，无新增/删除权限接口；角色只能分配已有权限码 | RBA-24~28 | 新增权限需通过种子数据/迁移发布 |
| L-14 | 体验账号接口只支持单角色 | `AdminUserController` 创建/修改每次替换为用户单个角色；底层 `wgh_user_role` 支持多角色但管理端未暴露 | RBA-32、RBA-36 | 多角色需求走数据库/后续接口扩展 |
| L-15 | 本地存储不跨实例共享 | `local` 类型写入各实例本地磁盘；多实例部署时上传与读取可能落到不同实例 | STO-01~15 | 多实例生产使用 OSS 或共享存储 |
| L-16 | 模型路由/故障转移无自动化单测 | 现有测试未覆盖 `ModelRouter#resolveChain` 与首 token 失败切链；仅手工验证 | ROUTE-01~10 | 补充 `ModelRouterTest` 与流式失败切链集成测试 |
| L-17 | 登出/改密/重置后旧 token 语义依赖 pv | 本地 token 通过 `pv` 比对失效；外部 OIDC token 无 pv 概念，改密/重置不影响其有效性 | Auth-35、Auth-50、Auth-56 | 外部 token 生命周期由 IdP 管理；必要时缩短 IdP token TTL |
| L-18 | 缺少大文件病毒扫描/内容安全检测 | 上传仅做格式、大小、空文件校验；无恶意内容扫描 | KB-08~17、STO-01~26 | 按合规要求接入扫描服务（当前未实现） |

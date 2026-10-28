# 全链路自动化测试报告

> 日期：2026-10-28
> 范围：设计模式重构（R1-R9）落地后的全量回归 + 多 Agent 缺陷修复验证 + 压力测试
> 环境：macOS / JDK 25（编译目标 21）/ Spring Boot 3 / PostgreSQL + pgvector（容器 postgres-with-vector）/ Maven 3
> 结论：**全部通过** —— 单元测试 374/374，功能 e2e 与冒烟 113/113，压力测试 8/8

---

## 1. 单元测试（mvn test，12 模块 BUILD SUCCESS）

| 模块 | 用例数 | 失败 | 错误 | 跳过 |
|---|---|---|---|---|
| rag-common | 11 | 0 | 0 | 0 |
| rag-config | 0 | - | - | - |
| rag-llm | 36 | 0 | 0 | 0 |
| rag-kb | 25 | 0 | 0 | 0 |
| rag-tool | 0 | - | - | - |
| rag-agent | 23 | 0 | 0 | 0 |
| rag-chat | 24 | 0 | 0 | 0 |
| rag-workflow | 63 | 0 | 0 | 0 |
| rag-agent-multi | 56 | 0 | 0 | 0 |
| rag-controller | 0 | - | - | - |
| rag-app（集成） | 136 | 0 | 0 | 0 |
| **合计** | **374** | **0** | **0** | **0** |

说明：
- 单测基础设施为 JUnit 5 + Mockito + Spring Boot Test，随 R1-R9 重构逐模块推广建立。
- rag-agent-multi 由重构前基线 52 扩至 56（+2 SseEventSender kv 重载 null 容忍、+2 CrewCoordinator 空图线性兜底）。
- rag-config / rag-tool / rag-controller 暂无双测覆盖，属已知缺口（见第 6 节）。

## 2. 功能 e2e 与冒烟（修复后代码新鲜复跑）

| 套件 | 脚本 | 通过 | 失败 | 跳过 |
|---|---|---|---|---|
| 工作流编排 e2e | test-workflow-e2e.sh | 67 | 0 | 0 |
| Agent e2e | test-agent-e2e.sh | 18 | 0 | 0 |
| Agent HITL e2e | test-agent-hitl-e2e.sh | 16 | 0 | 0 |
| 多 Agent 冒烟 | test-multi-agent-smoke.sh | 12 | 0 | 0 |
| **合计** | | **113** | **0** | **0** |

覆盖要点：
- 工作流：CRUD 与图校验、线性/变量渲染、条件分支、循环、HITL 暂停续跑、取消、LLM/检索节点、版本快照与回滚、CRON/Webhook 触发、模板市场实例化（占位 modelId 自动填充）。
- Agent：计划执行、反思、降级路径（agent_enabled=0）、事件序列完整性。
- Agent HITL：暂停、确认续跑、开关控制、取消置 SKIPPED。
- 多 Agent：SSE 事件序列（multi_agent_start / agent_start×3 / agent_done / multi_agent_complete）、终态 COMPLETED、步骤持久化、state 接口。

## 3. 压力测试（test-stress.sh，PASS=8 FAIL=0）

| 场景 | 并发/总量 | RPS | 平均耗时 | 失败 | 非2xx |
|---|---|---|---|---|---|
| S1 登录 | 200/20 | 2883.80 | 6.9ms | 0 | 0 |
| S2 模型列表 | 500/50 | 850.82 | 58.8ms | 0 | 0 |
| S3 聊天窗口分页 | 300/30 | 610.06 | 49.2ms | 0 | 0 |
| S4 工作流列表 | 300/30 | 177.05 | 169.4ms | 0 | 0 |
| S5 RAG 检索 | 30/2 | 13.05 | 153.2ms | 0 | 0 |
| S6 SSE 聊天 | 5 并发 | - | TTFB 80-97ms，全程 1.8s | 0 | - |
| S7 工作流执行 | 10 并发 | - | 全部完成 | 0 | - |

说明：
- S5 原设计 60/6，触发 DashScope 账户级 QPS 限流（429 Throttling.RateQuota，外部配额非系统缺陷），降为 30/2 后全绿。
- S2 曾出现"假绿"：ab 的 Failed requests 仅统计网络层失败，HTTP 5xx 计入 Non-2xx；已在脚本 ab_report 中补充 Non-2xx 检查，并修正路径为 `/api/models/chat/list`。
- S6 SSE 流式接口 ab 不支持，采用 curl 并发 + time_starttransfer 测首字节时间。

## 4. 本轮缺陷修复清单

| # | 缺陷 | 根因 | 修复 | 提交 |
|---|---|---|---|---|
| 1 | 多 Agent run 秒败 | `Map.of()` 遇 null 值抛 NPE（agentName/role/input 等字段允许为空） | SseEventSender 新增容忍 null 的 kv varargs 重载，5 个 Coordinator/Executor 共 14 处危险调用全部替换 | d262787 |
| 2 | 多 Agent 步骤持久化全挂 | 实体 `run_id` String('mag-...') vs DDL BIGINT 列类型不匹配 | schema.sql 改 VARCHAR(64)；data.sql 追加幂等 DO 块修复老库；运行库手动 ALTER | d262787 |
| 3 | Crew 模式空跑却标记 COMPLETED | 无显式图定义时 graph 为空 | CrewCoordinator 新增空图兜底：按 agents 顺序合成线性链 buildLinearGraph | d262787 |
| 4 | 冒烟脚本 line 58 炸 `unbound variable` | macOS bash 3.2 无 multibyte 支持，`$STEPS）` 全角括号首字节被并入变量名 | 统一 `${VAR}` 花括号写法（冒烟 + 压测两脚本，python 正则全量审计） | d262787 / c3ec4de |
| 5 | 压测 S2 假绿 | 路径错误（/api/models/list 不存在）+ ab 统计口径缺陷 | 修正为 /api/models/chat/list，ab_report 补 Non-2xx 检查 | c3ec4de |
| 6 | SSE 完成后 ERROR 日志刷屏 | SseEmitter 完成后容器 ASYNC 异步分发，JWT 过滤器（OncePerRequestFilter）不再执行，SecurityContext 为空被 AuthorizationFilter 误判匿名 | SecurityConfig 豁免 ASYNC/ERROR 分发类型（初始 REQUEST 分发仍强制 JWT，401 已验证） | f3e4d25 |

缺陷 1-3 为当日特性提交 e789532 引入的前序缺陷，非重构引入；缺陷 6 为重构后日志审计新发现。

## 5. 重构回归结论

R1-R9（R8 经决策跳过）共 7 项设计模式重构落地后，上述 374 单测 + 113 e2e/冒烟 + 8 压测全部通过，确认重构零行为回归：

- R1 workflow 策略注册表 / R2 Orchestrator 拆分 / R3 kb 解析切分策略 / R4 llm 模型工厂 / R5 chat 压缩责任链 / R6 common 模板渲染统一 / R7 SSE DomainEventPublisher 观察者统一出口 / R9 Controller 下沉。

## 6. 已知问题与限制

| 项 | 说明 | 处置 |
|---|---|---|
| MessageBus 事件静默吞掉 | MessageBus.java L129 `Map.of` 序列化 agent_message 必抛 NPE 且被 catch 静默 | 已知未修，另开变更处理 |
| DashScope 429 限流 | 账户级 QPS 配额，embedding/LLM 调用密集场景触发 | 外部依赖，压测降并发规避；生产需关注配额 |
| 单测缺口 | rag-config / rag-tool / rag-controller 零覆盖 | 后续迭代补充 |
| e2e 覆盖差距 | docs/test-cases.md 共 227 用例，自动化覆盖与黑盒缺口映射见该文附录 | 按缺口清单逐步补齐 |
| vite dev 代理 SSE | 开发代理不转发终止帧，前端状态复位须事件驱动 | 仅开发环境，已立约定 |

---

*报告生成于全链路自动化测试收尾阶段，对应提交链：d262787 → c3ec4de → f3e4d25*

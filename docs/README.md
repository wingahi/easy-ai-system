# 文档索引

目录按用途分层：`design/` 顶层只放**总体设计**与**详细设计**，单个功能的设计放在 `design/features/<功能域>/`；`review/`、`testing/`、`api/` 按用途分类；已被现行版本取代的历史设计统一归档到 `archive/`。

## design/ — 总体设计与详细设计

| 文档 | 一句话定位 | 更新时间 |
|---|---|---|
| [design-overview.md](design/design-overview.md) | 系统总体设计：模块边界、分层、关键流程与数据层 | 2026-11-12 |
| [design-detailed.md](design/design-detailed.md) | 详细设计：模块/关键类、认证、配额、公开访问、存储、SSE、引用溯源与资源可见性 | 2026-11-12 |

## design/features/ — 单个功能设计（按功能域）

| 文档 | 一句话定位 | 更新时间 |
|---|---|---|
| [agent/agent-design.md](design/features/agent/agent-design.md) | 单 Agent：Planner / Executor / Reflection / HITL | 2026-10-28 |
| [agent/multi-agent-overall-design.md](design/features/agent/multi-agent-overall-design.md) | 多 Agent 总体设计（Crew / Graph / GroupChat） | 2026-10-28 |
| [agent/multi-agent-detail-design.md](design/features/agent/multi-agent-detail-design.md) | 多 Agent 详细设计 | 2026-10-28 |
| [workflow/workflow-design.md](design/features/workflow/workflow-design.md) | 工作流引擎、节点执行器与模板市场 | 2026-10-28 |
| [rag-retrieval/design-rag-retrieval-enhancement.md](design/features/rag-retrieval/design-rag-retrieval-enhancement.md) | 混合检索、重排、查询变换与语义缓存 | 2026-11-02 |
| [context-management/design-long-task-context-and-tool-fallback.md](design/features/context-management/design-long-task-context-and-tool-fallback.md) | 长任务上下文管理（预算/压缩/记忆/缓存布局）与工具调用兜底链设计 | 2026-11-18 |
| [knowledge-base/design-document-formats.md](design/features/knowledge-base/design-document-formats.md) | 文档解析格式支持范围与解析链路 | 2026-11-02 |
| [storage/design-file-storage.md](design/features/storage/design-file-storage.md) | 文件存储抽象：本地与阿里云 OSS | 2026-11-03 |
| [data-access/design-data-access-extensibility.md](design/features/data-access/design-data-access-extensibility.md) | 数据接入层多数据库支持的评估与可扩展改造设计（含数据权限拦截器边界） | 2026-11-12 |
| [public-access/design-public-access.md](design/features/public-access/design-public-access.md) | 免登录分享、嵌入 Widget 与开放 API 设计 | 2026-11-04 |
| [quota/quota-design.md](design/features/quota/quota-design.md) | 配额模块：表结构、接口、配置与调用链 | 2026-11-04 |
| [observability/design-observability.md](design/features/observability/design-observability.md) | 指标、日志、告警与 Prometheus 接入 | 2026-11-03 |
| [platform/design-phase5-platform-features.md](design/features/platform/design-phase5-platform-features.md) | 阶段五平台化特性（工具、工作流、多 Agent 等）阶段记录 | 2026-11-06 |

## design/refactoring/ — 重构记录

| 文档 | 一句话定位 | 更新时间 |
|---|---|---|
| [design-pattern-refactoring.md](design/refactoring/design-pattern-refactoring.md) | 设计模式重构记录 | 2026-11-04 |

## review/ — 架构审视与计划

| 文档 | 一句话定位 | 更新时间 |
|---|---|---|
| [architecture-review-2026-11-04.md](review/architecture-review-2026-11-04.md) | 架构审视报告：25 条发现、风险分级与优化清单 | 2026-11-04 |
| [rag-capability-assessment-2026-11-05.md](review/rag-capability-assessment-2026-11-05.md) | RAG 全流程能力评估：对照五个难点的现状、缺口与改进建议 | 2026-11-05 |
| [feature-comparison-2026-11-09.md](review/feature-comparison-2026-11-09.md) | 同类产品功能对照：能力矩阵与差距定位 | 2026-11-09 |
| [gap-development-plan-2026-11-09.md](review/gap-development-plan-2026-11-09.md) | 差距补齐开发计划：T01–T13 拆分、批次、文件边界与验收（批次 2/3 已回填完成状态，T01–T11 已补测试用例） | 2026-11-18 |

## testing/ — 测试用例、报告与证据

| 文档 | 一句话定位 | 更新时间 |
|---|---|---|
| [test-cases.md](testing/test-cases.md) | 全量测试用例（编号、前置条件、步骤、预期与自动化落点；含批次 2 引用溯源 / 资源可见性 / OpenAI 兼容入口，批次 3 窗口检索参数 / 评测数据集版本化 / CI 检索门禁 / 表达式节点 / 单步调试 / URL 连接器 / 迁移脚本） | 2026-11-18 |
| [unit-test-report.md](testing/unit-test-report.md) | 单元测试执行结果与覆盖说明 | 2026-11-04 |

## testing/reports/ — 历次浏览器 / 端到端测试报告

| 文档 | 一句话定位 | 更新时间 |
|---|---|---|
| [browser-test-report-2026-11-03.md](testing/reports/browser-test-report-2026-11-03.md) | 2026-11-03 浏览器回归报告 | 2026-11-06 |
| [browser-test-report-2026-11-04.md](testing/reports/browser-test-report-2026-11-04.md) | 2026-11-04 浏览器全量回归与重启后复验报告 | 2026-11-04 |
| [browser-e2e-report-2026-11-06.md](testing/reports/browser-e2e-report-2026-11-06.md) | 2026-11-06 页面端到端测试报告：用例分布、缺陷修复复验与 BLOCKED 解除记录 | 2026-11-06 |
| [browser-e2e-report-2026-11-18.md](testing/reports/browser-e2e-report-2026-11-18.md) | 2026-11-18 全量浏览器回归报告：批次 3 迁移与重启、页面 84 条 PASS 84、接口 8 条 PASS 8（补跑被叫停，3 条未完成不计 PASS）、基线 93 条对照与缺陷清单 | 2026-11-18 |

所有浏览器截图及证据位于 [testing/evidence/](testing/evidence/)，文件名保持与报告引用一致（截图未纳入 Git 跟踪，仅保留在工作区）。

## api/ — 接口文档

| 文档 | 一句话定位 | 更新时间 |
|---|---|---|
| [api.md](api/api.md) | 对外接口清单、请求/响应示例、SSE 事件协议与资源可见性端点 | 2026-11-12 |

## archive/ — 历史归档（只读，不再更新）

已被现行版本取代的设计与早期一次性报告。新的结论请写到 `design/` 或 `review/`，不要回改本区。

| 文档 | 一句话定位 | 归档原因 |
|---|---|---|
| [design/overall-design.md](archive/design/overall-design.md) | 总体架构设计 v1.0（2026-10-28） | 已被 [design-overview.md](design/design-overview.md) v2.0 取代 |
| [design/detail-design.md](archive/design/detail-design.md) | 详细设计 v1.0（2026-10-28） | 已被 [design-detailed.md](design/design-detailed.md) v2.0 取代 |
| [testing/test-report-20261028.md](archive/testing/test-report-20261028.md) | 2026-10-28 全链路测试与测试代码规范化总结 | 早于现行测试基线与用例体系 |

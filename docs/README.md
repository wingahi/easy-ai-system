# 文档索引

本目录按用途分类存放设计、审视、测试与接口文档。历史专项设计（Agent / 工作流 / 多 Agent 等）暂时保留在根目录，后续可按需归档到 `design/`。

## design/ — 设计与方案

| 文档 | 一句话定位 | 更新时间 |
|---|---|---|
| [design-overview.md](design/design-overview.md) | 系统总体设计：模块边界、分层、关键流程与数据层 | 2026-11-04 |
| [design-detailed.md](design/design-detailed.md) | 详细设计：模块/关键类、认证、配额、公开访问、存储、SSE | 2026-11-04 |
| [design-phase5-platform-features.md](design/design-phase5-platform-features.md) | 阶段五平台化特性（工具、工作流、多 Agent 等）阶段记录 | 2026-11-03 |
| [design-public-access.md](design/design-public-access.md) | 免登录分享、嵌入 Widget 与开放 API 设计 | 2026-11-04 |
| [design-document-formats.md](design/design-document-formats.md) | 文档解析格式支持范围与解析链路 | 2026-11-02 |
| [design-observability.md](design/design-observability.md) | 指标、日志、告警与 Prometheus 接入 | 2026-11-03 |
| [design-data-access-extensibility.md](design/design-data-access-extensibility.md) | 数据接入层多数据库支持的评估与可扩展改造设计 | 2026-11-04 |
| [quota-design.md](design/quota-design.md) | 配额模块：表结构、接口、配置与调用链 | 2026-11-04 |

## review/ — 架构审视

| 文档 | 一句话定位 | 更新时间 |
|---|---|---|
| [architecture-review-2026-11-04.md](review/architecture-review-2026-11-04.md) | 架构审视报告：25 条发现、风险分级与优化清单 | 2026-11-04 |

## testing/ — 测试用例与报告

| 文档 | 一句话定位 | 更新时间 |
|---|---|---|
| [test-cases.md](testing/test-cases.md) | 全量测试用例（编号、前置条件、步骤、预期与自动化落点） | 2026-11-04 |
| [unit-test-report.md](testing/unit-test-report.md) | 单元测试执行结果与覆盖说明 | 2026-11-04 |
| [browser-test-report-2026-11-03.md](testing/browser-test-report-2026-11-03.md) | 2026-11-03 浏览器回归报告 | 2026-11-04 |
| [browser-test-report-2026-11-04.md](testing/browser-test-report-2026-11-04.md) | 2026-11-04 浏览器全量回归与重启后复验报告 | 2026-11-04 |

所有浏览器截图及证据位于 [testing/evidence/](testing/evidence/)，文件名保持与报告引用一致。

## api/ — 接口文档

| 文档 | 一句话定位 | 更新时间 |
|---|---|---|
| [api.md](api/api.md) | 对外接口清单、请求/响应示例与 SSE 事件协议 | 2026-10-29 |

## 根目录 — 历史专项设计

| 文档 | 一句话定位 | 更新时间 |
|---|---|---|
| [overall-design.md](overall-design.md) | 总体架构设计（早期版本，现行总体设计见 design-overview） | 2026-11-03 |
| [detail-design.md](detail-design.md) | 详细设计（早期版本，现行详细设计见 design-detailed） | 2026-11-03 |
| [agent-design.md](agent-design.md) | 单 Agent：Planner / Executor / Reflection / HITL | 2026-10-28 |
| [workflow-design.md](workflow-design.md) | 工作流引擎、节点执行器与模板市场 | 2026-10-28 |
| [multi-agent-overall-design.md](multi-agent-overall-design.md) | 多 Agent 总体设计（Crew / Graph / GroupChat） | 2026-10-28 |
| [multi-agent-detail-design.md](multi-agent-detail-design.md) | 多 Agent 详细设计 | 2026-10-28 |
| [design-file-storage.md](design-file-storage.md) | 文件存储抽象：本地与阿里云 OSS | 2026-11-03 |
| [design-rag-retrieval-enhancement.md](design-rag-retrieval-enhancement.md) | 混合检索、重排、查询变换与语义缓存 | 2026-11-02 |
| [design-pattern-refactoring.md](design-pattern-refactoring.md) | 设计模式重构记录 | 2026-11-04 |
| [test-report-20261028.md](test-report-20261028.md) | 2026-10-28 测试报告 | 2026-10-28 |

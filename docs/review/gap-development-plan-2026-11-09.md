# 缺口开发计划（可并行拆解）— 2026-11-09

> 输入：`docs/review/rag-capability-assessment-2026-11-05.md`（当前版本，另一会话仍在校订）、`docs/review/feature-comparison-2026-11-09.md`、当前代码。  
> 口径：**以代码为准**。评估报告中已被代码修复的结论不再排期（见第 1 节）。  
> 本文件只做拆解，不改业务代码；不 commit / push；不重启服务、不跑 Maven 测试、不碰数据库。  
> 目标：同一批次内的任务文件集合不重叠，可真正同时开工；冲突任务明确串行顺序。

---

## 0. 使用方式

- 先看第 2 节任务总表和第 4 节分批，再看第 3 节任务详情。
- 每个任务都标了“独占文件”和“共享热点文件”。**只要两个任务出现在同一共享热点文件的清单里，就不能同批并行**。
- 第 5 节列出本轮工作区的其他会话、构建锁、jar 冲突、服务重启影响；开工前必须先看。

---

## 1. 已完成项：不要重复排期

以下结论在评估报告中仍写作“缺失/部分具备”，但当前代码已经落地，按代码判断**不再作为开发任务**：

| 项 | 当前代码证据 | 结论 |
|---|---|---|
| `kbName` 元数据未写入 | `KnowledgeFileService.buildMetadata` 已写入 `meta.put("kbName", kb.getName())`（`rag-kb/.../service/KnowledgeFileService.java:465`）；读取侧有存量兜底（`RagRetrieveService.java:245-261`） | **已完成**，不要再做“补 kbName” |
| pg_trgm 关键词路恒空 | `KeywordSearchService.search` 已改为 `word_similarity(?, text) >= ?`（`KeywordSearchService.java:75-77`） | **已完成**；后续只做阈值实测与配置化 |
| overlap 满长丢弃 / 碎片块 | `RecursiveChunkingStrategy` 先按 `maxLength - overlap` 切再拼 overlap（`RecursiveChunkingStrategy.java:44-52,126-133`） | **已完成**；只需补回归测试 |
| 内容哈希去重 | `KnowledgeFileService` 上传时算 `contentHash` 并查重（`:87-119,299-310`），chunk 级也去重（`:416`） | **已完成** |
| 换 embedding 模型不重建 | `KnowledgeBase` 已有 `pendingCollection` / `pendingEmbeddingModelId`，`KnowledgeBaseService` 置 `PENDING` 并显式重建（`KnowledgeBaseService.java:81`） | **已完成**，只需补“变更自动触发”策略 |
| RAG 上下文无预算 | `rag-llm/.../util/RagContextBudgeter.java` 已存在并在聊天链路使用 | **已完成** |
| 引用来源 kbName 为空 | 元数据已写 + 读取兜底 | **已完成** |
| T03 chunk 级引用与原文定位 | `sources` 扩 10 字段、`[Sn]` 按文档编号、`SourcePanel.vue` + `renderMarkdownWithCitations` 三页接入（commit `96dfc42`） | **已完成**，见 T03 完成说明 |
| T04a 资源归属与可见性（后端） | 五类资源 `visibility` + `DataScopeInterceptor` + 5 个 `PATCH .../visibility`（commit `59c5eee`），迁移脚本 `sql/migrations/20261113/01_resource_visibility.sql` 已入库 | **已完成** |
| T04b 资源归属与可见性（前端） | 五个列表页可见性列 + `VisibilityControl.vue`，无写权限只读徽标（2026-11-12） | **已完成** |

> 原“检索参数问题”已由 T02 处理（commit `874f43d`）：`min-score` 默认与各环境 yml 统一为 `0.55`，`enable-hybrid` / `keyword-top-n` 在 yml 暴露，HYDE 改为「原查询 + 假设答案」双路。低覆盖自动补偿仍未实现，见 T02 保留项。

---

## 2. 任务总表

规模：小 ≤1 人日；中 2–5 人日；大 >5 人日。  
批次：1 可立即并行；2 依赖批次 1 的交付；3 依赖批次 2；4 最后。

| ID | 任务 | 优先级 | 规模 | 批次 | 独占文件/目录（关键） | 依赖 | 状态 |
|---|---|---|---|---|---|---|---|
| T01 | 文档解析增强：OCR + 表格结构 + 清洗 | P0 | 中 | 1 | `rag-kb/.../parser/**`、`DocumentParserDispatcher`、新建 `ocr/**`、`rag-kb/pom.xml` | 无 | 已完成 `6ed9d10` |
| T02 | 检索参数治理与自动补偿 | P0 | 小 | 1 | `RagProperties.java`、`application.yml`（retrieve 段）、`QueryTransformService.java`、`RagRetrieveService.java` | 无 | 部分完成 `874f43d`（参数与 HYDE 已完成，自动补偿未做） |
| T02F | 窗口级检索参数 UI | P0 | 小 | 3 | `ChatWindows.vue`（只此一个页面） | T02、T04b | 已完成 `8b52b5f` |
| T03 | chunk 级引用与原文定位 | P0 | 中 | 2 | `KnowledgeFileService.buildMetadata`、`VectorStoreService`（metadata 透传）、`RagChatService.buildSourcesList/extractRagContext`、三个聊天页 + 新建 `SourcePanel.vue` | T02（检索结果字段） | 已完成 `96dfc42`（`pageNo` 恒空，Agent 分支无 `[Sn]`） |
| T04a | 资源归属与可见性（后端） | P0 | 中 | 2 | `01-schema.sql` + `sql/migrations/20261113/01_resource_visibility.sql`、KB/ChatWindow/Workflow/ToolConfig/PromptTemplate 的 entity/service/controller、`rag-data` 数据范围拦截器 | 无（建议先做“owner + visibility”MVP） | 已完成 `59c5eee`（迁移脚本已入库） |
| T04b | 资源归属与可见性（前端） | P0 | 中 | 2（T04a 后） | `KnowledgeBase.vue`、`ChatWindows.vue`、`WorkflowList.vue`、`ToolManagement.vue`、`PromptTemplates.vue` | T04a | 已完成（2026-11-12，未单独提交） |
| T05 | OpenAI 兼容入口 + Python SDK | P1 | 中 | 1 | 新建 `OpenAiCompatController.java`、`ApiKeyAuthenticationFilter.java`、`SecurityConfig.java`、`ApiKeyService.java`、新建 `sdk/python/**`、`PublicAccess.vue` | 无 | 已完成 `a2d9046` |
| T06a | 生成侧评测指标补齐（无表结构） | P1 | 中 | 1 | `LlmJudgeService.java`、`RetrievalEvaluationService.java`、`RetrievalEvaluationController.java` | 无 | 已完成 `e814bb5` |
| T06b | 评测数据集版本化 + CI 门禁 | P1 | 中 | 3 | `01-schema.sql`（新表）、`eval/eval_recall.py`、`.github/workflows/ci.yml`、`KBEvaluation.vue` | T04a（schema）、T06a | 已完成 `adbdafe`（迁移 `sql/migrations/20261118/01_eval_dataset_versioning.sql`） |
| T07 | 审计覆盖补齐 | P1 | 小 | 1 | `AdminUserController`、`AdminRoleController`、`QuotaAdminController`、`AgentController`、`MultiAgentController` | 无（避开 Chat/OpenApi/ApiKey） | 部分完成 `1307d27`（AdminUser 走 `AccountAuditService` 显式审计，刻意不加 `@Audit`；资源 ID 仍只在 detail） |
| T08 | URL/HTTP 数据源增量同步（最小版） | P1 | 中 | 3 | 新建 `rag-kb/.../connector/**`、`KnowledgeFileService`（入库入口）、`01-schema.sql`（连接器表）、新建 `KBConnectors.vue` | T03（Metadata）、T04a（schema） | 已完成 `855f61f`（迁移 `sql/migrations/20261118/02_kb_connector.sql`；robots.txt / 整站爬取未做） |
| T09 | 工作流表达式/代码节点 + 单步调试 | P2 | 中 | 3 | `WorkflowConstants.java`、新建 `ExpressionNodeExecutor.java`、`NodeExecutorRegistry.java`、`GraphValidator.java`、`WorkflowController.java`、`WorkflowEditor.vue`、`WorkflowNodeCard.vue` | T04a（WorkflowController） | 已完成 `e27723f`（仅表达式节点，无任意脚本执行） |
| T10 | 大文件分片上传/断点续传 | P2 | 中 | 4 | `KnowledgeBaseController` 上传端点、`KnowledgeFileService`、`LocalFileStorageService`、`KBFiles.vue` | T08（入库链路） | 已完成 `140375a`（`wgh_upload_session`；迁移 `sql/migrations/20261118/03_upload_session.sql`） |
| T11 | 模型提供商扩展（Azure/Bedrock/Ollama） | P3 | 小 | 1 | `ModelCacheManager.java`、`LlmModel.java`、`rag-llm/pom.xml`、`ChatModels.vue`/`EmbeddingModels.vue`/`RerankModels.vue` | 无 | 已完成 `028f1bc`（Bedrock 未做） |
| T12 | 多实例与可观测 | P2 | 大 | 4 | `rag-config/.../redis/**`、`DefaultPublicAccessStore`、`ChatStreamCancellation`、`application.yml`、`docker-compose.yml`、新建 k8s/备份脚本 | T02（yml）、T05（鉴权路径） | 未开始 |
| T13 | Agent 长期记忆 / 代码沙箱 | P2（建议延后） | 大 | 5 | `rag-agent/**`、`rag-agent-multi/**`、新建 memory/sandbox 包 | T09（执行节点） | 未开始 |

**建议本轮不做**：GraphRAG/知识图谱、独立向量库、独立 ES、完整爬虫调度、多 Agent 模式继续扩张、分布式语义缓存。理由见 `rag-capability-assessment-2026-11-05.md` 第 5 节，本轮不再重复分析。

---

## 3. 任务详情

> **测试用例约定（2026-11-18）**：本节为 T01~T11 补测试用例，新增编号统一为 `TC-Txx-nn`。`docs/testing/test-cases.md` 已有同行为用例的，直接引用其编号（CIT / VIS / OAI / Eval / EVDS / CIG / CONN / WFX / WFDBG / LM / AUD），不重复造；只有尚未覆盖的行为才新增 `TC-` 用例。所有标注“需运行环境验证”的条目只代表待执行，不代表已通过。

### T01 文档解析增强：OCR + 表格结构 + 清洗（P0 / 中 / 批次 1）

**一句话目标**：让扫描 PDF、双栏 PDF、复杂表格入库后“有字、有序、表格不散”。

**状态**：已完成（2026-11-11，commit `6ed9d10`）。  
**保留**：OCR 依赖本机 Tesseract 可执行文件与 `chi_sim+eng` 语言包，缺失时记 WARN 并回退纯文本抽取；版面分析中栏检测优先于表格识别，纯两列表格可能被当作双栏处理；OCR 默认单页 20 字阈值、单文档最多 50 页、单页 60s 超时。

**涉及模块与文件**
- `rag-kb/src/main/java/com/wgh/rag/kb/parser/PdfParser.java`
- `rag-kb/src/main/java/com/wgh/rag/kb/parser/DocumentParserDispatcher.java`
- 新建 `rag-kb/src/main/java/com/wgh/rag/kb/parser/ocr/OcrService.java`（接口）+ `TessOcrService.java`（默认实现）
- 新建 `rag-kb/src/main/java/com/wgh/rag/kb/parser/table/TableExtractor.java`（PDF 表格转 Markdown）
- 新建 `rag-kb/src/main/java/com/wgh/rag/kb/parser/normalize/DocumentNormalizer.java`（空白折叠、页眉页脚、乱码修复、重复段落）
- `rag-kb/pom.xml`（OCR/表格依赖；不新增 Maven 模块）
- 新建 `rag-config/src/main/java/com/wgh/rag/config/property/OcrProperties.java`（**不修改** `RagProperties`，避免与 T02 冲突）
- 测试：`rag-kb/src/test/java/.../parser/OcrParserTest.java`、`TableExtractorTest.java`、`DocumentNormalizerTest.java`；测试资源放 `rag-kb/src/test/resources/parser/`

**需要的改动**
- 后端：`PdfParser` 先走 `PDFTextStripper`；文本为空或页均字数低于阈值时调用 `OcrService`；表格识别成功后输出 Markdown 表格块；`DocumentParserDispatcher` 在解析后统一调用 `DocumentNormalizer`。
- 表结构：无。
- 前端：无（复用 `KBFiles.vue` 的上传/预览）。
- 配置：`OcrProperties` 用默认值 + 环境变量（`WGH_OCR_ENABLED`、`WGH_OCR_LANG`、`WGH_OCR_MIN_TEXT_PER_PAGE`），**不改 `application.yml`**，避免与 T02/T12 抢同一个文件。

**依赖**：无。  
**不能并行**：与 T08/T10 无直接文件冲突；但 T01 改 `parser/**`，T08 只做下载入库，不要碰 parser。  
**验收**
- 单测：扫描版 PDF fixture → `ParsedDocument.text` 非空、chunkCount > 0；表格 fixture → 输出含 `|` 的 Markdown 行；双栏 fixture → 段落顺序稳定。
- 页面：在 `KBFiles.vue` 上传扫描 PDF，预览有正文、段落数 > 0（需服务可重启时验证；本轮只跑单测）。
- 命令：`mvn -pl rag-kb -am -Dtest=OcrParserTest,TableExtractorTest,DocumentNormalizerTest test`（持锁执行）。

**测试用例**

| 编号 | 前置条件 | 操作步骤 | 预期结果 | 验证方式 |
|---|---|---|---|---|
| TC-T01-01 | 扫描版 PDF fixture（无文本层或页均字数低于阈值） | 解析并走入库链路 | `text` 非空、`chunkCount > 0`；触发 OCR 兜底；解析状态最终 `SUCCESS` | 自动化单测（`OcrParserTest`） |
| TC-T01-02 | 本机已安装 `tesseract` 与 `chi_sim+eng` 语言包 | 上传扫描 PDF，观察 `processing_status` 与 chunk 内容 | 单页文字少于阈值（默认 20 字）触发 OCR；chunk 含识别文本；不产生空正文文件 | 需运行环境验证 |
| TC-T01-03 | 引擎缺失、语言包缺失或 OCR 抛错 | 解析扫描 PDF | 记录 WARN 并回退纯文本抽取；文档不置 `FAILED`，不阻断其他文档/其他页 | 自动化单测（mock `OcrService` 抛错） |
| TC-T01-04 | `WGH_OCR_ENABLED=false` | 上传扫描 PDF | 不调用 OCR；仅使用 `PDFTextStripper` 结果；行为与升级前一致 | 自动化单测 |
| TC-T01-05 | 文档页数超过 50；或单页 OCR 超过 60s | 解析整份文档 | 只对前 50 页做 OCR；超时页降级为文本抽取；整份解析仍完成 | 自动化单测（mock 超时/页数） |
| TC-T01-06 | 表格 fixture：多行、列边界对齐、单元格间距大于普通词距 | 解析 PDF | 输出 Markdown 表格行（含竖线与分隔行）；单元格不串列；表格块进入切分 | 自动化单测（`TableExtractorTest`） |
| TC-T01-07 | 双栏 PDF fixture（中部存在纵向空白带） | 解析 PDF | 阅读顺序为先左栏、后右栏；段落顺序稳定，不出现左右交错 | 自动化单测（`PdfLayoutAnalyzerTest`） |
| TC-T01-08 | 纯两列表格页面（列间距较大） | 解析 PDF | 单行切出 ≥3 个单元格时按表格处理；否则按双栏处理。纯两列表可能被判为双栏（已知取舍，不视为缺陷） | 自动化单测 |
| TC-T01-09 | ①≥3 页且页眉/页脚/页码重复；②2 页文档 | 调用 `DocumentNormalizer.removeRepeatedHeaderFooter` | ≥3 页时删除首/尾各最多两行重复内容；<3 页不删除，避免误删正文 | 自动化单测（`DocumentNormalizerTest`） |
| TC-T01-10 | 单页版面分析抛异常（mock） | 解析整份 PDF | 该页记录 WARN 并回退原始文本；其余页正常；整份文档成功返回 | 自动化单测 |

---

### T02 检索参数治理与自动补偿（P0 / 小 / 批次 1）

**一句话目标**：把“注释说 0.55、实际 0.3”“hybrid 默认关且 yml 看不到”“HYDE 丢掉原查询”三处纠正，并在低覆盖时自动补一次检索。

**状态**：部分完成（2026-11-11，commit `874f43d`）。  
**已完成**：`minScore` 默认与 dev/test/prod 三套 yml 统一为 `0.55`；`enable-hybrid`、`keyword-top-n` 在 yml 显式暴露（默认仍关闭）；HYDE 返回「原查询 + 假设答案」双路召回。  
**保留**：`RagRetrieveService` 的低覆盖自动补偿（`coverageRate < 0.4` 时扩大 topK、放宽阈值重试）未实现；窗口级检索参数 UI 仍归 T02F。

**涉及模块与文件**
- `rag-config/src/main/java/com/wgh/rag/config/property/RagProperties.java`
- `rag-app/src/main/resources/application.yml`（只改 `wgh.rag.retrieve` 段，追加 `enable-hybrid`，**不要动其他段**）
- `rag-kb/src/main/java/com/wgh/rag/kb/service/QueryTransformService.java`
- `rag-kb/src/main/java/com/wgh/rag/kb/service/RagRetrieveService.java`
- 测试：`QueryTransformServiceTest.java`、`RagRetrieveServiceTest.java`、`KeywordSearchServiceTest.java`

**需要的改动**
- 后端：`minScore` 默认统一为 0.55（代码 + yml）；`enable-hybrid` 在 yml 显式暴露，默认值按“有向量时开启”策略确定；HYDE 返回 `[原查询, 假设答案]` 双路，而不是替换；`RagRetrieveService` 增加“覆盖不足补偿”：`coverageRate < 0.4` 时 topK×2、minScore−0.1 重试一次（有上限、有开关）。
- 表结构：无。
- 前端：无（窗口级开关放 T02F，见批次 3）。
- 配置：`RagProperties.Retrieve` 新增 `autoCompensate`、`compensateCoverageThreshold`；注释与实际行为一致。

**依赖**：无。  
**不能并行**：与 T12/T10 共用 `application.yml`，必须在它们之前完成，或由 T12/T10 只追加不同段并人工合并；与 T06a 共用 `RagProperties` 的风险——T06a 必须新建 `RagEvaluationProperties`，不得改 `RagProperties`。  
**验收**
- 单测：HYDE 返回两个 query；`minScore` 默认 0.55；低覆盖 mock 触发一次重试且最多一次。
- 接口：`GET /api/kb/{id}/search` 返回 `keywordHitCount > 0`（使用真实 chunk + 短 query 的集成测试；本轮不连库则用 mock）。
- 页面：`ChatWindows.vue` 后续 T02F 展示 hybrid、minScore、queryTransform 三项。

**测试用例**

| 编号 | 前置条件 | 操作步骤 | 预期结果 | 验证方式 |
|---|---|---|---|---|
| TC-T02-01 | 默认配置 | 读取 `RagProperties.retrieve.minScore` 与 dev/test/prod 三套 yml | 默认值均为 `0.55`；代码与 yml 一致，无“注释 0.55、实际 0.3” | 自动化单测 + 配置断言 |
| TC-T02-02 | 知识库有相关/不相关 chunk | 调用 `/api/kb/{id}/search` | 低于 0.55 的 chunk 被过滤；相关 chunk 正常返回 | 引用 **KB-24、KB-29**（不重复造） |
| TC-T02-03 | 查询变换模式 HYDE，变换模型可用 | 触发检索并捕获 `transformedQueries` | 返回“原查询 + 假设答案”两条，原查询不被替换；重复项去重 | 自动化单测（`QueryTransformServiceTest`） |
| TC-T02-04 | `enable-hybrid` 分别为 false / true | 触发检索，观察召回路数与 RRF | false 仅向量路；true 向量 + 关键词并做 RRF(k=60) | 引用 **WRP-02、WRP-03** |
| TC-T02-05 | `keyword-top-n` 默认 10，分别配置 5 / 20 | 触发关键词检索 | 关键词路最多返回配置条数；默认 10；非法/缺省回落默认值 | 自动化单测（`KeywordSearchServiceTest`） |
| TC-T02-06 | 窗口 `hybridEnabled=false`、全局 `enable-hybrid=true` | 发送提问 | 仍启用混合检索（OR 语义，窗口关闭不能覆盖全局） | 引用 **WRP-04** |
| TC-T02-07 | 检索覆盖不足（mock `coverageRate < 0.4`） | 调用 `RagRetrieveService` | 当前实现只检索一次，不扩大 topK、不放宽 minScore（低覆盖补偿未实现，断言现状而非理想行为） | 自动化单测 |
| TC-T02-08 | 变换模型被禁用或超时 | 发送提问 | 降级原查询，主链路正常返回；日志 WARN | 引用 **WRP-15** |

---

### T02F 窗口级检索参数 UI（P0 / 小 / 批次 3）

**状态**：已完成（2026-11-18，commit `8b52b5f`）。  
**已完成**：`ChatWindows.vue` 增加混合检索、查询变换模式、变换模型三项，列表回显三态标签。  
**保留**：`minScore` 与低覆盖补偿开关未落到窗口表单（窗口实体无对应字段；低覆盖补偿本身未实现）；页面交互用例见 `test-cases.md` WRP-05、WRP-06、WRP-11、WRP-16。

**一句话目标**：把 T02 的 hybrid、minScore、queryTransform、自动补偿开关暴露到聊天窗口管理页。

**涉及文件**
- `rag-frontend/src/views/chat-admin/ChatWindows.vue`（唯一文件）
- 复用现有 `rag-frontend/src/api/**` 与窗口接口，不新增页面

**改动**
- 表单增加四项：混合检索、最小相似度、查询变换模式、低覆盖补偿开关；带默认值与说明文案；保存后回显。
- 后端接口/表结构/配置：无（字段已在 `ChatWindow` 与 `RagProperties` 中存在）。

**依赖**：T02（参数语义与默认值）、T04b（同一页面先完成归属改造）。  
**不能并行**：`ChatWindows.vue` 与 T04b 冲突，必须 T04b 之后。  
**验收**：页面切换参数并保存，重新打开窗口回显一致；调用 `/api/chat/windows/{id}` 返回对应字段；`npm run build` 通过。

---

### T03 chunk 级引用与原文定位（P0 / 中 / 批次 2）

**一句话目标**：每条引用都能落到“哪个知识库、哪个文件、第几段、原文哪一段”，前端可展开原文。

> **完成情况（2026-11-12，commit `96dfc42`）**：`sources` 已扩展为 `kbId/kbName/fileId/fileName/chunkIndex/pageNo/charStart/charEnd/score/snippet`；`[Sn]` 按文档首次出现顺序分配（同文档多段共用），且在 token 预算裁剪后编号；`SourcePanel.vue` + `renderMarkdownWithCitations` 已接入三个聊天页。**与原计划的偏差**：`pageNo` 恒空（解析链路只做段落摊平，无页级映射）；纯 Agent 分支上下文未加 `[Sn]`；同名文件跨知识库合并为一张卡片。

**涉及模块与文件**
- `rag-kb/src/main/java/com/wgh/rag/kb/service/KnowledgeFileService.java`（只改 `buildMetadata` 与切分属性写入）
- `rag-kb/src/main/java/com/wgh/rag/kb/service/VectorStoreService.java`（metadata 透传，不改表）
- `rag-chat/src/main/java/com/wgh/rag/chat/service/RagChatService.java`（`extractRagContext`、`buildSourcesList`）
- `rag-chat/src/main/java/com/wgh/rag/chat/entity/ChatMessage.java`（`sources` 已是 JSON，**不加列**）
- 前端：`rag-frontend/src/views/chat/ChatPlayground.vue`、`ShareChat.vue`、`PublicShareChat.vue`
- 新建 `rag-frontend/src/components/SourcePanel.vue`
- 测试：`RagChatServiceTest`（引用字段）、`KnowledgeFileServiceTest`（metadata）

**需要的改动**
- 后端：metadata 增 `pageNo`、`charStart`、`charEnd`、`snippet`（前 120 字）；`buildSourcesList` 输出 `kbId/kbName/fileId/fileName/chunkIndex/pageNo/charStart/charEnd/score/snippet`；`extractRagContext` 给每个 chunk 加 `[S1]`、`[S2]` 编号，system prompt 要求回答用编号引用。
- 表结构：无（`sources` JSON 扩展字段即可）。
- 前端：消息下方引用卡片点击展开 `SourcePanel`，显示 snippet + 段落序号 + 分数；历史消息与流式消息共用同一渲染组件。
- 配置：无。

**依赖**：T02（检索结果字段稳定）。  
**不能并行**：与 T08/T10 共用 `KnowledgeFileService`；与 T04b 不冲突（它改 `ChatWindows.vue`，本任务改三个聊天页）。  
**验收**
- SSE `sources` 事件包含新增字段；刷新历史消息后引用仍可展开。
- 单测：metadata 含 page/offset；`buildSourcesList` 去重规则与现有“同文档保留最高分”一致。
- 页面：点击 `[S1]` 能定位到对应引用卡片并高亮。

**测试用例**

本节行为已由 `docs/testing/test-cases.md` §32 完整覆盖，不新增编号，按需求点直接引用：

| 需求点 | 引用编号 | 说明 |
|---|---|---|
| `sources` 十个字段 | CIT-01 | 含 `pageNo` 为 `null` 的断言 |
| 同文档多段合并、保留最高分 | CIT-02 | 卡片数、`score`、`chunkIndex` 一并校验 |
| `[Sn]` 按文档分配、同文档共用 | CIT-05 | 上下文前缀与 `sources` 顺序一致 |
| 预算裁剪后编号一致 | CIT-06、CIT-08 | 被裁 chunk 不占号，无空卡片 |
| snippet 压缩空白 + 120 字截断 | CIT-09、CIT-10 | 短 chunk 不补全 |
| 点击编号滚动并高亮、跳过代码/链接 | CIT-07、CIT-11、CIT-12 | 页面用例 |
| 分享页只读态引用 | CIT-13、CIT-14 | 登录态 + 公开分享 |
| `pageNo` 恒空 | CIT-16 | 已知限制，不写页码 |
| 纯 Agent 分支无 `[Sn]` | CIT-17 | 已知限制，不出现无卡片编号 |
| 同名文件跨知识库合并 | CIT-04 | 已知限制（按 `fileName` 归并） |

---

### T04a 资源归属与可见性（后端，P0 / 中 / 批次 2）

**一句话目标**：让知识库、聊天窗口、工作流、工具、Prompt 模板有“所有者 + 可见性”，非所有者不可见、不可改。

> **完成情况（2026-11-12，commit `59c5eee`）**：五类资源已加 `visibility`（`PRIVATE` 默认 / `TEAM` / `PUBLIC`），owner 复用 `create_by`；读他人 `PRIVATE` 按不存在处理（业务码 `3001`），写他人 `TEAM`/`PUBLIC` 返回权限不足（业务码 `1004`），均为 HTTP 200 + 业务码；内置 Prompt 模板固定 `PUBLIC`、改可见性返回 `3003`；`DataScopeInterceptor` 仅改写 `page/list` 单表查询，白名单表 JOIN / UNION / CTE 失败关闭。**与原计划的偏差**：迁移脚本改以日期目录形式入库为 `sql/migrations/20261113/01_resource_visibility.sql`（原计划路径 `20261109_resource_visibility.sql`），存量库执行该脚本即可补列与索引，无需手工 DDL；`TEAM` 与 `PUBLIC` 当前判定等价。

**建议先做 MVP**：不引入 `tenant_id`，用现有 `BaseEntity.createBy` 作为 owner，新增 `visibility`（PRIVATE/TEAM/PUBLIC）与查询过滤；等真有多租户诉求再加 `tenant_id`。这样改动集中、可回滚。

**涉及模块与文件**
- 表结构：`rag-backend/sql/schema/01-schema.sql`（5 张表加 `visibility`，`create_by` 已有）；`rag-backend/sql/migrations/20261113/01_resource_visibility.sql`（存量库补列 + 索引 + 内置资源回填，幂等）
- 实体：`rag-kb/.../entity/KnowledgeBase.java`、`rag-chat/.../entity/ChatWindow.java`、`rag-workflow/.../entity/Workflow.java`、`rag-tool/.../entity/ToolConfig.java`、`rag-chat/.../entity/PromptTemplate.java`
- 服务：`KnowledgeBaseService.java`、`ChatWindowService.java`、`WorkflowService.java`、`ToolConfigService.java`、`PromptTemplateService.java`
- 控制器：`KnowledgeBaseController.java`、`ChatController.java`（`/windows` 与 `/tools` 都在这个类）、`WorkflowController.java`、`PromptTemplateController.java`
- 新建 `rag-data/src/main/java/com/wgh/rag/data/scope/DataScopeInterceptor.java` + `DataScopeContext.java`（MyBatis-Plus 拦截器，统一追加 `create_by = 当前用户 OR visibility != 'PRIVATE'`）
- 权限：`rag-auth/.../constant/AuthConstants.java`（新增 `PERM_RESOURCE_PUBLISH` 等，可选）；**不要改 `SecurityConfig.java`**（留给 T05）
- 测试：各 Service 的归属过滤单测；`DataScopeInterceptorTest`

**需要的改动**
- 后端接口：列表接口默认只返回“自己的 + 非 PRIVATE”；详情/更新/删除校验归属（非 owner 且非 ADMIN → 403/404）；新增 `PATCH /api/.../{id}/visibility`（或复用更新接口）。
- 表结构：5 张表各加 `visibility VARCHAR(16) DEFAULT 'PRIVATE'`；`create_by` 已有；加 `(create_by, visibility)` 索引。
- 前端：无（T04b）。
- 配置：无。

**依赖**：无（可与 T03 并行）。  
**不能并行**：`01-schema.sql` 与 T06b/T08/T10 冲突，必须 T04a 先做；`ChatController.java` 与 T09? T09 用 `WorkflowController`，不同；`Application.yml` 不碰。  
**边界**：内置 Prompt 模板（`builtin=1`）保持 PUBLIC 且只读；分享链接仍按 token 访问，不受 `visibility` 影响，避免破坏现有分享链路。  
**验收**
- 单测：用户 A 创建 KB，用户 B 列表不可见、详情 404/403；ADMIN 可见；`visibility=PUBLIC` 时 B 可见但不可改。
- 迁移脚本幂等：同一脚本执行两次无 ERROR（静态审查；本轮不连库）。
- 页面：T04b 完成后，用两个账号验证列表与详情。

**测试用例**

已由 `docs/testing/test-cases.md` §33 完整覆盖，不新增编号：

| 需求点 | 引用编号 | 说明 |
|---|---|---|
| 五类资源默认 `PRIVATE`、owner 读写 | VIS-01、VIS-02 | 含 `createBy` 断言 |
| ADMIN 越过可见性 | VIS-03 | 读 + 写 |
| 他人 PRIVATE：详情/写按不存在（3001） | VIS-04 | 不泄露存在性；与“404 语义”对应 |
| 他人 PRIVATE 不在列表 | VIS-05 | 分页总数同步减少 |
| 他人 TEAM/PUBLIC 可读、不可写（1004） | VIS-06~VIS-09 | 404 与 403 的语义差异 |
| TEAM 与 PUBLIC 判定等价 | VIS-11 | 已知限制 |
| 拦截器只改写 page/list 单表查询 | VIS-12、VIS-13、VIS-17、VIS-18 | 含去重与按 id 不激活 |
| JOIN / UNION / CTE 失败关闭 | VIS-14~VIS-16 | 抛 SQLException，不返回未过滤数据 |
| 内置 Prompt 固定 PUBLIC | VIS-19、VIS-20 | 改可见性返回 `3003` |
| 普通用户编辑内置模板被拦截 | VIS-21 | 403 / `1004` / 只读 |
| 非法 visibility → `2001` | VIS-22 | 原值不变 |
| 分享 token 不受可见性过滤 | VIS-23、VIS-24 | 匿名 + 只读 |
| 存量库未迁移报错、`system` 资源仅管理员可见 | VIS-29、VIS-30 | 升级检查项 |
| 迁移幂等与内置资源回填 | VIS-31、VIS-32 | 手工执行 |

---

### T04b 资源归属与可见性（前端，P0 / 中 / 批次 2，T04a 后）

> **完成情况（2026-11-12，未单独提交）**：五个列表页已加可见性列并接入 `VisibilityControl.vue`；五个 `PATCH .../visibility` 入口（`/api/kb/{id}`、`/api/chat/windows/{id}`、`/api/workflows/{id}`、`/api/prompt-templates/{id}`、`/api/chat/tools/{id}`）；无写权限时显示只读徽标 + tooltip，不提供可点按钮。原计划的“我的/共享/公开筛选与所有者列”未全部实现，当前以可见性徽标为主。

**涉及页面**
- `rag-frontend/src/views/kb/KnowledgeBase.vue`
- `rag-frontend/src/views/chat-admin/ChatWindows.vue`
- `rag-frontend/src/views/workflow/WorkflowList.vue`
- `rag-frontend/src/views/tools/ToolManagement.vue`
- `rag-frontend/src/views/prompt/PromptTemplates.vue`

**改动**
- 列表增加“我的/共享/公开”筛选与“所有者”列；创建/编辑弹窗增加可见性选择；非 owner 隐藏编辑/删除按钮。
- 复用现有 `api` 封装，不新增状态管理库。

**依赖**：T04a 的接口契约。  
**不能并行**：与 T02F 共用 `ChatWindows.vue`，T02F 必须等 T04b 完成。  
**验收**：两个浏览器会话（A/B 账号）分别登录，列表与按钮表现符合预期；`npm run build` 通过。

**测试用例**

前端交互已由 §33.5 覆盖，不新增编号：

| 需求点 | 引用编号 | 说明 |
|---|---|---|
| 五个列表页可见性列 | VIS-25 | 文案/颜色与 `VISIBILITY_OPTIONS` 一致 |
| 无写权限只读徽标 + tooltip | VIS-26 | 不出现下拉菜单 |
| 切换后即时更新、刷新保持 | VIS-27 | 失败回滚 |
| 内置模板不可切换 | VIS-28 | 只读 `PUBLIC` |
| 五个 `PATCH .../visibility` 入口 | VIS-02、VIS-08、VIS-22 | 接口契约与非法值校验 |

---

### T05 OpenAI 兼容入口 + Python SDK（P1 / 中 / 批次 1）

**一句话目标**：客户用现有 OpenAI SDK 改 base_url 就能调用本系统；同时提供最小 Python SDK。

**状态**：已完成（2026-11-11，commit `a2d9046`）。  
**保留**：只实现 `GET /v1/models` 与 `POST /v1/chat/completions`（含 SSE 流式），`n>1`、`logprobs`、function calling / tools 等参数未支持；`model` 只能映射到已启用聊天窗口（`wgh-window-<id>`、纯数字 id 或窗口名），不支持直接传底层 LLM 模型名；SDK 为单文件最小客户端（`sdk/python/wgh_rag_client.py`），未提供 `PublicAccess.vue` 接入示例；未做与官方 openai-python 或真实厂商端点的连通性验证。

**涉及模块与文件**
- 新建 `rag-controller/src/main/java/com/wgh/rag/controller/OpenAiCompatController.java`（路径 `/api/open/v1/chat/completions`，可另加 `/v1/chat/completions` 别名）
- `rag-public-access/.../config/ApiKeyAuthenticationFilter.java`（路径 → scope 映射，`CHAT_WRITE`）
- `rag-auth/.../config/SecurityConfig.java`（放行 API Key 过滤器处理的路径；只加本任务需要的匹配器）
- `rag-public-access/.../service/ApiKeyService.java`（scope 校验，必要时补 `MODEL_READ`）
- 新建 `sdk/python/wgh_rag/__init__.py`、`client.py`、`README.md`、`examples/chat.py`
- `rag-frontend/src/views/open/PublicAccess.vue`（API Key 创建时展示 base_url 与 curl 示例）
- 测试：`OpenAiCompatControllerTest`（stream / non-stream、错误格式）

**改动**
- 后端接口：非流式返回 OpenAI 形状 `{id, object, choices[{message}], usage}`；流式返回 `data: {...}\n\n` + `data: [DONE]`；错误返回 OpenAI 错误结构；模型名映射到 `wgh_chat_window` 或 `wgh_llm_model`。
- 表结构：无（复用 API Key）。
- 前端：开放平台页展示接入信息。
- 配置：无敏感项；base_url 写文档。

**依赖**：无。  
**不能并行**：`SecurityConfig.java` 是热点；T04a 不得改它。与 T07 不冲突（T07 不动 OpenApi/ApiKey 控制器）。  
**验收**
- `curl -N -H "Authorization: Bearer <key>" -d '{"model":"...","stream":true,...}' /api/open/v1/chat/completions` 返回标准 SSE。
- Python SDK 示例能跑通非流式与流式；无 scope 的 key 返回 403。
- 单测覆盖错误 JSON 结构。

**测试用例**

已由 `docs/testing/test-cases.md` §34 覆盖，仅补一条未覆盖参数边界：

| 编号 | 前置条件 | 操作步骤 | 预期结果 | 验证方式 |
|---|---|---|---|---|
| TC-T05-01 | 有效 Key（`chat:write`），窗口启用 | 请求体带 `n=2`、`logprobs`、`tools` / `tool_choice` | 这些参数被忽略（请求按单条 choice 处理）：返回 1 个 choice、无 `tool_calls`、不报 400；属当前已知限制 | 接口调用 |

| 需求点 | 引用编号 | 说明 |
|---|---|---|
| `/v1/models` 仅启用窗口 | OAI-01、OAI-02 | 含 `model:read` 403 |
| 非流式 / 流式结构 | OAI-03~OAI-05 | `chat.completion`、`chat.completion.chunk`、`[DONE]`、usage |
| 多模态图片输入 | OAI-06 | 不支持视觉时返回可读错误 |
| 401 / 403 / 404 / 429 / 400 错误体 | OAI-07~OAI-12 | OpenAI 错误结构 |
| `session_id` 续聊与归属 | OAI-13~OAI-16 | 非数字、他人会话 |
| `/api/open/v1` 别名 | OAI-17 | 与 `/v1` 一致 |
| Python SDK | — | 单文件客户端；真实厂商连通性未验证，需运行环境 |

---

### T06a 生成侧评测指标补齐（P1 / 中 / 批次 1）

**一句话目标**：在现有 Faithfulness / Answer Relevancy 之外补 Context Precision / Context Recall，并把多 judge 结果落到 `judge_details` JSON。

**状态**：已完成（2026-11-11，commit `e814bb5`）。  
**保留**：Context Precision / Recall 已实现并接入多 judge 聚合，但标注表 `wgh_kb_ground_truth` 只有相关 chunk、没有参考答案字段，缺参考答案时按 `NOT_COMPUTABLE_NO_REFERENCE` 返回、尚不能端到端产出数值；检索侧同时补了 MRR@K / NDCG@K（分母为标注相关集合）；评测配置集中在 `wgh.rag.evaluation.*`。

**涉及文件**
- `rag-chat/.../evaluation/LlmJudgeService.java`
- `rag-kb/.../service/RetrievalEvaluationService.java`
- `rag-controller/.../RetrievalEvaluationController.java`
- 新建 `rag-config/.../property/RagEvaluationProperties.java`（**不改 `RagProperties`**）
- 测试：`LlmJudgeServiceTest`、`RetrievalEvaluationServiceTest`

**改动**
- 后端：新增 context precision/recall 的 judge prompt、解析与一致性计算；`ChatMessage.judgeDetails` 存 JSON，不新增列；超标/超时降级不影响主链路。
- 表结构：无（`judge_details` 已是 JSON）。
- 前端：无（T06b）。
- 配置：judge 超时、重复次数、并发度。

**依赖**：无。  
**不能并行**：不要改 `RagProperties`（T02 独占）；不要改 `ChatMessage.java` 的列（T03 也不改列）。  
**验收**
- 单测：mock judge 返回固定 JSON → 指标计算正确；judge 超时 → 主链路不受影响。
- 接口：`POST /api/kb/{id}/evaluation` 返回新增指标（需服务可重启时验证）。

**测试用例**

`Eval-01`~`Eval-09` 覆盖方案开关与 judge 选择；以下补指标口径与“不可计算”状态：

| 编号 | 前置条件 | 操作步骤 | 预期结果 | 验证方式 |
|---|---|---|---|---|
| TC-T06a-01 | 标注相关集合含 1 条，检索结果第 3 位命中 | 调用 `RetrievalQuality.mrrAtK` | 返回 `1/3 ≈ 0.3333`；第 1 位命中时为 `1.0`；未命中为 `0` | 自动化单测（`RetrievalEvaluationServiceTest`） |
| TC-T06a-02 | 相关性为二值，K=3，命中位置为第 2 位（`rel=[0,1,0]`） | 计算 NDCG@3 | `DCG=1/log2(3)≈0.6309`，`IDCG=1`，`NDCG≈0.6309`；与手算一致 | 自动化单测 |
| TC-T06a-03 | 未提供标注相关集合 | 计算 Recall / MRR / NDCG | 三者均返回 `null`（不可计算），不以 0 冒充 | 自动化单测 |
| TC-T06a-04 | 有标注但前 K 条均不相关 | 同上 | Recall=0、MRR=0、NDCG=0（有效结论，不是 `null`） | 自动化单测 |
| TC-T06a-05 | judge 逐 chunk 判定 `rel=[1,0,1]`，K=3 | 计算 Context Precision@3 | `P@1=1`、`P@2=0.5`、`P@3=2/3`；`(1×1 + 0.5×0 + 0.6667×1)/2 ≈ 0.8333` | 自动化单测（`LlmJudgeServiceTest`） |
| TC-T06a-06 | 参考答案拆出 3 条声明，上下文可支持其中 2 条 | 计算 Context Recall | 返回 `2/3 ≈ 0.6667` | 自动化单测 |
| TC-T06a-07 | 未提供参考答案 | 执行 LLM_JUDGE 评估 | Context Precision / Recall 为 `null`，`contextMetricStatus=NOT_COMPUTABLE_NO_REFERENCE`；不写 0 分 | 自动化单测 |
| TC-T06a-08 | 无检索上下文（0 个 chunk） | 执行评估 | 状态 `NOT_COMPUTABLE_NO_CONTEXT`；Faithfulness / Answer Relevancy 仍按可用输入计算 | 自动化单测 |
| TC-T06a-09 | 有参考答案但 judge 未拆出任何原子声明 | 执行评估 | 状态 `NOT_COMPUTABLE_NO_CLAIMS`；指标为 `null` | 自动化单测 |
| TC-T06a-10 | 全部 judge 调用/解析失败 | 执行评估 | 状态 `NOT_COMPUTABLE_JUDGE_FAILED`；主回答链路不受影响，`judgeDetails` 记录错误 | 自动化单测 |
| TC-T06a-11 | judge 超时（mock 超时或禁用全部 judge） | 发送一次开启 LLM_JUDGE 的提问 | done 事件正常返回；评估异步失败只记 WARN/状态，不阻断回答（同 **Eval-08** 的异步语义） | 自动化单测 + 接口 |

---

### T06b 评测数据集版本化 + CI 门禁（P1 / 中 / 批次 3）

**状态**：已完成（2026-11-18，commit `adbdafe`）。  
**已完成**：`wgh_eval_dataset` / `wgh_eval_case` / `wgh_eval_result` 三表、写时复制版本、按版本归档、跨版本对比；`eval_recall.py` 增加 fixture 离线模式与五项阈值门禁；CI 增加 `retrieval-gate`。  
**保留**：fixture 不验证真实检索链路；尚无 A/B 实验对比；迁移脚本为 `sql/migrations/20261118/01_eval_dataset_versioning.sql`。

**涉及文件**
- `01-schema.sql`（新表 `wgh_eval_dataset`、`wgh_eval_case`，或复用 `wgh_kb_ground_truth` 加 `dataset_version`）
- `rag-backend/eval/eval_recall.py`
- `.github/workflows/ci.yml`
- `rag-frontend/src/views/kb/KBEvaluation.vue`
- `RetrievalEvaluationService.java`（版本化查询）

**改动**
- 数据集版本 CRUD；评测结果按版本归档；CI 增加“固定 fixture 评测 + 阈值门禁”（不依赖真实大模型时用 mock 模式）。
- 表结构：新表/新列；必须在 T04a 之后改 `01-schema.sql`。
- 前端：数据集版本选择、历史对比。
- 配置：CI 环境变量。

**依赖**：T04a（schema）、T06a。  
**不能并行**：`01-schema.sql`、`KBEvaluation.vue` 独享。  
**验收**：`python3 rag-backend/eval/eval_recall.py --dataset fixture.jsonl --k 5` 输出与页面一致；CI 在 mock 模式通过；数据集版本切换后结果不串。

**测试用例**

已由 `docs/testing/test-cases.md` §36、§37 完整覆盖，不新增编号：

| 需求点 | 引用编号 | 说明 |
|---|---|---|
| 数据集 / 用例 CRUD、归属校验 | EVDS-01~EVDS-06、EVDS-21、EVDS-22 | 名称/说明校验、跨知识库按 3001 |
| 写时复制、版本推进、旧版本只读 | EVDS-07~EVDS-14 | 新增/修改/删除均生成新版本 |
| 按版本评测归档 | EVDS-15~EVDS-18 | 指定旧版本不覆盖其他结果 |
| 跨版本对比（指标差值 + 逐查询 Recall） | EVDS-19、EVDS-20 | 缺失侧为 `null` |
| CI fixture 离线模式与五项阈值 | CIG-01~CIG-07 | 无 Key、无网络、无模型 |
| 不达标退出码 1、fixture 错误退出码 2 | CIG-04、CIG-08 | 与“运行失败”区分 |
| 脚本单测与 CI 作业隔离 | CIG-09、CIG-10 | `test_eval_recall.py`、独立 job |
| 迁移脚本 | MIG3-01~MIG3-07 | `20261118/01_eval_dataset_versioning.sql` |

---

### T07 审计覆盖补齐（P1 / 小 / 批次 1）

**一句话目标**：管理类写操作都能在 `wgh_audit_log` 查到。

**状态**：部分完成（2026-11-11，commit `1307d27`）。  
**已完成**：`AdminRoleController`、`AgentController`、`MultiAgentController`、`QuotaAdminController` 的写接口补 `@Audit`，并有 `AuditCoverageTest` 做覆盖校验。  
**保留**：`AdminUserController` 刻意不加 `@Audit`，改走 `AccountAuditService` 显式审计（`detail` 为 `target=<username>`，不含口令明文）；审计的资源标识仍只拼在 `detail` 文本里，未落独立 resource id 列。

**涉及文件**
- `rag-controller/.../AdminUserController.java`、`AdminRoleController.java`、`QuotaAdminController.java`、`AgentController.java`、`MultiAgentController.java`
- 必要时 `rag-log/.../aspect/AuditAspect.java`（补资源 ID 提取）

**改动**
- 给上述控制器的写接口加 `@Audit(action=..., resourceType=...)`；统一 action 命名。
- 表结构：无；前端：无；配置：无。

**依赖**：无。  
**不能并行**：**不要动** `ChatController`、`KnowledgeBaseController`、`WorkflowController`（T04a 会改）；**不要动** `OpenApiController`、`ApiKeyAdminController`（T05 相关）。  
**验收**：单测或代码审查确认每个写接口都有注解；服务可重启时调用一次接口后查审计表（本轮不连库）。

**测试用例**

| 编号 | 前置条件 | 操作步骤 | 预期结果 | 验证方式 |
|---|---|---|---|---|
| TC-T07-01 | `AdminRoleController` 已加载 | 反射检查 4 个写方法注解并触发切面 | `ROLE_CREATE/UPDATE/DELETE/PERMISSION_UPDATE` 均声明 `@Audit`；切面各写一条 SUCCESS 记录；`listRoles/listPermissions` 无注解 | 自动化单测（`AuditCoverageTest`） |
| TC-T07-02 | `QuotaAdminController` | 同 TC-T07-01 | `QUOTA_CONFIG_SAVE`、`QUOTA_USAGE_RESET`、`QUOTA_CONFIG_RESET` 均声明；读接口无注解 | 自动化单测 |
| TC-T07-03 | `AgentController` | 同 TC-T07-01 | HITL 确认、取消、重试、跳过 4 个写方法均声明 `@Audit`；查询接口无注解 | 自动化单测 |
| TC-T07-04 | `MultiAgentController` | 同 TC-T07-01 | run 创建/HITL/取消、crew 创建/更新/删除 6 个写方法均声明；流式与查询接口无注解 | 自动化单测 |
| TC-T07-05 | `AdminUserController#create`（ADMIN 登录） | 创建账号（口令 `secretPass123`）并捕获审计记录 | 走 `AccountAuditService` 显式审计，`action=CREATE`、`resourceType=ACCOUNT`、`resourceId=trial07`；`detail` 含 `target=trial07` 且**不含**口令明文 | 自动化单测 |
| TC-T07-06 | `AdminUserController#create` / `#resetPassword` | 反射检查注解 | 两方法均**不得**声明 `@Audit`（入参 DTO `toString()` 含明文口令，切面会原样写 detail） | 自动化单测 |
| TC-T07-07 | 审计落库抛异常（mock DB 失败） | 执行任一被 `@Audit` 的写操作 | 主流程正常返回；仅 WARN 日志，不把审计失败传播给调用方 | 引用 **AUD-03** |
| TC-T07-08 | 未覆盖边界 | 检查 `KB/Chat/Tool/Prompt/Connector/Chunk complete` 等写接口 | 这些接口已有 `@Audit`，但资源标识仍拼在 `detail` 文本（`类名#方法名`），没有独立 resource id 列；`AdminUser` 同理——属当前已知边界 | 代码审查 + 引用 **AUD-01** |

---

### T08 URL/HTTP 数据源增量同步（最小版，P1 / 中 / 批次 3）

**状态**：已完成（2026-11-18，commit `855f61f`）。  
**已完成**：单页 URL 抓取 + Jsoup 正文抽取、`content_hash` 判重、内容变化生成新文件、404/410 清理、SSRF 逐跳校验、运行记录、可选定时同步（`WGH_CONNECTOR_SCHEDULER_ENABLED` 默认 false）。  
**保留**：robots.txt 校验、整站爬取、非 HTML 数据源（PDF / RSS / 第三方 API）未实现；并发锁为进程内，多实例不互斥；迁移脚本为 `sql/migrations/20261118/02_kb_connector.sql`。

**一句话目标**：支持“填 URL → 抓正文 → 入库”，并支持定时重抓、内容未变不重复入库。

**涉及文件**
- 新建 `rag-kb/.../connector/UrlConnector.java`（Jsoup + Readability 正文抽取）
- 新建 `rag-kb/.../connector/ConnectorService.java`、`ConnectorScheduler.java`
- 新建 `rag-controller/.../ConnectorController.java`（避免动 `KnowledgeBaseController`）
- `rag-kb/.../service/KnowledgeFileService.java`（只调用现有入库入口，复用 contentHash）
- `01-schema.sql` + 新迁移：`wgh_kb_connector`、`wgh_kb_connector_run`
- 新建 `rag-frontend/src/views/kb/KBConnectors.vue`；`router/index.js` 加路由
- 测试：`UrlConnectorTest`（本地 HTML fixture）、`ConnectorServiceTest`

**改动**
- 后端：单页抓取（不 crawler）、robots 尊重、超时/重试、内容 hash 比较、删除源文档时逻辑删除对应文件；定时任务用 Spring `@Scheduled` 或现有触发器机制。
- 表结构：连接器配置表 + 运行记录表。
- 前端：知识库详情页增加“数据源”入口。
- 配置：抓取超时、UA、并发度（新建 `ConnectorProperties`，不改 `application.yml`，或只追加独立段）。

**依赖**：T03（metadata 复用）、T04a（schema 与归属）。  
**不能并行**：`KnowledgeFileService` 与 T10 冲突；`01-schema.sql` 与 T06b 同批但不同表——**仍建议串行**，因为同一文件。  
**验收**：配置一个本地 HTTP 页面 URL → 生成文件；第二次同步 contentHash 不变 → 不新增；源页面删除 → 文件逻辑删除；运行记录可查。

**测试用例**

已由 `docs/testing/test-cases.md` §40 完整覆盖，不新增编号：

| 需求点 | 引用编号 | 说明 |
|---|---|---|
| 配置 CRUD、必填与默认值 | CONN-01、CONN-03 | `enabled=0`、间隔默认 60 |
| 不可抓取 URL 拒绝 | CONN-02 | SSRF 前置校验 |
| 列表按创建人过滤、归属校验 | CONN-04、CONN-05 | 他人连接器按 3001 |
| 正文提取与噪声移除 | CONN-06 | `article/main/#content` 启发式 |
| 首次同步成功、运行记录 | CONN-07、CONN-17 | 文件 + 哈希 + 状态 |
| `UNCHANGED` 不重复入库 | CONN-08 | 复用既有 `fileId` |
| 内容变化生成新版本、旧文件逻辑删除 | CONN-09 | 知识库只保留最新正文 |
| 404/410 清理、其他失败保留 | CONN-10、CONN-11 | 仅 404/410 清空 |
| 空正文失败 | CONN-12 | 不入库 |
| SSRF 逐跳校验、重定向/响应上限 | CONN-13 | 拒绝内网/元数据地址 |
| 并发锁、定时跳过 | CONN-14 | 进程内锁，多实例不互斥（已知限制） |
| 定时默认关闭与到期判定 | CONN-15、CONN-16 | `WGH_CONNECTOR_SCHEDULER_ENABLED=true` 才启用 |
| 管理页交互 | CONN-18 | 需运行环境验证 |
| 迁移脚本 | MIG3-04~MIG3-07 | `20261118/02_kb_connector.sql`；robots.txt / 整站爬取未做（已知限制） |

> 真实外网抓取（DNS、证书、30x 跨域、超时、站点反爬）未纳入自动化，需在具备出网条件的环境执行 CONN-06~CONN-13，并标注“需运行环境验证”。

---

### T09 工作流表达式/代码节点 + 单步调试（P2 / 中 / 批次 3）

**状态**：已完成（2026-11-18，commit `e27723f`）。  
**已完成**：`EXPRESSION` 受限 SpEL 表达式节点（拒绝 `T()` / `new` / `@bean` / 赋值 / 实例方法，长度上限 2000）；`POST /api/workflows/{id}/debug/step` 单步、输入快照、游标续跑、HITL 暂停。  
**保留**：不做任意脚本执行（代码沙箱归 T13）；调试开关默认关闭；HITL 停在 `PAUSED` 后无法继续该调试会话。

**一句话目标**：工作流能安全地做变量计算，并能单步查看每个节点的输入/输出。

**建议**：先做“表达式节点”（SpEL + 受限上下文），不做任意脚本执行；代码沙箱归入 T13。

**涉及文件**
- `rag-workflow/.../constant/WorkflowConstants.java`（新增 `EXPRESSION`）
- 新建 `rag-workflow/.../engine/executor/ExpressionNodeExecutor.java`
- `rag-workflow/.../engine/executor/NodeExecutorRegistry.java`
- `rag-workflow/.../engine/GraphValidator.java`（表达式语法校验）
- `rag-workflow/.../engine/WorkflowEngine.java`（单步/断点调试接口）
- `rag-controller/.../WorkflowController.java`（`POST /{id}/debug/step`）
- `rag-frontend/src/views/workflow/WorkflowEditor.vue`、`rag-frontend/src/components/WorkflowNodeCard.vue`
- 测试：`ExpressionNodeExecutorTest`、`GraphValidatorTest`

**改动**
- 后端：表达式节点读写上下文变量；禁止 `T()`、`Runtime`、`Class` 等危险调用；调试接口返回节点输入/输出/耗时。
- 表结构：无（运行记录已有）。
- 前端：画布新增节点、属性面板、单步运行与节点执行结果面板。
- 配置：单步调试开关（默认关闭）。

**依赖**：T04a（`WorkflowController` 归属过滤先落地）。  
**不能并行**：与 T04b 不冲突（不同文件）；与 T08 可并行（不同目录）。  
**验收**：表达式节点能计算并传递变量；非法表达式在校验阶段被拦截；单步调试能停在指定节点并展示上下文。

**测试用例**

已由 `docs/testing/test-cases.md` §38、§39 完整覆盖，不新增编号：

| 需求点 | 引用编号 | 说明 |
|---|---|---|
| 合法表达式求值、`resultVar` 传变量 | WFX-01、WFX-02 | 下游 `{{vars.x}}` 可取值 |
| 上下文字段与白名单函数 | WFX-03、WFX-04 | `#input/#last/#loop/#vars/#nodes/#fn` |
| `T()` / `new` / `@bean` 被拒 | WFX-05~WFX-07 | 安全沙箱 |
| 赋值、实例方法被拒 | WFX-08、WFX-09 | `SimpleEvaluationContext` 限制 |
| 长度上限 2000 | WFX-10 | 超长直接拒绝 |
| 必填与语法校验 | WFX-11、WFX-12 | 保存校验 + 运行时错误 |
| 字符串字面量不误判 | WFX-13 | `'T(...)'`、`'a@b.com'` |
| 调试开关默认关、行为不变 | WFDBG-01、WFDBG-14 | 普通运行链路不受影响 |
| 逐节点输入快照与输出 | WFDBG-02~WFDBG-05 | 一次只推进预算内节点 |
| 游标持久化、快照续跑 | WFDBG-08、WFDBG-11 | `context_json` 游标 |
| HITL 停在 `PAUSED` | WFDBG-07 | 后续不能续跑（L-28，已知限制） |
| 编辑器交互 | WFDBG-13 | 需运行环境验证 |

---

### T10 大文件分片上传/断点续传（P2 / 中 / 批次 4）

**状态**：已完成（2026-11-18，commit `140375a`，迁移脚本 `9872d87`）。  
**已完成**：`wgh_upload_session` 会话表、`/files/chunk/init|{sessionId}|complete` 端点、按序拼接 + SHA-256 复用入库、重复分片幂等、会话归属校验、过期 `UPLOADING` 会话清理。  
**保留**：默认分片 5MB（1MB~64MB）、分片总数上限 2000；清理任务默认开启（24h / 60min / 200 条）；迁移脚本为 `sql/migrations/20261118/03_upload_session.sql`。

**涉及文件**
- `KnowledgeBaseController.java`（`/files/chunk/init|chunk|complete`）
- `KnowledgeFileService.java`、`LocalFileStorageService.java`
- 新建 `wgh_upload_session` 表或复用 Redis（推荐表，便于审计）
- `KBFiles.vue`（分片上传、进度、续传）
- 测试：`ChunkUploadServiceTest`

**依赖**：T08（入库链路稳定）。  
**不能并行**：`KnowledgeFileService`、`KBFiles.vue` 与 T08 串行；`application.yml` 与 T12 串行。  
**验收**：200MB 文件分片上传成功；中断后从已完成分片续传；合并后 contentHash 与单次上传一致。

**测试用例**

> 端点：`POST /api/kb/{kbId}/files/chunk/init`、`POST /api/kb/files/chunk/{sessionId}`（表单 `index` + `chunk`）、`GET /api/kb/files/chunk/{sessionId}`、`POST /api/kb/files/chunk/{sessionId}/complete`。会话表 `wgh_upload_session`，默认分片 5MB，范围 1MB~64MB，分片总数上限 2000。

| 编号 | 前置条件 | 操作步骤 | 预期结果 | 验证方式 |
|---|---|---|---|---|
| TC-T10-01 | 已登录且具备 `kb:write`；知识库存在 | `POST .../chunk/init`，`fileSize=12MB`，不传 `chunkSize` | 返回 `chunkSize=5MB`、`totalChunks=3`、`uploadUuid`、`uploadedChunks=[]`；会话状态 `UPLOADING` | 自动化单测（`ChunkUploadServiceTest`） |
| TC-T10-02 | 同上 | 分别传 `chunkSize=512KB`、`1MB`、`64MB`、`65MB` | 1MB/64MB 成功；512KB 与 65MB 返回 code `2001`“分片大小需在 1MB ~ 64MB 之间” | 自动化单测 |
| TC-T10-03 | 文件极大或分片过小 | 构造 `ceil(fileSize/chunkSize) > 2000` | 返回 code `2001`“分片数量超过上限 2000，请增大分片大小”；不创建会话 | 自动化单测 |
| TC-T10-04 | — | 分别提交空文件名、`fileSize=0`、负值 | code `2001`：“文件名不能为空”“文件大小必须大于 0”；不落库 | 自动化单测 |
| TC-T10-05 | 会话 `totalChunks=3` | 上传 `index=-1`、`index=3` | code `2001`“分片序号越界：x，有效范围 0 ~ 2”；不写对象、不改进度 | 自动化单测 |
| TC-T10-06 | 已上传 index=1 | 重复上传 index=1（不同内容） | 成功且幂等：对象被覆盖，`uploadedChunks` 集合仍只有 1 个元素；`uploadedCount` 不变 | 自动化单测 |
| TC-T10-07 | `totalChunks=5`，只上传 0、2 | `POST .../complete` | code `2001`“分片未上传完整，缺少序号：[1, 3, 4]”；会话仍 `UPLOADING`，不创建文件 | 自动化单测 |
| TC-T10-08 | 同一文件分别走单次上传与分片上传（3 片） | 比较两条文件记录的 `contentHash` | 两者 SHA-256 完全一致；分片顺序拼接正确，不因乱序上传错位 | 自动化单测 |
| TC-T10-09 | 会话已 `COMPLETED` 且有 `fileId` | 再次 `POST .../complete` | 直接返回既有文件（同一 `fileId`）；不重复入库、不重复切分/嵌入 | 自动化单测 |
| TC-T10-10 | 会话属于用户 A；B 为普通用户；C 未登录；D 为 ADMIN | 分别查询进度 / 上传分片 / 合并 | B 返回 code `3001`“上传会话不存在”（与不存在同文案，不泄露存在性）；C 经接口层 `@RequiresPermission` 返回 `1004`（未登录），Service 单测直调返回 `1001`；D 正常放行 | 自动化单测 + 接口 |
| TC-T10-11 | 会话已 `COMPLETED` | 再上传任一分片 | code `2001`“上传会话已结束，无法继续上传分片” | 自动化单测 |
| TC-T10-12 | 存在 `UPLOADING` 超 24h、`UPLOADING` 未超时、`COMPLETED` 三类会话 | 调用 `UploadSessionCleaner.cleanupExpired()` | 只删除超时 `UPLOADING` 会话及其分片对象；未超时与 `COMPLETED` 会话不动；单轮受 `batchSize`（默认 200）限制；单个失败不影响其他 | 自动化单测（`UploadSessionCleanerTest`） |
| TC-T10-13 | `wgh.upload.cleanup.enabled=false` / 默认 true | 启动应用 | false 时不注册清理任务并打日志；true 时按最小 1 小时阈值、最小 5 分钟间隔调度 | 自动化单测 + 配置断言 |
| TC-T10-14 | 前端运行、后端可用 | 选择大文件上传 → 中断 → 刷新后继续 → 完成 | 展示分片进度；刷新后按 `fileName+size` 复用会话续传；完成后文件出现在列表且可检索/预览 | 页面（需运行环境验证） |

---

### T11 模型提供商扩展（P3 / 小 / 批次 1）

**状态**：已完成（2026-11-11，commit `028f1bc`）。  
**已完成**：新增 `ProviderCatalog` / `ProviderCapability` 集中描述厂商能力，落地 Azure OpenAI、Ollama、智谱 GLM 三个 OpenAI 兼容工厂并注册；前端三个模型页按能力渲染提供商与默认端点。  
**保留**：计划中的 AWS Bedrock 未实现；各厂商真实连通性未验证（仅单测覆盖工厂构建与注册）；Azure 需填 v1 端点且模型名填部署名。

**涉及文件**
- `rag-llm/.../factory/ModelCacheManager.java`
- `rag-llm/.../entity/LlmModel.java`（provider 枚举/字段）
- `rag-llm/pom.xml`（Azure/Bedrock SDK 按需）
- `ChatModels.vue`、`EmbeddingModels.vue`、`RerankModels.vue`
- 测试：`ModelCacheManagerTest`

**改动**
- 新增 Azure OpenAI、AWS Bedrock、Ollama 原生适配；Ollama 也可先走 OpenAI 兼容端点，标注“原生”差异。
- 表结构：若 provider 是枚举则无；若用字典表则加种子数据。
- 配置：各 provider 密钥走环境变量/加密存储；前端表单增加字段。

**依赖**：无。  
**不能并行**：与 T01 仅构建锁竞争，文件不冲突。  
**验收**：每种 provider 至少一个“测试连接”成功；模型列表可创建、可调用；密钥不出现在响应中。

**测试用例**

| 编号 | 前置条件 | 操作步骤 | 预期结果 | 验证方式 |
|---|---|---|---|---|
| TC-T11-01 | — | 调用 `ProviderCatalog.normalize`：`custom`、` CUSTOM `、`azure`、`glm`、`openai` | 分别归一为 `CUSTOM_OPENAI`、`CUSTOM_OPENAI`、`AZURE_OPENAI`、`ZHIPU`、`OPENAI`；大小写与空格不敏感 | 自动化单测（`ModelCacheManagerTest`） |
| TC-T11-02 | — | 查询 `ProviderCatalog.all()` | 含 OPENAI / DASHSCOPE / QWEN / DEEPSEEK / AZURE_OPENAI / OLLAMA / ZHIPU / CUSTOM_OPENAI / CUSTOM_RERANK；DeepSeek `supportsEmbedding=false`，Ollama 与自定义默认端点正确 | 自动化单测 |
| TC-T11-03 | 模型 provider=`FOO` | `GET` 聊天模型（触发 `getChatModel`） | 抛 `不支持的LLM提供商: FOO`；不创建缓存实例 | 自动化单测 |
| TC-T11-04 | 嵌入模型 provider=`FOO` | `getEmbeddingModel` | 抛 `不支持的嵌入模型提供商: FOO` | 自动化单测 |
| TC-T11-05 | 嵌入模型 provider=`DEEPSEEK` | `getEmbeddingModel` | 抛 `不支持的嵌入模型提供商: DEEPSEEK`（能力目录拒绝） | 自动化单测 |
| TC-T11-06 | OPENAI 模型 `apiKey` 为空 | `getChatModel` | 抛 `模型提供商 OPENAI 缺少 API Key，请在模型管理中补充配置`；不发起外网调用 | 自动化单测 |
| TC-T11-07 | OLLAMA 模型 `apiKey` 为空、`baseUrl` 为空 | `getChatModel` | 不报缺 Key；使用默认 `http://localhost:11434/v1`；构建成功 | 自动化单测 |
| TC-T11-08 | AZURE_OPENAI 模型 `baseUrl` 为空 | 构建 | 抛“AZURE_OPENAI 需要配置 Base URL（形如 https://<resource>.openai.azure.com/openai/v1）” | 自动化单测 |
| TC-T11-09 | AZURE_OPENAI `baseUrl` 含 `/openai/deployments/` | 构建 | 抛“不支持旧版 /openai/deployments 路径，请填写 v1 端点” | 自动化单测 |
| TC-T11-10 | AZURE_OPENAI `baseUrl=https://res.openai.azure.com`（末尾带 `/`） | 构建 | 归一化为 `https://res.openai.azure.com/openai/v1`；使用 `api-key` 头而非 Bearer；模型名为部署名 | 自动化单测 |
| TC-T11-11 | ZHIPU 模型 `apiKey` 有值、`baseUrl` 为空 | 构建 | 使用默认 `https://open.bigmodel.cn/api/paas/v4`；对话/嵌入工厂均可构建 | 自动化单测 |
| TC-T11-12 | 历史 provider=`CUSTOM` | 构建对话/嵌入 | 归一为 `CUSTOM_OPENAI` 并复用自定义工厂；Key 可留空；不因旧值报“不支持” | 自动化单测 |
| TC-T11-13 | 前端运行，打开三个模型页 | 切换供应商查看端点与必填项 | 下拉含 Azure / Ollama / 智谱；选择后预填默认端点与能力提示；保存后列表/详情一致；`npm run build` 通过 | 页面（需运行环境验证） |

> 已知边界：AWS Bedrock 未实现；Azure / Ollama / 智谱的真实连通性未验证（仅覆盖工厂构建与注册，真实调用需运行环境 + 真实 Key/本地服务）；密钥不出现在响应中由现有模型 DTO 保证，未新增字段。

---

### T12 多实例与可观测（P2 / 大 / 批次 4）

**涉及文件**
- `rag-config/.../redis/**`、`rag-public-access/.../DefaultPublicAccessStore.java`（限流/分享会话改 Redis）
- `rag-chat/.../ChatStreamCancellation.java`（跨实例取消：Redis pub/sub 或 DB 标记）
- `application.yml`（actuator、metrics、tracing）
- `docker-compose.yml`（如需 Redis 强依赖）
- 新建 `deploy/k8s/**`、`scripts/backup-restore.sh`
- `rag-log/**`（traceId）

**改动**
- 限流、分享口令、取消信号、语义缓存元数据统一到共享存储；日志加 traceId；暴露 Prometheus；补 K8s 探针与备份恢复脚本。
- 表结构：无（或语义缓存索引）。
- 前端：无。
- 配置：Redis 连接、actuator 端点、采样率。

**依赖**：T02（yml）、T05（鉴权路径）。  
**不能并行**：`application.yml` 与 T10 串行；`DefaultPublicAccessStore` 与 T05 不冲突（T05 只改 filter）。  
**验收**：两实例同时运行，限流计数一致；实例 A 取消的流实例 B 能感知；`/actuator/prometheus` 有指标；备份脚本可恢复空库。

---

### T13 Agent 长期记忆 / 代码沙箱（P2，建议延后）

**不建议现在做**：需要跨会话用户记忆模型、权限、隐私与沙箱隔离，成本大且与当前“知识库问答”主诉求弱相关。若要做，单独立项，不与本轮并行。  
**触发条件**：出现“跨会话记住用户偏好/工单上下文”的明确需求，或客户要求执行用户代码。

---

## 4. 并行分批

### 批次 1（可立即并行，6 条，文件集互不重叠）

| 任务 | 独占文件 | 为什么可以并行 |
|---|---|---|
| T01 解析增强 | `rag-kb/.../parser/**`、`rag-kb/pom.xml`、新建 `ocr/**` | 不碰 `application.yml`、`RagProperties`、`KnowledgeFileService` |
| T02 检索参数治理 | `RagProperties`、`application.yml`（retrieve 段）、`QueryTransformService`、`RagRetrieveService` | 不碰 parser、controller、schema |
| T05 OpenAI 兼容 + SDK | 新建兼容 Controller、`ApiKeyAuthenticationFilter`、`SecurityConfig`、`sdk/python/**`、`PublicAccess.vue` | 新建类 + 独立前端页，不碰 Chat/KB/Workflow |
| T06a 评测指标 | `LlmJudgeService`、`RetrievalEvaluationService`、`RetrievalEvaluationController` | 不改 schema、不改 `RagProperties` |
| T07 审计补齐 | 5 个管理控制器（不含 Chat/KB/Workflow/OpenApi/ApiKey） | 明确避开所有热点控制器 |
| T11 模型提供商 | `ModelCacheManager`、`LlmModel`、`rag-llm/pom.xml`、三个模型页 | 不碰 KB/Chat/Workflow |

**批次 1 的门禁**：6 条都只需单模块编译/单测；构建必须持锁串行执行（见第 5 节），但**代码开发可同时进行**。

### 批次 2（依赖批次 1 的检索/接口交付，2 条并行 + 1 条紧随）

| 任务 | 依赖 | 说明 |
|---|---|---|
| T03 引用可核验 | T02（检索字段） | 与 T04a 文件不重叠 |
| T04a 资源归属后端 | 无 | 与 T03 并行；独占 schema 与 ChatController |
| T04b 资源归属前端 | T04a | 在 T04a 接口契约确定后开始，避免返工 |

**为什么这样切**：T03 与 T04a 是两条完全不同的文件链（`KnowledgeFileService/RagChatService` vs `entity/service/controller/schema`），可以真正并行；T04b 必须等 T04a 的字段与错误码。

### 批次 3（等批次 2 的共享文件释放）

| 任务 | 依赖 | 说明 |
|---|---|---|
| T02F 窗口级检索参数 UI | T02 + T04b | 唯一占用 `ChatWindows.vue`，必须在 T04b 之后 |
| T06b 评测数据集版本 + CI | T06a + T04a | 占用 `01-schema.sql` 与 `KBEvaluation.vue` |
| T08 URL 数据源同步 | T03 + T04a | 占用 `KnowledgeFileService` 与 schema；必须在 T03 之后 |
| T09 工作流表达式节点/调试 | T04a | 占用 `WorkflowController`；在 T04a 之后 |

**批次 3 内部**：T02F（前端）、T08（KB 后端+新前端页）、T09（工作流引擎+编辑器）、T06b（评测）四者文件不重叠，可并行；但 T08 与 T06b 都改 `01-schema.sql`，**建议 T06b 先合入 schema 变更，T08 再追加新表**，或由同一人合并该文件。

### 批次 4（收尾与规模化）

- T10 分片上传（依赖 T08）
- T12 多实例与可观测（依赖 T02/T05）
- T13 延后，不排期

**为什么放最后**：两者都触碰上传/运行链路与全局配置，且需要真实服务重启与多实例环境验证；在当前“服务不可重启、数据库不可碰”的约束下，本轮只能做代码与单测，收益无法闭环。

---

## 5. 工作区协同与冲突提醒（必读）

### 5.1 本轮其他会话

| 会话 | 影响 | 对本计划的约束 |
|---|---|---|
| 文档校订会话 | 正在改 `docs/review/rag-capability-assessment-2026-11-05.md` | 本计划文件是新增文件，不冲突；**不要编辑该评估报告**；若其结论变化，以代码为准回看第 1 节 |
| 服务重启/验证会话 | 可能重启 8080/9090、执行 `mvn package` | T01/T02/T05/T06a/T07/T11 的**接口级验收会被阻塞**；先做单测与代码审查，等对方释放服务窗口 |

### 5.2 构建锁与 jar 冲突

- 本机无 `flock`，Maven 构建统一用：`mkdir /tmp/wgh-maven-build.lockdir` → 执行 → `rmdir /tmp/wgh-maven-build.lockdir`；拿不到锁就等待，不要并行跑两个 `mvn`。
- **禁止在运行中的服务上执行 `mvn install/package/clean`**：正在加载的 fat jar 被覆盖会导致 `NoClassDefFoundError`。开发期只做 `mvn -pl <module> -am -DskipTests compile` 或定向 `-Dtest=... test`。
- 当前 8080 有运行中后端进程（历史 PID 20410），**不要 kill、不要重启**。需要重启才能验证的任务，本轮跳过，记录为“待服务窗口验证”。
- 不要执行全量 `mvn test`；收尾时如用户明确要求，再按模块跑。

### 5.3 共享文件与暂存区

- `rag-app/src/main/resources/application.yml`：其他会话可能已追加配置，**只追加新键，不覆盖、不回退**；T02/T10/T12 必须串行改这个文件。
- `rag-backend/sql/schema/01-schema.sql`：T04a/T06b/T08 串行；新迁移脚本命名 `YYYYMMDD_描述.sql`，必须幂等（`IF NOT EXISTS` / `ADD COLUMN IF NOT EXISTS`）。
- `ChatController.java`：窗口与工具接口都在这里，T04a 独占；T07 已排除；T05 不碰。
- `KnowledgeFileService.java`：T03 → T08 → T10 串行。
- `ChatWindows.vue`：T04b → T02F 串行。
- `WorkflowController.java`：T04a → T09 串行。
- 本轮不 commit；若后续提交，按“逐文件精确暂存”，先 `git status --short` 撤出不属于本任务的暂存文件。

### 5.4 数据库

- 不连接、不执行 `wgh_rag` / `wgh_rag_fresh`；SQL 只做静态审查与幂等性检查。
- 如需验证迁移，必须另建一次性数据库并在报告中注明，不得复用现有库。

---

## 6. 排期建议（按批次）

1. **第 1 批（立即）**：T01、T02、T05、T06a、T07、T11。开发并行；构建串行持锁；只做单模块编译与单测。
2. **第 2 批（T02 合入后）**：T03 与 T04a 并行；T04a 完成接口后 T04b 跟进。
3. **第 3 批（T03/T04a 合入后）**：T06b、T08、T09、T02F；schema 变更先 T06b 后 T08。
4. **第 4 批（服务窗口可用后）**：T10、T12；T13 延后。

**每批结束的统一门禁**
- 受影响模块 `mvn -pl <module> -am -DskipTests compile` 通过；定向单测通过。
- 前端 `npm run build` 通过（若改了页面）。
- 无新增全量测试失败；不执行全量 `mvn test`，除非用户明确要求。
- 服务重启后的端到端验证，统一交给服务窗口会话，不在本计划内自行重启。

---

## 7. 一页结论

- **能立即并行的 6 条**：T01 解析、T02 检索参数、T05 OpenAI 兼容、T06a 评测指标、T07 审计、T11 模型提供商；它们文件集互不重叠。
- **必须先串行的三处**：`01-schema.sql`（T04a → T06b → T08）、`KnowledgeFileService`（T03 → T08 → T10）、`ChatWindows.vue`（T04b → T02F）。
- **P0 的三条主线**：T04 资源归属（决定能不能多团队用）、T01 解析质量（决定入库准不准）、T03 引用可核验（决定用户信不信）。
- **最容易被低估的冲突**：`ChatController` 同时承载窗口与工具接口，T04a 必须先做归属过滤，T07/T05 都要绕开它。
- **本轮不要碰**：服务重启、数据库、全量 Maven 测试、`rag-capability-assessment-2026-11-05.md`。

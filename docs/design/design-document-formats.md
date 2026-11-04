# 更多文档格式解析设计（特性组 1）

对应 README §7.4 计划项 #5「更多文档格式」。目标是在不改变既有 PDF/DOC/DOCX/XLS/XLSX/CSV/TXT/MD 行为的前提下，补齐
PPTX / HTML(HTM) / JSON / EML，并统一元数据抽取、错误提示与解析大小上限。

> 实现状态：已完成（PPTX/HTML/JSON/EML 全部可用；图片 OCR 不在本次范围，见 §7 简化说明）。

---

## 1. 表结构

**无新增表、无 schema 变更。**

- 解析结果中的统一元数据 `documentMetadata` 仅随「切片预览」接口返回，不落库。
- 文档入库仍沿用既有表：`wgh_knowledge_file`（文件记录与状态）、`wgh_knowledge_file_version`（版本快照）、
  `wgh_document_chunk`（切片与向量）。
- 简化实现说明：元数据未持久化到 `wgh_knowledge_file`，如需按标题/页数筛选，后续可新增 JSONB 列或独立元数据表。

---

## 2. 接口清单

本特性组不新增对外接口，仅增强既有接口的返回内容与错误提示。

| 方法 | 路径 | 变化 | 说明 |
|------|------|------|------|
| POST | `/api/kb/{kbId}/files` | 支持新扩展名 | 上传 `pptx` / `html` / `htm` / `json` / `eml`，异步解析入库 |
| GET | `/api/kb/files/{fileId}/preview` | 新增 `documentMetadata` | 返回 `{title,pageCount,sheetCount,encoding,charCount}` 中可用的字段 |
| POST | `/api/kb/files/{fileId}/split` | 无变化 | 重新切片同样走统一解析与大小校验 |
| POST | `/api/kb/{kbId}/search` | 无变化 | 新格式切片后可直接检索命中 |

错误语义（均为业务错误，前端直接展示 message）：

| 场景 | 提示 |
|------|------|
| 扩展名未注册 | `不支持的文件格式: xxx` |
| 文件不存在/不可读 | `文件不存在或不可读: <path>` |
| 空文件 | `文件为空，无法解析: <type>` |
| 超过大小上限 | `文件过大（x.x MB），超过解析上限 N MB` |
| 文件损坏 | `<type> 文件解析失败（文件可能已损坏或格式不正确）: <原因>` |
| 缺少解析依赖 | `解析 <type> 需要依赖 <class>，请检查 rag-kb/pom.xml 中的解析依赖是否完整` |

---

## 3. 配置项

| Key | 默认值 | 说明 |
|-----|--------|------|
| `wgh.file.max-single-file-mb` | `50` | 单文件解析大小上限（MB），上传与重新切片均生效；与 `application.yml` 既有配置一致 |
| `spring.servlet.multipart.max-file-size` | Spring Boot 默认 `1MB`（本项目未覆盖） | 上传层限制；如需放宽大文件上传需同时调整该配置 |

> 简化说明：大小上限在解析层（`DocumentParserDispatcher`）统一校验；上传 HTTP 层仍受 Spring Multipart 限制，
> 两者是「与」关系，取更严格者。

---

## 4. 关键类与调用链路

### 4.1 类清单

| 类 | 位置 | 职责 |
|----|------|------|
| `DocumentParser` | `rag-kb/parser` | SPI：`supports(ext)` / `parse(File)` / 默认 `parseDetailed(File)` |
| `DocumentParserDispatcher` | `rag-kb/parser` | 按扩展名分发；统一存在性/空文件/大小上限校验与友好错误包装 |
| `ParsedDocument` | `rag-kb/parser` | record：`paragraphs,title,pageCount,sheetCount,encoding,charCount` + `metadata()` |
| `DocumentMetadataSupport` | `rag-kb/parser` | 标题推断、字符统计、编码探测（BOM / UTF-8 严格解码 / GBK 回退）、按编码读取 |
| `PptxParser` | `rag-kb/parser` | POI XSLF 遍历幻灯片，抽取文本框与表格，`pageCount=幻灯片数` |
| `HtmlParser` | `rag-kb/parser` | Jsoup 提取 `title` 与块级正文，移除 `script/style/noscript` |
| `JsonParser` | `rag-kb/parser` | Jackson 树模型扁平化 `path: value`，根数组逐元素一行 |
| `EmlParser` | `rag-kb/parser` | Jakarta Mail 解析邮件头与多部分正文，主题作为标题 |
| `PdfParser` | `rag-kb/parser` | 覆写 `parseDetailed`，返回真实页数 |
| `AbstractExcelParser` | `rag-kb/parser` | 覆写 `parseDetailed`，返回工作表数 |
| `PlainTextParser` / `CsvParser` | `rag-kb/parser` | 覆写 `parseDetailed`，返回实际识别编码 |

### 4.2 调用链路

```
POST /api/kb/{kbId}/files
  └─ KnowledgeFileService.upload            （保存原文件 + 落 wgh_knowledge_file，状态 PENDING）
      └─ processFileAsync                   （kb-file-processor 线程池）
          └─ DocumentParserDispatcher.parse(file, fileType)
              ├─ 扩展名 → 命中 DocumentParser.supports
              ├─ validateFile：存在 / 可读 / 非空 / ≤ max-single-file-mb
              ├─ parser.parseDetailed(file) → ParsedDocument
              │    ├─ PptxParser  (POI XSLF)
              │    ├─ HtmlParser  (Jsoup)
              │    ├─ JsonParser  (Jackson)
              │    └─ EmlParser   (Jakarta Mail)
              └─ 异常收敛：BusinessException 透传 / NoClassDefFoundError → 依赖缺失提示 / 其他 → 损坏提示
          └─ TextSplitter.splitDetailed(paragraphs, strategy, config, context)
              └─ EmbeddingModel.embed → pgvector（wgh_document_chunk / 向量表）
```

预览链路：

```
GET /api/kb/files/{fileId}/preview
  └─ KnowledgeFileService.previewSplit
      └─ DocumentParserDispatcher.parseDocument → ParsedDocument
      └─ result.documentMetadata = parsed.metadata()
```

---

## 5. 元数据口径

| 字段 | 来源 | 缺失时 |
|------|------|--------|
| `title` | HTML `<title>`；EML 主题；JSON `title/name/subject`；其余取首个非空段落（去除 `#/-/*` 前缀，截断 100 字） | 段落为空时省略 |
| `pageCount` | PDF 页数 / PPTX 幻灯片数 | 省略 |
| `sheetCount` | XLS/XLSX 工作表数 | 省略 |
| `encoding` | 文本类实际探测编码（UTF-8 / UTF-16LE / UTF-16BE / GBK）；二进制格式固定 `binary` | 省略 |
| `charCount` | 段落字符数总和 | 恒有值 |

编码探测策略：先看 BOM；无 BOM 时用严格 UTF-8 解码，失败回退 GBK；读取时去除 BOM。

---

## 6. 验收标准

| 编号 | 验收项 | 判定 |
|------|--------|------|
| A1 | 5 种新格式均可上传并解析出正文 | 上传后 `status=SUCCESS` 且 `chunkCount>0` |
| A2 | PPTX 返回幻灯片页数 | `documentMetadata.pageCount == 幻灯片数` |
| A3 | HTML 忽略脚本样式并返回 title | 正文不含 `alert(1)`，`title == <title>` |
| A4 | JSON 嵌套对象扁平化 | 出现 `spec.topK: 5`、`tags[0]: 检索` 形态 |
| A5 | EML 抽取主题/发件人/正文 | `title == 主题`，正文含发件人与正文文本 |
| A6 | GBK 文本不乱码 | `encoding == GBK` 且中文正确 |
| A7 | 损坏文件友好提示 | 抛 `BusinessException`，message 含「文件可能已损坏」 |
| A8 | 超限文件拒绝 | message 含「超过解析上限」 |
| A9 | 空文件拒绝 | message 含「文件为空」 |
| A10 | 上传 → 解析 → 切片 → 检索命中 | `DocumentIngestChainTest` 断言检索 top1 命中 |
| A11 | 未注册格式提示 | message 含「不支持的文件格式」 |
| A12 | 既有 8 种格式行为不回归 | `DocumentParserTest` 全绿 |

自动化落点：`rag-kb` 模块 `DocumentFormatParserTest`（8）、`DocumentIngestChainTest`（1）、
`DocumentParserDispatcherTest`（3）、`DocumentParserTest`（8），共 20 项。

---

## 7. 简化实现与已知限制（显式标注）

1. **无图片 OCR**：扫描版 PDF / 图片型 PPTX 仅能抽取文本层，图片内容不入库（图片 OCR 不在本次范围）。
2. **元数据不落库**：`documentMetadata` 仅在预览接口返回，列表页不展示页数/编码。
3. **PPTX 仅文本层**：只抽取文本框与表格文本，不解析 SmartArt、图表、备注页（备注页可后续扩展）。
4. **EML 跳过附件**：附件内容不解析，仅正文 `text/plain` / `text/html`。
5. **JSON 有上限**：扁平化最多 5000 条、深度 12 层，超出部分静默截断（避免超大 JSON 撑爆内存）。
6. **HTML 去重**：相邻重复文本会被去重，可能丢失刻意重复的列表项。
7. **上传层限制未放宽**：`spring.servlet.multipart.max-file-size` 仍为 Boot 默认值，需大文件时另行配置。
</content>

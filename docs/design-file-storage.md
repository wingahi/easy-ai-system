# 文件存储抽象（本地 / 阿里云 OSS）设计

> 范围：知识库文件上传、解析、预览、重切、删除所依赖的文件存储能力。
> 不包含：聊天图片（`ChatImageService`）、附件下载、前端组件。

## 1. 改造前盘点

### 1.1 上传链路中的本地 IO

| 位置 | 原实现 | 说明 |
|------|--------|------|
| `KnowledgeFileService#upload` | `Paths.get(uploadDir, kbId)` + `Files.createDirectories` + `kbDir.resolve(...)` + `Files.copy(...)` | 直接拼本地绝对/相对路径，并将 `targetPath.toString()` 写入 `wgh_knowledge_file.file_path` |
| `KnowledgeFileService#previewSplit` | `new File(file.getFilePath())` + `exists()` | 预览直接读本地路径 |
| `KnowledgeFileService#processFileAsync` | `new File(file.getFilePath())` | 异步解析直接读本地路径 |
| `KnowledgeFileService#delete` | 仅逻辑删除数据库记录 | 不清理磁盘对象 |
| `DocumentParserDispatcher#validateFile` | `File#exists/isFile/canRead/length` | 解析器入口只接受 `File`，并做存在性/空文件/大小校验 |
| 各 `DocumentParser` 实现 | `File` 入参（Tika/PDFBox/POI） | 解析器不直接接触存储层 |

### 1.2 `rag-config` 存储相关清点

改造前 `rag-config` **没有**任何文件存储实现或专用属性：

- 迁出（本次新建并已全部移入 `rag-storage`）：
  - `FileStorageService`、`StoredObject`、`StorageKeys`
  - `StorageProperties`（`wgh.rag.storage.*`）
  - `LocalFileStorageService`、`AliyunOssFileStorageService`
  - 对应单元测试
- 保留（非存储职责）：
  - `RagProperties`（检索 / 分割 / 上下文 / 语义缓存）
  - `PgVectorProperties`、`PgVectorConfig`
  - Redis 配置与端口抽象
- 说明：代码库中不存在 `wgh.rag.upload.*`；实际上传配置长期以 `@Value` 形式散落在业务模块：
  - `wgh.file.upload-dir`：原 `KnowledgeFileService`（已移除）与 `rag-chat/ChatImageService`（本次不动）
  - `wgh.file.max-single-file-mb`：`DocumentParserDispatcher`
- 结论：`rag-config` 迁移后不再包含存储实现和存储专用属性，仅保留原 RAG/PgVector/Redis 配置。

### 1.3 `FileVersionService` 快照与回滚

`FileVersionService` **不保存原文件**，快照只记录切分策略/配置/状态等元数据；回滚通过 `KnowledgeFileService#reSplit` 对当前存储对象重新切分并生成新版本。因此本次无需改变版本语义，只需保证 `reSplit` 读取文件走存储接口。

## 2. 模块与依赖

新建 `rag-storage`，承载存储接口、两种实现、配置与 key 规则：

```
rag-app
└── rag-controller
    ├── rag-chat ── rag-kb ──┬── rag-storage ── rag-common
    │                        └── rag-llm ── rag-config ── rag-common
    └── rag-config ── rag-common
```

- `rag-storage → rag-common`（`BusinessException`），不依赖 `rag-config`、`rag-kb`、`rag-data`；
- `rag-kb → rag-storage`；
- `rag-controller` 通过 `rag-chat → rag-kb → rag-storage` 传递依赖，无需显式声明（Controller 不直接使用存储 API）；
- `rag-config` 不依赖 `rag-storage`，无循环依赖。

## 3. 接口与 key 规则

### 3.1 接口

```java
public interface FileStorageService {
    StoredObject store(String key, InputStream in, long size, String contentType);
    InputStream openStream(String key);
    void delete(String key);
    String resolveUrl(String key);
    boolean exists(String key);
}
```

`StoredObject` 为 `record(String key, long size, String contentType)`，`key` 是逻辑 key，不含实现细节。

### 3.2 key 规则

```
kb/{kbId}/{fileUuid}/{sanitize(fileName)}
```

- `fileUuid` 每次上传独立生成，同名文件不会覆盖；
- `fileName` 仅保留 basename，并把 `\ / : * ? " < > |` 与空白替换为 `_`；
- key 不含 `..`，本地实现仍会做二次规范化与根目录校验，防路径穿越。

### 3.3 与 `wgh_knowledge_file.file_path` 的兼容映射

- 不新增列、不改表结构：`file_path` 继续存字符串。
- 新数据：存逻辑 key，例如 `kb/1/uuid/制度.pdf`。
- 历史数据：`./uploads/1/xxx.pdf` 这类路径由本地实现识别为“历史相对/绝对路径”，只要解析后仍位于 `base-dir` 下即可读取，无需迁移脚本。
- 切换到 OSS 后，历史 `file_path` 仍指向本地磁盘，OSS 实例无法读取；需要迁移对象或重新上传（见第 7 节）。

## 4. 两种实现

| 维度 | 本地 `LocalFileStorageService` | OSS `AliyunOssFileStorageService` |
|------|-------------------------------|-----------------------------------|
| 装配 | `wgh.rag.storage.type=local`（缺省） | `wgh.rag.storage.type=oss` |
| 写入 | 先写同目录 `.upload-*.part`，再 `ATOMIC_MOVE`（不支持时退化为 `REPLACE_EXISTING`） | 先 spool 到临时文件，再流式 `putObject`，失败按 `max-retries` 重试并清理残留对象 |
| 读取 | `Files.newInputStream` | `getObject` 返回内容流 |
| 删除 | `Files.deleteIfExists`（幂等） | `deleteObject`（幂等） |
| URL | `url-prefix + key`（仅逻辑地址，需静态服务配合） | `public-read=true` 返回公网直链；否则返回有效期可配的签名 URL |
| 安全 | 绝对路径/相对路径均做根目录校验；大小校验；空文件拒绝 | key 前缀隔离；大小校验；空文件拒绝；未配置时操作报错 |
| 配置缺失 | 目录创建失败仅 WARN，首次写入报错 | `@PostConstruct` 输出 WARN，应用照常启动，仅存储操作返回明确错误 |
| 内网 | 不适用 | `internal-endpoint` 配置后服务端读写走内网，签名/公网 URL 仍用 `endpoint` |

选型取舍：优先阿里云 OSS 而不是 S3 兼容协议，原因是本项目部署语境与现有依赖均以阿里云为主，`aliyun-sdk-oss:3.16.1` 已在本地仓库可解析；S3 兼容实现可在同一接口下另行扩展。

## 5. 配置键对照

| 迁移前 | 迁移后 | 说明 |
|--------|--------|------|
| `wgh.file.upload-dir`（仅 `KnowledgeFileService` 使用） | `wgh.rag.storage.local.base-dir`，默认值仍读 `WGH_UPLOAD_DIR` / `./uploads` | `ChatImageService` 仍使用 `wgh.file.upload-dir`，未改动 |
| `wgh.file.max-single-file-mb` | 保留不变（`DocumentParserDispatcher` 解析上限）；存储侧新增 `wgh.rag.storage.local.max-file-size-mb` / `oss.max-file-size-mb` | 解析与存储各自校验，默认均为 50 |
| 无 | `wgh.rag.storage.type` | `local`（默认）/ `oss` |
| 无 | `wgh.rag.storage.local.url-prefix` | 默认 `/uploads`，仅 `resolveUrl` 使用 |
| 无 | `wgh.rag.storage.oss.endpoint / internal-endpoint / bucket / access-key-id / access-key-secret` | OSS 必填（前四项 + endpoint），可全部由环境变量注入 |
| 无 | `wgh.rag.storage.oss.key-prefix` | 默认空，适合多业务共用 bucket |
| 无 | `wgh.rag.storage.oss.public-read / signed-url-expire-minutes` | 默认 `false` / 60 分钟 |
| 无 | `wgh.rag.storage.oss.max-retries` | 默认 3（含首次） |

环境变量对照：`WGH_STORAGE_TYPE`、`WGH_UPLOAD_DIR`、`WGH_STORAGE_URL_PREFIX`、`WGH_STORAGE_MAX_FILE_MB`、`WGH_OSS_ENDPOINT`、`WGH_OSS_INTERNAL_ENDPOINT`、`WGH_OSS_BUCKET`、`WGH_OSS_ACCESS_KEY_ID`、`WGH_OSS_ACCESS_KEY_SECRET`、`WGH_OSS_KEY_PREFIX`、`WGH_OSS_PUBLIC_READ`、`WGH_OSS_SIGNED_URL_EXPIRE_MINUTES`、`WGH_OSS_MAX_RETRIES`。

> 兼容说明：`wgh.rag.upload.*` 在本仓库从未存在，因此无需保留；若外部部署另有自定义键，可在 `StorageProperties` 上追加 `@DeprecatedConfigurationProperty` 兼容映射，但当前无此需求。

## 6. 业务接入

- `KnowledgeFileService#upload`：通过 `StorageKeys.kbKey` 生成 key，调用 `store`；入库失败时尽力清理对象。
- 预览/异步解析：通过 `openStream` 读出到临时文件（解析器只接受 `File`），解析结束 `finally` 清理临时文件；上层不再拼本地路径。
- 删除：数据库逻辑删除后调用 `delete`，失败仅 WARN（残留风险见第 8 节）。
- `reSplit` 复用同一异步处理逻辑，自动走存储接口。
- `FileVersionService` 不改：元数据快照 + 回滚重切语义保持不变。

## 7. 迁移与回滚

- 无 DDL：不新增列、不修改列类型。
- 本地 → OSS：
  1. 配置 `wgh.rag.storage.type=oss` 与 OSS 参数后重启；
  2. 历史文件仍指向本地 key/相对路径，OSS 实现无法读取，需要将 `base-dir` 下对象按原相对路径上传到 OSS（key 前缀需与 `key-prefix` 对齐），或重新上传并重建向量；
  3. 新上传文件直接写 OSS。
- OSS → 本地：同理反向迁移；或保持旧数据只读、新数据写本地（当前实现不支持跨后端回读，建议整体切换）。
- 回滚代码：切回 `wgh.rag.storage.type=local` 即可，无需改库；已写入 OSS 的对象不会被本地实现读取。

## 8. 已知限制与风险

1. **大文件分片上传**：当前 OSS 实现为单次流式 `putObject`，超大文件（>5GB 或弱网）建议后续接入分片上传；业务侧 `wgh.file.max-single-file-mb` 已限制默认 50MB。
2. **签名 URL 与鉴权**：`resolveUrl` 只是生成可访问地址，知识库预览接口仍受 JWT/RBAC 保护；若把签名 URL 暴露给前端，需评估有效期与越权分享风险，默认有效期 60 分钟。
3. **删除失败残留**：本地/OSS 删除失败只记录 WARN，数据库仍逻辑删除，可能产生孤儿对象；需要时可用离线清理脚本按 `file_path` 比对清理。
4. **跨后端回读不支持**：切换存储类型后旧对象不会自动迁移/回读（见第 7 节）。
5. **本地 `resolveUrl` 不是可用下载地址**：当前应用未挂载 `base-dir` 静态服务，返回值仅表达逻辑前缀。
6. **真实 OSS 未端到端验证**：本次只用 Mock OSS 客户端断言调用参数、重试与清理，没有真实凭证环境验证；上线前需在测试 bucket 做一次真实上传/下载/删除。

## 9. 验证记录

- 单元测试：`rag-storage` 18 项（本地 7 + key 4 + OSS 7）全部通过。
- 全量 `mvn test`：通过（详见交付结论）。
- 本地实测（`storage.type=local`）：上传 TXT/PDF → 解析 → 切片 → 检索命中 → 预览 → 删除，`file_path` 为 `kb/{kbId}/{uuid}/{fileName}`，删除后对象目录为空、数据库 `deleted=1`。
- OSS 空配置实测：`storage.type=oss` 且 OSS 全空时应用正常启动，WARN 提示缺少 `endpoint/bucket/accessKeyId/accessKeySecret`；上传返回明确业务错误。

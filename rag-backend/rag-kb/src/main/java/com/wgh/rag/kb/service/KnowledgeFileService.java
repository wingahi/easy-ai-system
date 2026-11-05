package com.wgh.rag.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.PageResult;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.storage.service.FileStorageService;
import com.wgh.rag.storage.service.StorageKeys;
import com.wgh.rag.storage.service.StoredObject;
import com.wgh.rag.kb.entity.KnowledgeBase;
import com.wgh.rag.kb.entity.KnowledgeFile;
import com.wgh.rag.kb.entity.KnowledgeFileVersion;
import com.wgh.rag.kb.mapper.KnowledgeFileMapper;
import com.wgh.rag.kb.mapper.KnowledgeFileVersionMapper;
import com.wgh.rag.kb.port.VectorStorePort;
import com.wgh.rag.kb.parser.DocumentParserDispatcher;
import com.wgh.rag.kb.parser.ParsedDocument;
import com.wgh.rag.kb.splitter.ChunkOutput;
import com.wgh.rag.kb.splitter.SplitContext;
import com.wgh.rag.kb.splitter.TextSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * 知识库文件管理服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeFileService {

    private final KnowledgeFileMapper fileMapper;
    private final KnowledgeFileVersionMapper versionMapper;
    private final KnowledgeBaseService kbService;
    private final DocumentParserDispatcher documentParser;
    private final TextSplitter textSplitter;
    private final VectorStorePort vectorStoreService;
    private final SemanticCacheService semanticCacheService;
    private final ObjectMapper objectMapper;
    private final FileStorageService fileStorageService;

    /**
     * 文件处理线程池：core=4, max=8, queue=50，避免裸 new Thread() 导致并发失控
     */
    private static final java.util.concurrent.ExecutorService FILE_PROCESSOR =
            new java.util.concurrent.ThreadPoolExecutor(
                    4, 8,
                    60L, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<>(50),
                    r -> {
                        Thread t = new Thread(r, "kb-file-processor");
                        t.setDaemon(true);
                        return t;
                    },
                    new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());

    /**
     * 上传文件
     */
    @Transactional
    public KnowledgeFile upload(Long kbId, MultipartFile multipartFile, 
                                 String splitStrategy, String splitConfig) {
        KnowledgeBase kb = kbService.getById(kbId);

        String originalName = multipartFile.getOriginalFilename();
        String fileType = getExtension(originalName);
        String storedKey = StorageKeys.kbKey(kbId, UUID.randomUUID().toString(), originalName);

        // 内容哈希用于同知识库去重：先算哈希再落存储，重复文件不产生存储对象与向量
        String contentHash;
        try (InputStream in = multipartFile.getInputStream()) {
            contentHash = sha256Hex(in);
        } catch (IOException e) {
            throw new BusinessException("文件读取失败: " + e.getMessage(), e);
        }
        KnowledgeFile duplicate = findDuplicateFile(kbId, contentHash);
        if (duplicate != null) {
            log.info("知识库 {} 已存在相同内容文件，跳过入库: existingFileId={}, hash={}",
                    kbId, duplicate.getId(), contentHash);
            return duplicate;
        }

        // 通过可插拔存储服务写入（本地/OSS 由配置决定），上层不拼接本地路径
        try (InputStream in = multipartFile.getInputStream()) {
            StoredObject stored = fileStorageService.store(
                    storedKey, in, multipartFile.getSize(), multipartFile.getContentType());
            storedKey = stored.key();
        } catch (IOException e) {
            throw new BusinessException("文件读取失败: " + e.getMessage(), e);
        }

        // 保存记录
        KnowledgeFile file = new KnowledgeFile();
        file.setKnowledgeBaseId(kbId);
        file.setFileName(originalName);
        file.setFileType(fileType);
        file.setFileSize(multipartFile.getSize());
        file.setFilePath(storedKey);
        file.setSplitStrategy(splitStrategy != null ? splitStrategy : "AUTO");
        file.setSplitConfig(splitConfig);
        file.setStatus("PENDING");
        file.setContentHash(contentHash);

        try {
            fileMapper.insert(file);
        } catch (RuntimeException e) {
            // 入库失败时清理已写入的对象，避免孤儿文件
            deleteStorageQuietly(storedKey);
            throw e;
        }

        // 异步处理
        processFileAsync(file, kb);

        return file;
    }

    /**
     * 按 ID 获取文件，不存在时统一抛 3001「文件不存在」。
     */
    public KnowledgeFile getById(Long fileId) {
        KnowledgeFile file = fileMapper.selectById(fileId);
        if (file == null) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "文件不存在");
        }
        return file;
    }

    /**
     * 重新分割文件
     */
    @Transactional
    public KnowledgeFile reSplit(Long fileId, String splitStrategy, String splitConfig) {
        KnowledgeFile file = getById(fileId);

        // 清理该文件的旧向量，避免重复入库
        KnowledgeBase kb = kbService.getById(file.getKnowledgeBaseId());
        vectorStoreService.deleteByFileId(kb.getCollectionName(), fileId);

        // 知识库内容已变更：清理引用该知识库的窗口语义缓存，避免继续返回旧答案
        semanticCacheService.invalidateByKbId(file.getKnowledgeBaseId());

        // 升级版本号 + 记录旧版本快照（回滚时按此版本的策略重切）
        Integer oldVersion = file.getVersion() == null ? 1 : file.getVersion();
        KnowledgeFileVersion snapshot = new KnowledgeFileVersion();
        snapshot.setFileId(fileId);
        snapshot.setVersion(oldVersion);
        snapshot.setChunkCount(file.getChunkCount());
        snapshot.setSplitStrategy(file.getSplitStrategy());
        snapshot.setSplitConfig(file.getSplitConfig());
        snapshot.setStatus("SUCCESS");
        versionMapper.insert(snapshot);

        file.setVersion(oldVersion + 1);
        file.setSplitStrategy(splitStrategy);
        file.setSplitConfig(splitConfig);
        file.setStatus("PENDING");
        file.setChunkCount(0);
        file.setErrorMessage(null);
        fileMapper.updateById(file);

        // 重新处理
        processFileAsync(file, kb);

        return file;
    }

    /**
     * 删除文件（逻辑删除 + 清理向量）
     */
    @Transactional
    public void delete(Long fileId) {
        KnowledgeFile file = getById(fileId);

        // 清理 pgvector 中该文件的向量
        KnowledgeBase kb = kbService.getById(file.getKnowledgeBaseId());
        if (kb != null) {
            vectorStoreService.deleteByFileId(kb.getCollectionName(), fileId);
        }

        // 知识库内容已变更：清理引用该知识库的窗口语义缓存
        semanticCacheService.invalidateByKbId(file.getKnowledgeBaseId());

        fileMapper.deleteById(fileId);

        // 逻辑删除已入库；对象存储清理为尽力而为，失败只记录 WARN（残留风险见设计文档）
        deleteStorageQuietly(file.getFilePath());

        kbService.updateCounts(file.getKnowledgeBaseId());
    }

    /**
     * 预览分割情况
     */
    public Map<String, Object> previewSplit(Long fileId) {
        KnowledgeFile file = getById(fileId);

        File parsedFile = materializeToTempFile(file.getFilePath(), file.getFileType());
        try {
            ParsedDocument parsed = documentParser.parseDocument(parsedFile, file.getFileType());
            List<String> paragraphs = parsed.paragraphs();
            TextSplitter.Strategy strategy = TextSplitter.Strategy.valueOf(file.getSplitStrategy());
            // 与异步入库链路一致：传入 embedding 模型上下文（SEMANTIC 需要）
            KnowledgeBase kb = kbService.getById(file.getKnowledgeBaseId());
            SplitContext context = new SplitContext(kb != null ? kb.getEmbeddingModelId() : null);
            List<ChunkOutput> chunks = textSplitter.splitDetailed(paragraphs, strategy, file.getSplitConfig(), context);

            Map<String, Object> result = new HashMap<>();
            result.put("fileName", file.getFileName());
            result.put("fileType", file.getFileType());
            result.put("paragraphCount", paragraphs.size());
            result.put("documentMetadata", parsed.metadata());
            result.put("chunkCount", chunks.size());

            // 返回前 100 个片段（含坐标信息）
            List<Map<String, Object>> chunkPreviews = new ArrayList<>();
            int previewSize = Math.min(chunks.size(), 100);
            for (int i = 0; i < previewSize; i++) {
                Map<String, Object> chunk = new HashMap<>();
                chunk.put("index", i);
                chunk.put("text", chunks.get(i).text());
                chunk.put("length", chunks.get(i).text().length());
                if (!chunks.get(i).attributes().isEmpty()) {
                    chunk.put("attributes", chunks.get(i).attributes());
                }
                chunkPreviews.add(chunk);
            }
            result.put("chunks", chunkPreviews);

            return result;
        } finally {
            deleteTempQuietly(parsedFile);
        }
    }

    /**
     * 列出知识库文件
     */
    public List<KnowledgeFile> listByKbId(Long kbId) {
        // kbId 不存在时明确返回业务码 3001，避免前端把空数组误判为“该知识库无文件”
        kbService.getById(kbId);
        return fileMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KnowledgeFile>()
                .eq(KnowledgeFile::getKnowledgeBaseId, kbId)
                .orderByDesc(KnowledgeFile::getCreateTime));
    }

    /**
     * 全量重建知识库向量。
     * <p>
     * 换 embedding 模型后由 {@link KbEmbeddingModelChangedEvent} 自动触发，也可通过管理接口手动触发。
     * 每个文件先清理旧向量（换模型时新表为空，此处为空操作），再提交到文件线程池异步重切/重嵌，
     * 调用方不阻塞；源文件始终从对象存储重新读取，不依赖任何旧向量。
     */
    public void rebuildAll(Long kbId) {
        KnowledgeBase kb = kbService.getById(kbId);
        List<KnowledgeFile> files = fileMapper.selectList(new LambdaQueryWrapper<KnowledgeFile>()
                .eq(KnowledgeFile::getKnowledgeBaseId, kbId));
        if (files.isEmpty()) {
            log.info("知识库 {} 无文件，跳过重建", kbId);
            return;
        }

        for (KnowledgeFile file : files) {
            vectorStoreService.deleteByFileId(kb.getCollectionName(), file.getId());
            file.setStatus("PENDING");
            file.setChunkCount(0);
            file.setErrorMessage(null);
            fileMapper.updateById(file);
        }
        kbService.updateCounts(kbId);
        for (KnowledgeFile file : files) {
            processFileAsync(file, kb);
        }
        log.info("知识库 {} 向量重建已提交: {} 个文件", kbId, files.size());
    }

    /**
     * embedding 模型切换后重放全部文件。
     * <p>
     * AFTER_COMMIT 保证知识库集合指针已提交到库，避免异步任务读到旧 collectionName；
     * 重建异常只记 ERROR（此时库内已是 PENDING 状态），可通过重建接口重新触发。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEmbeddingModelChanged(KbEmbeddingModelChangedEvent event) {
        log.info("知识库 {} embedding 模型变更（{} -> {}），开始全量重建",
                event.kbId(), event.oldModelId(), event.newModelId());
        try {
            rebuildAll(event.kbId());
        } catch (Exception e) {
            log.error("知识库 {} 重建提交失败，可通过重建接口手动触发", event.kbId(), e);
        }
    }

    // ============ 私有方法 ============

    /**
     * 同知识库内按内容哈希查重（逻辑删除行自动排除；FAILED 文件没有向量，允许重新上传）。
     */
    private KnowledgeFile findDuplicateFile(Long kbId, String contentHash) {
        List<KnowledgeFile> duplicates = fileMapper.selectList(new LambdaQueryWrapper<KnowledgeFile>()
                .eq(KnowledgeFile::getKnowledgeBaseId, kbId)
                .eq(KnowledgeFile::getContentHash, contentHash)
                .ne(KnowledgeFile::getStatus, "FAILED")
                .orderByAsc(KnowledgeFile::getId)
                .last("LIMIT 1"));
        return duplicates.isEmpty() ? null : duplicates.get(0);
    }

    /**
     * chunk 级去重：同一次切分内的重复文本 + 知识库已存在的 contentHash 都不重复嵌入。
     */
    List<TextSegment> dedupeSegments(List<TextSegment> segments, Set<String> existingHashes) {
        Set<String> seen = new HashSet<>(existingHashes);
        List<TextSegment> unique = new ArrayList<>(segments.size());
        for (TextSegment segment : segments) {
            if (seen.add(sha256Hex(segment.text()))) {
                unique.add(segment);
            }
        }
        return unique;
    }

    /** 文件级 SHA-256（流式计算，避免大文件整读进内存） */
    static String sha256Hex(InputStream in) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    /** 文本 / 分块 SHA-256 */
    static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    private String getExtension(String filename) {
        if (filename == null) return "";
        int dotIdx = filename.lastIndexOf('.');
        return dotIdx > 0 ? filename.substring(dotIdx + 1).toLowerCase() : "";
    }

    private void processFileAsync(KnowledgeFile file, KnowledgeBase kb) {
        // 使用线程池异步处理（避免裸 new Thread 导致并发失控）
        FILE_PROCESSOR.submit(() -> {
            File parsedFile = null;
            try {
                file.setStatus("PROCESSING");
                fileMapper.updateById(file);

                parsedFile = materializeToTempFile(file.getFilePath(), file.getFileType());
                List<String> paragraphs = documentParser.parse(parsedFile, file.getFileType());
                TextSplitter.Strategy strategy = TextSplitter.Strategy.valueOf(file.getSplitStrategy());
                // SEMANTIC 需要知识库绑定的 embedding 模型；STRUCTURED/SEMANTIC 会产出 chunk 属性
                List<ChunkOutput> chunks = textSplitter.splitDetailed(paragraphs, strategy, file.getSplitConfig(),
                        new SplitContext(kb.getEmbeddingModelId()));

                // 构造 TextSegment（附带 metadata 记录来源文件 + 切分属性如 headingPath/semanticBlockIndex）
                List<TextSegment> segments = new ArrayList<>();
                for (int i = 0; i < chunks.size(); i++) {
                    Map<String, Object> meta = buildChunkMetadata(file, kb, i, chunks.get(i).attributes());
                    meta.put("contentHash", sha256Hex(chunks.get(i).text()));
                    Metadata metadata = Metadata.from(meta);
                    segments.add(TextSegment.from(chunks.get(i).text(), metadata));
                }

                // 同一知识库内相同内容的 chunk 不重复嵌入（reSplit 会先删本文件旧向量，故不会自排除）
                Set<String> existingHashes = vectorStoreService.existingContentHashes(kb.getCollectionName());
                List<TextSegment> toInsert = dedupeSegments(segments, existingHashes);
                int skipped = segments.size() - toInsert.size();
                if (skipped > 0) {
                    log.info("文件 {} chunk 去重跳过 {} 条（知识库已存在或文件内重复）", file.getFileName(), skipped);
                }

                // 向量化并存入 pgvector
                if (!toInsert.isEmpty()) {
                    vectorStoreService.insertVectors(
                            kb.getCollectionName(),
                            toInsert,
                            kb.getEmbeddingModelId(),
                            kb.getDimension());
                }

                file.setChunkCount(toInsert.size());
                file.setStatus("SUCCESS");
                fileMapper.updateById(file);

                kbService.updateCounts(kb.getId());
                log.info("文件处理完成: {} -> {} chunks（去重跳过 {}）", file.getFileName(), toInsert.size(), skipped);

            } catch (Exception e) {
                log.error("文件处理失败: {}", file.getFileName(), e);
                file.setStatus("FAILED");
                file.setErrorMessage(e.getMessage());
                fileMapper.updateById(file);
            } finally {
                deleteTempQuietly(parsedFile);
            }
        });
    }

    /**
     * 构造 chunk 元数据：来源信息（fileId/fileName/kbId/kbName/chunkIndex）+ 切分属性。
     * kbName 供检索侧直接标注来源，避免每次引用再去查库。
     */
    Map<String, Object> buildChunkMetadata(KnowledgeFile file, KnowledgeBase kb, int chunkIndex,
                                           Map<String, Object> attributes) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("fileId", file.getId());
        meta.put("fileName", file.getFileName());
        meta.put("kbId", kb.getId());
        meta.put("kbName", kb.getName());
        meta.put("chunkIndex", chunkIndex);
        if (attributes != null) meta.putAll(attributes);
        return meta;
    }

    /**
     * 从存储后端读取对象到临时文件：现有解析器均面向 File（Tika/PDFBox/POI），
     * 由本服务通过 {@link FileStorageService#openStream} 物化，避免上层感知存储根目录。
     */
    private File materializeToTempFile(String storageKey, String fileType) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new BusinessException("文件存储 key 为空，无法读取");
        }
        String suffix = (fileType == null || fileType.isBlank()) ? ".bin" : "." + fileType;
        Path temp = null;
        try (InputStream in = fileStorageService.openStream(storageKey)) {
            temp = Files.createTempFile("wgh-kb-parse-", suffix);
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            return temp.toFile();
        } catch (IOException e) {
            deleteTempQuietly(temp == null ? null : temp.toFile());
            throw new BusinessException("读取存储文件失败: " + e.getMessage(), e);
        }
    }

    private void deleteTempQuietly(File temp) {
        if (temp == null) {
            return;
        }
        try {
            Files.deleteIfExists(temp.toPath());
        } catch (IOException e) {
            log.warn("清理解析临时文件失败: {} ({})", temp, e.getMessage());
        }
    }

    private void deleteStorageQuietly(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            return;
        }
        try {
            fileStorageService.delete(storageKey);
        } catch (Exception e) {
            log.warn("清理存储对象失败（可能残留）: {} ({})", storageKey, e.getMessage());
        }
    }
}

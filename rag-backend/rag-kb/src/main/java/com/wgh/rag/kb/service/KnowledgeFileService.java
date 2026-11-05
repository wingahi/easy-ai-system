package com.wgh.rag.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

    // ============ 私有方法 ============

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
                    Metadata metadata = Metadata.from(meta);
                    segments.add(TextSegment.from(chunks.get(i).text(), metadata));
                }

                // 向量化并存入 pgvector
                vectorStoreService.insertVectors(
                        kb.getCollectionName(), 
                        segments, 
                        kb.getEmbeddingModelId(), 
                        kb.getDimension());

                file.setChunkCount(chunks.size());
                file.setStatus("SUCCESS");
                fileMapper.updateById(file);

                kbService.updateCounts(kb.getId());
                log.info("文件处理完成: {} -> {} chunks", file.getFileName(), chunks.size());

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

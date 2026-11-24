package com.wgh.rag.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.PageResult;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.common.security.TenantContext;
import com.wgh.rag.data.scope.DataScopeGuard;
import com.wgh.rag.execution.api.ExecutionFacade;
import com.wgh.rag.execution.error.ExecutionCancelledException;
import com.wgh.rag.execution.error.LeaseLostException;
import com.wgh.rag.execution.model.ExecutionHandle;
import com.wgh.rag.execution.model.ExecutionLane;
import com.wgh.rag.execution.model.ExecutionRequest;
import com.wgh.rag.kb.job.KbJobTypes;
import com.wgh.rag.storage.service.FileStorageService;
import com.wgh.rag.storage.service.StorageKeys;
import com.wgh.rag.storage.service.StoredObject;
import com.wgh.rag.kb.entity.KnowledgeBase;
import com.wgh.rag.kb.entity.KnowledgeFile;
import com.wgh.rag.kb.entity.KnowledgeFileVersion;
import com.wgh.rag.kb.mapper.KnowledgeFileMapper;
import com.wgh.rag.kb.mapper.KnowledgeFileVersionMapper;
import com.wgh.rag.kb.port.VectorStorePort;
import com.wgh.rag.document.parser.DocumentParserDispatcher;
import com.wgh.rag.document.parser.ParsedDocument;
import com.wgh.rag.document.splitter.ChunkOutput;
import com.wgh.rag.document.splitter.SplitContext;
import com.wgh.rag.document.splitter.TextSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
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
     * 调度中心门面；关闭调度或尚未装配时为空。
     * <p>使用字段注入避免改变既有构造器签名（单测直接 new 时不注入，按本地线程池运行）。</p>
     */
    @Autowired(required = false)
    private ObjectProvider<ExecutionFacade> executionFacadeProvider;

    /**
     * chunk 事实层（wgh_kb_chunk）同步；字段注入避免改变既有构造器签名。
     * 事实层默认关闭时相关方法为空操作，入库链路行为不变。
     */
    @Autowired(required = false)
    private KbChunkService kbChunkService;

    /**
     * 关键词索引维护双路路由；字段注入避免改变既有构造器签名。
     * 未装配时（单测直接 new）只维护向量路，行为与改造前一致。
     */
    @Autowired(required = false)
    private KeywordIndexMaintenanceRouter keywordIndexMaintenance;

    /** 调度中心开关：开启时入库/重建走后台作业，关闭时保留原有本地线程池。 */
    @Value("${wgh.scheduler.enabled:true}")
    private boolean schedulerEnabled = true;

    /**
     * 文件处理线程池（仅作为调度中心关闭或提交失败时的本地兜底）：
     * core=4, max=8, queue=50，避免裸 new Thread() 导致并发失控
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
        requireWritableKb(kbId);

        String originalName = multipartFile.getOriginalFilename();
        String fileType = getExtension(originalName);

        // 内容哈希用于同知识库去重：先算哈希再落存储，重复文件不产生存储对象与向量
        String contentHash;
        try (InputStream in = multipartFile.getInputStream()) {
            contentHash = sha256Hex(in);
        } catch (IOException e) {
            throw new BusinessException("文件读取失败: " + e.getMessage(), e);
        }

        IngestOutcome outcome = storeAndRegister(kbId, originalName, fileType, multipartFile.getSize(),
                multipartFile.getContentType(), contentHash, multipartFile::getInputStream,
                splitStrategy, splitConfig);
        return outcome.file();
    }

    /**
     * 以可重复打开的输入流落存储并登记文件（单文件上传与分片合并共用）。
     * <p>
     * 调用方负责先算好 contentHash；命中同知识库重复内容时直接返回既有文件，不写存储、不重复入库。
     */
    @Transactional
    public IngestOutcome storeAndRegister(Long kbId, String originalName, String fileType, long size,
                                          String contentType, String contentHash, ContentSource content,
                                          String splitStrategy, String splitConfig) {
        KnowledgeBase kb = requireWritableKb(kbId);

        KnowledgeFile duplicate = findDuplicateFile(kbId, contentHash);
        if (duplicate != null) {
            log.info("知识库 {} 已存在相同内容文件，跳过入库: existingFileId={}, hash={}",
                    kbId, duplicate.getId(), contentHash);
            return new IngestOutcome(duplicate, true);
        }

        // 通过可插拔存储服务写入（本地/OSS 由配置决定），上层不拼接本地路径
        String storedKey = StorageKeys.kbKey(TenantContext.tenantIdOrDefault(), kbId,
                UUID.randomUUID().toString(), originalName);
        try (InputStream in = content.open()) {
            StoredObject stored = fileStorageService.store(storedKey, in, size, contentType);
            storedKey = stored.key();
        } catch (IOException e) {
            throw new BusinessException("文件读取失败: " + e.getMessage(), e);
        }

        // 保存记录
        KnowledgeFile file = new KnowledgeFile();
        file.setKnowledgeBaseId(kbId);
        file.setFileName(originalName);
        file.setFileType(fileType);
        file.setFileSize(size);
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

        return new IngestOutcome(file, false);
    }

    /**
     * 可重复打开的输入流来源（分片合并需要多次读取：先算哈希，再写存储）。
     */
    @FunctionalInterface
    public interface ContentSource {
        InputStream open() throws IOException;
    }

    /**
     * 以内存内容直接入库（URL 连接器等非上传来源复用）。
     * <p>
     * 与 {@link #upload} 共用同一套 content_hash 判重：同知识库内已存在相同内容时直接返回既有文件，
     * 不再写入存储对象、不重复切分/嵌入。
     */
    @Transactional
    public IngestOutcome ingestContent(Long kbId, String fileName, String fileType, byte[] content,
                                       String splitStrategy, String splitConfig) {
        KnowledgeBase kb = requireWritableKb(kbId);
        if (content == null || content.length == 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "入库内容为空");
        }

        String contentHash;
        try {
            contentHash = sha256Hex(new ByteArrayInputStream(content));
        } catch (IOException e) {
            throw new BusinessException("内容读取失败: " + e.getMessage(), e);
        }
        KnowledgeFile duplicate = findDuplicateFile(kbId, contentHash);
        if (duplicate != null) {
            log.info("知识库 {} 已存在相同内容，跳过入库: existingFileId={}, hash={}",
                    kbId, duplicate.getId(), contentHash);
            return new IngestOutcome(duplicate, true);
        }

        String storedKey = StorageKeys.kbKey(TenantContext.tenantIdOrDefault(), kbId,
                UUID.randomUUID().toString(), fileName);
        try (InputStream in = new ByteArrayInputStream(content)) {
            StoredObject stored = fileStorageService.store(
                    storedKey, in, content.length, "text/plain; charset=utf-8");
            storedKey = stored.key();
        } catch (IOException e) {
            throw new BusinessException("内容写入存储失败: " + e.getMessage(), e);
        }

        KnowledgeFile file = new KnowledgeFile();
        file.setKnowledgeBaseId(kbId);
        file.setFileName(fileName);
        file.setFileType(fileType);
        file.setFileSize((long) content.length);
        file.setFilePath(storedKey);
        file.setSplitStrategy(splitStrategy != null ? splitStrategy : "PARAGRAPH");
        file.setSplitConfig(splitConfig);
        file.setStatus("PENDING");
        file.setContentHash(contentHash);

        try {
            fileMapper.insert(file);
        } catch (RuntimeException e) {
            deleteStorageQuietly(storedKey);
            throw e;
        }

        processFileAsync(file, kb);
        return new IngestOutcome(file, false);
    }

    /**
     * 内存内容入库结果：{@code duplicate=true} 表示命中同知识库 content_hash 去重，未产生新文件。
     */
    public record IngestOutcome(KnowledgeFile file, boolean duplicate) {
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
        KnowledgeBase kb = requireWritableKb(file.getKnowledgeBaseId());
        vectorStoreService.deleteByFileId(kb.getCollectionName(), fileId);
        if (keywordIndexMaintenance != null) {
            keywordIndexMaintenance.deleteByFileId(kb.getCollectionName(), fileId);
        }
        if (kbChunkService != null) {
            kbChunkService.markFileDeleted(file.getKnowledgeBaseId(), fileId);
        }

        // 知识库内容已变更：清理引用该知识库的窗口语义缓存，避免继续返回旧答案
        semanticCacheService.invalidateByKbId(file.getKnowledgeBaseId());

        // 升级版本号 + 记录旧版本快照（回滚时按此版本的策略重切）
        // 快照必须反映旧版本的真实状态与 chunk 数，不能固定写成 SUCCESS
        Integer oldVersion = file.getVersion() == null ? 1 : file.getVersion();
        upsertVersionSnapshot(file, file.getStatus() == null ? "SUCCESS" : file.getStatus());

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
        KnowledgeBase kb = requireWritableKb(file.getKnowledgeBaseId());
        if (kb != null) {
            vectorStoreService.deleteByFileId(kb.getCollectionName(), fileId);
        }
        if (kbChunkService != null) {
            kbChunkService.markFileDeleted(file.getKnowledgeBaseId(), fileId);
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
        requireReadableKb(file.getKnowledgeBaseId());

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
        requireReadableKb(kbId);
        return fileMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KnowledgeFile>()
                .eq(KnowledgeFile::getKnowledgeBaseId, kbId)
                .orderByDesc(KnowledgeFile::getCreateTime));
    }

    /**
     * 显式触发全量重建（唯一实际执行入口，由 POST /api/kb/{id}/rebuild 调用）。
     * <p>
     * 抢占 RUNNING 后立刻返回，后台逐文件重切/重嵌并更新 rebuild_processed/rebuild_total；
     * 目标表是新建的，旧 collection 不动，成功后才切换指针并清理旧表。
     */
    public void rebuildAll(Long kbId) {
        requireWritableKb(kbId);
        KnowledgeBaseService.RebuildPlan plan = kbService.claimRebuild(kbId);
        submitRebuild(plan);
    }

    /**
     * 全量重建提交：调度中心开启时落后台作业（可跨实例领取、续租与重试），
     * 关闭或提交失败时回退原有本地线程池，保证功能不失效。
     */
    private void submitRebuild(KnowledgeBaseService.RebuildPlan plan) {
        Long kbId = plan.kb().getId();
        ExecutionFacade facade = executionFacade();
        if (facade != null) {
            try {
                ExecutionHandle handle = facade.submit(ExecutionRequest.builder(KbJobTypes.REBUILD, ExecutionLane.BACKGROUND)
                        .bizKey("kb-rebuild:" + kbId + ":" + plan.targetCollection())
                        .payload(rebuildPayload(plan))
                        .build());
                log.info("知识库重建已提交后台作业 kbId={} executionId={}", kbId, handle.executionId());
                return;
            } catch (RuntimeException e) {
                log.warn("知识库重建提交调度中心失败，回退本地线程池 kbId={} error={}", kbId, e.getMessage());
            }
        }
        FILE_PROCESSOR.submit(() -> runRebuild(plan));
    }

    /** 调度中心门面；关闭调度或未装配时返回 {@code null}，由调用方回退本地线程池。 */
    private ExecutionFacade executionFacade() {
        if (!schedulerEnabled || executionFacadeProvider == null) {
            return null;
        }
        return executionFacadeProvider.getIfAvailable();
    }

    private Map<String, Object> rebuildPayload(KnowledgeBaseService.RebuildPlan plan) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(KbJobTypes.PAYLOAD_KB_ID, String.valueOf(plan.kb().getId()));
        payload.put(KbJobTypes.PAYLOAD_TARGET_COLLECTION, plan.targetCollection());
        if (plan.targetModelId() != null) {
            payload.put(KbJobTypes.PAYLOAD_TARGET_MODEL_ID, String.valueOf(plan.targetModelId()));
        }
        if (plan.targetDimension() != null) {
            payload.put(KbJobTypes.PAYLOAD_TARGET_DIMENSION, String.valueOf(plan.targetDimension()));
        }
        return payload;
    }

    /**
     * 后台作业入口：按 kbId 重新读取知识库后执行重建。
     * <p>知识库已被删除时直接跳过，视为幂等完成。</p>
     */
    public void runRebuildJob(Long kbId, String targetCollection, Long targetModelId, Integer targetDimension,
                              Runnable heartbeat, Runnable cancellationCheck) {
        KnowledgeBase kb = kbService.getById(kbId);
        if (kb == null) {
            log.warn("知识库已不存在，跳过重建 kbId={}", kbId);
            return;
        }
        runRebuild(new KnowledgeBaseService.RebuildPlan(kb, targetCollection, targetModelId, targetDimension),
                heartbeat, cancellationCheck);
    }

    /**
     * 同步执行重建（单测直接调用）。任一文件失败即整体判失败：
     * 目标表被清理、collection/模型指针不切换，检索继续使用旧向量。
     */
    void runRebuild(KnowledgeBaseService.RebuildPlan plan) {
        runRebuild(plan, () -> {
        }, () -> {
        });
    }

    /**
     * 带执行生命周期回调的重建：按文件粒度续租与检查取消，供后台作业使用。
     */
    void runRebuild(KnowledgeBaseService.RebuildPlan plan, Runnable heartbeat, Runnable cancellationCheck) {
        KnowledgeBase kb = plan.kb();
        int total = plan.kb().getRebuildTotal() == null ? 0 : plan.kb().getRebuildTotal();
        int processed = 0;
        boolean success = true;
        try {
            List<KnowledgeFile> files = fileMapper.selectList(new LambdaQueryWrapper<KnowledgeFile>()
                    .eq(KnowledgeFile::getKnowledgeBaseId, kb.getId()));
            for (KnowledgeFile file : files) {
                cancellationCheck.run();
                heartbeat.run();
                processFile(file, kb, plan.targetCollection(), plan.targetModelId(), plan.targetDimension());
                processed++;
                kbService.updateRebuildProgress(kb.getId(), processed, total);
                log.info("知识库 {} 重建进度 {}/{}", kb.getId(), processed, total);
            }
        } catch (ExecutionCancelledException e) {
            // 取消是终态：把重建标记为失败供前端停止轮询，再交由调度层落 CANCELLED
            log.warn("知识库 {} 重建已取消（已完成 {}/{}）", kb.getId(), processed, total);
            kbService.finishRebuild(plan, processed, false);
            throw e;
        } catch (LeaseLostException e) {
            // 租约已丢失：另一实例可能已接管，停止写入，避免双写
            log.warn("知识库 {} 重建租约已丢失（已完成 {}/{}），停止写入", kb.getId(), processed, total);
            throw e;
        } catch (Exception e) {
            success = false;
            log.error("知识库 {} 重建失败（已完成 {}/{}），旧数据继续可用", kb.getId(), processed, total, e);
        }
        kbService.finishRebuild(plan, processed, success);
    }

    // ============ 私有方法 ============

    /**
     * 校验父知识库存在且当前用户可写。
     * <p>文件属于知识库子资源，所有写入口必须先校验父资源归属，避免只凭 kbId 越权写入。</p>
     */
    private KnowledgeBase requireWritableKb(Long kbId) {
        KnowledgeBase kb = kbService.getById(kbId);
        DataScopeGuard.assertWritable(kb.getCreateBy(), kb.getVisibility(), "知识库");
        return kb;
    }

    /** 校验父知识库存在且当前用户可读（他人 PRIVATE 按不存在处理）。 */
    private KnowledgeBase requireReadableKb(Long kbId) {
        KnowledgeBase kb = kbService.getById(kbId);
        DataScopeGuard.assertReadable(kb.getCreateBy(), kb.getVisibility(), "知识库");
        return kb;
    }

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

    /**
     * 异步处理文件：调度中心开启时提交后台作业，关闭或提交失败时回退本地线程池。
     * <p>提交动作放在事务提交之后，避免后台作业先于文件记录提交而读不到数据。</p>
     */
    private void processFileAsync(KnowledgeFile file, KnowledgeBase kb) {
        Long fileId = file.getId();
        Long kbId = kb.getId();
        int version = file.getVersion() == null ? 1 : file.getVersion();
        Runnable submit = () -> submitFileProcess(fileId, kbId, version);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submit.run();
                }
            });
        } else {
            submit.run();
        }
    }

    private void submitFileProcess(Long fileId, Long kbId, int version) {
        ExecutionFacade facade = executionFacade();
        if (facade != null) {
            try {
                ExecutionHandle handle = facade.submit(ExecutionRequest.builder(
                                KbJobTypes.FILE_PROCESS, ExecutionLane.BACKGROUND)
                        .bizKey("kb-file:" + fileId + ":" + version)
                        .payload(Map.of(KbJobTypes.PAYLOAD_FILE_ID, String.valueOf(fileId),
                                KbJobTypes.PAYLOAD_KB_ID, String.valueOf(kbId)))
                        .build());
                log.info("文件处理已提交后台作业 fileId={} executionId={}", fileId, handle.executionId());
                return;
            } catch (RuntimeException e) {
                log.warn("文件处理提交调度中心失败，回退本地线程池 fileId={} error={}", fileId, e.getMessage());
            }
        }
        // 本地兜底线程池不继承请求线程上下文：提交时冻结租户，执行前恢复，避免向量 metadata 租户标记丢失
        FILE_PROCESSOR.submit(com.wgh.rag.common.security.AuthContextPropagation.wrap(() -> {
            try {
                KnowledgeFile latest = fileMapper.selectById(fileId);
                KnowledgeBase kb = latest == null ? null : kbService.getById(kbId);
                if (latest == null || kb == null) {
                    log.warn("文件或知识库已不存在，跳过后台处理 fileId={} kbId={}", fileId, kbId);
                    return;
                }
                processFile(latest, kb, kb.getCollectionName(), kb.getEmbeddingModelId(), kb.getDimension());
            } catch (Exception e) {
                log.error("文件处理失败 fileId={}", fileId, e);
            }
        }));
    }

    /**
     * 后台作业入口：按 fileId 重新读取文件后执行解析/切分/向量化。
     * <p>文件已被删除时直接跳过，视为幂等完成。</p>
     */
    public void processFileJob(Long fileId, Long kbId) throws Exception {
        KnowledgeFile file = fileMapper.selectById(fileId);
        if (file == null) {
            log.warn("文件已不存在，跳过处理 fileId={}", fileId);
            return;
        }
        KnowledgeBase kb = kbService.getById(kbId);
        if (kb == null) {
            log.warn("知识库已不存在，跳过文件处理 fileId={} kbId={}", fileId, kbId);
            return;
        }
        processFile(file, kb, kb.getCollectionName(), kb.getEmbeddingModelId(), kb.getDimension());
    }

    /**
     * 记录或刷新指定版本的快照。
     * 上传/重切/重建都会走 processFile，解析完成（成功或失败）后以其真实 chunk 数与状态回填，
     * 避免版本历史出现「状态 SUCCESS、段落数 0」或当前版本在历史中缺失。
     */
    private void upsertVersionSnapshot(KnowledgeFile file, String status) {
        Integer version = file.getVersion() == null ? 1 : file.getVersion();
        KnowledgeFileVersion existing = versionMapper.selectOne(
                new LambdaQueryWrapper<KnowledgeFileVersion>()
                        .eq(KnowledgeFileVersion::getFileId, file.getId())
                        .eq(KnowledgeFileVersion::getVersion, version)
                        .last("LIMIT 1"));
        boolean create = existing == null;
        KnowledgeFileVersion snapshot = create ? new KnowledgeFileVersion() : existing;
        if (create) {
            snapshot.setFileId(file.getId());
            snapshot.setVersion(version);
        }
        snapshot.setChunkCount(file.getChunkCount() == null ? 0 : file.getChunkCount());
        snapshot.setSplitStrategy(file.getSplitStrategy());
        snapshot.setSplitConfig(file.getSplitConfig());
        snapshot.setStatus(status);
        if (create) {
            versionMapper.insert(snapshot);
        } else {
            versionMapper.updateById(snapshot);
        }
    }

    /**
     * 同步处理单个文件（上传/重切/重建共用），失败时标记 FAILED 并抛出，交由调用方决定整体语义。
     */
    private void processFile(KnowledgeFile file, KnowledgeBase kb, String collection,
                             Long embeddingModelId, Integer dimension) throws Exception {
        File parsedFile = null;
        try {
            file.setStatus("PROCESSING");
            fileMapper.updateById(file);

            parsedFile = materializeToTempFile(file.getFilePath(), file.getFileType());
            List<String> paragraphs = documentParser.parse(parsedFile, file.getFileType());
            TextSplitter.Strategy strategy = TextSplitter.Strategy.valueOf(file.getSplitStrategy());
            // SEMANTIC 需要知识库绑定的 embedding 模型；STRUCTURED/SEMANTIC 会产出 chunk 属性
            List<ChunkOutput> chunks = textSplitter.splitDetailed(paragraphs, strategy, file.getSplitConfig(),
                    new SplitContext(embeddingModelId));

            // 构造 TextSegment（附带 metadata 记录来源文件 + 切分属性如 headingPath/semanticBlockIndex）
            // charStart/charEnd 为 chunk 在归一化全文中的字符区间，供前端原文定位；找不到时退化为游标位置
            String documentText = String.join("\n", paragraphs);
            int searchFrom = 0;
            List<TextSegment> segments = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                ChunkOutput chunk = chunks.get(i);
                int charStart = documentText.indexOf(chunk.text(), searchFrom);
                if (charStart < 0) {
                    charStart = Math.min(searchFrom, documentText.length());
                }
                int charEnd = Math.min(charStart + chunk.text().length(), documentText.length());
                Map<String, Object> meta = buildChunkMetadata(file, kb, i, chunk.attributes(),
                        charStart, charEnd, chunk.text());
                meta.put("contentHash", sha256Hex(chunks.get(i).text()));
                Metadata metadata = Metadata.from(meta);
                segments.add(TextSegment.from(chunks.get(i).text(), metadata));
                searchFrom = charStart + 1;
            }

            // 同一知识库内相同内容的 chunk 不重复嵌入（目标表为新建表，重建时不会自排除）
            Set<String> existingHashes = vectorStoreService.existingContentHashes(collection);
            List<TextSegment> toInsert = dedupeSegments(segments, existingHashes);
            int skipped = segments.size() - toInsert.size();
            if (skipped > 0) {
                log.info("文件 {} chunk 去重跳过 {} 条（知识库已存在或文件内重复）", file.getFileName(), skipped);
            }

            // 事实层双写：先落原文与元数据（PENDING），再写向量并回填 embedding_id
            if (!toInsert.isEmpty() && kbChunkService != null) {
                kbChunkService.replaceFileChunks(kb.getId(), file.getId(), toInsert, embeddingModelId, dimension);
            }
            // 向量化并存入 pgvector
            if (!toInsert.isEmpty()) {
                List<String> embeddingIds = vectorStoreService.insertVectors(
                        collection, toInsert, embeddingModelId, dimension);
                if (kbChunkService != null && !embeddingIds.isEmpty()) {
                    kbChunkService.markSynced(kb.getId(), file.getId(), toInsert, embeddingIds);
                }
            }

            file.setChunkCount(toInsert.size());
            file.setStatus("SUCCESS");
            fileMapper.updateById(file);
            // 解析完成后回填当前版本的快照，版本历史的段落数才与文件实际 chunk 数一致
            upsertVersionSnapshot(file, "SUCCESS");

            kbService.updateCounts(kb.getId());
            log.info("文件处理完成: {} -> {} chunks（去重跳过 {}）", file.getFileName(), toInsert.size(), skipped);

        } catch (Exception e) {
            log.error("文件处理失败: {}", file.getFileName(), e);
            file.setStatus("FAILED");
            file.setErrorMessage(e.getMessage());
            fileMapper.updateById(file);
            upsertVersionSnapshot(file, "FAILED");
            throw e;
        } finally {
            deleteTempQuietly(parsedFile);
        }
    }

    /**
     * 构造 chunk 元数据：来源信息（fileId/fileName/kbId/kbName/chunkIndex）+ 切分属性 + 原文定位。
     * kbName 供检索侧直接标注来源，避免每次引用再去查库；charStart/charEnd/snippet 供前端展开原文。
     * pageNo 仅在切分属性提供时写入（当前解析器不产出页级映射）。
     */
    Map<String, Object> buildChunkMetadata(KnowledgeFile file, KnowledgeBase kb, int chunkIndex,
                                           Map<String, Object> attributes, int charStart, int charEnd,
                                           String chunkText) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("fileId", file.getId());
        meta.put("fileName", file.getFileName());
        meta.put("kbId", kb.getId());
        // 租户编码随向量落 metadata：检索编排层据此做租户断言，事实层/重建也据此回填 tenant_id
        meta.put("tenantId", TenantContext.tenantIdOrDefault());
        meta.put("kbName", kb.getName());
        meta.put("chunkIndex", chunkIndex);
        meta.put("charStart", charStart);
        meta.put("charEnd", charEnd);
        meta.put("snippet", snippetOf(chunkText));
        if (attributes != null) meta.putAll(attributes);
        return meta;
    }

    /** chunk 文本前 120 字（压缩空白），用于引用卡片预览。 */
    private String snippetOf(String chunkText) {
        if (chunkText == null) {
            return null;
        }
        String text = chunkText.replaceAll("\\s+", " ").trim();
        return text.length() <= 120 ? text : text.substring(0, 120);
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

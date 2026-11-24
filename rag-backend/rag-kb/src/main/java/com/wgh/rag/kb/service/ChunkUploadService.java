package com.wgh.rag.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.common.security.TenantContext;
import com.wgh.rag.data.scope.DataScopeContext;
import com.wgh.rag.data.scope.DataScopeGuard;
import com.wgh.rag.kb.entity.KnowledgeFile;
import com.wgh.rag.kb.entity.UploadSession;
import com.wgh.rag.kb.mapper.UploadSessionMapper;
import com.wgh.rag.storage.service.FileStorageService;
import com.wgh.rag.storage.service.StorageKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 大文件分片上传与断点续传。
 * <p>
 * 分片按对象写入存储（{@code kb/{kbId}/{uploadUuid}/parts/{index}.part}），会话进度记录在
 * {@code wgh_upload_session.uploaded_chunks}；合并时按顺序拼接分片、复用 {@link KnowledgeFileService}
 * 的去重与入库链路，合并成功后清理分片对象。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkUploadService {

    /** 默认分片大小：5 MB */
    public static final int DEFAULT_CHUNK_SIZE = 5 * 1024 * 1024;

    /** 分片大小下限：1 MB，避免产生过多小对象 */
    private static final int MIN_CHUNK_SIZE = 1024 * 1024;

    /** 分片大小上限：64 MB */
    private static final int MAX_CHUNK_SIZE = 64 * 1024 * 1024;

    /** 分片总数上限，防止异常参数产生大量对象 */
    private static final int MAX_TOTAL_CHUNKS = 2000;

    private static final String STATUS_UPLOADING = "UPLOADING";
    private static final String STATUS_COMPLETED = "COMPLETED";

    private final UploadSessionMapper sessionMapper;
    private final KnowledgeBaseService kbService;
    private final KnowledgeFileService fileService;
    private final FileStorageService fileStorageService;
    private final ObjectMapper objectMapper;

    /**
     * 初始化上传会话；同名文件重复初始化会返回新的会话（不自动续传，由前端按 fileName+size 自行复用）。
     */
    public InitResult init(Long kbId, String fileName, long fileSize, Integer chunkSize,
                           String splitStrategy, String splitConfig) {
        com.wgh.rag.kb.entity.KnowledgeBase kb = kbService.getById(kbId);
        // 分片初始化属于写入入口，必须校验父知识库可写
        DataScopeGuard.assertWritable(kb.getCreateBy(), kb.getVisibility(), "知识库");
        if (fileName == null || fileName.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "文件名不能为空");
        }
        if (fileSize <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "文件大小必须大于 0");
        }
        int size = chunkSize == null ? DEFAULT_CHUNK_SIZE : chunkSize;
        if (size < MIN_CHUNK_SIZE || size > MAX_CHUNK_SIZE) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "分片大小需在 1MB ~ 64MB 之间");
        }
        long total = (fileSize + size - 1) / size;
        if (total > MAX_TOTAL_CHUNKS) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "分片数量超过上限 " + MAX_TOTAL_CHUNKS + "，请增大分片大小");
        }

        UploadSession session = new UploadSession();
        session.setKnowledgeBaseId(kbId);
        // 分片存储按租户前缀隔离；写入时冻结，后续续传/合并不再依赖线程上下文
        session.setTenantId(TenantContext.tenantIdOrDefault());
        session.setUploadUuid(UUID.randomUUID().toString());
        session.setFileName(fileName);
        session.setFileType(extensionOf(fileName));
        session.setFileSize(fileSize);
        session.setChunkSize(size);
        session.setTotalChunks((int) total);
        session.setUploadedChunks("[]");
        session.setStatus(STATUS_UPLOADING);
        session.setSplitStrategy(splitStrategy != null ? splitStrategy : "PARAGRAPH");
        session.setSplitConfig(splitConfig);
        sessionMapper.insert(session);
        log.info("分片上传会话创建: sessionId={}, kbId={}, file={}, size={}, chunks={}",
                session.getId(), kbId, fileName, fileSize, total);

        return new InitResult(session.getId(), session.getUploadUuid(), size, (int) total, List.of());
    }

    /**
     * 上传单个分片。重复上传同一序号幂等：覆盖对象并保持进度集合不变。
     */
    @Transactional
    public UploadChunkResult uploadChunk(Long sessionId, int index, MultipartFile chunk) {
        UploadSession session = requireSession(sessionId);
        if (!STATUS_UPLOADING.equals(session.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "上传会话已结束，无法继续上传分片");
        }
        if (index < 0 || index >= session.getTotalChunks()) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "分片序号越界：" + index + "，有效范围 0 ~ " + (session.getTotalChunks() - 1));
        }
        if (chunk == null || chunk.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "分片内容为空");
        }

        String key = StorageKeys.chunkKey(session.getTenantId(),
                session.getKnowledgeBaseId(), session.getUploadUuid(), index);
        try (InputStream in = chunk.getInputStream()) {
            fileStorageService.store(key, in, chunk.getSize(), null);
        } catch (IOException e) {
            throw new BusinessException("分片读取失败: " + e.getMessage(), e);
        }

        Set<Integer> uploaded = parseUploaded(session.getUploadedChunks());
        uploaded.add(index);
        session.setUploadedChunks(writeUploaded(uploaded));
        sessionMapper.updateById(session);
        log.debug("分片上传完成: sessionId={}, index={}, uploaded={}/{}",
                sessionId, index, uploaded.size(), session.getTotalChunks());

        return new UploadChunkResult(index, uploaded.size(), session.getTotalChunks());
    }

    /**
     * 合并分片并入库；会话已完成时直接返回既有文件（幂等）。
     */
    @Transactional
    public KnowledgeFile complete(Long sessionId) {
        UploadSession session = requireSession(sessionId);
        if (STATUS_COMPLETED.equals(session.getStatus())) {
            if (session.getFileId() == null) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "上传会话已完成但缺少文件记录");
            }
            return fileService.getById(session.getFileId());
        }

        Set<Integer> uploaded = parseUploaded(session.getUploadedChunks());
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < session.getTotalChunks(); i++) {
            if (!uploaded.contains(i)) {
                missing.add(i);
            }
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "分片未上传完整，缺少序号：" + missing.subList(0, Math.min(missing.size(), 10)));
        }

        String contentHash;
        try (InputStream in = chunkStream(session)) {
            contentHash = sha256Hex(in);
        } catch (IOException e) {
            throw new BusinessException("分片合并失败: " + e.getMessage(), e);
        }

        KnowledgeFileService.IngestOutcome outcome = fileService.storeAndRegister(
                session.getKnowledgeBaseId(), session.getFileName(), session.getFileType(),
                session.getFileSize(), null, contentHash, () -> chunkStream(session),
                session.getSplitStrategy(), session.getSplitConfig());

        session.setStatus(STATUS_COMPLETED);
        session.setFileId(outcome.file().getId());
        sessionMapper.updateById(session);

        cleanupChunks(session);
        if (outcome.duplicate()) {
            log.info("分片合并命中内容去重: sessionId={}, existingFileId={}", sessionId, outcome.file().getId());
        } else {
            log.info("分片合并入库完成: sessionId={}, fileId={}, chunks={}",
                    sessionId, outcome.file().getId(), session.getTotalChunks());
        }
        return outcome.file();
    }

    /**
     * 查询会话进度，供前端刷新后续传。
     */
    @Transactional(readOnly = true)
    public Progress progress(Long sessionId) {
        UploadSession session = requireSession(sessionId);
        Set<Integer> uploaded = parseUploaded(session.getUploadedChunks());
        return new Progress(session.getId(), session.getFileName(), session.getFileSize(),
                session.getTotalChunks(), session.getStatus(), uploaded, session.getFileId());
    }

    private UploadSession requireSession(Long sessionId) {
        // 归属校验：匿名一律拒绝；非本人且非 ADMIN 按“不存在”处理，避免泄露会话存在性
        DataScopeContext.Scope user = DataScopeContext.resolveUser();
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "未授权访问");
        }
        UploadSession session = sessionMapper.selectById(sessionId);
        if (session == null || !(user.admin() || user.username().equals(session.getCreateBy()))) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "上传会话不存在");
        }
        return session;
    }

    /**
     * 按序号惰性打开分片流的拼接流：SequenceInputStream 会在读尽后关闭前一个分片流。
     */
    private InputStream chunkStream(UploadSession session) {
        return new SequenceInputStream(new Enumeration<InputStream>() {
            private int index = 0;

            @Override
            public boolean hasMoreElements() {
                return index < session.getTotalChunks();
            }

            @Override
            public InputStream nextElement() {
                String key = StorageKeys.chunkKey(session.getTenantId(),
                        session.getKnowledgeBaseId(), session.getUploadUuid(), index++);
                return fileStorageService.openStream(key);
            }
        });
    }

    private void cleanupChunks(UploadSession session) {
        for (int i = 0; i < session.getTotalChunks(); i++) {
            String key = StorageKeys.chunkKey(session.getTenantId(),
                    session.getKnowledgeBaseId(), session.getUploadUuid(), i);
            try {
                fileStorageService.delete(key);
            } catch (RuntimeException e) {
                // 分片清理失败不影响入库结果，留待后续人工/定时清理
                log.warn("分片对象清理失败: key={}, error={}", key, e.getMessage());
            }
        }
    }

    private Set<Integer> parseUploaded(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashSet<>();
        }
        try {
            Set<Integer> set = new LinkedHashSet<>();
            for (var node : objectMapper.readTree(json)) {
                set.add(node.asInt());
            }
            return set;
        } catch (IOException e) {
            log.warn("上传会话分片进度解析失败，按未上传处理: {}", e.getMessage());
            return new LinkedHashSet<>();
        }
    }

    private String writeUploaded(Set<Integer> uploaded) {
        try {
            return objectMapper.writeValueAsString(uploaded);
        } catch (IOException e) {
            throw new BusinessException("分片进度序列化失败: " + e.getMessage(), e);
        }
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 && dot < fileName.length() - 1
                ? fileName.substring(dot + 1).toUpperCase()
                : "UNKNOWN";
    }

    private static String sha256Hex(InputStream in) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            StringBuilder hex = new StringBuilder(digest.getDigestLength() * 2);
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 不可用", e);
        }
    }

    /** 初始化结果 */
    public record InitResult(Long sessionId, String uploadUuid, int chunkSize,
                             int totalChunks, List<Integer> uploadedChunks) {
    }

    /** 单个分片上传结果 */
    public record UploadChunkResult(int index, int uploadedCount, int totalChunks) {
    }

    /** 会话进度 */
    public record Progress(Long sessionId, String fileName, Long fileSize, int totalChunks,
                           String status, Set<Integer> uploadedChunks, Long fileId) {
    }
}

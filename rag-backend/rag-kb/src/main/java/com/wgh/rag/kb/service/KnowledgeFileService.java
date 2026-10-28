package com.wgh.rag.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.PageResult;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.kb.entity.KnowledgeBase;
import com.wgh.rag.kb.entity.KnowledgeFile;
import com.wgh.rag.kb.mapper.KnowledgeFileMapper;
import com.wgh.rag.kb.parser.DocumentParserDispatcher;
import com.wgh.rag.kb.splitter.TextSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
    private final KnowledgeBaseService kbService;
    private final DocumentParserDispatcher documentParser;
    private final TextSplitter textSplitter;
    private final VectorStoreService vectorStoreService;
    private final ObjectMapper objectMapper;

    @Value("${wgh.file.upload-dir:/tmp/wgh-rag/uploads}")
    private String uploadDir;

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
        
        // 确保上传目录存在
        Path kbDir = Paths.get(uploadDir, String.valueOf(kbId));
        try {
            Files.createDirectories(kbDir);
        } catch (IOException e) {
            throw new BusinessException("创建上传目录失败: " + e.getMessage());
        }

        // 保存文件
        String originalName = multipartFile.getOriginalFilename();
        String fileType = getExtension(originalName);
        String storedName = System.currentTimeMillis() + "_" + originalName;
        Path targetPath = kbDir.resolve(storedName);
        
        try {
            Files.copy(multipartFile.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new BusinessException("文件保存失败: " + e.getMessage());
        }

        // 保存记录
        KnowledgeFile file = new KnowledgeFile();
        file.setKnowledgeBaseId(kbId);
        file.setFileName(originalName);
        file.setFileType(fileType);
        file.setFileSize(multipartFile.getSize());
        file.setFilePath(targetPath.toString());
        file.setSplitStrategy(splitStrategy != null ? splitStrategy : "AUTO");
        file.setSplitConfig(splitConfig);
        file.setStatus("PENDING");
        
        fileMapper.insert(file);
        
        // 异步处理
        processFileAsync(file, kb);
        
        return file;
    }

    /**
     * 重新分割文件
     */
    @Transactional
    public KnowledgeFile reSplit(Long fileId, String splitStrategy, String splitConfig) {
        KnowledgeFile file = fileMapper.selectById(fileId);
        if (file == null) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "文件不存在");
        }

        // 清理该文件的旧向量，避免重复入库
        KnowledgeBase kb = kbService.getById(file.getKnowledgeBaseId());
        vectorStoreService.deleteByFileId(kb.getCollectionName(), fileId);

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
        KnowledgeFile file = fileMapper.selectById(fileId);
        if (file == null) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "文件不存在");
        }

        // 清理 pgvector 中该文件的向量
        KnowledgeBase kb = kbService.getById(file.getKnowledgeBaseId());
        if (kb != null) {
            vectorStoreService.deleteByFileId(kb.getCollectionName(), fileId);
        }

        fileMapper.deleteById(fileId);

        kbService.updateCounts(file.getKnowledgeBaseId());
    }

    /**
     * 预览分割情况
     */
    public Map<String, Object> previewSplit(Long fileId) {
        KnowledgeFile file = fileMapper.selectById(fileId);
        if (file == null) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "文件不存在");
        }
        
        File parsedFile = new File(file.getFilePath());
        if (!parsedFile.exists()) {
            throw new BusinessException("文件不存在于磁盘: " + file.getFilePath());
        }
        
        List<String> paragraphs = documentParser.parse(parsedFile, file.getFileType());
        TextSplitter.Strategy strategy = TextSplitter.Strategy.valueOf(file.getSplitStrategy());
        List<String> chunks = textSplitter.split(paragraphs, strategy, file.getSplitConfig());
        
        Map<String, Object> result = new HashMap<>();
        result.put("fileName", file.getFileName());
        result.put("fileType", file.getFileType());
        result.put("paragraphCount", paragraphs.size());
        result.put("chunkCount", chunks.size());
        
        // 返回前 100 个片段（含坐标信息）
        List<Map<String, Object>> chunkPreviews = new ArrayList<>();
        int previewSize = Math.min(chunks.size(), 100);
        for (int i = 0; i < previewSize; i++) {
            Map<String, Object> chunk = new HashMap<>();
            chunk.put("index", i);
            chunk.put("text", chunks.get(i));
            chunk.put("length", chunks.get(i).length());
            chunkPreviews.add(chunk);
        }
        result.put("chunks", chunkPreviews);
        
        return result;
    }

    /**
     * 列出知识库文件
     */
    public List<KnowledgeFile> listByKbId(Long kbId) {
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
            try {
                file.setStatus("PROCESSING");
                fileMapper.updateById(file);

                File parsedFile = new File(file.getFilePath());
                List<String> paragraphs = documentParser.parse(parsedFile, file.getFileType());
                TextSplitter.Strategy strategy = TextSplitter.Strategy.valueOf(file.getSplitStrategy());
                List<String> chunks = textSplitter.split(paragraphs, strategy, file.getSplitConfig());

                // 构造 TextSegment（附带 metadata 记录来源文件）
                List<TextSegment> segments = new ArrayList<>();
                for (int i = 0; i < chunks.size(); i++) {
                    Map<String, Object> meta = new HashMap<>();
                    meta.put("fileId", file.getId());
                    meta.put("fileName", file.getFileName());
                    meta.put("kbId", kb.getId());
                    meta.put("chunkIndex", i);
                    Metadata metadata = Metadata.from(meta);
                    segments.add(TextSegment.from(chunks.get(i), metadata));
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
            }
        });
    }
}

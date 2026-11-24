package com.wgh.rag.kb.connector;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.data.scope.DataScopeContext;
import com.wgh.rag.data.scope.DataScopeGuard;
import com.wgh.rag.kb.entity.KbConnector;
import com.wgh.rag.kb.entity.KbConnectorRun;
import com.wgh.rag.kb.entity.KnowledgeBase;
import com.wgh.rag.kb.entity.KnowledgeFile;
import com.wgh.rag.kb.mapper.KbConnectorMapper;
import com.wgh.rag.kb.mapper.KbConnectorRunMapper;
import com.wgh.rag.kb.service.KnowledgeBaseService;
import com.wgh.rag.kb.service.KnowledgeFileService;
import com.wgh.rag.common.net.SsrfBlockedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * URL 数据源连接器服务：配置 CRUD + 单次同步 + 运行记录。
 *
 * <p>同步语义（最小版）：
 * <ul>
 *   <li>抓取正文后交给 {@link KnowledgeFileService#ingestContent} 入库，复用 content_hash 判重；
 *   内容未变命中既有文件 → UNCHANGED，不重复切分/嵌入；</li>
 *   <li>内容变化 → 新建文件并逻辑删除该连接器上一版文件，知识库中只保留最新正文；</li>
 *   <li>源页面返回 404/410 → 逻辑删除对应文件（REMOVED）；</li>
 *   <li>抓取失败/SSRF 拒绝/正文为空 → FAILED，仅记录，不影响已有文件。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorService {

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_UNCHANGED = "UNCHANGED";
    public static final String STATUS_REMOVED = "REMOVED";
    public static final String STATUS_FAILED = "FAILED";

    private final KbConnectorMapper connectorMapper;
    private final KbConnectorRunMapper runMapper;
    private final KnowledgeBaseService kbService;
    private final KnowledgeFileService fileService;
    private final UrlConnector urlConnector;
    private final ConnectorProperties properties;

    /** 正在同步的连接器 ID，避免手动同步与定时任务并发写同一连接器 */
    private final Set<Long> syncing = ConcurrentHashMap.newKeySet();

    // ==================== CRUD ====================

    @Transactional(readOnly = true)
    public List<KbConnector> list(Long kbId) {
        kbService.getVisible(kbId);
        LambdaQueryWrapper<KbConnector> wrapper = new LambdaQueryWrapper<KbConnector>()
                .eq(KbConnector::getKnowledgeBaseId, kbId)
                .orderByDesc(KbConnector::getId);
        // 连接器无独立可见性：非管理员只看自己创建的，避免在共享知识库里泄露他人数据源配置
        DataScopeContext.Scope user = DataScopeContext.resolveUser();
        if (user != null && !user.admin()) {
            wrapper.eq(KbConnector::getCreateBy, user.username());
        }
        return connectorMapper.selectList(wrapper);
    }

    @Transactional
    public KbConnector create(Long kbId, ConnectorPayload payload) {
        KnowledgeBase kb = requireWritableKb(kbId);
        KbConnector connector = new KbConnector();
        connector.setKnowledgeBaseId(kb.getId());
        applyPayload(connector, payload);
        connectorMapper.insert(connector);
        return connector;
    }

    @Transactional
    public KbConnector update(Long kbId, Long id, ConnectorPayload payload) {
        requireWritableKb(kbId);
        KbConnector connector = getOwnedForWrite(kbId, id);
        applyPayload(connector, payload);
        connectorMapper.updateById(connector);
        return connector;
    }

    @Transactional
    public void delete(Long kbId, Long id) {
        requireWritableKb(kbId);
        getOwnedForWrite(kbId, id);
        // 运行记录保留归档，便于追溯历史同步结果
        connectorMapper.deleteById(id);
    }

    @Transactional(readOnly = true)
    public List<KbConnectorRun> listRuns(Long kbId, Long id, Integer limit) {
        kbService.getVisible(kbId);
        getOwnedForRead(kbId, id);
        int size = limit == null || limit <= 0 ? 20 : Math.min(limit, 100);
        return runMapper.selectList(new LambdaQueryWrapper<KbConnectorRun>()
                .eq(KbConnectorRun::getConnectorId, id)
                .orderByDesc(KbConnectorRun::getId)
                .last("LIMIT " + size));
    }

    // ==================== 同步 ====================

    /**
     * 手动触发一次同步。
     */
    @Transactional
    public KbConnectorRun sync(Long kbId, Long id) {
        requireWritableKb(kbId);
        KbConnector connector = getOwnedForWrite(kbId, id);
        return sync(connector.getId());
    }

    /**
     * 按连接器 ID 同步（手动与定时任务共用），同一连接器同一时刻只允许一个同步。
     */
    @Transactional
    public KbConnectorRun sync(Long connectorId) {
        KbConnector connector = connectorMapper.selectById(connectorId);
        if (connector == null) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "连接器不存在");
        }
        if (!syncing.add(connectorId)) {
            throw new BusinessException(ResultCode.OPERATION_NOT_ALLOWED, "该连接器正在同步中，请稍后再试");
        }
        try {
            return runSync(connector);
        } finally {
            syncing.remove(connectorId);
        }
    }

    /** 定时任务入口：并发中直接跳过，不抛异常打断整轮调度。 */
    @Transactional
    public void syncScheduled(Long connectorId) {
        if (!syncing.add(connectorId)) {
            log.debug("连接器 {} 正在同步，跳过本轮定时任务", connectorId);
            return;
        }
        try {
            runSync(connectorMapper.selectById(connectorId));
        } catch (Exception e) {
            log.warn("连接器 {} 定时同步异常: {}", connectorId, e.getMessage());
        } finally {
            syncing.remove(connectorId);
        }
    }

    private KbConnectorRun runSync(KbConnector connector) {
        if (connector == null) {
            return null;
        }
        LocalDateTime startedAt = LocalDateTime.now();
        long start = System.currentTimeMillis();
        KbConnectorRun run = new KbConnectorRun();
        run.setConnectorId(connector.getId());
        run.setStartedAt(startedAt);

        Integer httpStatus = null;
        try {
            UrlConnector.FetchedPage page = urlConnector.fetch(connector.getUrl());
            httpStatus = page.status();

            if (page.status() == 404 || page.status() == 410) {
                Long oldFileId = connector.getLastFileId();
                deleteFileQuietly(oldFileId);
                connector.setLastFileId(null);
                connector.setLastContentHash(null);
                mark(connector, STATUS_REMOVED, null);
                run.setStatus(STATUS_REMOVED);
                run.setMessage("源页面不存在（HTTP " + page.status() + "），已逻辑删除对应文件"
                        + (oldFileId == null ? "" : " " + oldFileId));
            } else if (page.status() >= 200 && page.status() < 300) {
                if (page.text() == null || page.text().isBlank()) {
                    throw new BusinessException(ResultCode.FILE_PARSE_FAILED, "抓取成功但正文为空，未入库");
                }
                String contentHash = sha256Hex(page.text());
                String fileName = buildFileName(page.title(), connector.getUrl());
                KnowledgeFileService.IngestOutcome outcome = fileService.ingestContent(
                        connector.getKnowledgeBaseId(), fileName, "txt",
                        page.contentBytes(), connector.getSplitStrategy(), connector.getSplitConfig());

                KnowledgeFile file = outcome.file();
                Long newFileId = file.getId();
                Long oldFileId = connector.getLastFileId();
                boolean replaced = oldFileId != null && !oldFileId.equals(newFileId);
                if (replaced) {
                    // 内容变化产生新版本；或命中其他来源的同内容文件，清理本连接器旧文件，避免知识库内重复
                    deleteFileQuietly(oldFileId);
                }

                connector.setLastFileId(newFileId);
                connector.setLastContentHash(contentHash);
                run.setFileId(newFileId);
                run.setContentHash(contentHash);
                run.setChunkCount(file.getChunkCount());

                if (outcome.duplicate()) {
                    mark(connector, STATUS_UNCHANGED, null);
                    run.setStatus(STATUS_UNCHANGED);
                    run.setMessage("内容未变化，复用已有文件 " + newFileId);
                } else {
                    mark(connector, STATUS_SUCCESS, null);
                    run.setStatus(STATUS_SUCCESS);
                    run.setMessage("已入库新文件 " + newFileId + (replaced ? "，旧文件 " + oldFileId + " 已逻辑删除" : ""));
                }
            } else {
                throw new BusinessException("抓取返回 HTTP " + page.status());
            }
        } catch (Exception e) {
            log.warn("连接器 {} 同步失败: {}", connector.getId(), e.getMessage());
            mark(connector, STATUS_FAILED, e.getMessage());
            run.setStatus(STATUS_FAILED);
            run.setMessage(e.getMessage());
        }

        run.setHttpStatus(httpStatus);
        run.setFinishedAt(LocalDateTime.now());
        run.setDurationMs(System.currentTimeMillis() - start);
        connector.setLastSyncTime(run.getFinishedAt());
        connectorMapper.updateById(connector);
        runMapper.insert(run);
        log.info("连接器 {} 同步完成: status={}, http={}, fileId={}, {}ms",
                connector.getId(), run.getStatus(), httpStatus, run.getFileId(), run.getDurationMs());
        return run;
    }

    // ==================== 内部工具 ====================

    private KnowledgeBase requireWritableKb(Long kbId) {
        KnowledgeBase kb = kbService.getVisible(kbId);
        DataScopeGuard.assertWritable(kb.getCreateBy(), kb.getVisibility(), "知识库");
        return kb;
    }

    private KbConnector getOwnedForRead(Long kbId, Long id) {
        KbConnector connector = load(kbId, id);
        DataScopeGuard.assertReadable(connector.getCreateBy(), null, "连接器");
        return connector;
    }

    private KbConnector getOwnedForWrite(Long kbId, Long id) {
        KbConnector connector = load(kbId, id);
        DataScopeGuard.assertWritable(connector.getCreateBy(), null, "连接器");
        return connector;
    }

    private KbConnector load(Long kbId, Long id) {
        KbConnector connector = connectorMapper.selectById(id);
        if (connector == null || !kbId.equals(connector.getKnowledgeBaseId())) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "连接器不存在");
        }
        return connector;
    }

    private void applyPayload(KbConnector connector, ConnectorPayload payload) {
        if (payload == null) {
            throw new BusinessException(ResultCode.PARAM_MISSING, "连接器配置不能为空");
        }
        String name = trimToNull(payload.name());
        if (name == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "连接器名称不能为空");
        }
        String url = trimToNull(payload.url());
        if (url == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "URL 不能为空");
        }
        try {
            urlConnector.validateUrl(url);
        } catch (SsrfBlockedException e) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "URL 不可抓取: " + e.getMessage());
        }

        connector.setName(name);
        connector.setUrl(url);
        if (payload.splitStrategy() != null && !payload.splitStrategy().isBlank()) {
            connector.setSplitStrategy(payload.splitStrategy().trim().toUpperCase());
        } else if (connector.getSplitStrategy() == null) {
            connector.setSplitStrategy("PARAGRAPH");
        }
        connector.setSplitConfig(payload.splitConfig());
        if (payload.syncIntervalMinutes() != null) {
            connector.setSyncIntervalMinutes(Math.max(0, payload.syncIntervalMinutes()));
        } else if (connector.getSyncIntervalMinutes() == null) {
            connector.setSyncIntervalMinutes(properties.getDefaultSyncIntervalMinutes());
        }
        if (payload.enabled() != null) {
            connector.setEnabled(payload.enabled() != 0 ? 1 : 0);
        } else if (connector.getEnabled() == null) {
            connector.setEnabled(0);
        }
    }

    private void mark(KbConnector connector, String status, String error) {
        connector.setLastStatus(status);
        connector.setLastError(error);
    }

    private void deleteFileQuietly(Long fileId) {
        if (fileId == null) {
            return;
        }
        try {
            fileService.delete(fileId);
        } catch (Exception e) {
            log.warn("清理连接器旧文件失败（可能已被删除）: fileId={}, {}", fileId, e.getMessage());
        }
    }

    private String buildFileName(String title, String url) {
        String base = trimToNull(title);
        if (base == null) {
            base = url;
        }
        String safe = base.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        if (safe.length() > 80) {
            safe = safe.substring(0, 80);
        }
        if (safe.isBlank()) {
            safe = "page";
        }
        return safe.endsWith(".txt") ? safe : safe + ".txt";
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    /**
     * 创建/更新连接器的入参（只允许配置字段，last_* 运行态字段由服务端维护）。
     */
    public record ConnectorPayload(String name, String url, String splitStrategy, String splitConfig,
                                   Integer syncIntervalMinutes, Integer enabled) {
    }
}

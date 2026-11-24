package com.wgh.rag.kb.storage;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.metrics.RagMetrics;
import com.wgh.rag.common.security.TenantContext;
import com.wgh.rag.kb.entity.KnowledgeFile;
import com.wgh.rag.kb.entity.UploadSession;
import com.wgh.rag.kb.mapper.KnowledgeFileMapper;
import com.wgh.rag.kb.mapper.UploadSessionMapper;
import com.wgh.rag.storage.properties.StorageProperties;
import com.wgh.rag.storage.service.FileStorageService;
import com.wgh.rag.storage.service.StorageInventoryService;
import com.wgh.rag.storage.service.StorageKeys;
import com.wgh.rag.storage.service.StorageObjectInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 孤儿对象扫描与清理。
 * <p>
 * “引用”判定口径：
 * <ul>
 *   <li>{@code wgh_knowledge_file.file_path}（逻辑删除行自动排除）；</li>
 *   <li>{@code wgh_upload_session} 中状态为 {@code UPLOADING} 的会话分片
 *       （{@code kb/{kbId}/{uploadUuid}/parts/{index}.part}）。</li>
 * </ul>
 * 存储中存在、但不在上述集合里的对象视为孤儿。默认只报告；仅在显式传入
 * {@code delete=true}（或配置 {@code wgh.rag.storage.orphan-cleanup.delete=true}）时才删除。
 * <p>
 * 多租户安全约束：扫描范围固定为 {@code StorageKeys.tenantPrefix(tenantId)}，只列举该前缀下的对象；
 * 归属无法确认的对象一律跳过、不删除；不做全库扫描推断。
 * <p>
 * 为降低与上传/入库并发导致的误删风险：先列举存储、再查询数据库引用；删除时还有
 * {@code min-age-minutes} 保护期，刚写入的对象不会被删除。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageOrphanCleanupService {

    private static final String STATUS_UPLOADING = "UPLOADING";

    private final FileStorageService storage;
    private final StorageInventoryService inventory;
    private final KnowledgeFileMapper fileMapper;
    private final UploadSessionMapper sessionMapper;
    private final RagMetrics metrics;
    private final StorageProperties storageProperties;
    private final StorageOrphanCleanupProperties properties;

    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 执行一次扫描（当前租户）。
     *
     * @param delete true 时删除已过保护期的孤儿对象；false 时只输出报告
     */
    public StorageOrphanReport scan(boolean delete) {
        return scan(TenantContext.tenantIdOrDefault(), delete);
    }

    /**
     * 执行一次租户范围内的扫描。
     * <p><b>安全约束</b>：只列举 {@code StorageKeys.tenantPrefix(tenantId)} 前缀下的对象；
     * 归属无法确认（key 不在该前缀/无法解析租户/元数据缺失）的对象一律跳过、不删除；
     * 不做全库扫描推断，也不删除其他租户前缀下的对象。</p>
     *
     * @param tenantId 扫描范围租户，不能为空
     * @param delete   true 时删除已过保护期的孤儿对象；false 时只输出报告
     */
    public StorageOrphanReport scan(String tenantId, boolean delete) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new BusinessException("孤儿扫描必须指定租户，禁止全局推断");
        }
        final String scopePrefix;
        try {
            scopePrefix = StorageKeys.tenantPrefix(tenantId);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("非法租户编码: " + tenantId);
        }
        if (!running.compareAndSet(false, true)) {
            throw new BusinessException("已有孤儿对象扫描任务正在执行");
        }
        long started = System.currentTimeMillis();
        try {
            // 先列举存储再查引用：扫描期间新上传的对象不会出现在本次列表中，避免被误判为孤儿
            List<StorageObjectInfo> listedObjects = inventory.listByPrefix(scopePrefix);
            List<StorageObjectInfo> objects = new ArrayList<>(listedObjects.size());
            int skippedUnattributable = 0;
            for (StorageObjectInfo object : listedObjects) {
                if (StorageKeys.belongsTo(object.key(), tenantId)) {
                    objects.add(object);
                } else {
                    // 归属无法确认：跳过，绝不进入删除候选
                    skippedUnattributable++;
                }
            }
            ReferenceKeys references = collectReferencedKeys(tenantId, scopePrefix);

            Set<String> listed = new HashSet<>(objects.size());
            for (StorageObjectInfo object : objects) {
                listed.add(object.key());
            }

            List<StorageObjectInfo> orphans = new ArrayList<>();
            long orphanBytes = 0;
            for (StorageObjectInfo object : objects) {
                if (!references.all().contains(object.key())) {
                    orphans.add(object);
                    orphanBytes += Math.max(0, object.size());
                }
            }

            int missing = 0;
            for (String key : references.canonical()) {
                if (!listed.contains(key)) {
                    missing++;
                }
            }

            int deleted = 0;
            int deleteFailed = 0;
            int skippedYoung = 0;
            int logLimit = Math.max(0, properties.getMaxReportEntries());

            // 「记录存在但存储对象缺失」：按知识文件逐条给出可定位的明细（受报告上限约束）
            List<StorageOrphanReport.MissingReference> missingDetails = new ArrayList<>();
            Set<String> seenMissingKeys = new HashSet<>();
            for (ReferenceCandidate candidate : references.fileCandidates()) {
                if (!listed.contains(candidate.storageKey()) && seenMissingKeys.add(candidate.storageKey())) {
                    missingDetails.add(new StorageOrphanReport.MissingReference(
                            candidate.fileId(), candidate.fileName(), candidate.storageKey()));
                }
            }
            int missingLogLimit = Math.max(1, logLimit);
            for (int i = 0; i < missingDetails.size() && i < missingLogLimit; i++) {
                StorageOrphanReport.MissingReference detail = missingDetails.get(i);
                log.warn("知识文件记录存在但存储对象缺失: fileId={}, fileName={}, key={}",
                        detail.fileId(), detail.fileName(), detail.storageKey());
            }
            if (missingDetails.size() > missingLogLimit) {
                log.warn("另有 {} 个知识文件缺失存储对象未逐条打印，详见指标 wgh.storage.orphans(missing)",
                        missingDetails.size() - missingLogLimit);
            }
            if (delete) {
                long now = System.currentTimeMillis();
                long minAgeMillis = Math.max(0, properties.getMinAgeMinutes()) * 60_000L;
                for (StorageObjectInfo orphan : orphans) {
                    if (!StorageKeys.belongsTo(orphan.key(), tenantId)) {
                        // 双保险：归属无法确认时跳过，绝不删除
                        skippedUnattributable++;
                        log.warn("孤儿对象归属无法确认，跳过删除: {}", orphan.key());
                        continue;
                    }
                    if (!isDeletable(orphan, now, minAgeMillis)) {
                        skippedYoung++;
                        log.info("孤儿对象未过删除保护期，跳过: {} (lastModified={})",
                                orphan.key(), orphan.lastModifiedMillis());
                        continue;
                    }
                    try {
                        storage.delete(orphan.key());
                        deleted++;
                        log.info("孤儿对象已删除: {} ({} bytes)", orphan.key(), orphan.size());
                    } catch (RuntimeException e) {
                        deleteFailed++;
                        log.warn("孤儿对象删除失败: key={}, error={}", orphan.key(), e.getMessage());
                    }
                }
            } else {
                for (int i = 0; i < orphans.size() && i < logLimit; i++) {
                    StorageObjectInfo orphan = orphans.get(i);
                    log.info("发现孤儿对象（未删除，delete=false）: {} ({} bytes)",
                            orphan.key(), orphan.size());
                }
                if (orphans.size() > logLimit) {
                    log.info("另有 {} 个孤儿对象未逐条打印，详见指标 wgh.storage.orphans",
                            orphans.size() - logLimit);
                }
            }

            long duration = System.currentTimeMillis() - started;
            List<String> sample = orphans.stream()
                    .limit(Math.max(1, logLimit))
                    .map(StorageObjectInfo::key)
                    .toList();

            metrics.recordStorageOrphans("detected", orphans.size());
            metrics.recordStorageOrphans("missing", missing);
            if (delete) {
                metrics.recordStorageOrphans("deleted", deleted);
                metrics.recordStorageOrphans("delete_failed", deleteFailed);
                metrics.recordStorageOrphans("skipped_young", skippedYoung);
            }

            log.info("孤儿对象扫描完成: storage={}, tenant={}, prefix={}, scanned={}, referenced={}, orphans={}, "
                            + "orphanBytes={}, deleted={}, deleteFailed={}, skippedYoung={}, "
                            + "skippedUnattributable={}, missingReferences={}, delete={}, cost={}ms",
                    storageProperties.getType(), tenantId, scopePrefix, objects.size(), references.all().size(),
                    orphans.size(), orphanBytes, deleted, deleteFailed, skippedYoung,
                    skippedUnattributable, missing, delete, duration);

            return new StorageOrphanReport(storageProperties.getType(), objects.size(), references.all().size(),
                    orphans.size(), orphanBytes, deleted, deleteFailed, skippedYoung, missing, duration, sample,
                    missingDetails.stream().limit(missingLogLimit).toList());
        } finally {
            running.set(false);
        }
    }

    private boolean isDeletable(StorageObjectInfo object, long now, long minAgeMillis) {
        if (object.lastModifiedMillis() <= 0) {
            // 元数据缺失时保守处理，不删除
            return false;
        }
        return now - object.lastModifiedMillis() >= minAgeMillis;
    }

    /**
     * 收集数据库仍引用的存储 key。
     * <p>
     * 同时保留原始值与 canonical 值：历史数据可能保存绝对路径（本地）或带 {@code ./} 的路径，
     * canonical 值用于与 {@link StorageInventoryService#listAll()} 的 key 对齐；保留原值只增加
     * 安全性（宁可少删）。
     */
    private ReferenceKeys collectReferencedKeys(String tenantId, String scopePrefix) {
        Set<String> all = new HashSet<>();
        Set<String> canonical = new HashSet<>();
        List<ReferenceCandidate> fileCandidates = new ArrayList<>();

        List<KnowledgeFile> files = fileMapper.selectList(new QueryWrapper<KnowledgeFile>()
                .select("id", "file_name", "file_path", "tenant_id")
                // 显式限定租户，避免平台管理员上下文（插件不追加条件）时跨租户收集引用
                .eq("tenant_id", tenantId)
                .isNotNull("file_path"));
        for (KnowledgeFile file : files) {
            String path = file == null ? null : file.getFilePath();
            if (path == null || path.isBlank()) {
                continue;
            }
            all.add(path);
            String key = inventory.canonicalKey(path);
            if (key == null || key.isBlank()) {
                // 归属无法确定：不作为引用，也不参与孤儿判定
                continue;
            }
            if (!key.startsWith(scopePrefix)) {
                // 记录路径不在本次租户扫描前缀内：跳过，避免跨租户推断
                log.debug("知识文件存储路径不在租户前缀内，跳过: fileId={}, key={}, prefix={}",
                        file.getId(), key, scopePrefix);
                continue;
            }
            all.add(key);
            canonical.add(key);
            fileCandidates.add(new ReferenceCandidate(file.getId(), file.getFileName(), key));
        }

        List<UploadSession> sessions = sessionMapper.selectList(new QueryWrapper<UploadSession>()
                .select("knowledge_base_id", "tenant_id", "upload_uuid", "total_chunks")
                .eq("tenant_id", tenantId)
                .eq("status", STATUS_UPLOADING));
        for (UploadSession session : sessions) {
            int total = session.getTotalChunks() == null ? 0 : session.getTotalChunks();
            String sessionTenant = session.getTenantId() == null || session.getTenantId().isBlank()
                    ? tenantId : session.getTenantId();
            for (int index = 0; index < total; index++) {
                String key = StorageKeys.chunkKey(sessionTenant,
                        session.getKnowledgeBaseId(), session.getUploadUuid(), index);
                if (!key.startsWith(scopePrefix)) {
                    continue;
                }
                all.add(key);
                canonical.add(key);
            }
        }
        return new ReferenceKeys(all, canonical, fileCandidates);
    }

    private record ReferenceKeys(Set<String> all, Set<String> canonical,
                                 List<ReferenceCandidate> fileCandidates) {
    }

    /** 知识文件引用，用于在扫描结果中定位“记录存在但存储对象缺失”。 */
    private record ReferenceCandidate(Long fileId, String fileName, String storageKey) {
    }
}

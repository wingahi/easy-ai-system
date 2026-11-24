package com.wgh.rag.kb.job;

import com.wgh.rag.execution.job.JobContext;
import com.wgh.rag.execution.job.JobHandler;
import com.wgh.rag.kb.service.KbChunkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 事实层向量回填作业（{@code KB_VECTOR_BACKFILL}），在后台车道执行。
 *
 * <p>扫描 {@code wgh_kb_chunk} 中 PENDING/STALE 的 chunk，按知识库/模型分组补齐向量并回写
 * embedding_id/vector_status；最后对指定知识库做一次行数对账。事实层关闭时为空操作。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KbVectorBackfillJobHandler implements JobHandler {

    private final KbChunkService kbChunkService;

    @Override
    public String jobType() {
        return KbJobTypes.VECTOR_BACKFILL;
    }

    @Override
    public void execute(JobContext context) {
        context.cancellation().throwIfCancellationRequested();
        Long kbId = parseLong(context.payload().getString(KbJobTypes.PAYLOAD_KB_ID));
        Integer batchSize = parseInt(context.payload().getString(KbJobTypes.PAYLOAD_BATCH_SIZE));
        int synced = kbChunkService.backfill(kbId, batchSize == null ? 0 : batchSize);
        if (kbId != null) {
            KbChunkService.ReconcileResult result = kbChunkService.reconcile(kbId);
            log.info("向量回填作业完成 executionId={} kbId={} synced={} facts={} vectors={} mismatch={}",
                    context.executionId(), kbId, synced, result.factCount(),
                    result.vectorCount(), result.mismatch());
        } else {
            log.info("向量回填作业完成 executionId={} synced={}", context.executionId(), synced);
        }
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer parseInt(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

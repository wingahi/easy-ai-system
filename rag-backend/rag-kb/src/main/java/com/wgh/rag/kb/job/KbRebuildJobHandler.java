package com.wgh.rag.kb.job;

import com.wgh.rag.execution.error.ExecutionErrorCodes;
import com.wgh.rag.execution.error.ExecutionException;
import com.wgh.rag.execution.job.JobContext;
import com.wgh.rag.execution.job.JobHandler;
import com.wgh.rag.kb.service.KnowledgeFileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 知识库全量重建作业处理器（{@code KB_REBUILD}，后台车道）。
 *
 * <p>重建是逐文件的长时间任务，单文件失败即整体失败；执行期间按文件粒度续租与检查取消，
 * 让长时间运行也能被其它实例感知。目标向量表与旧表切换逻辑仍由
 * {@link KnowledgeFileService#runRebuildJob} 负责，本处理器只做负载解析与生命周期衔接。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KbRebuildJobHandler implements JobHandler {

    private final KnowledgeFileService knowledgeFileService;

    @Override
    public String jobType() {
        return KbJobTypes.REBUILD;
    }

    @Override
    public void execute(JobContext context) {
        context.cancellation().throwIfCancellationRequested();
        Long kbId = parseLong(context, KbJobTypes.PAYLOAD_KB_ID);
        String targetCollection = context.payload().getString(KbJobTypes.PAYLOAD_TARGET_COLLECTION);
        Long targetModelId = parseLong(context, KbJobTypes.PAYLOAD_TARGET_MODEL_ID);
        Integer targetDimension = parseInt(context, KbJobTypes.PAYLOAD_TARGET_DIMENSION);
        if (kbId == null || targetCollection == null || targetCollection.isBlank()) {
            throw new ExecutionException(ExecutionErrorCodes.INCOMPATIBLE_PAYLOAD,
                    "KB_REBUILD 负载缺少 kbId/targetCollection", context.executionId());
        }
        knowledgeFileService.runRebuildJob(kbId, targetCollection, targetModelId, targetDimension,
                context::heartbeat,
                () -> context.cancellation().throwIfCancellationRequested());
        log.debug("知识库重建作业完成 executionId={} kbId={}", context.executionId(), kbId);
    }

    private Long parseLong(JobContext context, String key) {
        String value = context.payload().getString(key);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new ExecutionException(ExecutionErrorCodes.INCOMPATIBLE_PAYLOAD,
                    "KB_REBUILD 负载字段非法: " + key + "=" + value, context.executionId());
        }
    }

    private Integer parseInt(JobContext context, String key) {
        String value = context.payload().getString(key);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new ExecutionException(ExecutionErrorCodes.INCOMPATIBLE_PAYLOAD,
                    "KB_REBUILD 负载字段非法: " + key + "=" + value, context.executionId());
        }
    }
}

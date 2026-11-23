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
 * 单文件入库作业处理器（{@code KB_FILE_PROCESS}，后台车道）。
 *
 * <p>原实现由 {@code KnowledgeFileService} 内的静态线程池处理；接入调度中心后，
 * 作业落 {@code wgh_execution_job}，租约、重试与取消由调度层统一负责。
 * 执行体只按 {@code fileId}/{@code kbId} 重新读取数据库，保证重放时使用最新状态。</p>
 *
 * <p>幂等：同一 {@code bizKey}（文件 ID + 版本）只会产生一条作业；文件被删除时直接跳过，
 * 重复执行时向量侧按 contentHash 去重，不会重复入库。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KbFileProcessJobHandler implements JobHandler {

    private final KnowledgeFileService knowledgeFileService;

    @Override
    public String jobType() {
        return KbJobTypes.FILE_PROCESS;
    }

    @Override
    public void execute(JobContext context) throws Exception {
        context.cancellation().throwIfCancellationRequested();
        Long fileId = parseLong(context, KbJobTypes.PAYLOAD_FILE_ID);
        Long kbId = parseLong(context, KbJobTypes.PAYLOAD_KB_ID);
        if (fileId == null || kbId == null) {
            throw new ExecutionException(ExecutionErrorCodes.INCOMPATIBLE_PAYLOAD,
                    "KB_FILE_PROCESS 负载缺少 fileId/kbId", context.executionId());
        }
        knowledgeFileService.processFileJob(fileId, kbId);
        context.cancellation().throwIfCancellationRequested();
        log.debug("文件入库作业完成 executionId={} fileId={} kbId={}", context.executionId(), fileId, kbId);
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
                    "KB_FILE_PROCESS 负载字段非法: " + key + "=" + value, context.executionId());
        }
    }
}

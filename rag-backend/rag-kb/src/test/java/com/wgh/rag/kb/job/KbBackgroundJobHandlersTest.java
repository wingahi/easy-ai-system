package com.wgh.rag.kb.job;

import com.wgh.rag.execution.error.ExecutionErrorCodes;
import com.wgh.rag.execution.error.ExecutionException;
import com.wgh.rag.execution.job.ExecutionCancellationToken;
import com.wgh.rag.execution.job.JobContext;
import com.wgh.rag.execution.model.JobPayload;
import com.wgh.rag.kb.service.KnowledgeFileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文件入库与知识库重建作业处理器：验证负载解析与执行入口调用。
 */
@ExtendWith(MockitoExtension.class)
class KbBackgroundJobHandlersTest {

    @Mock
    private KnowledgeFileService knowledgeFileService;
    @Mock
    private JobContext context;
    @Mock
    private ExecutionCancellationToken cancellation;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(context.cancellation()).thenReturn(cancellation);
        org.mockito.Mockito.lenient().when(context.executionId()).thenReturn("exec-1");
    }

    @Test
    void fileProcessDispatchesToService() throws Exception {
        when(context.payload()).thenReturn(JobPayload.of(Map.of(
                KbJobTypes.PAYLOAD_FILE_ID, "11",
                KbJobTypes.PAYLOAD_KB_ID, "22")));

        new KbFileProcessJobHandler(knowledgeFileService).execute(context);

        verify(knowledgeFileService).processFileJob(11L, 22L);
    }

    @Test
    void fileProcessRejectsMissingPayload() {
        when(context.payload()).thenReturn(JobPayload.empty());

        assertThatThrownBy(() -> new KbFileProcessJobHandler(knowledgeFileService).execute(context))
                .isInstanceOf(ExecutionException.class)
                .satisfies(error -> assertThat(((ExecutionException) error).code())
                        .isEqualTo(ExecutionErrorCodes.INCOMPATIBLE_PAYLOAD));
    }

    @Test
    void rebuildDispatchesToServiceWithLifecycleCallbacks() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(KbJobTypes.PAYLOAD_KB_ID, "5");
        data.put(KbJobTypes.PAYLOAD_TARGET_COLLECTION, "wgh_kb_5_1");
        data.put(KbJobTypes.PAYLOAD_TARGET_MODEL_ID, "7");
        data.put(KbJobTypes.PAYLOAD_TARGET_DIMENSION, "1536");
        when(context.payload()).thenReturn(JobPayload.of(data));

        new KbRebuildJobHandler(knowledgeFileService).execute(context);

        verify(knowledgeFileService).runRebuildJob(eq(5L), eq("wgh_kb_5_1"), eq(7L), eq(1536),
                any(Runnable.class), any(Runnable.class));
    }

    @Test
    void rebuildRejectsMissingTargetCollection() {
        when(context.payload()).thenReturn(JobPayload.of(Map.of(KbJobTypes.PAYLOAD_KB_ID, "5")));

        assertThatThrownBy(() -> new KbRebuildJobHandler(knowledgeFileService).execute(context))
                .isInstanceOf(ExecutionException.class)
                .satisfies(error -> assertThat(((ExecutionException) error).code())
                        .isEqualTo(ExecutionErrorCodes.INCOMPATIBLE_PAYLOAD));
    }
}

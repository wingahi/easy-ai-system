package com.wgh.rag.controller.eval;

import com.wgh.rag.eval.service.EvalDatasetService;
import com.wgh.rag.execution.job.JobContext;
import com.wgh.rag.execution.job.JobHandler;
import com.wgh.rag.kb.service.RetrievalOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 评测运行作业（{@code EVAL_RUN}，后台车道）。
 *
 * <p>评测从请求线程移到后台车道后，接口返回 {@code executionId}，前端按
 * {@code GET /api/kb/{kbId}/eval-datasets/{datasetId}/evaluate/runs/{executionId}} 轮询状态与结果。
 * 事务边界仍在 {@link EvalDatasetService#evaluate}，作业层只负责解析负载与回写结果。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvalRunJobHandler implements JobHandler {

    /** 作业类型。 */
    public static final String JOB_TYPE = "EVAL_RUN";

    /** 负载字段：知识库 ID。 */
    public static final String KEY_KB_ID = "kbId";
    /** 负载字段：数据集 ID。 */
    public static final String KEY_DATASET_ID = "datasetId";
    /** 负载字段：数据集版本，可为空（空取当前版本）。 */
    public static final String KEY_VERSION = "version";
    /** 负载字段：TopK。 */
    public static final String KEY_TOP_K = "topK";
    /** 负载字段：是否混合检索。 */
    public static final String KEY_HYBRID = "hybrid";
    /** 负载字段：重排模型 ID，可为空。 */
    public static final String KEY_RERANK_MODEL_ID = "rerankModelId";

    private final EvalDatasetService datasetService;

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public void execute(JobContext context) {
        Map<String, Object> payload = context.request().payload().data();
        Long kbId = longValue(payload.get(KEY_KB_ID));
        Long datasetId = longValue(payload.get(KEY_DATASET_ID));
        if (kbId == null || datasetId == null) {
            throw new IllegalArgumentException("评测作业缺少 kbId/datasetId");
        }
        Integer version = intValue(payload.get(KEY_VERSION));
        Integer topK = intValue(payload.get(KEY_TOP_K));
        boolean hybrid = booleanValue(payload.get(KEY_HYBRID));
        Long rerankModelId = longValue(payload.get(KEY_RERANK_MODEL_ID));
        RetrievalOptions options = new RetrievalOptions(rerankModelId, hybrid, null, null, topK);
        Map<String, Object> result = datasetService.evaluate(kbId, datasetId, version, options);
        context.saveResult(result);
        log.info("评测作业完成 executionId={} kbId={} datasetId={} version={}",
                context.executionId(), kbId, datasetId, version);
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Long.parseLong(text.trim());
        }
        return null;
    }

    private Integer intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Integer.parseInt(text.trim());
        }
        return null;
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        return value instanceof String text && Boolean.parseBoolean(text.trim());
    }
}

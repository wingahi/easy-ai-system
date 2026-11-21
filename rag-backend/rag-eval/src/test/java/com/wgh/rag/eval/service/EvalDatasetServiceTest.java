package com.wgh.rag.eval.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.config.property.RagEvaluationProperties;
import com.wgh.rag.eval.entity.EvalCase;
import com.wgh.rag.eval.entity.EvalDataset;
import com.wgh.rag.eval.entity.EvalResult;
import com.wgh.rag.kb.entity.KnowledgeBase;
import com.wgh.rag.eval.mapper.EvalCaseMapper;
import com.wgh.rag.eval.mapper.EvalDatasetMapper;
import com.wgh.rag.eval.mapper.EvalResultMapper;
import com.wgh.rag.eval.mapper.KbGroundTruthMapper;
import com.wgh.rag.kb.mapper.KnowledgeBaseMapper;
import com.wgh.rag.kb.service.RagRetrieveService;
import com.wgh.rag.kb.service.RetrievalOptions;
import com.wgh.rag.kb.service.RetrieveResult;
import com.wgh.rag.eval.service.EvalDatasetService.CasePayload;
import com.wgh.rag.eval.service.EvalDatasetService.DatasetPayload;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 评测数据集版本化单测：用例 CRUD 推进版本、旧版本不可变、结果按版本归档与对比
 */
@DisplayName("评测数据集版本化测试")
class EvalDatasetServiceTest {

    private final EvalDatasetMapper datasetMapper = Mockito.mock(EvalDatasetMapper.class);
    private final EvalCaseMapper caseMapper = Mockito.mock(EvalCaseMapper.class);
    private final EvalResultMapper resultMapper = Mockito.mock(EvalResultMapper.class);
    private final KnowledgeBaseMapper knowledgeBaseMapper = Mockito.mock(KnowledgeBaseMapper.class);
    private final RagRetrieveService retrieveService = Mockito.mock(RagRetrieveService.class);

    /** 内存态数据集 / 用例 / 结果，模拟数据库读写以验证版本推进逻辑 */
    private final Map<Long, EvalDataset> datasetStore = new LinkedHashMap<>();
    private final List<EvalCase> caseStore = new ArrayList<>();
    private final List<EvalResult> resultStore = new ArrayList<>();
    private long sequence = 0;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private EvalDatasetService service;
    private RetrievalEvaluationService evaluationService;

    @BeforeEach
    void setUp() {
        evaluationService = new RetrievalEvaluationService(
                Mockito.mock(KbGroundTruthMapper.class), knowledgeBaseMapper, retrieveService,
                Mockito.mock(DataSource.class), objectMapper, new RagEvaluationProperties());
        service = new EvalDatasetService(datasetMapper, caseMapper, resultMapper,
                knowledgeBaseMapper, evaluationService, objectMapper);
        stubMappers();

        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        when(knowledgeBaseMapper.selectById(1L)).thenReturn(kb);
    }

    private void stubMappers() {
        when(datasetMapper.insert(any(EvalDataset.class))).thenAnswer(invocation -> {
            EvalDataset dataset = invocation.getArgument(0);
            dataset.setId(++sequence);
            datasetStore.put(dataset.getId(), dataset);
            return 1;
        });
        when(datasetMapper.selectById(anyLong()))
                .thenAnswer(invocation -> datasetStore.get(invocation.<Long>getArgument(0)));
        when(datasetMapper.updateById(any(EvalDataset.class))).thenAnswer(invocation -> {
            EvalDataset dataset = invocation.getArgument(0);
            datasetStore.put(dataset.getId(), dataset);
            return 1;
        });
        when(datasetMapper.selectList(any())).thenAnswer(invocation -> new ArrayList<>(datasetStore.values()));

        when(caseMapper.insert(any(EvalCase.class))).thenAnswer(invocation -> {
            EvalCase evalCase = invocation.getArgument(0);
            evalCase.setId(++sequence);
            caseStore.add(evalCase);
            return 1;
        });
        when(caseMapper.selectByVersion(anyLong(), anyInt())).thenAnswer(invocation -> {
            Long datasetId = invocation.getArgument(0);
            Integer version = invocation.getArgument(1);
            return caseStore.stream()
                    .filter(c -> Objects.equals(c.getDatasetId(), datasetId)
                            && Objects.equals(c.getDatasetVersion(), version))
                    .collect(Collectors.toList());
        });
        when(caseMapper.selectVersions(anyLong())).thenAnswer(invocation -> {
            Long datasetId = invocation.getArgument(0);
            return caseStore.stream()
                    .filter(c -> Objects.equals(c.getDatasetId(), datasetId))
                    .map(EvalCase::getDatasetVersion)
                    .distinct()
                    .sorted()
                    .collect(Collectors.toList());
        });

        when(resultMapper.insert(any(EvalResult.class))).thenAnswer(invocation -> {
            EvalResult result = invocation.getArgument(0);
            result.setId(++sequence);
            resultStore.add(result);
            return 1;
        });
        when(resultMapper.selectList(any())).thenAnswer(invocation -> new ArrayList<>(resultStore));
        when(resultMapper.selectById(anyLong())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            return resultStore.stream().filter(r -> Objects.equals(r.getId(), id)).findFirst().orElse(null);
        });
    }

    private EvalCase addCase(Long datasetId, String query, String relevant) {
        return service.addCase(1L, datasetId, new CasePayload(query, List.of(relevant)));
    }

    private EmbeddingMatch<TextSegment> match(String fileId, String chunkIndex) {
        Metadata metadata = Metadata.from(Map.of("fileId", fileId, "chunkIndex", chunkIndex));
        return new EmbeddingMatch<>(0.8, fileId + ":" + chunkIndex, null,
                TextSegment.from("chunk", metadata));
    }

    /** 数据集创建后版本号为 0，空名称被拒绝。 */
    @Test
    @DisplayName("数据集创建 - 初始版本为 0，名称为空时拒绝")
    void createDatasetStartsAtVersionZero() {
        EvalDataset dataset = service.create(1L, new DatasetPayload("回归集", "批次3"));

        assertThat(dataset.getId()).isNotNull();
        assertThat(dataset.getCurrentVersion()).isZero();
        assertThat(service.list(1L)).hasSize(1);
        assertThatThrownBy(() -> service.create(1L, new DatasetPayload(" ", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("数据集名称不能为空");
    }

    /** 每次新增用例推进一个版本，旧版本用例数不变。 */
    @Test
    @DisplayName("版本推进 - 新增用例每次生成新版本，旧版本用例数保持不变")
    void addCaseAdvancesVersionAndKeepsHistory() {
        EvalDataset dataset = service.create(1L, new DatasetPayload("回归集", null));

        addCase(dataset.getId(), "q1", "1:0");
        assertThat(dataset.getCurrentVersion()).isEqualTo(1);
        addCase(dataset.getId(), "q2", "2:0");
        assertThat(dataset.getCurrentVersion()).isEqualTo(2);

        assertThat(service.listVersions(1L, dataset.getId())).containsExactly(1, 2);
        assertThat(service.listCases(1L, dataset.getId(), 1)).hasSize(1);
        assertThat(service.listCases(1L, dataset.getId(), 2)).hasSize(2);
        // 不传版本时取当前版本
        assertThat(service.listCases(1L, dataset.getId(), null)).hasSize(2);
    }

    /** 修改用例生成新版本，旧版本内容保持原样。 */
    @Test
    @DisplayName("用例修改 - 生成新版本，旧版本保持原查询不变")
    void updateCaseCreatesNewVersionAndPreservesOld() {
        EvalDataset dataset = service.create(1L, new DatasetPayload("回归集", null));
        EvalCase first = addCase(dataset.getId(), "旧查询", "1:0");

        service.updateCase(1L, dataset.getId(), first.getId(), new CasePayload("新查询", List.of("1:1")));

        assertThat(dataset.getCurrentVersion()).isEqualTo(2);
        List<EvalCase> version1 = service.listCases(1L, dataset.getId(), 1);
        assertThat(version1).hasSize(1);
        assertThat(version1.get(0).getQueryText()).isEqualTo("旧查询");

        List<EvalCase> version2 = service.listCases(1L, dataset.getId(), 2);
        assertThat(version2).hasSize(1);
        assertThat(version2.get(0).getQueryText()).isEqualTo("新查询");
        assertThat(version2.get(0).getRelevantChunks()).contains("1:1");
    }

    /** 删除用例只影响新版本，历史版本仍完整。 */
    @Test
    @DisplayName("用例删除 - 新版本少一条用例，历史版本仍完整保留")
    void deleteCaseCreatesNewVersionWithOneLessCase() {
        EvalDataset dataset = service.create(1L, new DatasetPayload("回归集", null));
        addCase(dataset.getId(), "q1", "1:0");
        addCase(dataset.getId(), "q2", "2:0");
        // 用例 id 按版本重建，删除时使用当前版本（v2）的 id
        EvalCase currentFirst = service.listCases(1L, dataset.getId(), 2).get(0);

        service.deleteCase(1L, dataset.getId(), currentFirst.getId());

        assertThat(dataset.getCurrentVersion()).isEqualTo(3);
        assertThat(service.listCases(1L, dataset.getId(), 1)).hasSize(1);
        assertThat(service.listCases(1L, dataset.getId(), 2)).hasSize(2);
        List<EvalCase> version3 = service.listCases(1L, dataset.getId(), 3);
        assertThat(version3).hasSize(1);
        assertThat(version3.get(0).getQueryText()).isEqualTo("q2");
    }

    /** 用例 id 或数据集归属不匹配时返回资源不存在。 */
    @Test
    @DisplayName("越权/不存在 - 用例 id 或数据集归属不匹配时返回资源不存在")
    void unknownCaseOrDatasetRejected() {
        EvalDataset dataset = service.create(1L, new DatasetPayload("回归集", null));

        assertThatThrownBy(() -> service.deleteCase(1L, dataset.getId(), 9999L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("评测用例不存在");
        assertThatThrownBy(() -> service.listCases(2L, dataset.getId(), null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("评测数据集不存在");
    }

    /** 评测结果按版本写入归档表，指标与明细完整。 */
    @Test
    @DisplayName("结果归档 - 按评测版本落库指标与逐查询明细")
    void evaluateArchivesResultByVersion() {
        EvalDataset dataset = service.create(1L, new DatasetPayload("回归集", null));
        addCase(dataset.getId(), "q1", "1:0");
        when(retrieveService.searchMultiple(eq(List.of(1L)), eq("q1"), any(RetrievalOptions.class)))
                .thenReturn(new RetrieveResult(List.of(match("1", "0"), match("1", "7")), false, 0));

        Map<String, Object> resp = service.evaluate(1L, dataset.getId(), 1,
                new RetrievalOptions(null, false, null, null, 5));

        assertThat(resp.get("datasetVersion")).isEqualTo(1);
        assertThat(resp.get("resultId")).isNotNull();
        assertThat(resp.get("recallAtK")).isEqualTo(1.0);

        assertThat(resultStore).hasSize(1);
        EvalResult archived = resultStore.get(0);
        assertThat(archived.getDatasetId()).isEqualTo(dataset.getId());
        assertThat(archived.getDatasetVersion()).isEqualTo(1);
        assertThat(archived.getTopK()).isEqualTo(5);
        assertThat(archived.getQueryCount()).isEqualTo(1);
        assertThat(archived.getRecallAtK()).isEqualTo(1.0);
        assertThat(archived.getHybrid()).isZero();
        assertThat(archived.getDetailsJson()).contains("q1");
    }

    /** 空版本（无用例）不允许评测。 */
    @Test
    @DisplayName("结果归档 - 空版本不可评测")
    void evaluateEmptyVersionRejected() {
        EvalDataset dataset = service.create(1L, new DatasetPayload("回归集", null));

        assertThatThrownBy(() -> service.evaluate(1L, dataset.getId(), 0,
                new RetrievalOptions(null, false, null, null, 5)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("暂无可用用例版本");
    }

    /** 版本对比输出指标差值与逐查询差值。 */
    @Test
    @DisplayName("版本对比 - 返回指标差值以及逐查询 recall 差值")
    void compareResultsReturnsDeltas() throws Exception {
        EvalDataset dataset = service.create(1L, new DatasetPayload("回归集", null));
        EvalResult base = archivedResult(dataset.getId(), 1, 0.5, "[{\"query\":\"q1\",\"recall\":0.5}]");
        EvalResult target = archivedResult(dataset.getId(), 2, 0.75, "[{\"query\":\"q1\",\"recall\":1.0}]");

        Map<String, Object> comparison = service.compareResults(1L, dataset.getId(), base.getId(), target.getId());

        @SuppressWarnings("unchecked")
        Map<String, Object> delta = (Map<String, Object>) comparison.get("delta");
        assertThat(delta.get("recallAtK")).isEqualTo(0.25);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> queryDiffs = (List<Map<String, Object>>) comparison.get("queryDiffs");
        assertThat(queryDiffs).hasSize(1);
        assertThat(queryDiffs.get(0).get("baseRecall")).isEqualTo(0.5);
        assertThat(queryDiffs.get(0).get("targetRecall")).isEqualTo(1.0);
        assertThat(queryDiffs.get(0).get("delta")).isEqualTo(0.5);
    }

    /** 跨数据集的结果不允许对比。 */
    @Test
    @DisplayName("版本对比 - 结果不属于该数据集时拒绝")
    void compareRejectsForeignResult() {
        EvalDataset dataset = service.create(1L, new DatasetPayload("回归集", null));
        EvalResult foreign = archivedResult(dataset.getId() + 100, 1, 0.5, "[]");
        EvalResult target = archivedResult(dataset.getId(), 2, 0.75, "[]");

        assertThatThrownBy(() -> service.compareResults(1L, dataset.getId(), foreign.getId(), target.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("评测结果不存在");
    }

    /** 直接放入归档结果，用于对比逻辑测试 */
    private EvalResult archivedResult(Long datasetId, int version, double recall, String detailsJson) {
        EvalResult result = new EvalResult();
        result.setId(++sequence);
        result.setDatasetId(datasetId);
        result.setKnowledgeBaseId(1L);
        result.setDatasetVersion(version);
        result.setTopK(5);
        result.setHybrid(0);
        result.setQueryCount(1);
        result.setRecallAtK(recall);
        result.setDetailsJson(detailsJson);
        resultStore.add(result);
        return result;
    }

    /** 归档明细可还原为逐查询指标。 */
    @Test
    @DisplayName("明细解析 - 归档明细可被还原为查询与 recall 映射")
    void archivedDetailsReadable() throws Exception {
        EvalDataset dataset = service.create(1L, new DatasetPayload("回归集", null));
        addCase(dataset.getId(), "q1", "1:0");
        when(retrieveService.searchMultiple(any(), any(), any()))
                .thenReturn(new RetrieveResult(List.of(match("1", "0")), false, 0));

        service.evaluate(1L, dataset.getId(), 1, new RetrievalOptions(null, false, null, null, 5));

        List<Map<String, Object>> details = objectMapper.readValue(
                resultStore.get(0).getDetailsJson(), new TypeReference<List<Map<String, Object>>>() {
                });
        assertThat(details).hasSize(1);
        assertThat(details.get(0).get("query")).isEqualTo("q1");
        assertThat(details.get(0).get("recall")).isEqualTo(1.0);
    }
}

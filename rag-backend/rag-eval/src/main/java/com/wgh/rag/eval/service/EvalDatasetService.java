package com.wgh.rag.eval.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.data.scope.DataScopeGuard;
import com.wgh.rag.eval.entity.EvalCase;
import com.wgh.rag.eval.entity.EvalDataset;
import com.wgh.rag.eval.entity.EvalResult;
import com.wgh.rag.kb.entity.KnowledgeBase;
import com.wgh.rag.eval.mapper.EvalCaseMapper;
import com.wgh.rag.eval.mapper.EvalDatasetMapper;
import com.wgh.rag.eval.mapper.EvalResultMapper;
import com.wgh.rag.kb.mapper.KnowledgeBaseMapper;
import com.wgh.rag.eval.service.RetrievalEvaluationService.GroundTruthPayload;
import com.wgh.rag.kb.service.RetrievalOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 评测数据集版本化服务：数据集/用例 CRUD、版本快照、结果归档与跨版本对比。
 *
 * <p>版本推进规则：数据集初始版本为 0（空数据集）；每次用例变更（新增/修改/删除）都把当前版本
 * 的全部用例复制为 {@code currentVersion + 1}，只在新副本上应用本次变更，旧版本行永不修改。
 * 因此历史版本的用例集合不可变，历史评测结果可以按版本逐项对比。
 *
 * <p>指标计算复用 {@link RetrievalEvaluationService#evaluateSamples}，与 ground truth 链路口径一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalDatasetService {

    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_DESCRIPTION_LENGTH = 500;
    private static final int MAX_QUERY_LENGTH = 500;

    private final EvalDatasetMapper datasetMapper;
    private final EvalCaseMapper caseMapper;
    private final EvalResultMapper resultMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final RetrievalEvaluationService evaluationService;
    private final ObjectMapper objectMapper;

    /** 数据集创建/更新请求 */
    public record DatasetPayload(String name, String description) {
    }

    /** 用例创建/更新请求 */
    public record CasePayload(String queryText, List<String> relevantChunks) {
    }

    // ============ 数据集 CRUD ============

    public List<EvalDataset> list(Long kbId) {
        requireReadableKb(kbId);
        return datasetMapper.selectList(new LambdaQueryWrapper<EvalDataset>()
                .eq(EvalDataset::getKnowledgeBaseId, kbId)
                .orderByDesc(EvalDataset::getId));
    }

    public EvalDataset create(Long kbId, DatasetPayload payload) {
        requireWritableKb(kbId);
        EvalDataset dataset = new EvalDataset();
        dataset.setKnowledgeBaseId(kbId);
        applyDataset(dataset, payload);
        dataset.setCurrentVersion(0);
        datasetMapper.insert(dataset);
        return dataset;
    }

    public EvalDataset update(Long kbId, Long id, DatasetPayload payload) {
        requireWritableKb(kbId);
        EvalDataset dataset = requireOwned(kbId, id);
        applyDataset(dataset, payload);
        datasetMapper.updateById(dataset);
        return dataset;
    }

    public void delete(Long kbId, Long id) {
        requireWritableKb(kbId);
        requireOwned(kbId, id);
        datasetMapper.deleteById(id);
    }

    // ============ 版本与用例 ============

    /**
     * 数据集已有版本号（升序）；空数据集返回 [0]，便于前端始终有可选版本
     */
    public List<Integer> listVersions(Long kbId, Long datasetId) {
        requireReadableKb(kbId);
        EvalDataset dataset = requireOwned(kbId, datasetId);
        List<Integer> versions = caseMapper.selectVersions(datasetId);
        if (versions == null) {
            versions = new ArrayList<>();
        }
        Set<Integer> unique = new LinkedHashSet<>(versions);
        if (unique.isEmpty() || (dataset.getCurrentVersion() != null
                && !unique.contains(dataset.getCurrentVersion()))) {
            unique.add(dataset.getCurrentVersion() == null ? 0 : dataset.getCurrentVersion());
        }
        List<Integer> sorted = new ArrayList<>(unique);
        sorted.sort(Integer::compareTo);
        return sorted;
    }

    /**
     * 列出版本用例：version 为空时取当前版本
     */
    public List<EvalCase> listCases(Long kbId, Long datasetId, Integer version) {
        requireReadableKb(kbId);
        EvalDataset dataset = requireOwned(kbId, datasetId);
        int v = version != null ? version : currentVersion(dataset);
        requireVersionExists(dataset, v);
        return caseMapper.selectByVersion(datasetId, v);
    }

    /** 新增用例：生成新版本 */
    @Transactional(rollbackFor = Exception.class)
    public EvalCase addCase(Long kbId, Long datasetId, CasePayload payload) {
        requireWritableKb(kbId);
        EvalDataset dataset = requireOwned(kbId, datasetId);
        String query = requireQuery(payload);
        String relevant = serializeRelevant(payload);
        int version = currentVersion(dataset) + 1;
        List<EvalCase> rows = copyRows(caseMapper.selectByVersion(datasetId, currentVersion(dataset)), version);
        EvalCase added = newRow(datasetId, version, query, relevant);
        rows.add(added);
        commitVersion(dataset, version, rows);
        return added;
    }

    /** 修改用例：生成新版本，旧版本内容保持不变 */
    @Transactional(rollbackFor = Exception.class)
    public EvalCase updateCase(Long kbId, Long datasetId, Long caseId, CasePayload payload) {
        requireWritableKb(kbId);
        EvalDataset dataset = requireOwned(kbId, datasetId);
        String query = requireQuery(payload);
        String relevant = serializeRelevant(payload);
        int current = currentVersion(dataset);
        List<EvalCase> sources = caseMapper.selectByVersion(datasetId, current);
        int index = indexOf(sources, caseId);
        if (index < 0) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "评测用例不存在");
        }
        List<EvalCase> rows = new ArrayList<>(sources.size());
        for (int i = 0; i < sources.size(); i++) {
            EvalCase origin = sources.get(i);
            rows.add(i == index
                    ? newRow(datasetId, current + 1, query, relevant)
                    : newRow(datasetId, current + 1, origin.getQueryText(), origin.getRelevantChunks()));
        }
        commitVersion(dataset, current + 1, rows);
        return rows.get(index);
    }

    /** 删除用例：生成新版本（仅新版本缺少该用例，旧版本仍完整保留） */
    @Transactional(rollbackFor = Exception.class)
    public void deleteCase(Long kbId, Long datasetId, Long caseId) {
        requireWritableKb(kbId);
        EvalDataset dataset = requireOwned(kbId, datasetId);
        int current = currentVersion(dataset);
        List<EvalCase> sources = caseMapper.selectByVersion(datasetId, current);
        int index = indexOf(sources, caseId);
        if (index < 0) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "评测用例不存在");
        }
        sources.remove(index);
        commitVersion(dataset, current + 1, copyRows(sources, current + 1));
    }

    // ============ 评测与归档 ============

    /**
     * 按指定版本运行评测并把结果归档；version 为空时使用当前版本
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> evaluate(Long kbId, Long datasetId, Integer version, RetrievalOptions options) {
        requireWritableKb(kbId);
        EvalDataset dataset = requireOwned(kbId, datasetId);
        int v = version != null ? version : currentVersion(dataset);
        if (v <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "数据集暂无可用用例版本，无法评测");
        }
        requireVersionExists(dataset, v);
        List<EvalCase> cases = caseMapper.selectByVersion(datasetId, v);
        if (cases.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "该版本暂无评测用例，无法评测");
        }
        List<GroundTruthPayload> samples = new ArrayList<>(cases.size());
        for (EvalCase evalCase : cases) {
            samples.add(new GroundTruthPayload(evalCase.getQueryText(), parseKeys(evalCase.getRelevantChunks())));
        }
        Map<String, Object> metrics = evaluationService.evaluateSamples(kbId, samples, options);
        EvalResult archived = archive(dataset, v, metrics, options);

        Map<String, Object> resp = new LinkedHashMap<>(metrics);
        resp.put("resultId", archived.getId());
        resp.put("datasetId", datasetId);
        resp.put("datasetVersion", v);
        return resp;
    }

    /**
     * 数据集的历史评测结果（新版本在前）
     */
    public List<EvalResult> listResults(Long kbId, Long datasetId) {
        requireReadableKb(kbId);
        requireOwned(kbId, datasetId);
        return resultMapper.selectList(new LambdaQueryWrapper<EvalResult>()
                .eq(EvalResult::getDatasetId, datasetId)
                .orderByDesc(EvalResult::getDatasetVersion)
                .orderByDesc(EvalResult::getId));
    }

    /**
     * 对比两次归档结果：指标差值 + 逐查询 recall 差值
     */
    public Map<String, Object> compareResults(Long kbId, Long datasetId, Long baseId, Long targetId) {
        requireReadableKb(kbId);
        requireOwned(kbId, datasetId);
        EvalResult base = requireResult(datasetId, baseId);
        EvalResult target = requireResult(datasetId, targetId);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("datasetId", datasetId);
        resp.put("base", summarize(base));
        resp.put("target", summarize(target));

        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("recallAtK", diff(target.getRecallAtK(), base.getRecallAtK()));
        delta.put("precisionAtK", diff(target.getPrecisionAtK(), base.getPrecisionAtK()));
        delta.put("mrr", diff(target.getMrr(), base.getMrr()));
        delta.put("ndcg", diff(target.getNdcg(), base.getNdcg()));
        delta.put("queryHitRate", diff(target.getQueryHitRate(), base.getQueryHitRate()));
        delta.put("coverageRate", diff(target.getCoverageRate(), base.getCoverageRate()));
        resp.put("delta", delta);
        resp.put("queryDiffs", queryDiffs(base.getDetailsJson(), target.getDetailsJson()));
        return resp;
    }

    // ============ 私有方法 ============

    private EvalResult archive(EvalDataset dataset, int version, Map<String, Object> metrics,
                               RetrievalOptions options) {
        EvalResult result = new EvalResult();
        result.setDatasetId(dataset.getId());
        result.setKnowledgeBaseId(dataset.getKnowledgeBaseId());
        result.setDatasetVersion(version);
        result.setTopK(asInteger(metrics.get("topK")));
        result.setHybrid(options != null && options.hybridEnabled() ? 1 : 0);
        result.setQueryCount(asInteger(metrics.get("queryCount")));
        result.setRecallAtK(asDouble(metrics.get("recallAtK")));
        result.setPrecisionAtK(asDouble(metrics.get("precisionAtK")));
        result.setMrr(asDouble(metrics.get("mrr")));
        result.setNdcg(asDouble(metrics.get("ndcg")));
        result.setQueryHitRate(asDouble(metrics.get("queryHitRate")));
        result.setCoverageRate(asDouble(metrics.get("coverageRate")));
        result.setDetailsJson(writeJson(metrics.get("details")));
        resultMapper.insert(result);
        return result;
    }

    private Map<String, Object> summarize(EvalResult result) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", result.getId());
        item.put("datasetVersion", result.getDatasetVersion());
        item.put("topK", result.getTopK());
        item.put("hybrid", result.getHybrid());
        item.put("queryCount", result.getQueryCount());
        item.put("recallAtK", result.getRecallAtK());
        item.put("precisionAtK", result.getPrecisionAtK());
        item.put("mrr", result.getMrr());
        item.put("ndcg", result.getNdcg());
        item.put("queryHitRate", result.getQueryHitRate());
        item.put("coverageRate", result.getCoverageRate());
        item.put("createTime", result.getCreateTime());
        return item;
    }

    /** 逐查询 recall 对比：同一查询在两版中都可计算时给出差值 */
    private List<Map<String, Object>> queryDiffs(String baseJson, String targetJson) {
        Map<String, Double> base = recallByQuery(baseJson);
        Map<String, Double> target = recallByQuery(targetJson);
        Set<String> queries = new LinkedHashSet<>();
        queries.addAll(base.keySet());
        queries.addAll(target.keySet());
        List<Map<String, Object>> diffs = new ArrayList<>(queries.size());
        for (String query : queries) {
            Double baseRecall = base.get(query);
            Double targetRecall = target.get(query);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("query", query);
            item.put("baseRecall", baseRecall);
            item.put("targetRecall", targetRecall);
            item.put("delta", diff(targetRecall, baseRecall));
            diffs.add(item);
        }
        return diffs;
    }

    private Map<String, Double> recallByQuery(String detailsJson) {
        Map<String, Double> result = new LinkedHashMap<>();
        if (detailsJson == null || detailsJson.isBlank()) {
            return result;
        }
        try {
            List<Map<String, Object>> details = objectMapper.readValue(
                    detailsJson, new TypeReference<List<Map<String, Object>>>() {
                    });
            for (Map<String, Object> item : details) {
                Object query = item.get("query");
                if (query != null) {
                    result.put(String.valueOf(query), asDouble(item.get("recall")));
                }
            }
        } catch (Exception e) {
            log.warn("解析评测明细失败: {}", e.getMessage());
        }
        return result;
    }

    /** 复制当前版本用例为新版本行（新行 id 为空，由数据库生成） */
    private List<EvalCase> copyRows(List<EvalCase> source, int version) {
        List<EvalCase> rows = new ArrayList<>(source.size());
        for (EvalCase origin : source) {
            rows.add(newRow(origin.getDatasetId(), version,
                    origin.getQueryText(), origin.getRelevantChunks()));
        }
        return rows;
    }

    private int indexOf(List<EvalCase> rows, Long caseId) {
        for (int i = 0; i < rows.size(); i++) {
            if (Objects.equals(rows.get(i).getId(), caseId)) {
                return i;
            }
        }
        return -1;
    }

    private EvalCase newRow(Long datasetId, int version, String queryText, String relevantChunks) {
        EvalCase row = new EvalCase();
        row.setDatasetId(datasetId);
        row.setDatasetVersion(version);
        row.setQueryText(queryText);
        row.setRelevantChunks(relevantChunks);
        return row;
    }

    private void commitVersion(EvalDataset dataset, int version, List<EvalCase> rows) {
        for (EvalCase row : rows) {
            caseMapper.insert(row);
        }
        dataset.setCurrentVersion(version);
        datasetMapper.updateById(dataset);
    }

    private int currentVersion(EvalDataset dataset) {
        return dataset.getCurrentVersion() == null ? 0 : dataset.getCurrentVersion();
    }

    private void requireVersionExists(EvalDataset dataset, int version) {
        if (version < 0 || version > currentVersion(dataset)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "版本号不存在：" + version);
        }
    }

    private EvalResult requireResult(Long datasetId, Long resultId) {
        EvalResult result = resultId == null ? null : resultMapper.selectById(resultId);
        if (result == null || !Objects.equals(datasetId, result.getDatasetId())) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "评测结果不存在");
        }
        return result;
    }

    private void requireReadableKb(Long kbId) {
        KnowledgeBase kb = kbId == null ? null : knowledgeBaseMapper.selectById(kbId);
        if (kb == null) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "知识库不存在");
        }
        DataScopeGuard.assertReadable(kb.getCreateBy(), kb.getVisibility(), "知识库");
    }

    private void requireWritableKb(Long kbId) {
        KnowledgeBase kb = kbId == null ? null : knowledgeBaseMapper.selectById(kbId);
        if (kb == null) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "知识库不存在");
        }
        DataScopeGuard.assertWritable(kb.getCreateBy(), kb.getVisibility(), "知识库");
    }

    private EvalDataset requireOwned(Long kbId, Long id) {
        EvalDataset dataset = id == null ? null : datasetMapper.selectById(id);
        if (dataset == null || !Objects.equals(kbId, dataset.getKnowledgeBaseId())) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "评测数据集不存在");
        }
        return dataset;
    }

    private void applyDataset(EvalDataset dataset, DatasetPayload payload) {
        if (payload == null || payload.name() == null || payload.name().isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "数据集名称不能为空");
        }
        String name = payload.name().trim();
        if (name.length() > MAX_NAME_LENGTH) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "数据集名称长度不能超过 " + MAX_NAME_LENGTH);
        }
        String description = payload.description() == null ? null : payload.description().trim();
        if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "数据集说明长度不能超过 " + MAX_DESCRIPTION_LENGTH);
        }
        dataset.setName(name);
        dataset.setDescription(description);
    }

    private String requireQuery(CasePayload payload) {
        if (payload == null || payload.queryText() == null || payload.queryText().isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "评测查询不能为空");
        }
        String query = payload.queryText().trim();
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "评测查询长度不能超过 " + MAX_QUERY_LENGTH);
        }
        return query;
    }

    private String serializeRelevant(CasePayload payload) {
        Set<String> keys = new LinkedHashSet<>();
        if (payload != null && payload.relevantChunks() != null) {
            for (String key : payload.relevantChunks()) {
                if (key != null && !key.isBlank()) {
                    keys.add(key.trim());
                }
            }
        }
        if (keys.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "相关 chunk 集合不能为空");
        }
        return writeJson(new ArrayList<>(keys));
    }

    private List<String> parseKeys(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.warn("解析用例相关 chunk 失败: {}", json);
            return new ArrayList<>();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "评测数据序列化失败");
        }
    }

    private static Double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return null;
    }

    private static Integer asInteger(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Double diff(Double target, Double base) {
        if (target == null || base == null) {
            return null;
        }
        return Math.round((target - base) * 10000.0) / 10000.0;
    }
}

package com.wgh.rag.kb.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wgh.rag.llm.entity.LlmModel;
import com.wgh.rag.llm.mapper.LlmModelMapper;
import dev.langchain4j.data.segment.TextSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Rerank 重排序服务 - 按 provider 分发 HTTP 调用（langchain4j-community-dashscope 无 rerank 实现）
 * 支持：
 *  - DASHSCOPE：POST {baseUrl}/api/v1/services/rerank/text-rerank/text-rerank（gte-rerank-v2）
 *  - CUSTOM_RERANK 及其他：通用 {baseUrl}/rerank（Jina / SiliconFlow 兼容格式）
 * 任何失败均抛异常，由调用方降级为原始向量序，不阻断聊天主流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RerankService {

    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private static final String DASHSCOPE_RERANK_PATH = "/api/v1/services/rerank/text-rerank/text-rerank";

    private final LlmModelMapper llmModelMapper;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();

    /**
     * 对候选文档打分，返回与 segments 顺序一致的相关性分数（0-1，越大越相关）。
     *
     * @throws IllegalStateException 模型不可用 / HTTP 或解析失败（调用方应降级）
     */
    public List<Double> scoreAll(List<TextSegment> segments, String query, Long rerankModelId) {
        if (segments == null || segments.isEmpty()) return List.of();
        LlmModel config = llmModelMapper.selectById(rerankModelId);
        if (config == null || !"RERANK".equals(config.getModelType())
                || !Integer.valueOf(1).equals(config.getStatus())) {
            throw new IllegalStateException("RERANK 模型不可用: " + rerankModelId);
        }
        List<String> documents = segments.stream()
                .map(s -> s.text() == null ? "" : s.text())
                .toList();
        String provider = config.getProvider() == null ? "" : config.getProvider().toUpperCase();
        try {
            if ("DASHSCOPE".equals(provider)) {
                return parseResults(post(dashscopeUrl(config), dashscopeBody(config, query, documents), config.getApiKey()),
                        documents.size(), true);
            }
            return parseResults(post(genericUrl(config), genericBody(config, query, documents), config.getApiKey()),
                    documents.size(), false);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Rerank 调用失败: " + e.getMessage(), e);
        }
    }

    // ============ DashScope ============

    private String dashscopeUrl(LlmModel config) {
        String base = stripSlash(config.getBaseUrl() != null && !config.getBaseUrl().isBlank()
                ? config.getBaseUrl() : "https://dashscope.aliyuncs.com");
        // 允许用户直接配置完整端点
        return base.endsWith(DASHSCOPE_RERANK_PATH) ? base : base + DASHSCOPE_RERANK_PATH;
    }

    private String dashscopeBody(LlmModel config, String query, List<String> documents) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", config.getModelName());
        ObjectNode input = root.putObject("input");
        input.put("query", query);
        ArrayNode docs = input.putArray("documents");
        documents.forEach(docs::add);
        ObjectNode params = root.putObject("parameters");
        params.put("return_documents", false);
        params.put("top_n", documents.size());
        return root.toString();
    }

    // ============ 通用 /rerank（Jina / SiliconFlow 兼容） ============

    private String genericUrl(LlmModel config) {
        String base = stripSlash(config.getBaseUrl());
        if (base == null || base.isBlank()) {
            throw new IllegalStateException("CUSTOM_RERANK 模型未配置 baseUrl");
        }
        return base.endsWith("/rerank") ? base : base + "/rerank";
    }

    private String genericBody(LlmModel config, String query, List<String> documents) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", config.getModelName());
        root.put("query", query);
        ArrayNode docs = root.putArray("documents");
        documents.forEach(docs::add);
        root.put("top_n", documents.size());
        return root.toString();
    }

    // ============ HTTP & 解析 ============

    private String post(String url, String body, String apiKey) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + (apiKey != null ? apiKey : ""))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Rerank HTTP " + response.statusCode() + ": " + abbreviate(response.body()));
        }
        return response.body();
    }

    /**
     * 解析 results 数组（DashScope 位于 output.results，通用格式位于根 results），
     * 按 index 还原为与输入文档顺序一致的分数列表。
     */
    private List<Double> parseResults(String responseBody, int docCount, boolean nested) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode results = nested ? root.path("output").path("results") : root.path("results");
        if (!results.isArray() || results.isEmpty()) {
            throw new IllegalStateException("Rerank 响应缺少 results: " + abbreviate(responseBody));
        }
        double[] scores = new double[docCount];
        for (JsonNode item : results) {
            int index = item.path("index").asInt(-1);
            if (index >= 0 && index < docCount) {
                scores[index] = item.path("relevance_score").asDouble(0.0);
            }
        }
        List<Double> out = new ArrayList<>(docCount);
        for (double s : scores) out.add(s);
        return out;
    }

    private String stripSlash(String url) {
        if (url == null) return null;
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String abbreviate(String body) {
        if (body == null) return "";
        return body.length() > 300 ? body.substring(0, 300) + "..." : body;
    }
}

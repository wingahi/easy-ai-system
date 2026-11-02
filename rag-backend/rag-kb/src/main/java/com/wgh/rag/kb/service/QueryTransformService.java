package com.wgh.rag.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.llm.factory.ModelCacheManager;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 查询变换服务：REWRITE（改写为规范查询）/ MULTI_QUERY（语义变体）/ HYDE（假设性答案）。
 * 任何异常、超时或空输出都降级为原查询，不阻断检索主流程。
 */
@Slf4j
@Service
public class QueryTransformService {

    /** LLM 变换超时（毫秒），超时降级原查询 */
    private static final long DEFAULT_TIMEOUT_MS = 5000;

    private static final String PROMPT_REWRITE = """
            你是检索查询改写助手。将用户的口语化问题改写为一条适合向量检索的规范查询语句，\
            保留关键实体与意图，不要回答问题。只输出改写后的查询，不要输出任何解释。
            用户问题：%s""";

    private static final String PROMPT_MULTI_QUERY = """
            你是检索查询扩展助手。针对用户问题生成 3 个语义等价但表述不同的检索查询变体，用于提升召回。\
            以 JSON 数组格式输出，例如 ["变体1","变体2","变体3"]，不要输出其他内容。
            用户问题：%s""";

    private static final String PROMPT_HYDE = """
            请针对以下问题写一段 150 字以内的假设性答案（内容合理即可，允许虚构），用于语义检索。\
            只输出答案正文，不要输出解释。
            问题：%s""";

    private final ModelCacheManager modelCacheManager;
    private final ObjectMapper objectMapper;
    private final long timeoutMs;
    private final ExecutorService executor;

    @org.springframework.beans.factory.annotation.Autowired
    public QueryTransformService(ModelCacheManager modelCacheManager, ObjectMapper objectMapper) {
        this(modelCacheManager, objectMapper, DEFAULT_TIMEOUT_MS);
    }

    /** 测试用：可指定更短超时 */
    QueryTransformService(ModelCacheManager modelCacheManager, ObjectMapper objectMapper, long timeoutMs) {
        this.modelCacheManager = modelCacheManager;
        this.objectMapper = objectMapper;
        this.timeoutMs = timeoutMs;
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "query-transform");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 变换结果
     *
     * @param vectorQueries      实际走向量 embedding 的查询列表（MULTI_QUERY 含原查询 + 变体）
     * @param transformedQueries 展示用的变换产物（REWRITE→改写句；MULTI_QUERY→变体；HYDE→假设答案）
     * @param applied            是否实际执行了 LLM 变换（降级为 false）
     * @param latencyMs          变换耗时（未执行时为 0）
     */
    public record TransformResult(List<String> vectorQueries, List<String> transformedQueries,
                                  boolean applied, long latencyMs) {
        static TransformResult original(String query) {
            return new TransformResult(List.of(query), List.of(), false, 0);
        }
    }

    /**
     * 执行查询变换。mode 为 NONE / null / 空、模型不可用、超时、异常、空输出 → 全部降级原查询。
     */
    public TransformResult transform(String query, String mode, Long chatModelId) {
        if (query == null || query.isBlank() || mode == null || mode.isBlank()
                || "NONE".equalsIgnoreCase(mode) || chatModelId == null) {
            return TransformResult.original(query);
        }
        ChatModel model = modelCacheManager.getChatModel(chatModelId);
        if (model == null) {
            log.warn("查询变换模型 id={} 不可用，降级原查询", chatModelId);
            return TransformResult.original(query);
        }

        String upperMode = mode.trim().toUpperCase();
        String prompt = switch (upperMode) {
            case "REWRITE" -> String.format(PROMPT_REWRITE, query);
            case "MULTI_QUERY" -> String.format(PROMPT_MULTI_QUERY, query);
            case "HYDE" -> String.format(PROMPT_HYDE, query);
            default -> {
                log.warn("未知查询变换模式 {}，降级原查询", mode);
                yield null;
            }
        };
        if (prompt == null) return TransformResult.original(query);

        long start = System.currentTimeMillis();
        try {
            String output = CompletableFuture
                    .supplyAsync(() -> model.chat(prompt), executor)
                    .get(timeoutMs, TimeUnit.MILLISECONDS);
            long latency = System.currentTimeMillis() - start;
            if (output == null || output.isBlank()) {
                log.warn("查询变换输出为空（{}），降级原查询", upperMode);
                return TransformResult.original(query);
            }
            return switch (upperMode) {
                case "REWRITE" -> {
                    String rewritten = output.trim();
                    log.info("查询改写完成: [{}] → [{}], 耗时={}ms", query, rewritten, latency);
                    yield new TransformResult(List.of(rewritten), List.of(rewritten), true, latency);
                }
                case "MULTI_QUERY" -> parseMultiQuery(query, output, latency);
                case "HYDE" -> {
                    String answer = output.trim();
                    log.info("HyDE 假设答案生成完成: {} 字, 耗时={}ms", answer.length(), latency);
                    yield new TransformResult(List.of(answer), List.of(answer), true, latency);
                }
                default -> TransformResult.original(query);
            };
        } catch (Exception e) {
            log.warn("查询变换失败（{}），降级原查询: {}", upperMode, e.getMessage());
            return TransformResult.original(query);
        }
    }

    /**
     * 解析 MULTI_QUERY 输出：从响应中提取首个 JSON 数组；解析失败/空数组降级原查询。
     * 向量路 = 原查询 + 变体（去重），保证原意图不丢。
     */
    private TransformResult parseMultiQuery(String query, String output, long latency) {
        try {
            int begin = output.indexOf('[');
            int end = output.lastIndexOf(']');
            if (begin < 0 || end <= begin) throw new IllegalArgumentException("响应中未找到 JSON 数组");
            List<String> variants = objectMapper.readValue(
                    output.substring(begin, end + 1),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            List<String> cleaned = variants.stream()
                    .filter(v -> v != null && !v.isBlank())
                    .map(String::trim)
                    .toList();
            if (cleaned.isEmpty()) throw new IllegalArgumentException("JSON 数组为空");
            LinkedHashSet<String> vectorQueries = new LinkedHashSet<>();
            vectorQueries.add(query);
            vectorQueries.addAll(cleaned);
            log.info("Multi-Query 生成 {} 个变体, 耗时={}ms", cleaned.size(), latency);
            return new TransformResult(new ArrayList<>(vectorQueries), cleaned, true, latency);
        } catch (Exception e) {
            log.warn("Multi-Query 变体解析失败，降级原查询: {} | 响应: {}", e.getMessage(),
                    output.length() > 200 ? output.substring(0, 200) : output);
            return TransformResult.original(query);
        }
    }
}

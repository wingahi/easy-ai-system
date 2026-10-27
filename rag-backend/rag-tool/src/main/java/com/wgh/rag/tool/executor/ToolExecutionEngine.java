package com.wgh.rag.tool.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.tool.definition.ToolDefinitionParser;
import com.wgh.rag.tool.entity.ToolConfig;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 工具执行引擎 —— 窗口级工具的统一注册 + 执行入口
 *
 * 职责：
 * 1. 把一组 ToolConfig（由 ChatWindow.toolIds 引用）→ List<ToolSpecification>（给 LLM 看的）
 * 2. 维护 toolName → ToolConfig 的映射（执行时找到 executor + executorConfig）
 * 3. 分发 ToolExecutionRequest 到正确的 ToolExecutor 实现
 * 4. 维护窗口默认模型 ID（供 Skill 等子对话工具使用 subModelDefault=true 时继承）
 *
 * 每个 ChatWindow 的请求是隔离的：loadWindowTools() 按窗口重新加载，
 * 不会让窗口 A 的工具泄露给窗口 B。
 */
@Slf4j
@Service
public class ToolExecutionEngine {

    /** executorConfig 注入的窗口默认模型 key（$ 前缀避免覆盖用户配置） */
    public static final String WINDOW_DEFAULT_MODEL_KEY = "$windowDefaultModelId";

    private final List<ToolExecutor> executors;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 当前会话的 toolName → ToolConfig 映射 */
    private final ThreadLocal<Map<String, ToolConfig>> nameToConfig = ThreadLocal.withInitial(HashMap::new);

    /** 当前窗口的默认 LLM 模型 ID（供 Skill 子对话工具继承） */
    private final ThreadLocal<Long> windowDefaultModelId = new ThreadLocal<>();

    public ToolExecutionEngine(List<ToolExecutor> executors) {
        this.executors = executors;
    }

    /**
     * 按窗口加载工具 —— 不设置默认模型（兼容旧调用）
     */
    public List<ToolSpecification> loadWindowTools(List<ToolConfig> configs) {
        return loadWindowTools(configs, null);
    }

    /**
     * 按窗口加载工具 + 设置窗口默认模型（供 Skill 工具继承）
     *
     * @param configs      该窗口启用的 ToolConfig 列表（已 status=1 过滤）
     * @param defaultModelId 该窗口默认的 LLM 模型 ID（用于 Skill subModelDefault=true 场景）
     * @return 给 LLM 看的 ToolSpecification 列表
     */
    public List<ToolSpecification> loadWindowTools(List<ToolConfig> configs, Long defaultModelId) {
        Map<String, ToolConfig> map = new HashMap<>();
        List<ToolSpecification> specs = new ArrayList<>();

        for (ToolConfig cfg : configs) {
            if (cfg.getStatus() == null || cfg.getStatus() != 1) continue;
            try {
                ToolSpecification spec = ToolDefinitionParser.parse(cfg.getDefinition());
                map.put(spec.name(), cfg);
                specs.add(spec);
                log.info("注册窗口工具: {} (type={})", spec.name(), cfg.getToolType());
            } catch (Exception e) {
                log.error("工具 definition 解析失败，跳过: id={}, name={}", cfg.getId(), cfg.getName(), e);
            }
        }

        nameToConfig.set(map);
        windowDefaultModelId.set(defaultModelId);
        log.info("当前窗口注册工具数: {}, 默认模型: {}", specs.size(), defaultModelId);
        return specs;
    }

    /**
     * 执行 LLM 发起的工具调用
     */
    public ToolExecutionResultMessage execute(ToolExecutionRequest request) {
        return execute(request, nameToConfig.get(), windowDefaultModelId.get());
    }

    /**
     * 执行工具调用（显式传入窗口工具注册表）
     *
     * 流式模型的回调可能运行在其他线程（如 ForkJoinPool），ThreadLocal 会失效，
     * 因此需要在发起调用前用 {@link #snapshotWindowTools()} 取快照，显式传进来。
     */
    public ToolExecutionResultMessage execute(ToolExecutionRequest request,
                                              Map<String, ToolConfig> registry,
                                              Long defaultModelId) {
        ToolConfig config = registry != null ? registry.get(request.name()) : null;
        if (config == null) {
            log.error("未知工具: {}（当前窗口注册了: {}）",
                    request.name(), registry != null ? registry.keySet() : "[]");
            return new ToolExecutionResultMessage(
                    request.id(), request.name(),
                    "Error: Tool '" + request.name() + "' is not available for this chat window"
            );
        }

        ToolExecutor executor = executors.stream()
                .filter(e -> Objects.equals(e.supportedToolType(), config.getToolType()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "未找到 toolType=" + config.getToolType() + " 对应的 ToolExecutor"));

        Map<String, Object> executorConfig = parseExecutorConfig(config.getExecutorConfig());
        // 注入窗口默认模型 ID（executor 可通过 subModelDefault=true 继承）
        if (defaultModelId != null) {
            executorConfig.put(WINDOW_DEFAULT_MODEL_KEY, defaultModelId);
        }
        return executor.execute(executorConfig, request);
    }

    /**
     * 导出当前线程的窗口工具注册表快照 —— 供异步回调线程执行工具时使用
     */
    public Map<String, ToolConfig> snapshotWindowTools() {
        return new HashMap<>(nameToConfig.get());
    }

    /**
     * 清理当前线程的工具映射 + 默认模型 —— 请求结束时调用
     */
    public void clearWindowTools() {
        nameToConfig.remove();
        windowDefaultModelId.remove();
    }

    /**
     * 批量执行工具调用 —— 一次 LLM response 可能包含多个 tool calls
     */
    public List<ToolExecutionResultMessage> executeBatch(List<ToolExecutionRequest> requests) {
        return requests.stream()
                .map(this::execute)
                .collect(Collectors.toList());
    }

    /**
     * 批量执行工具调用（显式传入注册表快照，供异步回调线程使用）
     */
    public List<ToolExecutionResultMessage> executeBatch(List<ToolExecutionRequest> requests,
                                                         Map<String, ToolConfig> registry,
                                                         Long defaultModelId) {
        return requests.stream()
                .map(r -> execute(r, registry, defaultModelId))
                .collect(Collectors.toList());
    }

    private Map<String, Object> parseExecutorConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) return new HashMap<>();
        try {
            return mapper.readValue(configJson, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception e) {
            log.warn("executorConfig JSON 解析失败: {}", configJson, e);
            return new HashMap<>();
        }
    }
}

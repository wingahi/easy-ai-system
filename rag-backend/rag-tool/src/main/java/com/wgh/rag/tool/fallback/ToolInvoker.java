package com.wgh.rag.tool.fallback;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.tool.entity.ToolConfig;
import com.wgh.rag.tool.executor.ToolExecutionEngine;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 工具调用兜底链入口：熔断短路 → 任务预算 → 分类 → 退避重试 → 结构化结果。
 *
 * <p>硬约束：
 * <ul>
 *   <li>只对可重试错误（网络 / 限流 / 5xx）重试；SSRF、401/403、400/422、404 一律不重试；</li>
 *   <li>{@code idempotentOnly=true} 时，仅 HTTP GET/HEAD 或 executorConfig.idempotent=true 允许重试；</li>
 *   <li>不改变工具返回文本格式（仍为 {@code Error: ...} / {@code HTTP <code> - ...}），
 *       结构化信息通过 {@link ToolInvocationOutcome} 回传。</li>
 * </ul>
 */
@Slf4j
@Service
public class ToolInvoker {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ERROR_PREFIX = "Error: ";

    private final ToolExecutionEngine executionEngine;
    private final ToolFallbackProperties properties;
    private final ToolCircuitBreaker circuitBreaker;

    public ToolInvoker(ToolExecutionEngine executionEngine,
                       ToolFallbackProperties properties,
                       ToolCircuitBreaker circuitBreaker) {
        this.executionEngine = executionEngine;
        this.properties = properties;
        this.circuitBreaker = circuitBreaker;
    }

    /** 创建一个任务级预算（每次用户请求 / Agent 步骤 / 工作流运行各一个）。 */
    public ToolTaskBudget newTaskBudget() {
        return ToolTaskBudget.of(properties);
    }

    /**
     * 执行一次工具调用（含兜底链）。
     *
     * @param budget 任务级预算；传 null 时本次调用使用独立预算（不跨调用累计）
     */
    public ToolInvocationOutcome invoke(ToolExecutionRequest request,
                                        Map<String, ToolConfig> registry,
                                        Long defaultModelId,
                                        ToolTaskBudget budget) {
        long start = System.currentTimeMillis();
        ToolConfig config = registry != null ? registry.get(request.name()) : null;
        ToolTaskBudget effectiveBudget = budget != null ? budget : newTaskBudget();

        if (!properties.isEnabled()) {
            return singleShot(request, registry, defaultModelId, start);
        }

        String circuitKey = circuitKey(request, config);
        if (!circuitBreaker.tryAcquire(circuitKey)) {
            log.warn("工具熔断中，短路调用: {} (key={})", request.name(), circuitKey);
            ToolExecutionResultMessage message = errorMessage(request,
                    ERROR_PREFIX + "CircuitOpenException - 工具连续失败已暂时熔断，请稍后重试或改用其他方式");
            return new ToolInvocationOutcome(message, false, ToolFailureKind.CIRCUIT_OPEN,
                    false, 0, System.currentTimeMillis() - start, true, false);
        }

        boolean idempotent = isIdempotent(config);
        int maxAttempts = Math.max(1, properties.getMaxAttempts());
        int attempts = 0;
        ToolExecutionResultMessage last = null;
        ToolFailureKind kind = null;

        while (attempts < maxAttempts) {
            if (!effectiveBudget.tryAcquire()) {
                log.warn("工具任务预算耗尽: tool={}, 已用请求={}", request.name(), effectiveBudget.used());
                ToolExecutionResultMessage message = errorMessage(request,
                        ERROR_PREFIX + "ToolBudgetExhaustedException - 本次任务工具调用预算已耗尽");
                return new ToolInvocationOutcome(message, false, ToolFailureKind.BUDGET_EXHAUSTED,
                        false, attempts, System.currentTimeMillis() - start, false, true);
            }
            attempts++;
            last = executeSafely(request, registry, defaultModelId);
            kind = ToolFailureClassifier.classify(last.text());
            if (kind == null) {
                circuitBreaker.onSuccess(circuitKey);
                return ToolInvocationOutcome.success(last, attempts,
                        System.currentTimeMillis() - start);
            }
            if (!shouldRetry(kind, idempotent, attempts, maxAttempts, effectiveBudget)) {
                break;
            }
            if (!sleepBackoff(attempts, effectiveBudget)) {
                break;
            }
            log.info("工具调用失败可重试，第 {} 次重试: {} ({})", attempts, request.name(), kind);
        }

        circuitBreaker.onFailure(circuitKey);
        log.warn("工具调用最终失败: {}, 分类={}, 尝试={} 次", request.name(), kind, attempts);
        return ToolInvocationOutcome.failure(last, kind, attempts, System.currentTimeMillis() - start);
    }

    private ToolInvocationOutcome singleShot(ToolExecutionRequest request,
                                             Map<String, ToolConfig> registry,
                                             Long defaultModelId,
                                             long start) {
        ToolExecutionResultMessage message = executeSafely(request, registry, defaultModelId);
        ToolFailureKind kind = ToolFailureClassifier.classify(message.text());
        long latency = System.currentTimeMillis() - start;
        return kind == null
                ? ToolInvocationOutcome.success(message, 1, latency)
                : ToolInvocationOutcome.failure(message, kind, 1, latency);
    }

    private ToolExecutionResultMessage executeSafely(ToolExecutionRequest request,
                                                     Map<String, ToolConfig> registry,
                                                     Long defaultModelId) {
        try {
            return executionEngine.execute(request, registry, defaultModelId);
        } catch (Exception e) {
            log.error("工具执行异常: {}", request.name(), e);
            return errorMessage(request, ERROR_PREFIX + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    /**
     * 重试判定顺序：
     * 分类可重试 → 幂等约束 → 剩余次数 → 剩余时长。
     */
    private boolean shouldRetry(ToolFailureKind kind, boolean idempotent,
                                int attempts, int maxAttempts, ToolTaskBudget budget) {
        if (kind == null || !kind.retryable()) {
            // 含显式排除的 SSRF：绝不重试
            return false;
        }
        if (properties.isIdempotentOnly() && !idempotent) {
            return false;
        }
        if (attempts >= maxAttempts) {
            return false;
        }
        return budget.remainingMs() > 0;
    }

    /** 指数退避 + 抖动，返回 false 表示被中断或预算不足。 */
    private boolean sleepBackoff(int attempts, ToolTaskBudget budget) {
        long base = Math.max(properties.getBackoffMs(), 0L) * (1L << Math.min(attempts - 1, 6));
        double jitter = 1 + properties.getJitterRatio() * (ThreadLocalRandom.current().nextDouble() * 2 - 1);
        long delay = Math.max((long) (base * jitter), 0L);
        delay = Math.min(delay, budget.remainingMs());
        if (delay <= 0) {
            return false;
        }
        try {
            Thread.sleep(delay);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private String circuitKey(ToolExecutionRequest request, ToolConfig config) {
        if (config != null && config.getId() != null) {
            return String.valueOf(config.getId());
        }
        return "name:" + request.name();
    }

    /** 幂等判定：executorConfig.idempotent 优先，否则 HTTP GET/HEAD 视为幂等。 */
    private boolean isIdempotent(ToolConfig config) {
        if (config == null) {
            return false;
        }
        Map<String, Object> cfg = parseConfig(config.getExecutorConfig());
        Object flag = cfg.get("idempotent");
        if (flag != null) {
            return Boolean.parseBoolean(String.valueOf(flag));
        }
        if ("HTTP".equalsIgnoreCase(config.getToolType())) {
            String method = String.valueOf(cfg.getOrDefault("method", "GET")).toUpperCase();
            return "GET".equals(method) || "HEAD".equals(method);
        }
        return false;
    }

    private Map<String, Object> parseConfig(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.debug("解析工具执行配置失败，按默认配置处理: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    private ToolExecutionResultMessage errorMessage(ToolExecutionRequest request, String text) {
        return new ToolExecutionResultMessage(request.id(), request.name(), text);
    }
}

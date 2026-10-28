package com.wgh.rag.agentmulti.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多 Agent 运行时上下文 —— 跨 Coordinator / Agent 执行传递数据、循环状态、pending 状态与对话历史。
 * <p>
 * 参考 workflow RunContext 的 vars + nodeOutputs 风格，但简化为扁平 Map，
 * 并额外支持 per-agent 对话历史（供 GroupChat 使用）。
 */
@Slf4j
public class MultiAgentContext {

    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([\\w.\\-]+)\\s*\\}\\}");

    /** 运行时数据：agentId.text / agentId.summary / vars.xxx 等 */
    private final Map<String, Object> data = new ConcurrentHashMap<>();

    /** 业务变量（显式区分于运行时产物） */
    private final Map<String, Object> vars = new ConcurrentHashMap<>();

    /** loopId → 当前迭代次数 */
    private final Map<String, Integer> loopIterations = new ConcurrentHashMap<>();

    /** agentId → 尚未消费的依赖 agentId 集合（用于 parallel 依赖跟踪） */
    private final Map<String, Set<String>> pendingAgents = new ConcurrentHashMap<>();

    /** agentId → 该 Agent 的 Chat 历史（供 GroupChat / Reflection 读取） */
    private final Map<String, List<Object>> history = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;

    public MultiAgentContext() {
        this(new ObjectMapper());
    }

    public MultiAgentContext(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    // ==================== 通用数据访问 ====================

    public MultiAgentContext put(String key, Object value) {
        data.put(key, value);
        return this;
    }

    public Object get(String key) {
        return data.get(key);
    }

    public Object getOrDefault(String key, Object defaultValue) {
        return data.getOrDefault(key, defaultValue);
    }

    public boolean containsKey(String key) {
        return data.containsKey(key);
    }

    public Map<String, Object> allData() {
        return data;
    }

    // ==================== 变量区 ====================

    public MultiAgentContext setVars(String name, Object value) {
        vars.put(name, value);
        return this;
    }

    public Object getVars(String name) {
        return vars.get(name);
    }

    public Map<String, Object> getVarsMap() {
        return vars;
    }

    // ==================== 循环迭代 ====================

    public int incrementLoop(String loopId) {
        return loopIterations.merge(loopId, 1, Integer::sum);
    }

    public int getLoopIteration(String loopId) {
        return loopIterations.getOrDefault(loopId, 0);
    }

    public Map<String, Integer> loopIterations() {
        return loopIterations;
    }

    // ==================== Pending Agents ====================

    public Set<String> getPendingAgents(String agentId) {
        return pendingAgents.computeIfAbsent(agentId, k -> ConcurrentHashMap.newKeySet());
    }

    public Map<String, Set<String>> pendingAgents() {
        return pendingAgents;
    }

    // ==================== 历史 ====================

    public List<Object> getHistory(String agentId) {
        return history.computeIfAbsent(agentId, k -> new ArrayList<>());
    }

    public void appendHistory(String agentId, Object message) {
        getHistory(agentId).add(message);
    }

    public Map<String, List<Object>> allHistory() {
        return history;
    }

    // ==================== 模板渲染 ====================

    /**
     * 用 {{path}} 模板语法渲染字符串。
     * <p>
     * path 解析优先级：
     * <ol>
     *   <li>vars.x — 从 vars 取</li>
     *   <li>last / input — 特殊别名</li>
     *   <li>loop.iteration / loop.xxx — 循环迭代</li>
     *   <li>agentId.text / agentId.xxx — 运行时 data</li>
     * </ol>
     */
    public String render(String template) {
        if (template == null || template.isEmpty()) return template == null ? "" : template;
        Matcher matcher = VAR.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            Object value = resolvePath(matcher.group(1));
            matcher.appendReplacement(sb, Matcher.quoteReplacement(stringify(value)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    public Object resolvePath(String path) {
        if (path == null) return null;
        String[] parts = path.split("\\.");
        if (parts.length == 0) return null;

        switch (parts[0]) {
            case "vars" -> {
                if (parts.length < 2) return null;
                return traverse(vars.get(parts[1]), parts, 2);
            }
            case "input" -> {
                Object v = vars.get("input");
                if (v != null) return v;
                return data.get("input");
            }
            case "last" -> {
                Object v = vars.get("lastOutput");
                if (v != null) return v;
                return data.get("lastOutput");
            }
            case "loop" -> {
                if (parts.length > 1 && "iteration".equals(parts[1])) return currentLoopIteration();
                return loopIterations.get(parts.length > 1 ? parts[1] : null);
            }
            default -> {
                Object current = data.get(parts[0]);
                return traverse(current, parts, 1);
            }
        }
    }

    private Object currentLoopIteration() {
        Optional<Integer> max = loopIterations.values().stream().max(Integer::compare);
        return max.orElse(0);
    }

    @SuppressWarnings("unchecked")
    private Object traverse(Object current, String[] parts, int from) {
        for (int i = from; i < parts.length && current != null; i++) {
            String key = parts[i];
            if (current instanceof Map<?, ?> map) {
                current = ((Map<String, Object>) map).get(key);
            } else if (current instanceof List<?> list) {
                try {
                    int idx = Integer.parseInt(key);
                    current = idx >= 0 && idx < list.size() ? list.get(idx) : null;
                } catch (NumberFormatException e) {
                    return null;
                }
            } else {
                return null;
            }
        }
        return current;
    }

    public String stringify(Object value) {
        if (value == null) return "";
        if (value instanceof String s) return s;
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            try {
                return objectMapper.writeValueAsString(value);
            } catch (Exception e) {
                log.warn("JSON stringify 失败", e);
                return String.valueOf(value);
            }
        }
        return String.valueOf(value);
    }

    // ==================== Snapshot ====================

    /** 返回一份浅拷贝快照（用于持久化 contextJson） */
    public Map<String, Object> snapshot() {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("data", new LinkedHashMap<>(data));
        snap.put("vars", new LinkedHashMap<>(vars));
        snap.put("loopIterations", new LinkedHashMap<>(loopIterations));
        snap.put("pendingAgents", new LinkedHashMap<>(pendingAgents));
        snap.put("history", new LinkedHashMap<>(history));
        return snap;
    }
}

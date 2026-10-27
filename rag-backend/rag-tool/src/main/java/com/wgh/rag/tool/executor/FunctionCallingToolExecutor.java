package com.wgh.rag.tool.executor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

/**
 * FUNCTION_CALLING 工具执行器 —— 反射调用本地 Java handler 方法
 *
 * <h3>executorConfig schema：</h3>
 * <pre>
 * {
 *   "handlerClass": "com.wgh.rag.tool.demo.OrderQueryHandler",  // 全限定类名
 *   "method": "query"                                          // 方法名（默认 execute）
 * }
 * </pre>
 *
 * 约定：handler 方法接收一个 {@code Map<String,Object>} 参数（即 LLM 传来的 arguments），
 * 返回 String 时直接作为工具结果，返回其它对象时序列化为 JSON。
 */
@Slf4j
@Component
public class FunctionCallingToolExecutor implements ToolExecutor {

    private static final String TYPE = "FUNCTION_CALLING";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String supportedToolType() {
        return TYPE;
    }

    @Override
    public ToolExecutionResultMessage execute(Map<String, Object> executorConfig, ToolExecutionRequest request) {
        long start = System.currentTimeMillis();
        try {
            String className = (String) executorConfig.get("handlerClass");
            String methodName = (String) executorConfig.getOrDefault("method", "execute");
            if (className == null || className.isBlank()) {
                return error(request, "executorConfig.handlerClass 不能为空");
            }

            Map<String, Object> args = parseArguments(request.arguments());
            Object result = invoke(className, methodName, args);

            String text = result == null ? "null"
                    : (result instanceof String s ? s : MAPPER.writeValueAsString(result));
            log.info("Function Calling 工具调用: {}.{} (args={}, {}ms)", className, methodName, args,
                    System.currentTimeMillis() - start);
            return new ToolExecutionResultMessage(request.id(), request.name(), text);

        } catch (Exception e) {
            log.error("Function Calling 工具调用失败: {}", request.name(), e);
            return error(request, e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    private Object invoke(String className, String methodName, Map<String, Object> args) throws Exception {
        Class<?> clazz = Class.forName(className);
        Method target = findMethod(clazz, methodName);
        target.setAccessible(true);

        Object instance = Modifier.isStatic(target.getModifiers())
                ? null
                : clazz.getDeclaredConstructor().newInstance();

        if (target.getParameterCount() == 0) {
            return target.invoke(instance);
        }
        return target.invoke(instance, args);
    }

    /** 优先匹配单参数 (Map) 方法，其次匹配无参方法 */
    private Method findMethod(Class<?> clazz, String methodName) throws NoSuchMethodException {
        Method noArg = null;
        for (Method m : clazz.getDeclaredMethods()) {
            if (!m.getName().equals(methodName)) continue;
            if (m.getParameterCount() == 1 && Map.class.isAssignableFrom(m.getParameterTypes()[0])) {
                return m;
            }
            if (m.getParameterCount() == 0) noArg = m;
        }
        if (noArg != null) return noArg;
        throw new NoSuchMethodException(clazz.getName() + "#" + methodName
                + " 不存在（需为无参方法或单参 Map 方法）");
    }

    private Map<String, Object> parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) return new HashMap<>();
        try {
            return MAPPER.readValue(argumentsJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("工具参数解析失败，当作空 map: {}", argumentsJson, e);
            return new HashMap<>();
        }
    }

    private ToolExecutionResultMessage error(ToolExecutionRequest request, String message) {
        return new ToolExecutionResultMessage(request.id(), request.name(), "Error: " + message);
    }
}

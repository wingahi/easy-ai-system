package com.wgh.rag.execution.model;

import java.util.Map;
import java.util.Objects;

/**
 * 作业负载。
 *
 * <p>跨进程（后台作业表）与进程内（交互式提交）使用同一结构。{@code schemaVersion} 用于 payload
 * 演进：旧版本 payload 遇到不兼容的新代码时，调度层应直接置为
 * {@link com.wgh.rag.execution.model.ExecutionStatus#DEAD}，错误码
 * {@link com.wgh.rag.execution.error.ExecutionErrorCodes#INCOMPATIBLE_PAYLOAD}。
 *
 * @param schemaVersion 负载结构版本，从 {@link #CURRENT_SCHEMA_VERSION} 开始
 * @param data          不可变负载数据，序列化由调度层实现负责
 */
public record JobPayload(int schemaVersion, Map<String, Object> data) {

    /** 当前负载结构版本。 */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public JobPayload {
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion 必须为正数");
        }
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    /**
     * 空负载。
     *
     * @return 当前版本的空负载
     */
    public static JobPayload empty() {
        return new JobPayload(CURRENT_SCHEMA_VERSION, Map.of());
    }

    /**
     * 以当前版本构造负载。
     *
     * @param data 负载数据，可为 {@code null}
     * @return 负载
     */
    public static JobPayload of(Map<String, Object> data) {
        return new JobPayload(CURRENT_SCHEMA_VERSION, data);
    }

    /**
     * 读取字符串字段。
     *
     * @param key 字段名
     * @return 字段值；不存在或非字符串时返回 {@code null}
     */
    public String getString(String key) {
        Objects.requireNonNull(key, "key");
        Object value = data.get(key);
        return value instanceof String text ? text : null;
    }
}

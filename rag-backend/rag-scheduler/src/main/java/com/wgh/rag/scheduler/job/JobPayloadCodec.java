package com.wgh.rag.scheduler.job;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 作业负载 JSON 编解码。
 *
 * <p>调度层负责序列化：契约层的 {@code JobPayload} 只声明结构，不绑定任何 JSON 库。
 * 反序列化失败说明历史 payload 已不可读，调用方应按 {@code INCOMPATIBLE_PAYLOAD} 处理。
 */
@Component
public class JobPayloadCodec {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    /** 负载 → JSON。 */
    public String toJson(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data == null ? Map.of() : data);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("作业负载无法序列化为 JSON", e);
        }
    }

    /** JSON → 负载；空串/空白按空对象处理。 */
    public Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, MAP_TYPE);
            return parsed == null ? Map.of() : new LinkedHashMap<>(parsed);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("作业负载 JSON 无法解析", e);
        }
    }
}

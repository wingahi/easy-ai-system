package com.wgh.rag.chat.service.window;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 窗口级配置解析（知识库 ID 等 JSON 字段）。
 */
@Slf4j
public final class ChatWindowSettings {

    private ChatWindowSettings() {
    }

    /** 解析窗口绑定的知识库 ID 列表；格式错误时返回空列表并记录日志。 */
    public static List<Long> parseKbIds(ObjectMapper objectMapper, String kbIdsJson) {
        if (kbIdsJson == null || kbIdsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(kbIdsJson, new TypeReference<List<Long>>() {
            });
        } catch (Exception e) {
            log.warn("解析知识库ID列表失败: {}", kbIdsJson);
            return List.of();
        }
    }
}

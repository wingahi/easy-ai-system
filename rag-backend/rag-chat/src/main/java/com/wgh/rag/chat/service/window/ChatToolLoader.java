package com.wgh.rag.chat.service.window;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.chat.entity.ChatWindow;
import com.wgh.rag.tool.entity.ToolConfig;
import com.wgh.rag.tool.executor.ToolExecutionEngine;
import com.wgh.rag.tool.service.ToolConfigService;
import dev.langchain4j.agent.tool.ToolSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 窗口级工具加载：只注册该窗口 toolIds 指定且启用的工具，实现窗口间隔离。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatToolLoader {

    private final ObjectMapper objectMapper;
    private final ToolConfigService toolConfigService;
    private final ToolExecutionEngine toolExecutionEngine;

    public List<ToolSpecification> load(ChatWindow window) {
        String toolIdsJson = window.getToolIds();
        if (toolIdsJson == null || toolIdsJson.isBlank()) {
            log.info("窗口 {} 未配置工具，跳过工具加载", window.getId());
            return List.of();
        }

        List<Long> toolIds;
        try {
            toolIds = objectMapper.readValue(toolIdsJson, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("解析窗口 toolIds 失败: {}", toolIdsJson, e);
            return List.of();
        }

        if (toolIds.isEmpty()) {
            return List.of();
        }

        List<ToolConfig> configs = toolConfigService.listByIds(toolIds).stream()
                .filter(c -> c.getStatus() != null && c.getStatus() == 1)
                .toList();

        if (configs.isEmpty()) {
            log.info("窗口 {} 配置的 {} 个工具全部禁用或不存在", window.getId(), toolIds.size());
            return List.of();
        }

        return toolExecutionEngine.loadWindowTools(configs, window.getLlmModelId());
    }
}

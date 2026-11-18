package com.wgh.rag.workflow.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.tool.entity.ToolConfig;
import com.wgh.rag.tool.executor.ToolExecutionEngine;
import com.wgh.rag.tool.fallback.ToolCircuitBreaker;
import com.wgh.rag.tool.fallback.ToolFallbackProperties;
import com.wgh.rag.tool.fallback.ToolInvoker;
import com.wgh.rag.tool.service.ToolConfigService;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 接线测试：确认工作流 TOOL 节点经过兜底链（重试生效、错误文本格式不变）。
 */
@DisplayName("ToolNodeInvoker 兜底接线")
class ToolNodeInvokerFallbackTest {

    private ToolExecutionEngine engine;
    private ToolConfigService toolConfigService;
    private ToolNodeInvoker invoker;
    private ToolFallbackProperties properties;

    @BeforeEach
    void setUp() {
        engine = Mockito.mock(ToolExecutionEngine.class);
        toolConfigService = Mockito.mock(ToolConfigService.class);
        properties = new ToolFallbackProperties();
        properties.setBackoffMs(1);
        properties.setJitterRatio(0);
        invoker = new ToolNodeInvoker(toolConfigService,
                new ToolInvoker(engine, properties, new ToolCircuitBreaker(properties)),
                new ObjectMapper());

        ToolConfig tool = new ToolConfig();
        tool.setId(1L);
        tool.setName("t");
        tool.setToolType("HTTP");
        tool.setExecutorConfig("{\"method\":\"GET\"}");
        when(toolConfigService.getById(1L)).thenReturn(tool);
    }

    @Test
    @DisplayName("网络错误重试后返回成功文本")
    void retryThenReturnText() {
        when(engine.execute(any(), any(), any()))
                .thenReturn(new ToolExecutionResultMessage("wf-1", "t", "Error: HttpTimeoutException - timeout"))
                .thenReturn(new ToolExecutionResultMessage("wf-1", "t", "ok"));

        String text = invoker.invoke(1L, Map.of());

        assertThat(text).isEqualTo("ok");
        verify(engine, times(2)).execute(any(), any(), any());
    }

    @Test
    @DisplayName("SSRF 拦截不重试，返回 Error 文本")
    void ssrfNotRetried() {
        when(engine.execute(any(), any(), any()))
                .thenReturn(new ToolExecutionResultMessage("wf-1", "t", "Error: SsrfBlockedException - blocked"));

        String text = invoker.invoke(1L, Map.of());

        assertThat(text).startsWith("Error: ");
        verify(engine, times(1)).execute(any(), any(), any());
    }
}

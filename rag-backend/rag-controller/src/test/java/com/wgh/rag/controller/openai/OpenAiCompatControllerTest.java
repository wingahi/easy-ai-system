package com.wgh.rag.controller.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.chat.entity.ChatSession;
import com.wgh.rag.chat.entity.ChatWindow;
import com.wgh.rag.chat.service.ChatWindowService;
import com.wgh.rag.chat.service.RagChatService;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.PageResult;
import com.wgh.rag.common.result.ResultCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 控制器层验证：鉴权身份、非流式结构、流式分块、错误结构、models 列表。
 */
class OpenAiCompatControllerTest {

    private ChatWindowService windowService;
    private RagChatService ragChatService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        windowService = mock(ChatWindowService.class);
        ragChatService = mock(RagChatService.class);
        OpenAiCompatController controller =
                new OpenAiCompatController(windowService, ragChatService, new ObjectMapper());
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new OpenAiErrorHandler())
                .build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "api_7", null, List.of(new SimpleGrantedAuthority("ROLE_API_CLIENT"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldReturnOpenAiCompletionForNonStreamingRequest() throws Exception {
        when(windowService.findById(1L)).thenReturn(window());
        when(windowService.createSession(eq(1L), anyString())).thenReturn(session(99L));
        when(ragChatService.streamChat(eq(1L), eq(99L), eq("你好"), eq(true), any()))
                .thenReturn(emitterProducing("你", "好"));

        MvcResult result = mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model":"wgh-window-1","messages":[{"role":"user","content":"你好"}]}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(10_000L);

        MvcResult dispatched = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("chat.completion"))
                .andExpect(jsonPath("$.model").value("wgh-window-1"))
                .andExpect(jsonPath("$.choices[0].message.role").value("assistant"))
                .andExpect(jsonPath("$.choices[0].message.content").value("你好"))
                .andExpect(jsonPath("$.choices[0].finish_reason").value("stop"))
                .andExpect(jsonPath("$.usage.total_tokens").isNumber())
                .andExpect(jsonPath("$.wgh_session_id").value(99))
                .andReturn();
        assertThat(dispatched.getResponse().getContentType()).contains(MediaType.APPLICATION_JSON_VALUE);
    }

    @Test
    void shouldStreamOpenAiChunksIncrementally() throws Exception {
        when(windowService.findById(1L)).thenReturn(window());
        when(windowService.createSession(eq(1L), anyString())).thenReturn(session(99L));
        when(ragChatService.streamChat(eq(1L), eq(99L), eq("你好"), eq(true), any()))
                .thenReturn(emitterProducing("你", "好"));

        MvcResult result = mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model":"wgh-window-1","stream":true,
                                 "messages":[{"role":"user","content":"你好"}]}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(10_000L);

        MvcResult dispatched = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn();
        String body = dispatched.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body)
                .contains("data: {")
                .contains("\"object\":\"chat.completion.chunk\"")
                .contains("\"delta\":{\"role\":\"assistant\",\"content\":\"\"}")
                .contains("\"delta\":{\"content\":\"你\"}")
                .contains("\"delta\":{\"content\":\"好\"}")
                .contains("\"finish_reason\":\"stop\"")
                .contains("data: [DONE]");
    }

    @Test
    void shouldReturnModelNotFoundError() throws Exception {
        when(windowService.findById(404L)).thenReturn(null);
        when(windowService.page(eq(1L), eq(500L), eq("missing")))
                .thenReturn(new PageResult<>(0L, 1L, 500L, List.of()));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model":"missing","messages":[{"role":"user","content":"hi"}]}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
                .andExpect(jsonPath("$.error.code").value("model_not_found"));
    }

    @Test
    void shouldRejectEmptyMessages() throws Exception {
        when(windowService.findById(1L)).thenReturn(window());

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model":"wgh-window-1","messages":[]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_request"));
    }

    @Test
    void shouldMapQuotaExceededToRateLimitError() throws Exception {
        when(windowService.findById(1L)).thenReturn(window());
        when(windowService.createSession(eq(1L), anyString())).thenReturn(session(99L));
        when(ragChatService.streamChat(eq(1L), eq(99L), eq("hi"), eq(true), any()))
                .thenThrow(new BusinessException(ResultCode.QUOTA_EXCEEDED, "配额已用尽"));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model":"wgh-window-1","messages":[{"role":"user","content":"hi"}]}
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.type").value("rate_limit_error"))
                .andExpect(jsonPath("$.error.code").value("insufficient_quota"));
    }

    @Test
    void shouldListEnabledWindowsAsModels() throws Exception {
        when(windowService.page(eq(1L), eq(500L), eq(null)))
                .thenReturn(new PageResult<>(2L, 1L, 500L, List.of(window(), disabledWindow(2L))));

        mockMvc.perform(get("/v1/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("list"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value("wgh-window-1"))
                .andExpect(jsonPath("$.data[0].object").value("model"));
    }

    private ChatWindow window() {
        return window(1L, "默认窗口", 1);
    }

    private ChatWindow disabledWindow(long id) {
        return window(id, "停用窗口", 0);
    }

    private ChatWindow window(long id, String name, int status) {
        ChatWindow window = new ChatWindow();
        window.setId(id);
        window.setName(name);
        window.setStatus(status);
        window.setCreateTime(LocalDateTime.of(2026, 1, 1, 0, 0));
        return window;
    }

    private ChatSession session(long id) {
        ChatSession session = new ChatSession();
        session.setId(id);
        return session;
    }

    private SseEmitter emitterProducing(String... chunks) {
        SseEmitter emitter = new SseEmitter(30_000L);
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(10);
                for (String chunk : chunks) {
                    emitter.send(SseEmitter.event().name("content").data(Map.of("text", chunk)));
                }
                emitter.send(SseEmitter.event().name("done")
                        .data(Map.of("fullResponse", String.join("", chunks))));
                emitter.complete();
            } catch (Exception ignored) {
                // 测试线程结束
            }
        });
        thread.setDaemon(true);
        thread.start();
        return emitter;
    }
}

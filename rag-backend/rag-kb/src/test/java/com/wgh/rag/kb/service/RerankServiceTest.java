package com.wgh.rag.kb.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.wgh.rag.llm.entity.LlmModel;
import com.wgh.rag.llm.mapper.LlmModelMapper;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/**
 * RerankService 单测：用 JDK 内置 HttpServer 验证两种 provider 的报文构造与解析
 */
class RerankServiceTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private volatile int responseStatus = 200;
    private volatile String responseBody = "{}";

    private final LlmModelMapper mapper = Mockito.mock(LlmModelMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RerankService service = new RerankService(mapper, objectMapper);

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private LlmModel model(String provider, String url) {
        LlmModel m = new LlmModel();
        m.setId(9L);
        m.setModelType("RERANK");
        m.setProvider(provider);
        m.setModelName("gte-rerank-v2");
        m.setBaseUrl(url);
        m.setApiKey("sk-test");
        m.setStatus(1);
        return m;
    }

    @Test
    void dashscope_rerank_posts_to_services_path_and_parses_nested_results() throws Exception {
        responseBody = "{\"output\":{\"results\":[{\"index\":0,\"relevance_score\":0.31},{\"index\":1,\"relevance_score\":0.92}]}}";
        Mockito.when(mapper.selectById(9L)).thenReturn(model("DASHSCOPE", baseUrl));

        List<Double> scores = service.scoreAll(
                List.of(TextSegment.from("文档A"), TextSegment.from("文档B")), "查询", 9L);

        assertThat(scores).containsExactly(0.31, 0.92);
        JsonNode body = objectMapper.readTree(lastBody.get());
        assertThat(body.path("model").asText()).isEqualTo("gte-rerank-v2");
        assertThat(body.path("input").path("query").asText()).isEqualTo("查询");
        assertThat(body.path("input").path("documents")).hasSize(2);
        assertThat(body.path("parameters").path("top_n").asInt()).isEqualTo(2);
        assertThat(lastAuth.get()).isEqualTo("Bearer sk-test");
    }

    @Test
    void custom_rerank_posts_to_slash_rerank_and_parses_root_results() throws Exception {
        responseBody = "{\"results\":[{\"index\":1,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.2}]}";
        Mockito.when(mapper.selectById(9L)).thenReturn(model("CUSTOM_RERANK", baseUrl + "/v1"));

        List<Double> scores = service.scoreAll(
                List.of(TextSegment.from("文档A"), TextSegment.from("文档B")), "查询", 9L);

        assertThat(scores).containsExactly(0.2, 0.9);
        JsonNode body = objectMapper.readTree(lastBody.get());
        assertThat(body.path("model").asText()).isEqualTo("gte-rerank-v2");
        assertThat(body.path("query").asText()).isEqualTo("查询");
        assertThat(body.path("documents")).hasSize(2);
    }

    @Test
    void http_error_throws_for_caller_fallback() {
        responseStatus = 401;
        responseBody = "{\"error\":\"unauthorized\"}";
        Mockito.when(mapper.selectById(9L)).thenReturn(model("DASHSCOPE", baseUrl));

        assertThatThrownBy(() -> service.scoreAll(List.of(TextSegment.from("文档A")), "查询", 9L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("401");
    }

    @Test
    void missing_results_throws_for_caller_fallback() {
        responseBody = "{\"output\":{}}";
        Mockito.when(mapper.selectById(9L)).thenReturn(model("DASHSCOPE", baseUrl));

        assertThatThrownBy(() -> service.scoreAll(List.of(TextSegment.from("文档A")), "查询", 9L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("results");
    }

    @Test
    void unavailable_model_throws() {
        Mockito.when(mapper.selectById(9L)).thenReturn(null);
        assertThatThrownBy(() -> service.scoreAll(List.of(TextSegment.from("文档A")), "查询", 9L))
                .isInstanceOf(IllegalStateException.class);

        LlmModel disabled = model("DASHSCOPE", baseUrl);
        disabled.setStatus(0);
        Mockito.when(mapper.selectById(9L)).thenReturn(disabled);
        assertThatThrownBy(() -> service.scoreAll(List.of(TextSegment.from("文档A")), "查询", 9L))
                .isInstanceOf(IllegalStateException.class);
    }
}

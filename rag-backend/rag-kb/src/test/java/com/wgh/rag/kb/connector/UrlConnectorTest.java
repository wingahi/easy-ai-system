package com.wgh.rag.kb.connector;

import com.sun.net.httpserver.HttpServer;
import com.wgh.rag.common.net.SsrfBlockedException;
import com.wgh.rag.common.net.SsrfGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * URL 抓取与正文抽取：本地 HTTP fixture + SSRF 拒绝路径。
 */
class UrlConnectorTest {

    private static final String ARTICLE_HTML = """
            <html><head><title>测试文章</title></head>
            <body>
              <nav>导航栏广告</nav>
              <header>站点页头</header>
              <article>
                <h1>正文标题</h1>
                <p>正文第一段，包含核心内容。</p>
                <p>正文第二段，包含更多内容。</p>
                <script>var junk = '脚本内容';</script>
                <style>.junk { color: red; }</style>
              </article>
              <footer>页脚版权信息</footer>
            </body></html>
            """;

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/page", exchange -> respond(exchange, 200, ARTICLE_HTML));
        server.createContext("/missing", exchange -> respond(exchange, 404, "not found"));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void fetchExtractsMainContentAndDropsNoise() {
        UrlConnector connector = connector(true);

        UrlConnector.FetchedPage page = connector.fetch(baseUrl + "/page");

        assertThat(page.status()).isEqualTo(200);
        assertThat(page.title()).isEqualTo("测试文章");
        assertThat(page.text()).contains("正文第一段", "正文第二段");
        assertThat(page.text()).doesNotContain("导航栏广告", "站点页头", "页脚版权信息", "脚本内容");
    }

    @Test
    void fetchReturnsStatusForMissingPage() {
        UrlConnector.FetchedPage page = connector(true).fetch(baseUrl + "/missing");

        assertThat(page.status()).isEqualTo(404);
        assertThat(page.text()).isNull();
    }

    @Test
    void fetchRejectsNonHttpScheme() {
        UrlConnector connector = connector(false);

        assertThatThrownBy(() -> connector.fetch("file:///etc/passwd"))
                .isInstanceOf(SsrfBlockedException.class)
                .hasMessageContaining("http/https");
    }

    @Test
    void fetchRejectsCloudMetadataAddress() {
        UrlConnector connector = connector(false);

        assertThatThrownBy(() -> connector.fetch("http://169.254.169.254/latest/meta-data/"))
                .isInstanceOf(SsrfBlockedException.class)
                .hasMessageContaining("目标地址不允许访问");
    }

    @Test
    void parseHtmlFallsBackToBodyWhenNoMainContainer() {
        String html = "<html><head><title>无容器</title></head><body><p>纯正文内容</p></body></html>";

        UrlConnector.FetchedPage page = connector(true)
                .parseHtml(200, html.getBytes(StandardCharsets.UTF_8), "https://example.com/a");

        assertThat(page.title()).isEqualTo("无容器");
        assertThat(page.text()).isEqualTo("纯正文内容");
    }

    private UrlConnector connector(boolean allowPrivateNetwork) {
        ConnectorProperties properties = new ConnectorProperties();
        properties.setFetchTimeoutMs(5_000);
        properties.setUserAgent("test-agent");
        properties.setMaxContentChars(200_000);
        SsrfGuard guard = new SsrfGuard(allowPrivateNetwork, "", 3, 1024 * 1024, InetAddress::getAllByName);
        return new UrlConnector(guard, properties);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}

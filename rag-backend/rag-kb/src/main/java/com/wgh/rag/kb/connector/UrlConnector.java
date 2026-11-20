package com.wgh.rag.kb.connector;

import com.wgh.rag.common.net.SsrfBlockedException;
import com.wgh.rag.common.net.SsrfGuard;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * URL 数据源抓取器：单页 HTTP GET + Jsoup 正文抽取。
 *
 * <p>所有出站请求统一走 {@link SsrfGuard}：协议白名单、内网/回环/链路本地地址拒绝、
 * 重定向逐跳校验、响应体积上限。不提供任何绕过该防护的抓取路径。
 * <p>仅抓取单个页面，不做站点爬取。
 */
@Slf4j
@Component
public class UrlConnector {

    /** 正文块级元素选择器（与 HtmlParser 保持一致，避免容器元素重复取文本） */
    private static final String BLOCK_SELECTOR = "h1,h2,h3,h4,h5,h6,p,li,td,th,pre,blockquote";

    private final SsrfGuard ssrfGuard;
    private final ConnectorProperties properties;
    private final HttpClient httpClient;

    public UrlConnector(SsrfGuard ssrfGuard, ConnectorProperties properties) {
        this.ssrfGuard = ssrfGuard;
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1_000, properties.getFetchTimeoutMs())))
                // 关闭自动重定向，改为手动跟随并逐跳做 SSRF 校验
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** 校验 URL 是否可抓取（协议/主机/IP），不通过时抛 {@link SsrfBlockedException}。 */
    public URI validateUrl(String rawUrl) {
        return ssrfGuard.validate(rawUrl);
    }

    /**
     * 抓取单页并抽取正文。
     *
     * <p>非 2xx 不抛异常，由调用方按状态码决定语义（404/410 视为源文档已删除）。
     */
    public FetchedPage fetch(String rawUrl) {
        URI current = validateUrl(rawUrl);
        int redirects = 0;
        try {
            while (true) {
                HttpResponse<InputStream> response = send(current);

                if (isRedirect(response.statusCode())) {
                    Optional<String> location = response.headers().firstValue("Location");
                    closeQuietly(response.body());
                    if (location.isEmpty()) {
                        return new FetchedPage(response.statusCode(), null, null, current.toString());
                    }
                    if (redirects >= ssrfGuard.getMaxRedirects()) {
                        throw new SsrfBlockedException("重定向次数超过上限: " + ssrfGuard.getMaxRedirects());
                    }
                    // 逐跳校验，禁止 30x 跳转到内网/云元数据地址
                    current = ssrfGuard.validate(current.resolve(location.get().trim()));
                    redirects++;
                    continue;
                }

                byte[] body = readLimited(response.body());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    return new FetchedPage(response.statusCode(), null, null, current.toString());
                }
                return parseHtml(response.statusCode(), body, current.toString());
            }
        } catch (IOException e) {
            throw new IllegalStateException("抓取失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("抓取被中断", e);
        }
    }

    /**
     * 从 HTML 字节流抽取标题与正文（包级可见，便于单测直接喂 fixture）。
     */
    FetchedPage parseHtml(int status, byte[] body, String baseUrl) {
        if (body == null || body.length == 0) {
            return new FetchedPage(status, null, "", baseUrl);
        }
        Document doc;
        try {
            // charsetName 传 null 时由 Jsoup 依据 meta charset 自动识别
            doc = Jsoup.parse(new ByteArrayInputStream(body), null, baseUrl);
        } catch (IOException e) {
            throw new IllegalStateException("HTML 解析失败: " + e.getMessage(), e);
        }
        doc.select("script,style,noscript,template,iframe,svg,nav,footer,header,aside,form,button").remove();

        Element root = firstNonEmpty(doc, "article", "main", "[role=main]", "#content", ".content");
        if (root == null) {
            root = doc.body();
        }
        if (root == null) {
            return new FetchedPage(status, doc.title(), "", baseUrl);
        }

        // 容器元素自身也带文本时（如 <main> 直接写正文），块级元素列表可能为空，兜底整体取文本
        String text = extractBlocks(root);
        if (text.isEmpty()) {
            text = normalizeLine(root.text());
        }
        if (text.length() > Math.max(1_000, properties.getMaxContentChars())) {
            text = text.substring(0, Math.max(1_000, properties.getMaxContentChars()));
        }
        String title = doc.title();
        return new FetchedPage(status, title == null || title.isBlank() ? null : title.trim(), text, baseUrl);
    }

    private Element firstNonEmpty(Document doc, String... selectors) {
        for (String selector : selectors) {
            Element el = doc.selectFirst(selector);
            if (el != null && !el.text().isBlank()) {
                return el;
            }
        }
        return null;
    }

    private String extractBlocks(Element root) {
        Set<String> blocks = new LinkedHashSet<>();
        for (Element el : root.select(BLOCK_SELECTOR)) {
            String text = normalizeLine(el.text());
            if (!text.isEmpty()) {
                blocks.add(text);
            }
        }
        return String.join("\n\n", blocks);
    }

    private String normalizeLine(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
    }

    private HttpResponse<InputStream> send(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofMillis(Math.max(1_000, properties.getFetchTimeoutMs())))
                .header("User-Agent", properties.getUserAgent())
                .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5")
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    /** 读取响应体并限制最大字节数，避免超大页面拖垮内存。 */
    private byte[] readLimited(InputStream in) throws IOException {
        long limit = ssrfGuard.getMaxResponseBytes();
        try (InputStream input = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            long remaining = limit;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (remaining <= 0) {
                    break;
                }
                int toWrite = (int) Math.min(read, remaining);
                out.write(buffer, 0, toWrite);
                remaining -= toWrite;
                if (toWrite < read) {
                    break;
                }
            }
            return out.toByteArray();
        }
    }

    private boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private void closeQuietly(InputStream in) {
        if (in == null) {
            return;
        }
        try {
            in.close();
        } catch (IOException ignored) {
            // 忽略关闭异常
        }
    }

    /**
     * 抓取结果：{@code text} 为已归一化的正文，非 2xx 时为 null。
     */
    public record FetchedPage(int status, String title, String text, String finalUrl) {

        public boolean success() {
            return status >= 200 && status < 300 && text != null && !text.isBlank();
        }

        public byte[] contentBytes() {
            return text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
        }

        /** 供日志/调试使用：正文前 80 字。 */
        public String preview() {
            if (text == null) {
                return "";
            }
            List<String> lines = new ArrayList<>(List.of(text.split("\n")));
            String first = lines.isEmpty() ? "" : lines.get(0);
            return first.length() <= 80 ? first : first.substring(0, 80);
        }
    }
}

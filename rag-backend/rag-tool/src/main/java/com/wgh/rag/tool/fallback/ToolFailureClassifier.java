package com.wgh.rag.tool.fallback;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.Locale;
import java.util.Set;

/**
 * 工具失败分类器。
 *
 * <p>不改变工具返回文本格式，只基于既有文本约定识别失败类型：
 * <ul>
 *   <li>HTTP 非 2xx：{@code HTTP <code> - <body>}</li>
 *   <li>异常：{@code Error: <ExceptionSimpleName> - <message>}</li>
 * </ul>
 */
public final class ToolFailureClassifier {

    private static final Set<Class<? extends Throwable>> NETWORK_EXCEPTIONS = Set.of(
            HttpTimeoutException.class,
            ConnectException.class,
            SocketTimeoutException.class,
            UnknownHostException.class,
            NoRouteToHostException.class,
            SocketException.class,
            IOException.class);

    private static final String ERROR_PREFIX = "Error: ";
    private static final String HTTP_PREFIX = "HTTP ";

    private ToolFailureClassifier() {
    }

    /**
     * 判断工具返回文本是否为失败；失败时返回分类，成功返回 {@code null}。
     */
    public static ToolFailureKind classify(String text) {
        if (text == null || text.isBlank()) {
            return ToolFailureKind.UNKNOWN;
        }
        String trimmed = text.stripLeading();
        if (trimmed.startsWith(HTTP_PREFIX)) {
            return classifyHttpStatus(parseStatusCode(trimmed));
        }
        if (trimmed.startsWith(ERROR_PREFIX)) {
            return classifyException(extractExceptionName(trimmed.substring(ERROR_PREFIX.length())));
        }
        return null;
    }

    /** 工具返回是否视为成功（非 Error / 非 HTTP 非 2xx）。 */
    public static boolean isSuccess(String text) {
        return classify(text) == null;
    }

    private static ToolFailureKind classifyHttpStatus(int statusCode) {
        if (statusCode == 401 || statusCode == 403) {
            return ToolFailureKind.AUTH;
        }
        if (statusCode == 400 || statusCode == 422) {
            return ToolFailureKind.BAD_PARAM;
        }
        if (statusCode == 404 || statusCode == 410) {
            return ToolFailureKind.NOT_FOUND;
        }
        if (statusCode == 429) {
            return ToolFailureKind.RATE_LIMIT;
        }
        if (statusCode >= 500 && statusCode < 600) {
            return ToolFailureKind.SERVER;
        }
        // 1xx/2xx/3xx 未按失败处理；其他 4xx 视为不可重试的未知错误
        return ToolFailureKind.UNKNOWN;
    }

    private static ToolFailureKind classifyException(String simpleName) {
        if (simpleName == null || simpleName.isBlank()) {
            return ToolFailureKind.UNKNOWN;
        }
        String name = simpleName.toLowerCase(Locale.ROOT);
        if (name.contains("ssrf")) {
            // 显式排除：SSRF 拦截绝不重试
            return ToolFailureKind.SSRF;
        }
        if (name.contains("ratelimit") || name.contains("toomanyrequests")) {
            return ToolFailureKind.RATE_LIMIT;
        }
        if (name.contains("auth") || name.contains("unauthorized") || name.contains("forbidden")) {
            return ToolFailureKind.AUTH;
        }
        if (name.contains("illegalargument") || name.contains("badrequest") || name.contains("parse")) {
            return ToolFailureKind.BAD_PARAM;
        }
        if (name.contains("notfound")) {
            return ToolFailureKind.NOT_FOUND;
        }
        for (Class<? extends Throwable> type : NETWORK_EXCEPTIONS) {
            if (type.getSimpleName().equalsIgnoreCase(simpleName)) {
                return ToolFailureKind.NETWORK;
            }
        }
        return ToolFailureKind.UNKNOWN;
    }

    /** 从 {@code HTTP 429 - body} 中解析状态码，解析失败返回 -1。 */
    private static int parseStatusCode(String text) {
        int i = HTTP_PREFIX.length();
        int start = i;
        while (i < text.length() && Character.isDigit(text.charAt(i))) {
            i++;
        }
        if (i == start) {
            return -1;
        }
        try {
            return Integer.parseInt(text.substring(start, i));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 从 {@code SsrfBlockedException - msg} 中取异常简单类名（兼容全限定名）。 */
    private static String extractExceptionName(String rest) {
        String head = rest;
        int dash = rest.indexOf(" - ");
        if (dash > 0) {
            head = rest.substring(0, dash);
        }
        head = head.trim();
        int dot = head.lastIndexOf('.');
        return dot >= 0 ? head.substring(dot + 1) : head;
    }
}

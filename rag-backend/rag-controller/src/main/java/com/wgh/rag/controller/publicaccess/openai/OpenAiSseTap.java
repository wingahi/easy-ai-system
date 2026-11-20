package com.wgh.rag.controller.publicaccess.openai;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Set;

/**
 * 将 {@link SseEmitter} 的事件流转换为回调，供 OpenAI 兼容入口转码使用。
 * <p>
 * 背景：{@code RagChatService.streamChat(...)} 只返回 {@link SseEmitter}，事件直接写向 HTTP
 * 响应，调用方无法订阅。这里在服务返回 emitter 后立即挂载一个内部 Handler，把 emitter 的
 * 发送/完成/异常回调转成 {@link EventSink} 回调，从而可以逐 token 透传为 OpenAI 分块，
 * 而不是等全部生成完再一次性返回（且不修改 RagChatService 内部实现）。
 * <p>
 * 实现说明：Spring 的 {@code ResponseBodyEmitter#initialize(Handler)} 为包内可见，且
 * {@code Handler} 为非 public 接口，因此通过反射 + 动态代理挂载。该行为由
 * {@code OpenAiSseTapTest} 固定，Spring 升级若改变内部契约会立即暴露。
 */
final class OpenAiSseTap {

    /**
     * 事件回调：name 为 SSE 事件名（如 content/done/error），payload 为对应数据对象。
     */
    interface EventSink {

        void event(String name, Object payload);

        void completed();

        void failed(Throwable error);
    }

    private static final class HandlerAccess {

        private static final Class<?> HANDLER_TYPE;
        private static final Method INITIALIZE;
        private static final Throwable FAILURE;

        static {
            Class<?> handlerType = null;
            Method initialize = null;
            Throwable failure = null;
            try {
                handlerType = Class.forName(
                        "org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter$Handler");
                initialize = ResponseBodyEmitter.class.getDeclaredMethod("initialize", handlerType);
                initialize.setAccessible(true);
            } catch (Throwable e) {
                failure = e;
            }
            HANDLER_TYPE = handlerType;
            INITIALIZE = initialize;
            FAILURE = failure;
        }
    }

    private OpenAiSseTap() {
    }

    static void attach(SseEmitter emitter, EventSink sink) {
        if (HandlerAccess.FAILURE != null) {
            throw new IllegalStateException("OpenAI 兼容流式桥接不可用：无法访问 Spring SseEmitter 内部处理器",
                    HandlerAccess.FAILURE);
        }
        EventParser parser = new EventParser(sink);
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "send" -> {
                    if (args == null || args.length == 0) {
                        return null;
                    }
                    if (args.length == 2) {
                        parser.accept(args[0], (MediaType) args[1]);
                    } else if (args[0] instanceof Set<?> items) {
                        // Spring 6.x 的批量发送：按构建顺序逐项解析
                        for (Object item : items) {
                            if (item instanceof ResponseBodyEmitter.DataWithMediaType data) {
                                parser.accept(data.getData(), data.getMediaType());
                            }
                        }
                    }
                    return null;
                }
                case "complete" -> {
                    sink.completed();
                    return null;
                }
                case "completeWithError" -> {
                    sink.failed(args != null && args.length > 0 ? (Throwable) args[0] : null);
                    return null;
                }
                case "toString" -> {
                    return "OpenAiSseTapHandler";
                }
                case "hashCode" -> {
                    return System.identityHashCode(proxy);
                }
                case "equals" -> {
                    return proxy == (args == null || args.length == 0 ? null : args[0]);
                }
                default -> {
                    // onTimeout / onError / onCompletion：该 emitter 未绑定 MVC，不会被触发
                    return null;
                }
            }
        };
        Object proxy = Proxy.newProxyInstance(ResponseBodyEmitter.class.getClassLoader(),
                new Class<?>[]{HandlerAccess.HANDLER_TYPE}, handler);
        try {
            HandlerAccess.INITIALIZE.invoke(emitter, proxy);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getTargetException();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("初始化 SseEmitter 处理器失败", cause);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("初始化 SseEmitter 处理器失败", e);
        }
    }

    /**
     * 解析 SseEmitter 产生的数据片段：
     * 文本片段（mediaType 非空）为 {@code event:xxx\n} / {@code data:} 等框架文本，
     * 业务负载（mediaType 为空）为真正的数据对象。
     */
    private static final class EventParser {

        private final EventSink sink;
        private final StringBuilder text = new StringBuilder();

        private EventParser(EventSink sink) {
            this.sink = sink;
        }

        private void accept(Object data, MediaType mediaType) {
            if (mediaType != null) {
                text.append(data);
                return;
            }
            String name = extractEventName(text);
            text.setLength(0);
            if (name != null) {
                sink.event(name, data);
            }
        }

        private static String extractEventName(CharSequence buffered) {
            String raw = buffered.toString();
            int index = raw.lastIndexOf("event:");
            if (index < 0) {
                return null;
            }
            int start = index + "event:".length();
            int end = raw.indexOf('\n', start);
            String name = (end < 0 ? raw.substring(start) : raw.substring(start, end)).trim();
            return name.isEmpty() ? null : name;
        }
    }
}

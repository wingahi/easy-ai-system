package com.wgh.rag.agentmulti.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.agentmulti.constant.MultiAgentEvents;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * SSE 统一事件发送工具 —— 所有多 Agent 事件走此出口，统一 JSON 序列化 + 错误处理。
 *
 * <p>关键语义：<b>客户端断开不等于执行失败</b>。页面刷新/卸载/网络中断时，
 * SSE 写出会失败（{@link java.io.IOException}），随后对已完成的 emitter 再发送会抛
 * {@link IllegalStateException}。两者都只代表「没人接收事件」，不能向上传播——
 * 否则会冒泡到 {@code MultiAgentRuntime} 的异常分支，把仍在执行的 Run 误标为 FAILED。</p>
 *
 * <p>因此本类在首次写失败后把该 emitter 标记为已关闭，后续事件直接跳过；
 * 执行线程照常推进并在结束时更新 Run 状态。真正的执行异常仍由运行时按原逻辑落 FAILED。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SseEventSender {

    private final ObjectMapper objectMapper;

    /** 已断开的下游：弱引用，避免长期持有已结束的 emitter */
    private final Set<SseEmitter> closedEmitters =
            Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));

    /** 产出观察者（HITL 续跑计费）：按 emitter 绑定，输出类事件成功下发后回调一次。 */
    private final Map<SseEmitter, Runnable> outputObservers = Collections.synchronizedMap(new WeakHashMap<>());

    /** 绑定产出观察者；{@code observer} 由调用方保证幂等。 */
    public void bindOutputObserver(SseEmitter emitter, Runnable observer) {
        if (emitter != null && observer != null) {
            outputObservers.put(emitter, observer);
        }
    }

    /** 解绑产出观察者（执行结束时调用，避免弱引用表长期残留）。 */
    public void unbindOutputObserver(SseEmitter emitter) {
        if (emitter != null) {
            outputObservers.remove(emitter);
        }
    }

    /** 发送一个命名事件。payload 会被包裹上 timestamp 字段后序列化为 JSON。 */
    public void send(SseEmitter emitter, String eventName, Object payload) {
        if (emitter == null || closedEmitters.contains(emitter)) {
            return;
        }
        String json;
        Map<String, Object> finalPayload;
        try {
            if (payload instanceof Map<?, ?> m) {
                finalPayload = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    finalPayload.put(String.valueOf(e.getKey()), e.getValue());
                }
            } else {
                finalPayload = new LinkedHashMap<>();
                finalPayload.put("data", payload);
            }
            finalPayload.put("timestamp", LocalDateTime.now());
            json = objectMapper.writeValueAsString(finalPayload);
        } catch (Exception e) {
            // 序列化问题属于单个事件，跳过即可，不能据此关闭连接或影响执行状态
            log.warn("SSE 事件序列化失败，跳过该事件 event={}: {}", eventName, e.getMessage());
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(eventName).data(json));
        } catch (Exception e) {
            markClosed(emitter, eventName, e);
            return;
        }
        notifyOutputDelivered(emitter, eventName, finalPayload);
    }

    /**
     * 输出类事件成功下发后回调计费观察者（仅首包有效；回调异常不影响执行）。
     */
    private void notifyOutputDelivered(SseEmitter emitter, String eventName, Map<String, Object> payload) {
        if (!MultiAgentEvents.MULTI_AGENT_COMPLETE.equals(eventName)
                && !MultiAgentEvents.AGENT_DONE.equals(eventName)) {
            return;
        }
        Runnable observer = outputObservers.get(emitter);
        if (observer == null) {
            return;
        }
        Object output = payload.get("output");
        if (output == null || String.valueOf(output).isBlank()) {
            return;
        }
        try {
            observer.run();
        } catch (Exception e) {
            log.warn("产出观察者回调失败（不影响执行）: event={}, reason={}", eventName, e.getMessage());
        }
    }

    /**
     * 便捷重载：以 kv 对形式构造 payload，容忍 null 值。
     * （Map.of 遇 null 值直接抛 NPE，事件字段允许为空的场景必须走此入口。）
     */
    public void send(SseEmitter emitter, String eventName, Object... kv) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (kv != null) {
            for (int i = 0; i + 1 < kv.length; i += 2) {
                payload.put(String.valueOf(kv[i]), kv[i + 1]);
            }
        }
        send(emitter, eventName, (Object) payload);
    }

    /** 发送注释（保持连接心跳用） */
    public void sendComment(SseEmitter emitter, String comment) {
        if (emitter == null || closedEmitters.contains(emitter)) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().comment(comment));
        } catch (Exception e) {
            markClosed(emitter, "comment", e);
        }
    }

    private void markClosed(SseEmitter emitter, String eventName, Exception error) {
        boolean first = closedEmitters.add(emitter);
        if (first) {
            log.info("SSE 客户端已断开，停止推送事件（本次执行继续，状态不受影响）: event={}, reason={}",
                    eventName, error.getMessage());
            try {
                // 不用 completeWithError：对已完成的 emitter 会抛 IllegalStateException，
                // 且会把传输层断开误当成执行异常。
                emitter.complete();
            } catch (Exception ignored) {
                // 下游已彻底关闭，忽略
            }
        }
    }
}

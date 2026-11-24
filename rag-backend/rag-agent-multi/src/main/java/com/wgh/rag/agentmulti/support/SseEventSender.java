package com.wgh.rag.agentmulti.support;

import com.wgh.rag.agentmulti.constant.MultiAgentEvents;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 多 Agent 统一事件发送工具 —— 所有事件走传输无关的 {@link AgentEventStream}。
 *
 * <p>关键语义：<b>客户端断开不等于执行失败</b>。页面刷新/卸载/网络中断时，
 * 写出会失败；本类把该出口标记为已关闭并停止推送，执行线程照常推进并在结束时更新 Run 状态。
 * 真正的执行异常仍由运行时按原逻辑落 FAILED。</p>
 */
@Slf4j
@Component
public class SseEventSender {

    /** 已断开的出口：弱引用，避免长期持有已结束的流 */
    private final Set<AgentEventStream> closedStreams =
            Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));

    /** 产出观察者（HITL 续跑计费）：按出口绑定，输出类事件成功下发后回调一次。 */
    private final Map<AgentEventStream, Runnable> outputObservers =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** 绑定产出观察者；{@code observer} 由调用方保证幂等。 */
    public void bindOutputObserver(AgentEventStream stream, Runnable observer) {
        if (stream != null && observer != null) {
            outputObservers.put(stream, observer);
        }
    }

    /** 解绑产出观察者（执行结束时调用，避免弱引用表长期残留）。 */
    public void unbindOutputObserver(AgentEventStream stream) {
        if (stream != null) {
            outputObservers.remove(stream);
        }
    }

    /** 发送一个命名事件。payload 会被包裹上 timestamp 字段。 */
    public void send(AgentEventStream stream, String eventName, Object payload) {
        if (stream == null || closedStreams.contains(stream)) {
            return;
        }
        Map<String, Object> finalPayload;
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
        try {
            stream.sendEvent(eventName, finalPayload);
        } catch (Exception e) {
            markClosed(stream, eventName, e);
            return;
        }
        notifyOutputDelivered(stream, eventName, finalPayload);
    }

    /**
     * 输出类事件成功下发后回调计费观察者（仅首包有效；回调异常不影响执行）。
     */
    private void notifyOutputDelivered(AgentEventStream stream, String eventName, Map<String, Object> payload) {
        if (!MultiAgentEvents.MULTI_AGENT_COMPLETE.equals(eventName)
                && !MultiAgentEvents.AGENT_DONE.equals(eventName)) {
            return;
        }
        Runnable observer = outputObservers.get(stream);
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
    public void send(AgentEventStream stream, String eventName, Object... kv) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (kv != null) {
            for (int i = 0; i + 1 < kv.length; i += 2) {
                payload.put(String.valueOf(kv[i]), kv[i + 1]);
            }
        }
        send(stream, eventName, (Object) payload);
    }

    /** 发送注释（保持连接心跳用） */
    public void sendComment(AgentEventStream stream, String comment) {
        if (stream == null || closedStreams.contains(stream)) {
            return;
        }
        try {
            stream.sendComment(comment);
        } catch (Exception e) {
            markClosed(stream, "comment", e);
        }
    }

    private void markClosed(AgentEventStream stream, String eventName, Exception error) {
        boolean first = closedStreams.add(stream);
        if (first) {
            log.info("事件下游已断开，停止推送事件（本次执行继续，状态不受影响）: event={}, reason={}",
                    eventName, error.getMessage());
            stream.complete();
        }
    }
}

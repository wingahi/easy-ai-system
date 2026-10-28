package com.wgh.rag.agentmulti.groupchat;

import com.wgh.rag.agentmulti.dto.AgentInstance;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GroupChat 消息总线 —— 每个 GroupChatCoordinator 执行时 new 一个。
 * <p>
 * 职责：
 * <ul>
 *   <li>维护 per-agent 独立历史（多 Agent 共享对话但各自有记忆视图）</li>
 *   <li>维护全局历史（供路由器 / Summarizer 看全局）</li>
 *   <li>提供 send / broadcast / subscribe 原语</li>
 * </ul>
 */
@Slf4j
@Component
public class MessageBus {

    /** 每个 Agent 独立对话历史（per-agent view） */
    private final Map<String, List<ChatMessage>> perAgentHistory = new ConcurrentHashMap<>();

    /** 全局历史 */
    private final List<ChatMessage> globalHistory = Collections.synchronizedList(new ArrayList<>());

    /** Agent 注册表 */
    private final Map<String, AgentInstance> agents = new LinkedHashMap<>();

    /** Agent 消息处理器（执行时触发） */
    private final Map<String, MessageHandler> subscribers = new ConcurrentHashMap<>();

    private SseEmitter emitter;

    public MessageBus() {}

    /** 使用 Agent 列表构造（group chat 入口） */
    public MessageBus(List<AgentInstance> agentList) {
        if (agentList != null) {
            for (AgentInstance a : agentList) {
                this.agents.put(a.getAgentId(), a);
                this.perAgentHistory.put(a.getAgentId(), new ArrayList<>());
            }
        }
    }

    public MessageBus(List<AgentInstance> agentList, SseEmitter emitter) {
        this(agentList);
        this.emitter = emitter;
    }

    // ==================== Agent 注册 ====================

    public void registerAgent(AgentInstance agent) {
        this.agents.put(agent.getAgentId(), agent);
        this.perAgentHistory.computeIfAbsent(agent.getAgentId(), k -> new ArrayList<>());
    }

    public AgentInstance getAgent(String agentId) {
        return agents.get(agentId);
    }

    public Collection<AgentInstance> getAllAgents() {
        return agents.values();
    }

    public List<String> getAgentIds() {
        return new ArrayList<>(agents.keySet());
    }

    public void setEmitter(SseEmitter emitter) {
        this.emitter = emitter;
    }

    // ==================== Subscribe ====================

    public void subscribe(String agentId, MessageHandler handler) {
        subscribers.put(agentId, handler);
    }

    @FunctionalInterface
    public interface MessageHandler {
        void onMessage(ChatMessage msg);
    }

    // ==================== 发送 ====================

    /** 私聊：只追加到 toAgent 的 history */
    public synchronized void send(String fromAgentId, String toAgentId, ChatMessage msg) {
        perAgentHistory.computeIfAbsent(toAgentId, k -> new ArrayList<>()).add(msg);
        perAgentHistory.computeIfAbsent(fromAgentId, k -> new ArrayList<>()).add(msg);
        globalHistory.add(msg);

        MessageHandler h = subscribers.get(toAgentId);
        if (h != null) {
            try { h.onMessage(msg); } catch (Exception e) { log.warn("subscriber error", e); }
        }
    }

    /** 广播：给除了 fromAgentId 之外所有 agent 追加历史 */
    public synchronized void broadcast(String fromAgentId, ChatMessage msg) {
        for (Map.Entry<String, AgentInstance> e : agents.entrySet()) {
            String id = e.getKey();
            if (!id.equals(fromAgentId)) {
                perAgentHistory.computeIfAbsent(id, k -> new ArrayList<>()).add(msg);
            }
        }
        // 发送者也能看到自己发的（可选）
        perAgentHistory.computeIfAbsent(fromAgentId, k -> new ArrayList<>()).add(msg);
        globalHistory.add(msg);

        // 触发所有 subscribers
        for (Map.Entry<String, MessageHandler> h : subscribers.entrySet()) {
            if (!h.getKey().equals(fromAgentId)) {
                try { h.getValue().onMessage(msg); } catch (Exception ex) { log.warn("subscriber error", ex); }
            }
        }

        // SSE
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event().name("agent_message")
                        .data(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
                                "fromAgentId", fromAgentId,
                                "role", msg.role,
                                "content", msg.content,
                                "timestamp", msg.timestamp))));
            } catch (Exception ignore) {}
        }
    }

    // ==================== 历史访问 ====================

    public List<ChatMessage> getHistory(String agentId) {
        return perAgentHistory.getOrDefault(agentId, List.of());
    }

    public List<ChatMessage> getAllHistory() {
        return globalHistory;
    }

    public int globalSize() { return globalHistory.size(); }

    // ==================== ChatMessage ====================

    /** GroupChat 对话消息 —— 和 LangChain4j ChatMessage 结构不同，这里是总线内部用的 DTO。 */
    public static class ChatMessage {

        public enum Role { USER, ASSISTANT, SYSTEM, TOOL }

        public Role role;
        public String fromAgentId;
        public String toAgentId;
        public String content;
        public LocalDateTime timestamp;
        public List<ToolInvocation> toolCalls;

        public ChatMessage() {}

        public ChatMessage(Role role, String fromAgentId, String content) {
            this(role, fromAgentId, null, content, null);
        }

        public ChatMessage(Role role, String fromAgentId, String toAgentId,
                           String content, List<ToolInvocation> toolCalls) {
            this.role = role;
            this.fromAgentId = fromAgentId;
            this.toAgentId = toAgentId;
            this.content = content;
            this.timestamp = LocalDateTime.now();
            this.toolCalls = toolCalls;
        }

        public static ChatMessage user(String content) {
            return new ChatMessage(Role.USER, "user", null, content, null);
        }

        public static ChatMessage user(String toAgentId, String content) {
            return new ChatMessage(Role.USER, "user", toAgentId, content, null);
        }

        public static ChatMessage assistant(String fromAgentId, String content) {
            return new ChatMessage(Role.ASSISTANT, fromAgentId, null, content, null);
        }

        public static ChatMessage assistant(String fromAgentId, String content,
                                             List<ToolInvocation> toolCalls) {
            return new ChatMessage(Role.ASSISTANT, fromAgentId, null, content, toolCalls);
        }

        public String displayName() {
            return fromAgentId == null ? "user" : fromAgentId;
        }

        @Override
        public String toString() {
            return "[" + role + "/" + displayName() + "] " + content;
        }
    }

    /** 内嵌的工具调用记录（GroupChat 里也需要暴露 tool 信息） */
    public static class ToolInvocation {
        public String toolName;
        public String arguments;
        public String result;
        public boolean success;

        public ToolInvocation() {}

        public ToolInvocation(String toolName, String arguments, String result, boolean success) {
            this.toolName = toolName;
            this.arguments = arguments;
            this.result = result;
            this.success = success;
        }
    }
}

package com.wgh.rag.chat.service.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.chat.constant.ChatConstants;
import com.wgh.rag.chat.entity.ChatMessage;
import com.wgh.rag.chat.entity.ChatSession;
import com.wgh.rag.chat.mapper.ChatMessageMapper;
import com.wgh.rag.chat.mapper.ChatSessionMapper;
import com.wgh.rag.kb.service.AgentMemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 聊天消息持久化：用户消息、助手终态消息、检索指标回填与会话记忆写入。
 *
 * <p>落库语义集中在这里，避免普通 RAG、Agent、工作流三条链路各自维护一份
 * 「插入消息 + 更新会话计数」的逻辑。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatMessageStore {

    private final ChatMessageMapper messageMapper;
    private final ChatSessionMapper sessionMapper;
    private final ObjectMapper objectMapper;
    private final AgentMemoryService agentMemoryService;

    /** 保存用户消息（多模态图片序列化进 attachments）。 */
    public ChatMessage saveUserMessage(Long sessionId, String content, List<String> images) {
        ChatMessage msg = new ChatMessage();
        msg.setSessionId(sessionId);
        msg.setRole(ChatConstants.ROLE_USER);
        msg.setContent(content);
        msg.setMessageType(images == null || images.isEmpty()
                ? ChatConstants.MESSAGE_TYPE_TEXT : ChatConstants.MESSAGE_TYPE_IMAGE);
        if (images != null && !images.isEmpty()) {
            try {
                msg.setAttachments(objectMapper.writeValueAsString(images));
            } catch (Exception e) {
                log.warn("序列化图片附件失败", e);
            }
        }
        messageMapper.insert(msg);
        return msg;
    }

    public ChatMessage saveAssistantMessage(ChatSession session, String content, List<?> sources,
                                            Double hitRate, Double accuracyRate, Double mrr, Double ndcg) {
        return saveAssistantMessage(session, content, sources, hitRate, accuracyRate, mrr, ndcg,
                ChatConstants.MESSAGE_STATUS_COMPLETED);
    }

    public ChatMessage saveAssistantMessage(ChatSession session, String content, List<?> sources,
                                            Double hitRate, Double accuracyRate, Double mrr, Double ndcg,
                                            String status) {
        ChatMessage msg = new ChatMessage();
        try {
            msg.setSessionId(session.getId());
            msg.setRole(ChatConstants.ROLE_ASSISTANT);
            msg.setContent(content);
            msg.setMessageType(ChatConstants.MESSAGE_TYPE_TEXT);
            msg.setHitRate(hitRate);
            msg.setAccuracyRate(accuracyRate);
            msg.setMrr(mrr);
            msg.setNdcg(ndcg);
            msg.setStatus(status);
            if (sources != null && !sources.isEmpty()) {
                msg.setSources(objectMapper.writeValueAsString(sources));
            }
            messageMapper.insert(msg);
            rememberSession(session.getId(), content);
            incrementMessageCount(session);
        } catch (Exception e) {
            log.error("保存assistant消息失败", e);
        }
        return msg;
    }

    /** 工作流链路落库：与普通聊天一致地插入消息并更新会话计数（不写会话记忆）。 */
    public ChatMessage saveWorkflowMessage(ChatSession session, String content) {
        ChatMessage msg = new ChatMessage();
        try {
            msg.setSessionId(session.getId());
            msg.setRole(ChatConstants.ROLE_ASSISTANT);
            msg.setContent(content);
            msg.setMessageType(ChatConstants.MESSAGE_TYPE_TEXT);
            messageMapper.insert(msg);
            incrementMessageCount(session);
        } catch (Exception e) {
            log.error("保存工作流 assistant 消息失败: session={}", session.getId(), e);
        }
        return msg;
    }

    /** HITL 确认恢复执行后的最终答复补写（REST 通道没有 SSE，需要主动落库）。 */
    public void appendAssistantMessage(Long sessionId, String content) {
        if (sessionId == null || content == null || content.isBlank()) return;
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null) return;
        saveAssistantMessage(session, content, List.of(), null, null, null, null);
    }

    /** 回填用户消息上的检索指标（coverageRate / hitRate）。 */
    public void updateRetrievalMetrics(ChatMessage userMsg, Double coverageRate, Double hitRate) {
        userMsg.setCoverageRate(coverageRate);
        userMsg.setHitRate(hitRate);
        messageMapper.updateById(userMsg);
    }

    private void incrementMessageCount(ChatSession session) {
        ChatSession update = new ChatSession();
        update.setId(session.getId());
        update.setMessageCount(session.getMessageCount() != null ? session.getMessageCount() + 2 : 2);
        sessionMapper.updateById(update);
    }

    /** 会话级记忆写入：每轮助手答复完成后落一条摘要，失败只记日志。 */
    private void rememberSession(Long sessionId, String content) {
        if (sessionId == null || content == null || content.isBlank()) {
            return;
        }
        try {
            String summary = content.length() > 800 ? content.substring(0, 800) : content;
            agentMemoryService.remember(AgentMemoryService.ScopeType.SESSION,
                    String.valueOf(sessionId), null, "SUMMARY", summary, null);
        } catch (Exception e) {
            log.warn("写入会话记忆失败（忽略）: sessionId={}, reason={}", sessionId, e.getMessage());
        }
    }
}

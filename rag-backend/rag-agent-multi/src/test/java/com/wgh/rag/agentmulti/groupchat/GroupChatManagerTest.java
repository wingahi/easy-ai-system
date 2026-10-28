package com.wgh.rag.agentmulti.groupchat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.agentmulti.dto.AgentInstance;
import com.wgh.rag.agentmulti.entity.MultiAgentRun;
import com.wgh.rag.agentmulti.groupchat.MessageBus.ChatMessage;
import com.wgh.rag.llm.factory.ModelCacheManager;
import com.wgh.rag.workflow.engine.ConditionEvaluator;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * GroupChatManager 单元测试 —— Round Robin 轮转、终止判断、Dynamic Router JSON 解析与降级。
 * 依赖（ModelCacheManager / ConditionEvaluator）全部 Mockito mock，不启动 Spring 容器。
 */
@ExtendWith(MockitoExtension.class)
class GroupChatManagerTest {

    @Mock
    private ModelCacheManager modelCacheManager;
    @Mock
    private ConditionEvaluator conditionEvaluator;
    @Mock
    private ChatModel chatModel;

    private GroupChatManager manager;

    private List<AgentInstance> agents;

    @BeforeEach
    void setUp() {
        manager = new GroupChatManager(modelCacheManager, conditionEvaluator, new ObjectMapper());
        agents = List.of(
                AgentInstance.builder().agentId("a1").name("研究员").role("researcher").goal("搜集资料").chatModelId(1L).build(),
                AgentInstance.builder().agentId("a2").name("写手").role("writer").goal("撰写稿件").chatModelId(1L).build(),
                AgentInstance.builder().agentId("a3").name("审稿人").role("reviewer").goal("审核质量").chatModelId(1L).build());
    }

    private MultiAgentRun run(String mode, int currentRound, int maxRounds) {
        MultiAgentRun run = new MultiAgentRun();
        run.setExecutionMode(mode);
        run.setCurrentRound(currentRound);
        run.setMaxRounds(maxRounds);
        return run;
    }

    /** RoundRobin：连续 pickNextSpeaker 按 agents 列表顺序轮转，round 超过数量后取模回绕 */
    @Test
    void pickNextSpeaker_roundRobin_rotatesInOrder() {
        MultiAgentRun run = run("GROUP_CHAT_ROUND_ROBIN", 0, 10);
        List<ChatMessage> history = List.of();

        assertThat(manager.pickNextSpeaker(run, agents, history)).isEqualTo("a1");
        run.setCurrentRound(1);
        assertThat(manager.pickNextSpeaker(run, agents, history)).isEqualTo("a2");
        run.setCurrentRound(2);
        assertThat(manager.pickNextSpeaker(run, agents, history)).isEqualTo("a3");
        // 第 3 轮取模回绕到 a1
        run.setCurrentRound(3);
        assertThat(manager.pickNextSpeaker(run, agents, history)).isEqualTo("a1");
    }

    /** shouldTerminate：currentRound >= maxRounds 时终止 */
    @Test
    void shouldTerminate_reachesMaxRounds() {
        MultiAgentRun run = run("GROUP_CHAT_ROUND_ROBIN", 5, 5);

        assertThat(manager.shouldTerminate(run, List.of())).isTrue();
        // 未达上限时不因此条件终止
        MultiAgentRun notYet = run("GROUP_CHAT_ROUND_ROBIN", 4, 5);
        assertThat(manager.shouldTerminate(notYet, List.of())).isFalse();
    }

    /** shouldTerminate：全局历史最后一条消息包含 "TERMINATE" 时终止 */
    @Test
    void shouldTerminate_lastMessageContainsTerminate() {
        MultiAgentRun run = run("GROUP_CHAT_ROUND_ROBIN", 1, 10);
        List<ChatMessage> history = List.of(
                ChatMessage.assistant("a1", "讨论完毕"),
                ChatMessage.assistant("a2", "同意结论，TERMINATE"));

        assertThat(manager.shouldTerminate(run, history)).isTrue();
    }

    /** DynamicRouter：模型返回合法 JSON {"agentId":"a2","reason":"..."} 时解析出 a2 */
    @Test
    void pickNextSpeaker_dynamicRouter_parsesValidJson() {
        MultiAgentRun run = run("GROUP_CHAT_DYNAMIC_ROUTER", 0, 10);
        run.setDynamicRouterModelId(9L);
        when(modelCacheManager.getChatModel(9L)).thenReturn(chatModel);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder()
                        .aiMessage(AiMessage.from("{\"agentId\":\"a2\",\"reason\":\"轮到写手撰写\"}"))
                        .build());

        String next = manager.pickNextSpeaker(run, agents, List.of(ChatMessage.user("开始")));

        assertThat(next).isEqualTo("a2");
    }

    /** DynamicRouter：模型返回非 JSON 散文时降级 Round Robin，不抛异常 */
    @Test
    void pickNextSpeaker_dynamicRouter_nonJsonFallsBackToRoundRobin() {
        MultiAgentRun run = run("GROUP_CHAT_DYNAMIC_ROUTER", 1, 10);
        run.setDynamicRouterModelId(9L);
        when(modelCacheManager.getChatModel(9L)).thenReturn(chatModel);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder()
                        .aiMessage(AiMessage.from("我觉得接下来应该让写手发言，因为他文笔好。"))
                        .build());

        assertThatCode(() -> {
            String next = manager.pickNextSpeaker(run, agents, List.of(ChatMessage.user("开始")));
            // 降级行为：Round Robin，round=1 → a2
            assertThat(next).isEqualTo("a2");
        }).doesNotThrowAnyException();
    }

    /**
     * DynamicRouter：模型幻觉返回不存在的 agentId（如 "a99"）时，
     * 应校验存在性并降级 Round Robin，避免调用方拿到不存在的 agentId 导致 NPE。
     */
    @Test
    void pickNextSpeaker_dynamicRouter_unknownAgentIdFallsBackToRoundRobin() {
        MultiAgentRun run = run("GROUP_CHAT_DYNAMIC_ROUTER", 0, 10);
        run.setDynamicRouterModelId(9L);
        when(modelCacheManager.getChatModel(9L)).thenReturn(chatModel);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder()
                        .aiMessage(AiMessage.from("{\"agentId\":\"a99\",\"reason\":\"幻觉出的 Agent\"}"))
                        .build());

        String next = manager.pickNextSpeaker(run, agents, List.of(ChatMessage.user("开始")));

        // 修复后断言：幻觉 agentId 被拒绝，降级 Round Robin（round=0 → a1）
        assertThat(next).isEqualTo("a1");
    }

    /** DynamicRouter：routerModelId 为 null 时 fallback 到第一个 agent 的 chatModelId（三元笔误回归） */
    @Test
    void pickNextSpeaker_dynamicRouter_nullRouterModelIdFallsBackToFirstAgentModel() {
        MultiAgentRun run = run("GROUP_CHAT_DYNAMIC_ROUTER", 2, 10);
        // 不设置 dynamicRouterModelId → 应使用 agents.get(0).chatModelId = 1L
        when(modelCacheManager.getChatModel(1L)).thenReturn(chatModel);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder()
                        .aiMessage(AiMessage.from("{\"agentId\":\"a3\",\"reason\":\"审稿\"}"))
                        .build());

        String next = manager.pickNextSpeaker(run, agents, List.of(ChatMessage.user("开始")));

        assertThat(next).isEqualTo("a3");
    }
}

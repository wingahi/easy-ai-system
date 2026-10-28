package com.wgh.rag.agentmulti.groupchat;

import com.wgh.rag.agentmulti.dto.AgentInstance;
import com.wgh.rag.agentmulti.groupchat.MessageBus.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MessageBus 单元测试 —— 验证 broadcast / send 的 per-agent 历史分发与全局历史顺序。
 */
class MessageBusTest {

    private MessageBus bus;

    @BeforeEach
    void setUp() {
        // 注册 3 个 Agent：a1 / a2 / a3
        bus = new MessageBus(List.of(
                AgentInstance.builder().agentId("a1").name("研究员").build(),
                AgentInstance.builder().agentId("a2").name("写手").build(),
                AgentInstance.builder().agentId("a3").name("审稿人").build()));
    }

    /** broadcast：除发送者外所有 Agent 收到消息；按实现发送者自己的 history 也会追加（"发送者也能看到自己发的"） */
    @Test
    void broadcast_appendsToAllAgentsHistory() {
        bus.broadcast("a1", ChatMessage.assistant("a1", "大家好"));

        // 接收方各自视角都有该消息
        assertThat(bus.getHistory("a2")).extracting(m -> m.content).containsExactly("大家好");
        assertThat(bus.getHistory("a3")).extracting(m -> m.content).containsExactly("大家好");
        // 实现行为：发送者自己的 history 也会追加自己发出的消息
        assertThat(bus.getHistory("a1")).extracting(m -> m.content).containsExactly("大家好");
        // 全局历史只追加一次
        assertThat(bus.getAllHistory()).hasSize(1);
    }

    /** send 点对点：只出现在发送方与接收方的 history，第三者不可见；全局历史追加一条 */
    @Test
    void send_pointToPoint_onlyParticipantsSeeIt() {
        bus.send("a1", "a2", ChatMessage.assistant("a1", "只给你看"));

        assertThat(bus.getHistory("a2")).extracting(m -> m.content).containsExactly("只给你看");
        // 实现行为：发送方自己的 history 也保留副本（对话双方视图）
        assertThat(bus.getHistory("a1")).extracting(m -> m.content).containsExactly("只给你看");
        // 未参与的 a3 视角为空
        assertThat(bus.getHistory("a3")).isEmpty();
        assertThat(bus.getAllHistory()).hasSize(1);
    }

    /** getHistory 视角隔离：多条消息交错时，各 Agent 只能看到自己参与 / 收到的部分 */
    @Test
    void getHistory_perAgentViewIsolation() {
        bus.send("a1", "a2", ChatMessage.assistant("a1", "私聊给 a2"));
        bus.broadcast("a1", ChatMessage.assistant("a1", "广播给大家"));
        bus.send("a3", "a1", ChatMessage.assistant("a3", "回复 a1"));

        // a2 视角：私聊 + 广播，不含 a3→a1 的私聊
        assertThat(bus.getHistory("a2")).extracting(m -> m.content)
                .containsExactly("私聊给 a2", "广播给大家");
        // a3 视角：广播 + 自己发给 a1 的私聊
        assertThat(bus.getHistory("a3")).extracting(m -> m.content)
                .containsExactly("广播给大家", "回复 a1");
        // a1 视角：三条全部可见（自己私聊发出 + 自己广播 + 收到 a3 私聊）
        assertThat(bus.getHistory("a1")).extracting(m -> m.content)
                .containsExactly("私聊给 a2", "广播给大家", "回复 a1");
    }

    /** getAllHistory 全局顺序：按追加顺序保留全部消息 */
    @Test
    void getAllHistory_preservesGlobalOrder() {
        bus.broadcast("a1", ChatMessage.assistant("a1", "第一条"));
        bus.send("a2", "a3", ChatMessage.assistant("a2", "第二条"));
        bus.broadcast("a3", ChatMessage.assistant("a3", "第三条"));

        assertThat(bus.getAllHistory()).extracting(m -> m.content)
                .containsExactly("第一条", "第二条", "第三条");
        assertThat(bus.globalSize()).isEqualTo(3);
    }
}

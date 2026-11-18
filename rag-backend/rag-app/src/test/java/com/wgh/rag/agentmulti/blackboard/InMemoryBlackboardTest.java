package com.wgh.rag.agentmulti.blackboard;

import com.wgh.rag.agentmulti.context.MultiAgentContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多 Agent 共享黑板测试。
 */
@DisplayName("多 Agent 共享黑板")
class InMemoryBlackboardTest {

    @Test
    @DisplayName("读写与覆盖")
    void putAndGet() {
        Blackboard board = new InMemoryBlackboard();
        board.put("card.a1", "v1");
        board.put("card.a1", "v2");

        assertThat(board.get("card.a1")).contains("v2");
        assertThat(board.get("missing")).isEmpty();
        assertThat(board.snapshot()).containsEntry("card.a1", "v2");
    }

    @Test
    @DisplayName("前缀快照 - 只返回匹配条目")
    void snapshotByPrefix() {
        Blackboard board = new InMemoryBlackboard();
        board.put("card.a1", "c1");
        board.put("card.a2", "c2");
        board.put("plan.manager", "p");

        assertThat(board.snapshot("card.")).containsOnlyKeys("card.a1", "card.a2");
        assertThat(board.snapshot("plan.")).containsExactly(Map.entry("plan.manager", "p"));
    }

    @Test
    @DisplayName("容量上限 - 淘汰最旧条目")
    void evictsOldest() {
        Blackboard board = new InMemoryBlackboard(2);
        board.put("k1", 1);
        board.put("k2", 2);
        board.put("k3", 3);

        assertThat(board.snapshot()).containsOnlyKeys("k2", "k3");
    }

    @Test
    @DisplayName("清空")
    void clear() {
        Blackboard board = new InMemoryBlackboard();
        board.put("k", 1);
        board.clear();

        assertThat(board.snapshot()).isEmpty();
    }

    @Test
    @DisplayName("上下文复用同一份黑板 - 快照包含黑板内容")
    void contextSharesBlackboard() {
        MultiAgentContext ctx = new MultiAgentContext();
        ctx.blackboard().put("card.a1", "value");

        assertThat(ctx.blackboard().get("card.a1")).contains("value");
        assertThat(ctx.snapshot()).containsKey("blackboard");
        @SuppressWarnings("unchecked")
        Map<String, Object> snapshot = (Map<String, Object>) ctx.snapshot().get("blackboard");
        assertThat(snapshot).containsEntry("card.a1", "value");
    }
}

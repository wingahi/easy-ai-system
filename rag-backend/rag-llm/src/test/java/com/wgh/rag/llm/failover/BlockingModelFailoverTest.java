package com.wgh.rag.llm.failover;

import com.wgh.rag.llm.entity.LlmModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 同步故障转移适配层的切链语义：与普通 RAG 保持同一判定口径。 */
class BlockingModelFailoverTest {

    private LlmModel model(Long id) {
        LlmModel m = new LlmModel();
        m.setId(id);
        m.setModelName("model-" + id);
        return m;
    }

    @Test
    void switchesToNextModelWhenFirstFailsBeforeContent() {
        List<Long> calls = new ArrayList<>();
        List<Long> switched = new ArrayList<>();

        String result = BlockingModelFailover.call(
                List.of(model(1L), model(2L)),
                (attempt, m) -> {
                    calls.add(m.getId());
                    if (m.getId() == 1L) throw new IllegalStateException("first down");
                    attempt.markStarted();
                    return "ok-from-2";
                },
                new BlockingModelFailover.Listener() {
                    @Override
                    public void onAttemptStart(FailoverAttempt attempt) {
                        if (attempt.index() > 0) switched.add(attempt.model().getId());
                    }
                });

        assertEquals("ok-from-2", result);
        assertEquals(List.of(1L, 2L), calls);
        assertEquals(List.of(2L), switched);
    }

    @Test
    void allModelsFailReturnsClearBusinessError() {
        ModelFailoverException ex = assertThrows(ModelFailoverException.class, () ->
                BlockingModelFailover.call(
                        List.of(model(1L), model(2L)),
                        (attempt, m) -> {
                            throw new IllegalStateException("down-" + m.getId());
                        },
                        new BlockingModelFailover.Listener() {
                        }));

        assertTrue(ex.getMessage().startsWith("所有候选模型均调用失败（共 2 个"));
        assertTrue(ex.getMessage().contains("down-2"));
    }

    @Test
    void doesNotSwitchAfterContentProduced() {
        List<Long> calls = new ArrayList<>();

        ModelFailoverException ex = assertThrows(ModelFailoverException.class, () ->
                BlockingModelFailover.call(
                        List.of(model(1L), model(2L)),
                        (attempt, m) -> {
                            calls.add(m.getId());
                            attempt.markStarted();
                            throw new IllegalStateException("tool round failed");
                        },
                        new BlockingModelFailover.Listener() {
                        }));

        assertTrue(ex.getMessage().contains("不切换备用模型"));
        assertEquals(List.of(1L), calls, "已产出内容后不得切到备用模型");
        assertFalse(calls.contains(2L));
    }

    @Test
    void singleModelFailureKeepsOriginalErrorText() {
        ModelFailoverException ex = assertThrows(ModelFailoverException.class, () ->
                BlockingModelFailover.call(
                        List.of(model(1L)),
                        (attempt, m) -> {
                            throw new IllegalStateException("模型服务不可用");
                        },
                        new BlockingModelFailover.Listener() {
                        }));

        assertEquals("模型服务不可用", ex.getMessage());
    }
}

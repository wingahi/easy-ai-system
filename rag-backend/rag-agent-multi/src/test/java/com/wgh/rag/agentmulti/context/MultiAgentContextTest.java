package com.wgh.rag.agentmulti.context;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MultiAgentContext 单元测试 —— 验证扁平 data 读写、vars 读写、循环计数与 {{path}} 模板渲染。
 */
class MultiAgentContextTest {

    private MultiAgentContext ctx;

    @BeforeEach
    void setUp() {
        ctx = new MultiAgentContext();
    }

    /** put/get 基本读写：写入后能原样取回，未写入的 key 返回 null */
    @Test
    void putGet_basicReadWrite() {
        ctx.put("a1.text", "hello");
        ctx.put("count", 42);

        assertThat(ctx.get("a1.text")).isEqualTo("hello");
        assertThat(ctx.get("count")).isEqualTo(42);
        assertThat(ctx.get("missing")).isNull();
        assertThat(ctx.containsKey("a1.text")).isTrue();
        assertThat(ctx.getOrDefault("missing", "fallback")).isEqualTo("fallback");
    }

    /** render 渲染已存入的运行时产物：{{a1.text}} 命中 data 中 a1(Map).text */
    @Test
    void render_existingAgentOutput() {
        ctx.put("a1", Map.of("text", "你好，世界"));

        assertThat(ctx.render("{{a1.text}}")).isEqualTo("你好，世界");
    }

    /** render 引用不存在的 key：解析为 null → 渲染为空串，不抛异常 */
    @Test
    void render_missingKey_rendersEmptyString() {
        assertThat(ctx.render("{{nope.text}}")).isEmpty();
        assertThat(ctx.render("{{vars.missing}}")).isEmpty();
    }

    /** render 嵌套变量与普通文本混合：vars 与 agent 产物共存于同一模板 */
    @Test
    void render_mixedVarsAndPlainText() {
        ctx.setVars("name", "WGH");
        ctx.put("a1", Map.of("text", "done"));

        String out = ctx.render("任务{{vars.name}}执行结果：{{a1.text}}！");

        assertThat(out).isEqualTo("任务WGH执行结果：done！");
    }

    /** incrementLoop/getLoopIteration：递增返回最新值，未知 loopId 默认为 0，互不影响 */
    @Test
    void loopIteration_incrementAndRead() {
        assertThat(ctx.getLoopIteration("loopA")).isZero();

        assertThat(ctx.incrementLoop("loopA")).isEqualTo(1);
        assertThat(ctx.incrementLoop("loopA")).isEqualTo(2);
        assertThat(ctx.incrementLoop("loopB")).isEqualTo(1);

        assertThat(ctx.getLoopIteration("loopA")).isEqualTo(2);
        assertThat(ctx.getLoopIteration("loopB")).isEqualTo(1);
        assertThat(ctx.getLoopIteration("neverTouched")).isZero();
    }

    /** vars 读写：setVars/getVars 与 getVarsMap 视图一致 */
    @Test
    void vars_readWrite() {
        ctx.setVars("input", "原始问题");
        ctx.setVars("threshold", 0.75);

        assertThat(ctx.getVars("input")).isEqualTo("原始问题");
        assertThat(ctx.getVars("threshold")).isEqualTo(0.75);
        assertThat(ctx.getVars("missing")).isNull();
        assertThat(ctx.getVarsMap()).containsEntry("input", "原始问题");
    }
}

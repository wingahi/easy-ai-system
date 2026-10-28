package com.wgh.rag.workflow.engine;

import com.wgh.rag.workflow.dto.RunContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VariableResolver 行为单测 —— 钉住 {{path}} 模板渲染现有全部语法形态与边界，
 * 作为 R6 模板渲染统一重构的行为不变式基线。
 */
class VariableResolverTest {

    private VariableResolver resolver;
    private RunContext ctx;

    @BeforeEach
    void setUp() {
        resolver = new VariableResolver();
        ctx = new RunContext();
    }

    /** null 模板渲染为空串，空模板原样返回 */
    @Test
    void render_nullOrEmptyTemplate() {
        assertThat(resolver.render(null, ctx)).isEmpty();
        assertThat(resolver.render("", ctx)).isEmpty();
    }

    /** 无占位符 / 不匹配占位符形态的模板原样返回 */
    @Test
    void render_noPlaceholder_returnsTemplateAsIs() {
        assertThat(resolver.render("plain text", ctx)).isEqualTo("plain text");
        assertThat(resolver.render("{{}}", ctx)).isEqualTo("{{}}");
        assertThat(resolver.render("{{ a b }}", ctx)).isEqualTo("{{ a b }}");
        assertThat(resolver.render("{ {input} }", ctx)).isEqualTo("{ {input} }");
    }

    /** {{input}} / {{last}} 取自 vars 池的固定键 */
    @Test
    void render_inputAndLast_fromVars() {
        ctx.getVars().put("input", "原始问题");
        ctx.getVars().put("lastOutput", "上轮输出");

        assertThat(resolver.render("Q:{{input}}", ctx)).isEqualTo("Q:原始问题");
        assertThat(resolver.render("{{last}}", ctx)).isEqualTo("上轮输出");
    }

    /** 优先级 quirk：nodeOutputs 中名为 input 的节点不会遮蔽 vars.input */
    @Test
    void render_inputShadowsNodeNamedInput() {
        ctx.getVars().put("input", "变量值");
        ctx.getNodeOutputs().put("input", Map.of("text", "节点输出"));

        assertThat(resolver.render("{{input}}", ctx)).isEqualTo("变量值");
    }

    /** {{vars.x}} 支持 Map 嵌套与 List 下标，越界 / 非数字下标 / 缺失键渲染为空串 */
    @Test
    void render_varsNestedAndIndexedPaths() {
        ctx.getVars().put("user", Map.of("name", "WGH", "tags", List.of("a", "b")));

        assertThat(resolver.render("{{vars.user.name}}", ctx)).isEqualTo("WGH");
        assertThat(resolver.render("{{vars.user.tags.0}}", ctx)).isEqualTo("a");
        assertThat(resolver.render("{{vars.user.tags.1}}", ctx)).isEqualTo("b");
        assertThat(resolver.render("{{vars.user.tags.5}}", ctx)).isEmpty();
        assertThat(resolver.render("{{vars.user.tags.-1}}", ctx)).isEmpty();
        assertThat(resolver.render("{{vars.user.tags.x}}", ctx)).isEmpty();
        assertThat(resolver.render("{{vars.missing}}", ctx)).isEmpty();
        assertThat(resolver.render("{{vars.user.missing}}", ctx)).isEmpty();
    }

    /** 单段 {{nodeId}} 默认取输出 Map 的 text 键；缺 text 键时渲染为空串 */
    @Test
    void render_nodeOutput_defaultsToTextKey() {
        ctx.getNodeOutputs().put("n1", Map.of("text", "答案", "summary", "摘要"));
        ctx.getNodeOutputs().put("n2", Map.of("summary", "只有摘要"));

        assertThat(resolver.render("{{n1}}", ctx)).isEqualTo("答案");
        assertThat(resolver.render("{{n1.text}}", ctx)).isEqualTo("答案");
        assertThat(resolver.render("{{n1.summary}}", ctx)).isEqualTo("摘要");
        assertThat(resolver.render("{{n2}}", ctx)).isEmpty();
    }

    /** 节点输出支持深路径：{{nodeId.sources.0.fileName}} */
    @Test
    void render_nodeOutputDeepPath() {
        ctx.getNodeOutputs().put("ret1", Map.of(
                "text", "检索结果",
                "sources", List.of(Map.of("fileName", "a.pdf", "page", 3))));

        assertThat(resolver.render("来源：{{ret1.sources.0.fileName}}", ctx)).isEqualTo("来源：a.pdf");
        assertThat(resolver.render("{{ret1.sources.0.page}}", ctx)).isEqualTo("3");
        assertThat(resolver.render("{{ret1.sources.9.fileName}}", ctx)).isEmpty();
        assertThat(resolver.render("{{ret1.sources.x}}", ctx)).isEmpty();
    }

    /** 深路径中间值不可遍历（如 String 上继续取子键）渲染为空串 */
    @Test
    void render_deepPathOnScalar_rendersEmpty() {
        ctx.getNodeOutputs().put("n1", Map.of("text", "标量"));

        assertThat(resolver.render("{{n1.text.foo}}", ctx)).isEmpty();
    }

    /** 缺失节点 / 缺失键渲染为空串，不抛异常 */
    @Test
    void render_missingNodeOrKey_rendersEmpty() {
        assertThat(resolver.render("{{nope}}", ctx)).isEmpty();
        assertThat(resolver.render("{{nope.text}}", ctx)).isEmpty();
        ctx.getNodeOutputs().put("n1", Map.of("text", "答案"));
        assertThat(resolver.render("{{n1.missing}}", ctx)).isEmpty();
    }

    /** {{loop.iteration}} 取 RunContext.currentLoopIteration；其余 loop.* 与裸 {{loop}} 渲染为空串 */
    @Test
    void render_loopPaths() {
        ctx.setCurrentLoopIteration(3);

        assertThat(resolver.render("第{{loop.iteration}}轮", ctx)).isEqualTo("第3轮");
        assertThat(resolver.render("{{loop.other}}", ctx)).isEmpty();
        assertThat(resolver.render("{{loop}}", ctx)).isEmpty();
    }

    /** 占位符允许首尾空白；路径段允许连字符（不按 - 切分） */
    @Test
    void render_whitespaceAndHyphenPaths() {
        ctx.getVars().put("x", 1);
        ctx.getNodeOutputs().put("my-node", Map.of("text", "T"));

        assertThat(resolver.render("{{ vars.x }}", ctx)).isEqualTo("1");
        assertThat(resolver.render("{{my-node.text}}", ctx)).isEqualTo("T");
        assertThat(resolver.render("{{\tvars.x\t}}", ctx)).isEqualTo("1");
    }

    /** 非字符串值：数字 / 布尔转字符串，Map / List 序列化为 JSON，null 为空串 */
    @Test
    void render_nonStringValues() {
        ctx.getVars().put("n", 42);
        ctx.getVars().put("b", true);
        ctx.getVars().put("m", Map.of("a", 1));
        ctx.getVars().put("l", List.of(1, 2));

        assertThat(resolver.render("{{vars.n}}", ctx)).isEqualTo("42");
        assertThat(resolver.render("{{vars.b}}", ctx)).isEqualTo("true");
        assertThat(resolver.render("{{vars.m}}", ctx)).isEqualTo("{\"a\":1}");
        assertThat(resolver.render("{{vars.l}}", ctx)).isEqualTo("[1,2]");
        assertThat(resolver.render("{{vars.absent}}", ctx)).isEmpty();
    }

    /** 替换值中的 $ 与反斜杠按字面输出（quoteReplacement 语义） */
    @Test
    void render_valueWithRegexMetachars_literal() {
        ctx.getVars().put("p", "C:\\dir$1{x}");

        assertThat(resolver.render("{{vars.p}}!", ctx)).isEqualTo("C:\\dir$1{x}!");
    }

    /** 同模板多占位符与普通文本混排 */
    @Test
    void render_multiplePlaceholdersMixedWithText() {
        ctx.getVars().put("name", "WGH");
        ctx.getNodeOutputs().put("a1", Map.of("text", "done"));

        assertThat(resolver.render("任务{{vars.name}}执行结果：{{a1.text}}！", ctx))
                .isEqualTo("任务WGH执行结果：done！");
    }

    /** resolvePath 返回原始对象（不做字符串化）；null 路径 / null ctx / 裸 vars 返回 null */
    @Test
    void resolvePath_returnsRawObjects() {
        ctx.getVars().put("n", 42);
        ctx.getVars().put("m", Map.of("a", 1));
        ctx.getNodeOutputs().put("n1", Map.of("text", "答案"));

        assertThat(resolver.resolvePath("vars.n", ctx)).isEqualTo(42);
        assertThat(resolver.resolvePath("vars.m", ctx)).isEqualTo(Map.of("a", 1));
        assertThat(resolver.resolvePath("n1", ctx)).isEqualTo("答案");
        assertThat(resolver.resolvePath(null, ctx)).isNull();
        assertThat(resolver.resolvePath("vars", ctx)).isNull();
        assertThat(resolver.resolvePath("vars.n", null)).isNull();
    }

    /** stringify：null→空串、String 原样、数字/布尔转字符串、Map/List 走 JSON */
    @Test
    void stringify_valueKinds() {
        assertThat(resolver.stringify(null)).isEmpty();
        assertThat(resolver.stringify("s")).isEqualTo("s");
        assertThat(resolver.stringify(42)).isEqualTo("42");
        assertThat(resolver.stringify(false)).isEqualTo("false");
        assertThat(resolver.stringify(Map.of("k", "v"))).isEqualTo("{\"k\":\"v\"}");
        assertThat(resolver.stringify(List.of("x"))).isEqualTo("[\"x\"]");
    }
}

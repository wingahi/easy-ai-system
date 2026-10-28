package com.wgh.rag.agentmulti.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MultiAgentContext 模板渲染行为单测 —— 钉住 {{path}} 现有全部语法形态与边界，
 * 作为 R6 模板渲染统一重构的行为不变式基线（与 workflow VariableResolver 的差异点也在此钉住）。
 */
class MultiAgentContextRenderTest {

    private MultiAgentContext ctx;

    @BeforeEach
    void setUp() {
        ctx = new MultiAgentContext();
    }

    /** null 模板渲染为空串，空模板原样返回 */
    @Test
    void render_nullOrEmptyTemplate() {
        assertThat(ctx.render(null)).isEmpty();
        assertThat(ctx.render("")).isEmpty();
    }

    /** 无占位符 / 不匹配占位符形态的模板原样返回 */
    @Test
    void render_noPlaceholder_returnsTemplateAsIs() {
        assertThat(ctx.render("plain text")).isEqualTo("plain text");
        assertThat(ctx.render("{{}}")).isEqualTo("{{}}");
        assertThat(ctx.render("{{ a b }}")).isEqualTo("{{ a b }}");
    }

    /** 与 workflow 的差异点：单段 {{agentId}} 返回 data 中的整个值（Map 序列化为 JSON），无 text 默认键 */
    @Test
    void render_singleSegmentAgentId_returnsWholeValue() {
        Map<String, Object> output = new java.util.LinkedHashMap<>();
        output.put("text", "你好");
        output.put("summary", "摘要");
        ctx.put("a1", output);

        assertThat(ctx.render("{{a1.text}}")).isEqualTo("你好");
        assertThat(ctx.render("{{a1.summary}}")).isEqualTo("摘要");
        assertThat(ctx.render("{{a1}}")).isEqualTo("{\"text\":\"你好\",\"summary\":\"摘要\"}");
    }

    /** agentId 深路径：Map 嵌套 + List 下标；越界 / 非数字下标渲染为空串 */
    @Test
    void render_agentDeepPath() {
        ctx.put("ret1", Map.of(
                "text", "检索结果",
                "sources", List.of(Map.of("fileName", "a.pdf"))));

        assertThat(ctx.render("来源：{{ret1.sources.0.fileName}}")).isEqualTo("来源：a.pdf");
        assertThat(ctx.render("{{ret1.sources.9.fileName}}")).isEmpty();
        assertThat(ctx.render("{{ret1.sources.x}}")).isEmpty();
    }

    /** data 值为标量时：单段原样输出，继续取子键渲染为空串 */
    @Test
    void render_scalarDataValue() {
        ctx.put("a1", "标量");

        assertThat(ctx.render("{{a1}}")).isEqualTo("标量");
        assertThat(ctx.render("{{a1.text}}")).isEmpty();
    }

    /** 缺失 key 渲染为空串，不抛异常 */
    @Test
    void render_missingKeys_renderEmpty() {
        assertThat(ctx.render("{{nope}}")).isEmpty();
        assertThat(ctx.render("{{nope.text}}")).isEmpty();
        assertThat(ctx.render("{{vars.missing}}")).isEmpty();
        assertThat(ctx.render("{{vars}}")).isEmpty();
    }

    /** {{vars.x}} 支持嵌套 Map 与 List 下标 */
    @Test
    void render_varsNestedAndIndexed() {
        ctx.setVars("user", Map.of("name", "WGH", "tags", List.of("a", "b")));

        assertThat(ctx.render("{{vars.user.name}}")).isEqualTo("WGH");
        assertThat(ctx.render("{{vars.user.tags.1}}")).isEqualTo("b");
        assertThat(ctx.render("{{vars.user.tags.5}}")).isEmpty();
    }

    /** 与 workflow 的差异点：{{input}} / {{last}} 先查 vars，vars 缺失时兜底 data */
    @Test
    void render_inputAndLast_varsThenDataFallback() {
        ctx.put("input", "data输入");
        ctx.put("lastOutput", "data上轮");

        assertThat(ctx.render("{{input}}")).isEqualTo("data输入");
        assertThat(ctx.render("{{last}}")).isEqualTo("data上轮");

        ctx.setVars("input", "vars输入");
        ctx.setVars("lastOutput", "vars上轮");

        assertThat(ctx.render("{{input}}")).isEqualTo("vars输入");
        assertThat(ctx.render("{{last}}")).isEqualTo("vars上轮");
    }

    /** 与 workflow 的差异点：{{loop.iteration}} 取所有循环迭代的最大值（无循环时为 0）；{{loop.xxx}} 查指定循环 */
    @Test
    void render_loopPaths() {
        assertThat(ctx.render("{{loop.iteration}}")).isEqualTo("0");

        ctx.incrementLoop("loopA");
        ctx.incrementLoop("loopA");
        ctx.incrementLoop("loopB");

        assertThat(ctx.render("第{{loop.iteration}}轮")).isEqualTo("第2轮");
        assertThat(ctx.render("{{loop.loopA}}")).isEqualTo("2");
        assertThat(ctx.render("{{loop.loopB}}")).isEqualTo("1");
        assertThat(ctx.render("{{loop.unknown}}")).isEmpty();
    }

    /** 现状 quirk 钉住：裸 {{loop}} 触发 ConcurrentHashMap.get(null)，抛 NullPointerException（本次重构不改变该行为） */
    @Test
    void render_bareLoop_throwsNpe() {
        assertThatThrownBy(() -> ctx.render("{{loop}}")).isInstanceOf(NullPointerException.class);
    }

    /** 占位符允许首尾空白；路径段允许连字符 */
    @Test
    void render_whitespaceAndHyphenPaths() {
        ctx.setVars("x", 1);
        ctx.put("my-agent", Map.of("text", "T"));

        assertThat(ctx.render("{{ vars.x }}")).isEqualTo("1");
        assertThat(ctx.render("{{my-agent.text}}")).isEqualTo("T");
    }

    /** 非字符串值：数字 / 布尔转字符串，Map / List 序列化为 JSON，null 为空串 */
    @Test
    void render_nonStringValues() {
        ctx.setVars("n", 42);
        ctx.setVars("b", true);
        ctx.setVars("m", Map.of("a", 1));
        ctx.setVars("l", List.of(1, 2));

        assertThat(ctx.render("{{vars.n}}")).isEqualTo("42");
        assertThat(ctx.render("{{vars.b}}")).isEqualTo("true");
        assertThat(ctx.render("{{vars.m}}")).isEqualTo("{\"a\":1}");
        assertThat(ctx.render("{{vars.l}}")).isEqualTo("[1,2]");
    }

    /** 替换值中的 $ 与反斜杠按字面输出（quoteReplacement 语义） */
    @Test
    void render_valueWithRegexMetachars_literal() {
        ctx.setVars("p", "C:\\dir$1{x}");

        assertThat(ctx.render("{{vars.p}}!")).isEqualTo("C:\\dir$1{x}!");
    }

    /** 与 workflow 的差异点：stringify 使用构造时注入的 ObjectMapper（此处以 INDENT_OUTPUT 证明注入生效） */
    @Test
    void render_usesInjectedObjectMapper() {
        ObjectMapper indentMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        MultiAgentContext custom = new MultiAgentContext(indentMapper);
        custom.put("a1", Map.of("text", "你好"));

        assertThat(custom.render("{{a1}}")).contains(System.lineSeparator());
    }

    /** resolvePath 返回原始对象（不做字符串化）；null 路径返回 null */
    @Test
    void resolvePath_returnsRawObjects() {
        ctx.setVars("n", 42);
        ctx.put("a1", Map.of("text", "你好"));

        assertThat(ctx.resolvePath("vars.n")).isEqualTo(42);
        assertThat(ctx.resolvePath("a1")).isEqualTo(Map.of("text", "你好"));
        assertThat(ctx.resolvePath("a1.text")).isEqualTo("你好");
        assertThat(ctx.resolvePath(null)).isNull();
    }

    /** stringify：null→空串、String 原样、数字/布尔转字符串、Map/List 走 JSON */
    @Test
    void stringify_valueKinds() {
        assertThat(ctx.stringify(null)).isEmpty();
        assertThat(ctx.stringify("s")).isEqualTo("s");
        assertThat(ctx.stringify(42)).isEqualTo("42");
        assertThat(ctx.stringify(false)).isEqualTo("false");
        assertThat(ctx.stringify(Map.of("k", "v"))).isEqualTo("{\"k\":\"v\"}");
        assertThat(ctx.stringify(List.of("x"))).isEqualTo("[\"x\"]");
    }
}

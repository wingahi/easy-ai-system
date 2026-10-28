package com.wgh.rag.common.template;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TemplateRenderer 单元测试 —— 验证共享渲染核心：占位符语法、替换循环、traverse 与默认 stringify。
 */
class TemplateRendererTest {

    /** null 模板渲染为空串，空模板原样返回 */
    @Test
    void render_nullOrEmptyTemplate() {
        assertThat(TemplateRenderer.render(null, path -> "x")).isEmpty();
        assertThat(TemplateRenderer.render("", path -> "x")).isEmpty();
    }

    /** 无占位符 / 不匹配形态（{{}}、占位符内含空格）原样返回 */
    @Test
    void render_noMatch_returnsTemplateAsIs() {
        assertThat(TemplateRenderer.render("plain", path -> "x")).isEqualTo("plain");
        assertThat(TemplateRenderer.render("{{}}", path -> "x")).isEqualTo("{{}}");
        assertThat(TemplateRenderer.render("{{ a b }}", path -> "x")).isEqualTo("{{ a b }}");
    }

    /** 占位符首尾空白被忽略，resolver 收到的是裁剪后的 path；多占位符全部替换 */
    @Test
    void render_capturesTrimmedPathAndReplacesAll() {
        Map<String, Object> values = Map.of("a", "1", "b.c", "2");

        String out = TemplateRenderer.render("{{ a }}+{{b.c}}={{a}}", values::get);

        assertThat(out).isEqualTo("1+2=1");
    }

    /** resolver 未命中（null）渲染为空串；替换值中的 $ 与反斜杠按字面输出 */
    @Test
    void render_nullValueAndRegexMetachars() {
        assertThat(TemplateRenderer.render("[{{missing}}]", path -> null)).isEqualTo("[]");
        assertThat(TemplateRenderer.render("{{p}}!", path -> "C:\\dir$1")).isEqualTo("C:\\dir$1!");
    }

    /** 自定义 stringifier 生效 */
    @Test
    void render_customStringifier() {
        String out = TemplateRenderer.render("{{a}}", path -> 42, v -> "<" + v + ">");

        assertThat(out).isEqualTo("<42>");
    }

    /** 默认 stringify：null→空串、String 原样、数字/布尔转字符串、Map/List 走 JSON */
    @Test
    void stringify_valueKinds() {
        assertThat(TemplateRenderer.stringify(null)).isEmpty();
        assertThat(TemplateRenderer.stringify("s")).isEqualTo("s");
        assertThat(TemplateRenderer.stringify(42)).isEqualTo("42");
        assertThat(TemplateRenderer.stringify(true)).isEqualTo("true");
        assertThat(TemplateRenderer.stringify(Map.of("k", "v"))).isEqualTo("{\"k\":\"v\"}");
        assertThat(TemplateRenderer.stringify(List.of(1, 2))).isEqualTo("[1,2]");
    }

    /** traverse：Map 键缺失、List 越界 / 负下标 / 非数字下标、标量不可遍历，均返回 null */
    @Test
    void traverse_mapListAndScalars() {
        Map<String, Object> root = Map.of("name", "WGH", "tags", List.of("a", "b"));

        assertThat(TemplateRenderer.traverse(root, new String[]{"name"}, 0)).isEqualTo("WGH");
        assertThat(TemplateRenderer.traverse(root, new String[]{"tags", "1"}, 0)).isEqualTo("b");
        assertThat(TemplateRenderer.traverse(root, new String[]{"tags", "5"}, 0)).isNull();
        assertThat(TemplateRenderer.traverse(root, new String[]{"tags", "-1"}, 0)).isNull();
        assertThat(TemplateRenderer.traverse(root, new String[]{"tags", "x"}, 0)).isNull();
        assertThat(TemplateRenderer.traverse(root, new String[]{"missing"}, 0)).isNull();
        assertThat(TemplateRenderer.traverse(root, new String[]{"name", "sub"}, 0)).isNull();
        assertThat(TemplateRenderer.traverse(null, new String[]{"a"}, 0)).isNull();
    }

    /** traverse：from 偏移生效，跳过前 from 段 */
    @Test
    void traverse_fromOffset() {
        Map<String, Object> root = Map.of("child", Map.of("leaf", "v"));

        assertThat(TemplateRenderer.traverse(root, new String[]{"skip", "child", "leaf"}, 1)).isEqualTo("v");
        assertThat(TemplateRenderer.traverse(root, new String[]{"skip"}, 1)).isEqualTo(root);
    }
}

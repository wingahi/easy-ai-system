package com.wgh.rag.workflow.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 表达式沙箱：支持的语法子集求值 + 危险语法拒绝 + 语法校验。
 */
@DisplayName("表达式沙箱")
class ExpressionSandboxTest {

    private Map<String, Object> variables() {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("input", "hello");
        vars.put("last", "上一节点");
        vars.put("loop", 2);
        vars.put("vars", Map.of("x", 3, "name", "张三"));
        vars.put("nodes", Map.of("n1", Map.of("text", "abc", "count", 2)));
        vars.put("fn", ExpressionSandbox.Functions.INSTANCE);
        return vars;
    }

    private Object eval(String expression) {
        return ExpressionSandbox.evaluate(expression, variables());
    }

    // ===== 允许的语法 =====

    @Test
    @DisplayName("算术/比较/逻辑/三元")
    void basicOperators() {
        assertThat(eval("#vars.x + 2")).isEqualTo(5);
        assertThat(eval("#vars.x * 2 > 5 ? 'yes' : 'no'")).isEqualTo("yes");
        assertThat(eval("#vars.x == 3 && #input != null")).isEqualTo(true);
        assertThat(eval("#nodes.n1.count + #loop")).isEqualTo(4);
    }

    @Test
    @DisplayName("上下文字段与下标读取")
    void contextAccess() {
        assertThat(eval("#nodes.n1.text")).isEqualTo("abc");
        assertThat(eval("#nodes['n1']['text']")).isEqualTo("abc");
        assertThat(eval("#vars['name']")).isEqualTo("张三");
        assertThat(eval("#last")).isEqualTo("上一节点");
    }

    @Test
    @DisplayName("字符串拼接与内联列表")
    void stringAndList() {
        assertThat(eval("#input + ' world'")).isEqualTo("hello world");
        assertThat(eval("{1,2,3}[1]")).isEqualTo(2);
    }

    @Test
    @DisplayName("白名单函数可用")
    void whitelistedFunctions() {
        assertThat(eval("#fn.upper(#nodes.n1.text)")).isEqualTo("ABC");
        assertThat(eval("#fn.len(#input)")).isEqualTo(5);
        assertThat(eval("#fn.intOf(#vars['x']) + 1")).isEqualTo(4);
        assertThat(eval("#fn.substr(#input, 1, 4)")).isEqualTo("ell");
        assertThat(eval("#fn.orElse('', '兜底')")).isEqualTo("兜底");
        assertThat(eval("#fn.split('a,b', ',')")).isEqualTo(List.of("a", "b"));
        assertThat(eval("#fn.bool('0')")).isEqualTo(false);
    }

    // ===== 拒绝的危险语法 =====

    @Test
    @DisplayName("拒绝类型引用 T(...)")
    void rejectsTypeReference() {
        assertThatThrownBy(() -> eval("T(java.lang.Runtime).getRuntime()"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("类型引用");
    }

    @Test
    @DisplayName("拒绝 new 构造对象")
    void rejectsConstructor() {
        assertThatThrownBy(() -> eval("new java.util.Date()"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("new");
    }

    @Test
    @DisplayName("拒绝 Bean 引用 @bean")
    void rejectsBeanReference() {
        assertThatThrownBy(() -> eval("@someBean"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Bean");
    }

    @Test
    @DisplayName("拒绝实例方法调用（getClass 反射逃逸）")
    void rejectsInstanceMethods() {
        assertThatThrownBy(() -> eval("#vars.getClass()"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("表达式求值失败");
        assertThatThrownBy(() -> eval("'abc'.toUpperCase()"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("表达式求值失败");
    }

    @Test
    @DisplayName("拒绝赋值与超长表达式")
    void rejectsAssignmentAndOversize() {
        assertThatThrownBy(() -> eval("#vars.x = 9"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("表达式求值失败");

        String tooLong = "1" + "+1".repeat(1200);
        assertThatThrownBy(() -> eval(tooLong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("长度超过上限");
    }

    @Test
    @DisplayName("字符串字面量中的关键字不触发误判")
    void literalDoesNotTriggerDenylist() {
        assertThat(eval("'T(java.lang.Runtime)'"))
                .isEqualTo("T(java.lang.Runtime)");
        assertThat(eval("'a@b.com'")).isEqualTo("a@b.com");
    }

    // ===== 语法校验 =====

    @Test
    @DisplayName("语法校验：合法通过、非法报错、空表达式拒绝")
    void validateSyntax() {
        ExpressionSandbox.validateSyntax("#vars.x + 1");

        assertThatThrownBy(() -> ExpressionSandbox.validateSyntax("#vars.x +"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("语法错误");
        assertThatThrownBy(() -> ExpressionSandbox.validateSyntax("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
    }
}

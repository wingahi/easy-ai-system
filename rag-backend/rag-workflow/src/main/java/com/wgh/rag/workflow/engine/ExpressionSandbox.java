package com.wgh.rag.workflow.engine;

import org.springframework.expression.AccessException;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.MethodExecutor;
import org.springframework.expression.MethodResolver;
import org.springframework.expression.ParseException;
import org.springframework.expression.PropertyAccessor;
import org.springframework.expression.TypedValue;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 受限表达式沙箱 —— 表达式节点的唯一求值入口，同时为 GraphValidator 提供语法校验。
 *
 * <p>采用 SpEL 解析，但运行在 {@link SimpleEvaluationContext} 上：
 * <ul>
 *   <li>不支持类型引用 {@code T(...)}（无 TypeLocator）、Bean 引用 {@code @bean}（无 BeanResolver）、
 *       构造器调用 {@code new ...}、赋值 {@code =}、实例方法调用（未开启 withInstanceMethods）；</li>
 *   <li>仅注册一个只读 Map 属性访问器，使 {@code #vars.x} / {@code #nodes.n1.text} 可用；</li>
 *   <li>方法调用只允许白名单函数对象 {@code #fn} 上的方法，其余目标一律拒绝。</li>
 * </ul>
 *
 * <p>支持的语法子集：
 * 字面量（字符串/数字/布尔/null/内联 List）、变量（{@code #input} / {@code #last} / {@code #loop}）、
 * 上下文字段（{@code #vars.xxx} / {@code #nodes.节点ID.text}）、Map/List 下标、
 * 算术 {@code + - * / %}、比较 {@code == != > < >= <=}、逻辑 {@code && || !}、
 * 三元与 Elvis、{@code matches} 运算符，以及 {@code #fn} 下的有限函数。
 *
 * <p>明确拒绝：任意类加载/反射构造、Bean 调用、文件/网络访问、实例方法链、赋值。
 * 表达式长度上限 {@value #MAX_EXPRESSION_LENGTH} 字符。
 */
public final class ExpressionSandbox {

    /** 表达式长度上限，避免超长表达式拖慢解析/求值 */
    public static final int MAX_EXPRESSION_LENGTH = 2000;

    /** 结构级危险语法（在剥离字符串字面量后的骨架上匹配，避免误伤普通文本） */
    private static final Pattern TYPE_REFERENCE = Pattern.compile("(?<![A-Za-z0-9_])T\\s*\\(");
    private static final Pattern CONSTRUCTOR = Pattern.compile("(?<![A-Za-z0-9_])new\\s+", Pattern.CASE_INSENSITIVE);

    private static final SpelExpressionParser PARSER = new SpelExpressionParser();

    private ExpressionSandbox() {
    }

    /**
     * 校验表达式语法与安全约束（不执行）。
     *
     * @throws IllegalArgumentException 表达式为空、超长、含危险语法或语法错误
     */
    public static void validateSyntax(String expression) {
        assertSafe(expression);
        try {
            PARSER.parseExpression(expression);
        } catch (ParseException e) {
            throw new IllegalArgumentException("表达式语法错误：" + oneLine(e.getMessage()), e);
        }
    }

    /**
     * 在受限上下文中求值。
     *
     * @param expression 表达式文本
     * @param variables  可引用变量（以 #name 形式暴露，值建议为 String/Number/Boolean/Map/List）
     * @return 求值结果（可能为 null）
     * @throws IllegalArgumentException 安全校验失败、语法错误或求值失败
     */
    public static Object evaluate(String expression, Map<String, Object> variables) {
        assertSafe(expression);
        Expression parsed;
        try {
            parsed = PARSER.parseExpression(expression);
        } catch (ParseException e) {
            throw new IllegalArgumentException("表达式语法错误：" + oneLine(e.getMessage()), e);
        }
        SimpleEvaluationContext context = SimpleEvaluationContext
                .forPropertyAccessors(new ReadOnlyMapPropertyAccessor())
                .withAssignmentDisabled()
                .withMethodResolvers(new WhitelistedFunctionResolver())
                .build();
        if (variables != null) {
            for (Map.Entry<String, Object> entry : variables.entrySet()) {
                context.setVariable(entry.getKey(), entry.getValue());
            }
        }
        try {
            return parsed.getValue(context);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("表达式求值失败：" + oneLine(e.getMessage()), e);
        }
    }

    // ==================== 安全校验 ====================

    private static void assertSafe(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("表达式不能为空");
        }
        if (expression.length() > MAX_EXPRESSION_LENGTH) {
            throw new IllegalArgumentException("表达式长度超过上限 " + MAX_EXPRESSION_LENGTH + " 字符");
        }
        String skeleton = stripStringLiterals(expression);
        if (TYPE_REFERENCE.matcher(skeleton).find()) {
            throw new IllegalArgumentException("不允许使用类型引用 T(...)");
        }
        if (CONSTRUCTOR.matcher(skeleton).find()) {
            throw new IllegalArgumentException("不允许使用 new 构造对象");
        }
        if (skeleton.indexOf('@') >= 0) {
            throw new IllegalArgumentException("不允许引用 Bean（@bean）");
        }
    }

    /** 将单引号字符串字面量替换为空格，避免字面量中的关键字触发误判。 */
    private static String stripStringLiterals(String expression) {
        StringBuilder sb = new StringBuilder(expression.length());
        boolean inLiteral = false;
        boolean escaped = false;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (inLiteral) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '\'') {
                    inLiteral = false;
                }
                sb.append(' ');
            } else if (c == '\'') {
                inLiteral = true;
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String oneLine(String message) {
        if (message == null) return "未知错误";
        String s = message.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    // ==================== 只读 Map 属性访问器 ====================

    /** 允许 {@code #vars.x} / {@code #nodes.n1.text} 形式读取 Map，不支持写入。 */
    private static final class ReadOnlyMapPropertyAccessor implements PropertyAccessor {

        @Override
        public Class<?>[] getSpecificTargetClasses() {
            return new Class<?>[]{Map.class};
        }

        @Override
        public boolean canRead(EvaluationContext context, Object target, String name) {
            return target instanceof Map;
        }

        @Override
        public TypedValue read(EvaluationContext context, Object target, String name) throws AccessException {
            if (!(target instanceof Map<?, ?> map)) {
                throw new AccessException("仅支持读取 Map 字段");
            }
            Object value = map.get(name);
            return value == null ? TypedValue.NULL : new TypedValue(value);
        }

        @Override
        public boolean canWrite(EvaluationContext context, Object target, String name) {
            return false;
        }

        @Override
        public void write(EvaluationContext context, Object target, String name, Object newValue)
                throws AccessException {
            throw new AccessException("表达式上下文只读，不允许赋值");
        }
    }

    // ==================== 方法白名单 ====================

    /**
     * 只允许调用 {@link Functions} 上声明的 public 实例方法；其他目标（包括 String、Class、Map 等）一律拒绝，
     * 从而阻断 {@code getClass()}、{@code forName()} 等反射逃逸路径。
     */
    private static final class WhitelistedFunctionResolver implements MethodResolver {

        @Override
        public MethodExecutor resolve(EvaluationContext context, Object targetObject, String name,
                                      List<org.springframework.core.convert.TypeDescriptor> argumentTypes) {
            if (targetObject == null || targetObject.getClass() != Functions.class) {
                return null;
            }
            int argCount = argumentTypes == null ? 0 : argumentTypes.size();
            for (Method method : Functions.class.getDeclaredMethods()) {
                if (!method.getName().equals(name)
                        || method.getParameterCount() != argCount
                        || !Modifier.isPublic(method.getModifiers())
                        || Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                return (ctx, target, args) -> invoke(method, target, args);
            }
            return null;
        }

        private static TypedValue invoke(Method method, Object target, Object[] args) throws AccessException {
            try {
                return new TypedValue(method.invoke(target, args));
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                Exception wrapped = cause instanceof Exception ex ? ex : e;
                throw new AccessException("函数执行失败：" + cause.getMessage(), wrapped);
            } catch (IllegalAccessException | IllegalArgumentException e) {
                throw new AccessException("函数调用失败：" + e.getMessage(), e);
            }
        }
    }

    // ==================== 有限函数集 ====================

    /** 表达式中以 {@code #fn.xxx(...)} 形式调用的有限函数集（无 I/O、无反射、无类加载）。 */
    public static final class Functions {

        public static final Functions INSTANCE = new Functions();

        private Functions() {
        }

        public String str(Object value) {
            return value == null ? "" : String.valueOf(value);
        }

        public Integer intOf(Object value) {
            if (value instanceof Number n) return n.intValue();
            if (value == null) return 0;
            try {
                return (int) Double.parseDouble(String.valueOf(value).trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        public Double num(Object value) {
            if (value instanceof Number n) return n.doubleValue();
            if (value == null) return 0d;
            try {
                return Double.parseDouble(String.valueOf(value).trim());
            } catch (NumberFormatException e) {
                return 0d;
            }
        }

        public Boolean bool(Object value) {
            if (value == null) return false;
            if (value instanceof Boolean b) return b;
            if (value instanceof Number n) return n.doubleValue() != 0d;
            if (value instanceof Collection<?> c) return !c.isEmpty();
            if (value instanceof Map<?, ?> m) return !m.isEmpty();
            String s = String.valueOf(value).trim();
            return !s.isEmpty() && !"false".equalsIgnoreCase(s) && !"null".equalsIgnoreCase(s) && !"0".equals(s);
        }

        public Integer len(Object value) {
            if (value == null) return 0;
            if (value instanceof CharSequence s) return s.length();
            if (value instanceof Collection<?> c) return c.size();
            if (value instanceof Map<?, ?> m) return m.size();
            if (value.getClass().isArray()) return java.lang.reflect.Array.getLength(value);
            return String.valueOf(value).length();
        }

        public String upper(Object value) {
            return str(value).toUpperCase(Locale.ROOT);
        }

        public String lower(Object value) {
            return str(value).toLowerCase(Locale.ROOT);
        }

        public String trim(Object value) {
            return str(value).trim();
        }

        public String substr(Object value, Object start) {
            return substr(value, start, null);
        }

        public String substr(Object value, Object start, Object end) {
            String s = str(value);
            int from = clamp(intOf(start), 0, s.length());
            int to = end == null ? s.length() : clamp(intOf(end), 0, s.length());
            return from >= to ? "" : s.substring(from, to);
        }

        public String replace(Object value, Object target, Object replacement) {
            return str(value).replace(str(target), str(replacement));
        }

        public List<String> split(Object value, Object separator) {
            String s = str(value);
            String sep = str(separator);
            List<String> parts = new ArrayList<>();
            if (s.isEmpty()) return parts;
            if (sep.isEmpty()) {
                parts.add(s);
                return parts;
            }
            for (String part : s.split(Pattern.quote(sep))) {
                parts.add(part);
            }
            return parts;
        }

        public Boolean isEmpty(Object value) {
            return len(value) == 0;
        }

        public Object orElse(Object value, Object fallback) {
            if (value == null) return fallback;
            if (value instanceof CharSequence s && s.toString().isBlank()) return fallback;
            if (value instanceof Collection<?> c && c.isEmpty()) return fallback;
            return value;
        }

        private int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }
    }
}

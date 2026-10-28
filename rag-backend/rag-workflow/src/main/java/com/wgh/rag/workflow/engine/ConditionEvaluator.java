package com.wgh.rag.workflow.engine;

import org.springframework.stereotype.Component;

/**
 * 条件表达式求值器 —— 白名单式极简实现，不使用 SpEL / Groovy，避免表达式注入。
 *
 * 支持：== / != / > / < / >= / <= / contains / is_empty
 * 无运算符时按真值判断（非空、非 false、非 0、非 null）。
 */
@Component
public class ConditionEvaluator {

    public boolean evaluate(String expression) {
        if (expression == null) return false;
        String expr = expression.trim();
        if (expr.isEmpty()) return false;

        // 去掉最外层成对引号
        if (expr.length() > 1 && expr.startsWith("\"") && expr.endsWith("\"")) {
            expr = expr.substring(1, expr.length() - 1).trim();
        }

        if (expr.endsWith(" is_empty")) {
            String left = expr.substring(0, expr.length() - " is_empty".length()).trim();
            return left.isEmpty() || "null".equalsIgnoreCase(left);
        }

        int idx = expr.indexOf(" contains ");
        if (idx > 0) {
            String left = unquote(expr.substring(0, idx).trim());
            String right = unquote(expr.substring(idx + " contains ".length()).trim());
            return left.contains(right);
        }

        String[][] ops = {{">=", "ge"}, {"<=", "le"}, {"==", "eq"}, {"!=", "ne"}, {">", "gt"}, {"<", "lt"}};
        for (String[] op : ops) {
            int pos = expr.indexOf(op[0]);
            if (pos > 0) {
                String left = expr.substring(0, pos).trim();
                String right = expr.substring(pos + op[0].length()).trim();
                return compare(left, right, op[1]);
            }
        }

        return truthy(unquote(expr));
    }

    private boolean compare(String left, String right, String op) {
        Double l = parseNumber(left);
        Double r = parseNumber(right);
        if (l != null && r != null) {
            return switch (op) {
                case "gt" -> l > r;
                case "lt" -> l < r;
                case "ge" -> l >= r;
                case "le" -> l <= r;
                case "eq" -> l.doubleValue() == r.doubleValue();
                case "ne" -> l.doubleValue() != r.doubleValue();
                default -> false;
            };
        }
        String ls = unquote(left);
        String rs = unquote(right);
        return switch (op) {
            case "eq" -> ls.equals(rs);
            case "ne" -> !ls.equals(rs);
            case "gt" -> ls.compareTo(rs) > 0;
            case "lt" -> ls.compareTo(rs) < 0;
            case "ge" -> ls.compareTo(rs) >= 0;
            case "le" -> ls.compareTo(rs) <= 0;
            default -> false;
        };
    }

    private Double parseNumber(String s) {
        try {
            return Double.parseDouble(unquote(s));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean truthy(String s) {
        if (s == null) return false;
        String v = s.trim();
        return !v.isEmpty()
                && !"false".equalsIgnoreCase(v)
                && !"null".equalsIgnoreCase(v)
                && !"0".equals(v);
    }

    private String unquote(String s) {
        if (s == null) return "";
        String v = s.trim();
        if (v.length() > 1 && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }
}

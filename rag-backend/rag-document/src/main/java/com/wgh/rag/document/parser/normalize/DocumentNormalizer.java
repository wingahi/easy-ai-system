package com.wgh.rag.document.parser.normalize;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 文本规范化层：各解析器复用，负责把抽取出的“脏文本”收敛成稳定、可切分的内容。
 * <p>
 * 处理范围：
 * <ul>
 *   <li>全角字母/数字、全角空格转半角；</li>
 *   <li>去除控制字符、零宽字符、软连字符；</li>
 *   <li>连字符断行合并（docu-\nment → document）；</li>
 *   <li>折叠多余空白与空行；</li>
 *   <li>常见 UTF-8 被按 Latin-1 解码产生的乱码修复；</li>
 *   <li>合并连续重复段落；</li>
 *   <li>跨页删除重复页眉页脚与页码（页数 ≥ 3 时启用，避免误删正文）。</li>
 * </ul>
 * 设计上保持保守：只在明确证据（重复出现、页边缘、页码形态）下删除内容。
 */
public class DocumentNormalizer {

    private static final Pattern HYPHEN_LINE_BREAK =
            Pattern.compile("(?<=\\p{L})-\\s*\\n\\s*(?=\\p{Ll})");
    private static final Pattern CONTROL =
            Pattern.compile("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]");
    private static final Pattern HORIZONTAL_SPACE =
            Pattern.compile("[ \\t\\u00A0]+");
    private static final Pattern MULTI_BLANK =
            Pattern.compile("\\n{3,}");
    private static final Pattern PAGE_NUMBER = Pattern.compile(
            "^(?:[-–—]?\\s*\\d{1,4}\\s*[-–—]?"
                    + "|第\\s*\\d{1,4}\\s*页(?:\\s*[/共]?\\s*\\d{1,4}\\s*页?)?"
                    + "|page\\s+\\d{1,4}(?:\\s+of\\s+\\d{1,4})?)$",
            Pattern.CASE_INSENSITIVE);

    /** 常见 mojibake 映射；长串优先替换，避免 “â€” 提前吞掉 “â€“”。 */
    private static final Map<String, String> MOJIBAKE = new LinkedHashMap<>();

    static {
        MOJIBAKE.put("\u00E2\u20AC\u201C", "–");
        MOJIBAKE.put("\u00E2\u20AC\u201D", "—");
        MOJIBAKE.put("\u00E2\u20AC\u2122", "'");
        MOJIBAKE.put("\u00E2\u20AC\u0098", "'");
        MOJIBAKE.put("\u00E2\u20AC\u0153", "\"");
        MOJIBAKE.put("\u00E2\u20AC\u009D", "\"");
        MOJIBAKE.put("\u00E2\u20AC\u00A6", "…");
        MOJIBAKE.put("\u00C3\u00A9", "é");
        MOJIBAKE.put("\u00C3\u00A8", "è");
        MOJIBAKE.put("\u00C3\u00BC", "ü");
        MOJIBAKE.put("\u00C3\u00B6", "ö");
        MOJIBAKE.put("\u00C3\u00A4", "ä");
    }

    /**
     * 规范化段落列表：逐段清洗，丢弃空段，合并连续重复段。
     */
    public List<String> normalize(List<String> paragraphs) {
        if (paragraphs == null || paragraphs.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        String previous = null;
        for (String paragraph : paragraphs) {
            if (paragraph == null) {
                continue;
            }
            String normalized = normalizeText(paragraph);
            if (normalized.isBlank() || normalized.equals(previous)) {
                continue;
            }
            result.add(normalized);
            previous = normalized;
        }
        return result;
    }

    /**
     * 规范化一段文本（可包含换行）。
     */
    public String normalizeText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String value = repairMojibake(text);
        value = value.replace("\r\n", "\n").replace('\r', '\n');
        value = HYPHEN_LINE_BREAK.matcher(value).replaceAll("");
        value = toHalfWidth(value);
        value = CONTROL.matcher(value).replaceAll("");
        value = value.replace("\u200B", "")
                .replace("\uFEFF", "")
                .replace("\u00AD", "");

        String[] lines = value.split("\n", -1);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = HORIZONTAL_SPACE.matcher(lines[i]).replaceAll(" ").trim();
            if (i > 0) {
                builder.append('\n');
            }
            builder.append(line);
        }
        return MULTI_BLANK.matcher(builder.toString()).replaceAll("\n\n").strip();
    }

    /**
     * 跨页删除重复页眉页脚与页码。
     * <p>
     * 输入为按页分组的段落块；页数少于 3 时不做删除（证据不足，避免误删）。
     * 只检查每页首/尾各两个段落块，且最多删除首/尾各两行。
     *
     * @return 清理后的页段落块（不修改入参）
     */
    public List<List<String>> removeRepeatedHeaderFooter(List<List<String>> pages) {
        if (pages == null || pages.isEmpty()) {
            return List.of();
        }
        List<List<String>> copy = new ArrayList<>();
        for (List<String> page : pages) {
            copy.add(page == null ? new ArrayList<>() : new ArrayList<>(page));
        }
        if (copy.size() < 3) {
            return copy;
        }

        Map<String, Integer> counts = new HashMap<>();
        for (List<String> page : copy) {
            Set<String> seen = new HashSet<>();
            for (String candidate : edgeLines(page)) {
                String key = canonical(candidate);
                if (!key.isEmpty() && key.length() <= 80 && seen.add(key)) {
                    counts.merge(key, 1, Integer::sum);
                }
            }
        }
        int threshold = Math.max(3, (int) Math.ceil(copy.size() * 0.5));
        Set<String> repeated = new HashSet<>();
        counts.forEach((key, count) -> {
            if (count >= threshold) {
                repeated.add(key);
            }
        });

        for (List<String> page : copy) {
            removeEdgeLines(page, repeated);
        }
        return copy;
    }

    private void removeEdgeLines(List<String> page, Set<String> repeated) {
        int firstNonEmpty = -1;
        int lastNonEmpty = -1;
        for (int i = 0; i < page.size(); i++) {
            String block = page.get(i);
            if (block != null && !block.isBlank()) {
                if (firstNonEmpty < 0) {
                    firstNonEmpty = i;
                }
                lastNonEmpty = i;
            }
        }
        for (int index = 0; index < page.size(); index++) {
            String block = page.get(index);
            if (block == null || block.isBlank()) {
                continue;
            }
            String[] lines = block.split("\n", -1);
            int start = 0;
            int end = lines.length - 1;
            if (index == firstNonEmpty) {
                int removed = 0;
                while (start <= end && removed < 2 && isEdgeNoise(lines[start], repeated)) {
                    start++;
                    removed++;
                }
            }
            if (index == lastNonEmpty) {
                int removed = 0;
                while (end >= start && removed < 2 && isEdgeNoise(lines[end], repeated)) {
                    end--;
                    removed++;
                }
            }
            List<String> kept = new ArrayList<>();
            for (int i = start; i <= end; i++) {
                kept.add(lines[i]);
            }
            page.set(index, String.join("\n", kept).strip());
        }
        page.removeIf(block -> block == null || block.isBlank());
    }

    private boolean isEdgeNoise(String line, Set<String> repeated) {
        if (line == null || line.isBlank()) {
            return false;
        }
        String trimmed = line.trim();
        return PAGE_NUMBER.matcher(trimmed).matches() || repeated.contains(canonical(trimmed));
    }

    private List<String> edgeLines(List<String> page) {
        List<String> lines = new ArrayList<>();
        for (String block : page) {
            if (block == null || block.isBlank()) {
                continue;
            }
            for (String line : block.split("\n", -1)) {
                if (!line.isBlank()) {
                    lines.add(line);
                }
            }
        }
        List<String> result = new ArrayList<>();
        if (lines.isEmpty()) {
            return result;
        }
        result.add(lines.get(0));
        if (lines.size() > 1) {
            result.add(lines.get(lines.size() - 1));
        }
        return result;
    }

    private String canonical(String line) {
        String normalized = normalizeText(line);
        if (normalized.isEmpty()) {
            return "";
        }
        return HORIZONTAL_SPACE.matcher(normalized.replaceAll("\\d+", "#")).replaceAll(" ").trim();
    }

    private String repairMojibake(String text) {
        String value = text;
        for (Map.Entry<String, String> entry : MOJIBAKE.entrySet()) {
            if (value.contains(entry.getKey())) {
                value = value.replace(entry.getKey(), entry.getValue());
            }
        }
        return value;
    }

    private String toHalfWidth(String text) {
        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u3000') {
                builder.append(' ');
            } else if (c >= '\uFF10' && c <= '\uFF19') {
                builder.append((char) (c - '\uFF10' + '0'));
            } else if (c >= '\uFF21' && c <= '\uFF3A') {
                builder.append((char) (c - '\uFF21' + 'A'));
            } else if (c >= '\uFF41' && c <= '\uFF5A') {
                builder.append((char) (c - '\uFF41' + 'a'));
            } else {
                builder.append(c);
            }
        }
        return builder.toString();
    }
}

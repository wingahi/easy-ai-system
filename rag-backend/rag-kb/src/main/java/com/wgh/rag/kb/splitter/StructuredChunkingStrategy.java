package com.wgh.rag.kb.splitter;

import com.wgh.rag.config.property.RagProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * STRUCTURED：Markdown 结构化切分 - 按标题层级（# ~ ####）聚合成块，
 * 标题路径（如 "第3章/3.2节"）写入 chunk metadata 的 headingPath；
 * 表格（连续 | 行）整块保留不拆，超过 maxLength 才回退按长度切分。
 */
@Component
@RequiredArgsConstructor
public class StructuredChunkingStrategy implements ChunkingStrategy {

    private static final Pattern HEADING = Pattern.compile("^(#{1,4})\\s+(.+)$");
    /** metadata key：标题路径 */
    public static final String ATTR_HEADING_PATH = "headingPath";

    private final RagProperties ragProperties;
    private final LengthChunkingStrategy lengthStrategy;

    @Override
    public TextSplitter.Strategy type() {
        return TextSplitter.Strategy.STRUCTURED;
    }

    @Override
    public int priority() {
        return 7;
    }

    @Override
    public List<String> split(List<String> paragraphs, String customConfig) {
        return splitDetailed(paragraphs, customConfig, null).stream().map(ChunkOutput::text).toList();
    }

    @Override
    public List<ChunkOutput> splitDetailed(List<String> paragraphs, String customConfig, SplitContext context) {
        int maxLength = ragProperties.getSplit().getMaxLength();
        List<ChunkOutput> out = new ArrayList<>();
        // 标题栈：维护当前 headingPath（按层级 1~4 弹出同级/更深层级）
        LinkedList<Integer> levels = new LinkedList<>();
        LinkedList<String> titles = new LinkedList<>();
        String headingPath = "";
        StringBuilder buf = new StringBuilder();

        // 段落内部可能含多行（表格、标题与正文粘连），逐行处理
        List<String> lines = paragraphs.stream()
                .flatMap(p -> p.lines())
                .map(String::trim)
                .filter(l -> !l.isEmpty())
                .toList();

        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                flush(out, buf, headingPath);
                int level = heading.group(1).length();
                while (!levels.isEmpty() && levels.getLast() >= level) {
                    levels.removeLast();
                    titles.removeLast();
                }
                levels.addLast(level);
                titles.addLast(heading.group(2).trim());
                headingPath = String.join("/", titles);
                // 标题行并入下一块内容开头，保证 chunk 自带语境
                appendLine(buf, line);
                i++;
                continue;
            }
            if (isTableRow(line)) {
                // 收集连续表格行为一个整体
                StringBuilder table = new StringBuilder();
                while (i < lines.size() && isTableRow(lines.get(i))) {
                    appendLine(table, lines.get(i));
                    i++;
                }
                if (table.length() > maxLength) {
                    // 表格本身超长 → 先清缓冲，表格回退按长度切分（保留 headingPath）
                    flush(out, buf, headingPath);
                    for (String piece : lengthStrategy.splitByLength(table.toString())) {
                        out.add(new ChunkOutput(piece, attrs(headingPath)));
                    }
                } else {
                    // 表格整块保留：缓冲放不下则先 flush，绝不在表格中间断块
                    if (buf.length() > 0 && buf.length() + table.length() + 1 > maxLength) {
                        flush(out, buf, headingPath);
                    }
                    appendLine(buf, table.toString());
                }
                continue;
            }
            if (line.length() > maxLength) {
                flush(out, buf, headingPath);
                for (String piece : lengthStrategy.splitByLength(line)) {
                    out.add(new ChunkOutput(piece, attrs(headingPath)));
                }
            } else {
                if (buf.length() > 0 && buf.length() + line.length() + 1 > maxLength) {
                    flush(out, buf, headingPath);
                }
                appendLine(buf, line);
            }
            i++;
        }
        flush(out, buf, headingPath);
        return out;
    }

    /** 表格行：以 | 开头（含分隔行 |---|） */
    private boolean isTableRow(String line) {
        return line.startsWith("|");
    }

    private void appendLine(StringBuilder buf, String line) {
        if (buf.length() > 0) buf.append("\n");
        buf.append(line);
    }

    private void flush(List<ChunkOutput> out, StringBuilder buf, String headingPath) {
        if (buf.length() == 0) return;
        String text = buf.toString().trim();
        if (!text.isEmpty()) {
            out.add(new ChunkOutput(text, attrs(headingPath)));
        }
        buf.setLength(0);
    }

    private Map<String, Object> attrs(String headingPath) {
        return headingPath.isEmpty() ? Map.of() : Map.of(ATTR_HEADING_PATH, headingPath);
    }
}

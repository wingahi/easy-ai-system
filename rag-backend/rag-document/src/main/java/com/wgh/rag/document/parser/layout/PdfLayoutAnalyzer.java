package com.wgh.rag.document.parser.layout;

import com.wgh.rag.document.parser.table.PdfTableBlock;
import com.wgh.rag.document.parser.table.TableExtractor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PDF 版面分析：把按内容流顺序输出的文字，还原为“行 → 栏 → 阅读顺序”。
 * <p>
 * 处理两类典型问题：
 * <ul>
 *   <li>双栏/多栏 PDF：识别页面中部的纵向空白带（gutter），先读完左栏再读右栏；</li>
 *   <li>表格：同一行按大间距切出多个单元格，连续对齐的单元格行还原为 Markdown 表格。</li>
 * </ul>
 * 任何一页分析失败只记录 WARN，返回空版面，由调用方回退到原始文本抽取，不影响整份文档。
 */
@Slf4j
public class PdfLayoutAnalyzer {

    private static final float ROW_Y_TOLERANCE = 2.5f;
    /** 单元格切分的最小水平间距；普通词间距远小于该值。 */
    private static final float MIN_CELL_GAP = 10f;
    /** 栏间空白带的最小宽度。 */
    private static final float GUTTER_MIN_GAP = 12f;
    /** 一行内单元格达到该数量时，优先按表格处理而不是双栏。 */
    private static final int TABLE_ROW_SEGMENTS = 3;

    private final TableExtractor tableExtractor = new TableExtractor();

    public PdfDocumentLayout analyze(PDDocument document) {
        if (document == null) {
            return PdfDocumentLayout.empty();
        }
        List<PdfPageLayout> pages = new ArrayList<>();
        for (int i = 0; i < document.getNumberOfPages(); i++) {
            pages.add(analyzePage(document, i));
        }
        return new PdfDocumentLayout(pages);
    }

    public PdfPageLayout analyzePage(PDDocument document, int pageIndex) {
        try {
            List<TextPosition> glyphs = collectGlyphs(document, pageIndex);
            if (glyphs.isEmpty()) {
                return PdfPageLayout.empty(pageIndex);
            }
            float pageWidth = (float) document.getPage(pageIndex).getMediaBox().getWidth();
            List<RowCluster> rows = groupRows(glyphs);
            Gutter gutter = detectGutter(rows, pageWidth);
            List<PdfTextLine> lines = buildLines(pageIndex, rows, gutter);

            Map<PdfTextLine, PdfTableBlock> tableByFirstLine = new IdentityHashMap<>();
            Set<PdfTextLine> tableLines = Collections.newSetFromMap(new IdentityHashMap<>());
            Map<Integer, List<PdfTextLine>> byColumn = new HashMap<>();
            for (PdfTextLine line : lines) {
                if (line.columnIndex() >= 0) {
                    byColumn.computeIfAbsent(line.columnIndex(), key -> new ArrayList<>()).add(line);
                }
            }
            for (List<PdfTextLine> columnLines : byColumn.values()) {
                columnLines.sort(Comparator.comparingDouble(PdfTextLine::y));
                for (PdfTableBlock block : tableExtractor.extract(columnLines)) {
                    if (block.lines().isEmpty()) {
                        continue;
                    }
                    tableByFirstLine.put(block.lines().get(0), block);
                    tableLines.addAll(block.lines());
                }
            }

            List<PdfTextLine> ordered = readingOrder(lines, gutter != null);
            String text = render(ordered, tableByFirstLine, tableLines);
            return new PdfPageLayout(pageIndex, text, splitParagraphs(text),
                    gutter != null, !tableByFirstLine.isEmpty(), lines);
        } catch (Exception e) {
            log.warn("PDF 版面分析失败（页 {}），回退文本抽取: {}", pageIndex + 1, e.getMessage());
            return PdfPageLayout.empty(pageIndex);
        }
    }

    private List<TextPosition> collectGlyphs(PDDocument document, int pageIndex) throws IOException {
        GlyphCollector collector = new GlyphCollector();
        collector.setStartPage(pageIndex + 1);
        collector.setEndPage(pageIndex + 1);
        collector.getText(document);
        return collector.glyphs();
    }

    private List<RowCluster> groupRows(List<TextPosition> glyphs) {
        List<TextPosition> sorted = new ArrayList<>(glyphs);
        // PDFBox 的 YDirAdj 以页面顶部为原点，数值越小越靠上
        sorted.sort(Comparator.comparingDouble(TextPosition::getYDirAdj)
                .thenComparingDouble(TextPosition::getXDirAdj));

        List<RowCluster> rows = new ArrayList<>();
        RowCluster current = null;
        for (TextPosition glyph : sorted) {
            float height = Math.max(1f, glyph.getHeightDir());
            if (current == null
                    || Math.abs(current.y - glyph.getYDirAdj()) > Math.max(ROW_Y_TOLERANCE, current.height * 0.5f)) {
                current = new RowCluster(glyph.getYDirAdj(), height);
                rows.add(current);
            }
            current.height = Math.max(current.height, height);
            current.glyphs.add(glyph);
        }
        for (RowCluster row : rows) {
            row.segments = splitSegments(row.glyphs);
        }
        return rows;
    }

    private List<Segment> splitSegments(List<TextPosition> glyphs) {
        if (glyphs.isEmpty()) {
            return List.of();
        }
        List<TextPosition> sorted = new ArrayList<>(glyphs);
        sorted.sort(Comparator.comparingDouble(TextPosition::getXDirAdj));

        List<List<TextPosition>> groups = new ArrayList<>();
        List<TextPosition> current = new ArrayList<>();
        for (TextPosition glyph : sorted) {
            if (!current.isEmpty()) {
                TextPosition previous = current.get(current.size() - 1);
                float previousEnd = previous.getXDirAdj() + Math.max(0.1f, previous.getWidthDirAdj());
                float gap = glyph.getXDirAdj() - previousEnd;
                float threshold = Math.max(MIN_CELL_GAP,
                        Math.max(previous.getHeightDir(), glyph.getHeightDir()) * 1.0f);
                if (gap > threshold) {
                    groups.add(current);
                    current = new ArrayList<>();
                }
            }
            current.add(glyph);
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }

        List<Segment> segments = new ArrayList<>();
        for (List<TextPosition> group : groups) {
            segments.add(toSegment(group));
        }
        return segments;
    }

    private Segment toSegment(List<TextPosition> glyphs) {
        StringBuilder builder = new StringBuilder();
        TextPosition previous = null;
        float startX = Float.MAX_VALUE;
        float endX = -Float.MAX_VALUE;
        float height = 1f;
        for (TextPosition glyph : glyphs) {
            String unicode = glyph.getUnicode();
            float x = glyph.getXDirAdj();
            float width = Math.max(0.1f, glyph.getWidthDirAdj());
            height = Math.max(height, glyph.getHeightDir());
            if (previous != null && builder.length() > 0) {
                float previousEnd = previous.getXDirAdj() + Math.max(0.1f, previous.getWidthDirAdj());
                float gap = x - previousEnd;
                float wordGap = Math.max(1.2f,
                        Math.min(Math.max(0.1f, previous.getWidthDirAdj()), width) * 0.35f);
                char previousChar = builder.charAt(builder.length() - 1);
                char nextChar = unicode.isEmpty() ? 0 : unicode.charAt(0);
                if (gap > wordGap && !(isCjk(previousChar) && isCjk(nextChar))) {
                    builder.append(' ');
                }
            }
            builder.append(unicode);
            startX = Math.min(startX, x);
            endX = Math.max(endX, x + width);
            previous = glyph;
        }
        String text = builder.toString().replaceAll("[ \\t]+", " ").trim();
        if (startX == Float.MAX_VALUE) {
            startX = 0f;
        }
        if (endX == -Float.MAX_VALUE) {
            endX = startX;
        }
        return new Segment(text, startX, endX, height);
    }

    /**
     * 识别页面中部是否存在稳定的纵向空白带。
     * <p>
     * 做法是在页面中部横向扫描，统计有多少行在该位置存在足够宽的空白间隔；
     * 取覆盖行数最多的位置，再收敛为所有覆盖行共有的空白区间。
     * 含 3 个及以上单元格的行优先视为表格行，此时不参与栏检测，避免宽表格被误拆成两栏。
     */
    private Gutter detectGutter(List<RowCluster> rows, float pageWidth) {
        if (rows.size() < 3 || pageWidth <= 0) {
            return null;
        }
        for (RowCluster row : rows) {
            if (row.segments.size() >= TABLE_ROW_SEGMENTS) {
                return null;
            }
        }

        float minX = pageWidth * 0.25f;
        float maxX = pageWidth * 0.75f;
        List<List<Gap>> rowGaps = new ArrayList<>();
        for (RowCluster row : rows) {
            List<Gap> gaps = new ArrayList<>();
            for (int i = 0; i + 1 < row.segments.size(); i++) {
                Segment left = row.segments.get(i);
                Segment right = row.segments.get(i + 1);
                float start = left.endX;
                float end = right.x;
                float center = (start + end) / 2f;
                if (end - start >= GUTTER_MIN_GAP && center >= minX && center <= maxX) {
                    gaps.add(new Gap(start, end));
                }
            }
            rowGaps.add(gaps);
        }

        int threshold = Math.max(3, (int) Math.ceil(rows.size() * 0.4));
        float pageCenter = pageWidth / 2f;
        float bestX = -1f;
        int bestCount = 0;
        for (float x = minX; x <= maxX; x += 2f) {
            int count = 0;
            for (List<Gap> gaps : rowGaps) {
                if (findGap(gaps, x) != null) {
                    count++;
                }
            }
            if (count > bestCount
                    || (count == bestCount && bestX >= 0
                    && Math.abs(x - pageCenter) < Math.abs(bestX - pageCenter))) {
                bestCount = count;
                bestX = x;
            }
        }
        if (bestCount < threshold || bestX < 0) {
            return null;
        }

        float commonStart = -Float.MAX_VALUE;
        float commonEnd = Float.MAX_VALUE;
        for (List<Gap> gaps : rowGaps) {
            Gap gap = findGap(gaps, bestX);
            if (gap != null) {
                commonStart = Math.max(commonStart, gap.start);
                commonEnd = Math.min(commonEnd, gap.end);
            }
        }
        if (commonEnd - commonStart < GUTTER_MIN_GAP) {
            return null;
        }
        // 向内收缩 2pt，避免列末字符刚好压在边界上被误判为跨栏
        return new Gutter(commonStart + 2f, commonEnd - 2f);
    }

    private Gap findGap(List<Gap> gaps, float x) {
        for (Gap gap : gaps) {
            if (x >= gap.start && x <= gap.end) {
                return gap;
            }
        }
        return null;
    }

    private List<PdfTextLine> buildLines(int pageIndex, List<RowCluster> rows, Gutter gutter) {
        List<PdfTextLine> lines = new ArrayList<>();
        for (RowCluster row : rows) {
            if (row.segments.isEmpty()) {
                continue;
            }
            if (gutter == null) {
                lines.add(toLine(pageIndex, 0, row, row.segments));
                continue;
            }
            List<Segment> left = new ArrayList<>();
            List<Segment> right = new ArrayList<>();
            List<Segment> spanning = new ArrayList<>();
            for (Segment segment : row.segments) {
                if (segment.endX <= gutter.startX) {
                    left.add(segment);
                } else if (segment.x >= gutter.endX) {
                    right.add(segment);
                } else {
                    spanning.add(segment);
                }
            }
            if (!spanning.isEmpty()) {
                List<Segment> all = new ArrayList<>();
                all.addAll(left);
                all.addAll(spanning);
                all.addAll(right);
                lines.add(toLine(pageIndex, PdfTextLine.SPANNING_COLUMN, row, all));
            } else {
                if (!left.isEmpty()) {
                    lines.add(toLine(pageIndex, 0, row, left));
                }
                if (!right.isEmpty()) {
                    lines.add(toLine(pageIndex, 1, row, right));
                }
            }
        }
        return lines;
    }

    private PdfTextLine toLine(int pageIndex, int columnIndex, RowCluster row, List<Segment> segments) {
        List<PdfCell> cells = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        float x = Float.MAX_VALUE;
        float endX = -Float.MAX_VALUE;
        for (Segment segment : segments) {
            cells.add(new PdfCell(segment.text, segment.x, segment.endX));
            if (segment.text != null && !segment.text.isBlank()) {
                if (text.length() > 0) {
                    text.append(' ');
                }
                text.append(segment.text);
            }
            x = Math.min(x, segment.x);
            endX = Math.max(endX, segment.endX);
        }
        if (x == Float.MAX_VALUE) {
            x = 0f;
        }
        if (endX == -Float.MAX_VALUE) {
            endX = x;
        }
        return new PdfTextLine(pageIndex, columnIndex, x, endX, row.y, row.height,
                cells, text.toString());
    }

    private List<PdfTextLine> readingOrder(List<PdfTextLine> lines, boolean multiColumn) {
        List<PdfTextLine> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparingDouble(PdfTextLine::y)
                .thenComparingInt(PdfTextLine::columnIndex));
        if (!multiColumn) {
            return sorted;
        }
        List<PdfTextLine> ordered = new ArrayList<>();
        List<PdfTextLine> pending = new ArrayList<>();
        for (PdfTextLine line : sorted) {
            if (line.columnIndex() == PdfTextLine.SPANNING_COLUMN) {
                flushColumnLines(pending, ordered);
                ordered.add(line);
            } else {
                pending.add(line);
            }
        }
        flushColumnLines(pending, ordered);
        return ordered;
    }

    private void flushColumnLines(List<PdfTextLine> pending, List<PdfTextLine> ordered) {
        if (pending.isEmpty()) {
            return;
        }
        pending.sort(Comparator.comparingInt(PdfTextLine::columnIndex)
                .thenComparing(Comparator.comparingDouble(PdfTextLine::y)));
        ordered.addAll(pending);
        pending.clear();
    }

    private String render(List<PdfTextLine> ordered,
                          Map<PdfTextLine, PdfTableBlock> tableByFirstLine,
                          Set<PdfTextLine> tableLines) {
        StringBuilder builder = new StringBuilder();
        Set<PdfTextLine> emitted = Collections.newSetFromMap(new IdentityHashMap<>());
        PdfTextLine previous = null;
        for (PdfTextLine line : ordered) {
            if (emitted.contains(line)) {
                continue;
            }
            PdfTableBlock table = tableByFirstLine.get(line);
            if (table != null) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(table.markdown()).append('\n');
                emitted.addAll(table.lines());
                previous = null;
                continue;
            }
            if (previous != null) {
                builder.append('\n');
                if (shouldSeparate(previous, line)) {
                    builder.append('\n');
                }
            }
            builder.append(line.text());
            previous = line;
        }
        return builder.toString().strip();
    }

    private boolean shouldSeparate(PdfTextLine previous, PdfTextLine current) {
        if (previous.columnIndex() != current.columnIndex()) {
            return true;
        }
        if (previous.columnIndex() == PdfTextLine.SPANNING_COLUMN) {
            return true;
        }
        float gap = current.y() - previous.y();
        float reference = Math.max(1f, Math.max(previous.height(), current.height()));
        return gap > reference * 1.8f;
    }

    private List<String> splitParagraphs(String text) {
        List<String> paragraphs = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return paragraphs;
        }
        for (String block : text.split("\\n\\s*\\n")) {
            String trimmed = block.trim();
            if (!trimmed.isEmpty()) {
                paragraphs.add(trimmed);
            }
        }
        return paragraphs;
    }

    private static boolean isCjk(char c) {
        return (c >= '\u4E00' && c <= '\u9FFF')
                || (c >= '\u3400' && c <= '\u4DBF')
                || (c >= '\u3000' && c <= '\u303F')
                || (c >= '\uFF00' && c <= '\uFFEF');
    }

    /** 仅收集文字位置，不生成 PDFTextStripper 默认文本。 */
    private static final class GlyphCollector extends PDFTextStripper {
        private final List<TextPosition> collected = new ArrayList<>();

        private GlyphCollector() throws IOException {
            super();
        }

        @Override
        protected void processTextPosition(TextPosition text) {
            if (text != null) {
                String unicode = text.getUnicode();
                if (unicode != null && !unicode.isEmpty() && !unicode.isBlank()) {
                    collected.add(text);
                }
            }
        }

        private List<TextPosition> glyphs() {
            return collected;
        }
    }

    private static final class RowCluster {
        private final float y;
        private float height;
        private final List<TextPosition> glyphs = new ArrayList<>();
        private List<Segment> segments = List.of();

        private RowCluster(float y, float height) {
            this.y = y;
            this.height = height;
        }
    }

    private record Segment(String text, float x, float endX, float height) {
    }

    private record Gutter(float startX, float endX) {
    }

    private record Gap(float start, float end) {
    }
}

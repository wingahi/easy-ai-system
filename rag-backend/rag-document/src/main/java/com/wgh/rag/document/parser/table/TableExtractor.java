package com.wgh.rag.document.parser.table;

import com.wgh.rag.document.parser.layout.PdfTextLine;

import java.util.ArrayList;
import java.util.List;

/**
 * 表格识别：把版面分析切出的“多单元格行”还原为 Markdown 表格。
 * <p>
 * 判定条件刻意保守：连续 ≥ 3 行、每行 ≥ 2 个单元格、相邻行列起始 x 基本对齐。
 * 这样普通正文中的偶发大间距不会被误判为表格。
 */
public class TableExtractor {

    private static final int MIN_COLUMNS = 2;
    private static final int MIN_ROWS = 3;
    private static final float ALIGN_TOLERANCE = 12f;

    /**
     * 从同一栏内按 y 降序排列的行中识别表格。
     */
    public List<PdfTableBlock> extract(List<PdfTextLine> lines) {
        List<PdfTableBlock> blocks = new ArrayList<>();
        if (lines == null || lines.isEmpty()) {
            return blocks;
        }
        int index = 0;
        while (index < lines.size()) {
            if (!isTableRow(lines.get(index))) {
                index++;
                continue;
            }
            int end = index;
            while (end + 1 < lines.size()
                    && isTableRow(lines.get(end + 1))
                    && aligned(lines.get(end), lines.get(end + 1))) {
                end++;
            }
            int rowCount = end - index + 1;
            if (rowCount >= MIN_ROWS) {
                List<PdfTextLine> blockLines = List.copyOf(lines.subList(index, end + 1));
                List<List<String>> rows = new ArrayList<>();
                for (PdfTextLine line : blockLines) {
                    List<String> cells = new ArrayList<>();
                    for (var cell : line.cells()) {
                        cells.add(cell.text());
                    }
                    rows.add(List.copyOf(cells));
                }
                blocks.add(new PdfTableBlock(blockLines, List.copyOf(rows), toMarkdown(rows)));
                index = end + 1;
            } else {
                index++;
            }
        }
        return blocks;
    }

    private boolean isTableRow(PdfTextLine line) {
        return line != null && line.cells().size() >= MIN_COLUMNS;
    }

    private boolean aligned(PdfTextLine previous, PdfTextLine current) {
        if (Math.abs(previous.cells().size() - current.cells().size()) > 1) {
            return false;
        }
        int count = Math.min(previous.cells().size(), current.cells().size());
        for (int i = 0; i < count; i++) {
            if (Math.abs(previous.cells().get(i).x() - current.cells().get(i).x()) > ALIGN_TOLERANCE) {
                return false;
            }
        }
        return true;
    }

    /**
     * 行列表转 Markdown：第一行作为表头，自动补齐列数。
     */
    public static String toMarkdown(List<List<String>> rows) {
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        int columns = 0;
        for (List<String> row : rows) {
            if (row != null) {
                columns = Math.max(columns, row.size());
            }
        }
        if (columns == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int r = 0; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            builder.append('|');
            for (int c = 0; c < columns; c++) {
                String cell = row != null && c < row.size() ? row.get(c) : "";
                builder.append(' ').append(escape(cell)).append(" |");
            }
            builder.append('\n');
            if (r == 0) {
                builder.append('|');
                for (int c = 0; c < columns; c++) {
                    builder.append(" --- |");
                }
                builder.append('\n');
            }
        }
        return builder.toString().stripTrailing();
    }

    private static String escape(String cell) {
        if (cell == null) {
            return "";
        }
        return cell.replace("|", "\\|")
                .replaceAll("\\s+", " ")
                .trim();
    }
}

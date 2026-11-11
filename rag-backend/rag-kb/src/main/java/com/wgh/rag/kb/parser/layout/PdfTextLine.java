package com.wgh.rag.kb.parser.layout;

import java.util.List;

/**
 * 版面分析后的一行文本。
 * <p>
 * {@code columnIndex >= 0} 表示属于某一栏；{@link #SPANNING_COLUMN} 表示跨栏（通栏标题等）。
 * {@code cells} 保留了该行按水平间距切出的单元格，表格识别直接复用。
 */
public record PdfTextLine(
        int pageIndex,
        int columnIndex,
        float x,
        float endX,
        float y,
        float height,
        List<PdfCell> cells,
        String text) {

    /** 跨栏行标记。 */
    public static final int SPANNING_COLUMN = -1;

    public PdfTextLine {
        cells = cells == null ? List.of() : List.copyOf(cells);
        text = text == null ? "" : text;
    }
}

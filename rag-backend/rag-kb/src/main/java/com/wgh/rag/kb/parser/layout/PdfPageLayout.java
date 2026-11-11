package com.wgh.rag.kb.parser.layout;

import java.util.List;

/**
 * 单页版面分析结果。
 *
 * @param pageIndex   从 0 开始的页索引
 * @param text        按阅读顺序渲染后的文本（表格已转为 Markdown）
 * @param paragraphs  按空行切分的段落块
 * @param multiColumn 是否识别为多栏版面
 * @param hasTable    是否识别到表格
 * @param lines       几何行（调试/测试使用）
 */
public record PdfPageLayout(
        int pageIndex,
        String text,
        List<String> paragraphs,
        boolean multiColumn,
        boolean hasTable,
        List<PdfTextLine> lines) {

    public PdfPageLayout {
        text = text == null ? "" : text;
        paragraphs = paragraphs == null ? List.of() : List.copyOf(paragraphs);
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public static PdfPageLayout empty(int pageIndex) {
        return new PdfPageLayout(pageIndex, "", List.of(), false, false, List.of());
    }
}

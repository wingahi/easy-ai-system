package com.wgh.rag.document.parser.table;

import com.wgh.rag.document.parser.layout.PdfTextLine;

import java.util.List;

/**
 * 表格识别结果：命中的原始行 + 结构化单元格 + Markdown 文本。
 */
public record PdfTableBlock(List<PdfTextLine> lines, List<List<String>> rows, String markdown) {

    public PdfTableBlock {
        lines = lines == null ? List.of() : List.copyOf(lines);
        rows = rows == null ? List.of() : List.copyOf(rows);
        markdown = markdown == null ? "" : markdown;
    }
}

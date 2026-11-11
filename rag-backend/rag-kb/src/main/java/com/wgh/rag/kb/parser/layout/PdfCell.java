package com.wgh.rag.kb.parser.layout;

/**
 * 版面分析中的最小单元格：文本 + 水平起止坐标。
 */
public record PdfCell(String text, float x, float endX) {
    public PdfCell {
        text = text == null ? "" : text;
    }
}

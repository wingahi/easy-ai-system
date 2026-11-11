package com.wgh.rag.kb.parser.layout;

import java.util.List;

/**
 * 整份 PDF 的逐页版面分析结果。
 */
public record PdfDocumentLayout(List<PdfPageLayout> pages) {

    public PdfDocumentLayout {
        pages = pages == null ? List.of() : List.copyOf(pages);
    }

    public static PdfDocumentLayout empty() {
        return new PdfDocumentLayout(List.of());
    }

    public PdfPageLayout page(int pageIndex) {
        if (pageIndex < 0 || pageIndex >= pages.size()) {
            return PdfPageLayout.empty(pageIndex);
        }
        return pages.get(pageIndex);
    }
}

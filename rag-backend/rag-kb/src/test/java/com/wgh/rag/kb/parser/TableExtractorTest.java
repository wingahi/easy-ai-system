package com.wgh.rag.kb.parser;

import com.wgh.rag.kb.parser.table.TableExtractor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 表格结构还原测试：Markdown 输出 + 自造三列表格 PDF 端到端。
 */
@DisplayName("表格结构还原测试")
class TableExtractorTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Markdown - 输出表头、分隔行并转义单元格中的竖线")
    void toMarkdown() {
        String markdown = TableExtractor.toMarkdown(List.of(
                List.of("A", "B"),
                List.of("1", "2"),
                List.of("x|y", "3")));

        assertThat(markdown)
                .contains("| A | B |")
                .contains("| --- | --- |")
                .contains("| 1 | 2 |")
                .contains("| x\\|y | 3 |");
    }

    @Test
    @DisplayName("PDF - 三列表格还原为 Markdown 表格块")
    void extractTableFromPdf() throws Exception {
        File file = tempDir.resolve("table.pdf").toFile();
        String[][] data = {
                {"Name", "Qty", "Price"},
                {"Apple", "10", "3.5"},
                {"Banana", "20", "1.2"}
        };
        float[] xs = {50f, 220f, 390f};
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                float y = 700f;
                for (String[] row : data) {
                    for (int column = 0; column < row.length; column++) {
                        stream.beginText();
                        stream.setFont(font, 10);
                        stream.newLineAtOffset(xs[column], y);
                        stream.showText(row[column]);
                        stream.endText();
                    }
                    y -= 24f;
                }
            }
            doc.save(file);
        }

        ParsedDocument parsed = new PdfParser().parseDetailed(file);

        assertThat(parsed.text())
                .contains("| Name | Qty | Price |")
                .contains("| --- | --- | --- |")
                .contains("| Apple | 10 | 3.5 |")
                .contains("| Banana | 20 | 1.2 |");
    }
}

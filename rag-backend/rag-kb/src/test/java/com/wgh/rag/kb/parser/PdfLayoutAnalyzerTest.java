package com.wgh.rag.kb.parser;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 版式/栏检测测试：双栏阅读顺序与单栏顺序稳定性。
 */
@DisplayName("PDF 版面与栏顺序测试")
class PdfLayoutAnalyzerTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("双栏 - 先读完左栏再读右栏，顺序稳定")
    void twoColumnReadingOrder() throws Exception {
        File file = tempDir.resolve("two-column.pdf").toFile();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                float[] ys = {700f, 676f, 652f};
                String[] left = {"LEFT-ALPHA", "LEFT-BETA", "LEFT-GAMMA"};
                String[] right = {"RIGHT-ALPHA", "RIGHT-BETA", "RIGHT-GAMMA"};
                for (int i = 0; i < ys.length; i++) {
                    stream.beginText();
                    stream.setFont(font, 12);
                    stream.newLineAtOffset(50f, ys[i]);
                    stream.showText(left[i]);
                    stream.endText();

                    stream.beginText();
                    stream.setFont(font, 12);
                    stream.newLineAtOffset(340f, ys[i]);
                    stream.showText(right[i]);
                    stream.endText();
                }
            }
            doc.save(file);
        }

        ParsedDocument parsed = new PdfParser().parseDetailed(file);
        String text = parsed.text();

        assertThat(text).contains("LEFT-ALPHA", "RIGHT-ALPHA");
        assertThat(text.indexOf("LEFT-ALPHA")).isLessThan(text.indexOf("LEFT-GAMMA"));
        assertThat(text.indexOf("LEFT-GAMMA")).isLessThan(text.indexOf("RIGHT-ALPHA"));
    }

    @Test
    @DisplayName("单栏 - 从上到下保持原始阅读顺序")
    void singleColumnOrder() throws Exception {
        File file = tempDir.resolve("single-column.pdf").toFile();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                String[] lines = {"FIRST-LINE", "SECOND-LINE", "THIRD-LINE"};
                float y = 700f;
                for (String line : lines) {
                    stream.beginText();
                    stream.setFont(font, 12);
                    stream.newLineAtOffset(50f, y);
                    stream.showText(line);
                    stream.endText();
                    y -= 24f;
                }
            }
            doc.save(file);
        }

        String text = new PdfParser().parseDetailed(file).text();

        assertThat(text.indexOf("FIRST-LINE")).isLessThan(text.indexOf("SECOND-LINE"));
        assertThat(text.indexOf("SECOND-LINE")).isLessThan(text.indexOf("THIRD-LINE"));
    }
}

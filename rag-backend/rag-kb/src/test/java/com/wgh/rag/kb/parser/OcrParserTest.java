package com.wgh.rag.kb.parser;

import com.wgh.rag.config.property.RagProperties;
import com.wgh.rag.kb.parser.ocr.OcrProperties;
import com.wgh.rag.kb.parser.ocr.OcrService;
import com.wgh.rag.kb.parser.ocr.TessOcrService;
import com.wgh.rag.kb.splitter.ChunkOutput;
import com.wgh.rag.kb.splitter.SplitContext;
import com.wgh.rag.kb.splitter.TextSplitter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 扫描件 OCR 链路测试：
 * <ul>
 *   <li>扫描 PDF（无文本层）走 OCR 后正文非空、可切分出 chunk；</li>
 *   <li>OCR 引擎不可用时优雅降级，解析不抛错；</li>
 *   <li>本机存在 Tesseract 时验证真实识别（不存在则跳过）。</li>
 * </ul>
 */
@DisplayName("OCR 解析与降级测试")
class OcrParserTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("扫描件 - OCR 结果作为正文并产生 chunk")
    void scannedPdfUsesOcrText() throws Exception {
        File file = tempDir.resolve("scanned.pdf").toFile();
        createBlankPdf(file);

        PdfParser parser = new PdfParser();
        parser.setOcrProperties(ocrProperties(1));
        parser.setOcrService(new FakeOcrService(true,
                "OCR-RECOGNIZED-TEXT\n\nSecond paragraph from scanned page."));

        ParsedDocument parsed = parser.parseDetailed(file);

        assertThat(parsed.pageCount()).isEqualTo(1);
        assertThat(parsed.text()).contains("OCR-RECOGNIZED-TEXT");
        assertThat(parsed.paragraphs()).isNotEmpty();

        TextSplitter splitter = new TextSplitter(new RagProperties());
        List<ChunkOutput> chunks = splitter.splitDetailed(
                parsed.paragraphs(), TextSplitter.Strategy.RECURSIVE, null, new SplitContext(null));
        assertThat(chunks).isNotEmpty();
    }

    @Test
    @DisplayName("降级 - OCR 引擎不可用时不抛错，保留文本抽取结果")
    void unavailableOcrFallsBackGracefully() throws Exception {
        File file = tempDir.resolve("scanned-no-ocr.pdf").toFile();
        createBlankPdf(file);

        PdfParser parser = new PdfParser();
        parser.setOcrProperties(ocrProperties(1));
        parser.setOcrService(new FakeOcrService(false, ""));

        assertThatCode(() -> {
            ParsedDocument parsed = parser.parseDetailed(file);
            assertThat(parsed.pageCount()).isEqualTo(1);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("真实引擎 - 本机存在 Tesseract 时识别简单英文图片")
    void realTesseractRecognition() {
        OcrProperties properties = new OcrProperties();
        properties.setLang("eng");
        properties.setTimeoutSeconds(30);
        TessOcrService service = new TessOcrService(properties);
        Assumptions.assumeTrue(service.isAvailable(), "本机未安装 Tesseract，跳过真实 OCR 验证");

        BufferedImage image = new BufferedImage(480, 140, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(Color.BLACK);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 56));
            graphics.drawString("HELLO OCR", 30, 90);
        } finally {
            graphics.dispose();
        }

        String text = service.recognize(image);

        assertThat(text).isNotBlank();
        assertThat(text.toUpperCase()).contains("HELLO");
    }

    private OcrProperties ocrProperties(int minTextPerPage) {
        OcrProperties properties = new OcrProperties();
        properties.setEnabled(true);
        properties.setMinTextPerPage(minTextPerPage);
        properties.setDpi(100);
        properties.setTimeoutSeconds(10);
        return properties;
    }

    /** 生成没有文本层的“扫描件”：页面上只有一个矩形色块。 */
    private void createBlankPdf(File file) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                stream.addRect(50, 600, 200, 100);
                stream.fill();
            }
            doc.save(file);
        }
    }

    private static final class FakeOcrService implements OcrService {
        private final boolean available;
        private final String text;

        private FakeOcrService(boolean available, String text) {
            this.available = available;
            this.text = text;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public String recognize(BufferedImage image) {
            return available ? text : "";
        }
    }
}

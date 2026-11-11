package com.wgh.rag.kb.parser;

import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.kb.parser.layout.PdfDocumentLayout;
import com.wgh.rag.kb.parser.layout.PdfLayoutAnalyzer;
import com.wgh.rag.kb.parser.layout.PdfPageLayout;
import com.wgh.rag.kb.parser.normalize.DocumentNormalizer;
import com.wgh.rag.kb.parser.ocr.OcrProperties;
import com.wgh.rag.kb.parser.ocr.OcrService;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF 解析器：文本抽取 + 版面分析（双栏/表格）+ 扫描页 OCR 降级。
 * <p>
 * 解析顺序：
 * <ol>
 *   <li>{@link PDFTextStripper} 抽取每页文本（保持原有行为）；</li>
 *   <li>版面分析识别双栏阅读顺序与表格，命中时使用分析后的段落；</li>
 *   <li>单页文字数低于阈值时尝试 OCR；引擎不可用/失败只记日志，回退到文本抽取，不中断解析；</li>
 *   <li>跨页删除重复页眉页脚，再交给 {@link DocumentNormalizer} 做统一清洗。</li>
 * </ol>
 */
@Slf4j
@Component
public class PdfParser implements DocumentParser {

    private final PdfLayoutAnalyzer layoutAnalyzer = new PdfLayoutAnalyzer();
    private final DocumentNormalizer normalizer = new DocumentNormalizer();

    /** 可选注入：没有 OCR 引擎时保持 null，解析走纯文本抽取。 */
    private OcrService ocrService;
    private OcrProperties ocrProperties = new OcrProperties();

    /** OCR 可用性缓存，避免每页重复探测。 */
    private volatile Boolean ocrAvailable;

    @Autowired(required = false)
    public void setOcrService(OcrService ocrService) {
        this.ocrService = ocrService;
    }

    @Autowired(required = false)
    public void setOcrProperties(OcrProperties ocrProperties) {
        if (ocrProperties != null) {
            this.ocrProperties = ocrProperties;
        }
    }

    @Override
    public boolean supports(String extension) {
        return "pdf".equals(extension);
    }

    @Override
    public List<String> parse(File file) {
        return parseDetailed(file).paragraphs();
    }

    @Override
    public ParsedDocument parseDetailed(File file) {
        int pageCount = 0;
        List<List<String>> pageBlocks = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(file)) {
            pageCount = doc.getNumberOfPages();
            PdfDocumentLayout layout = layoutAnalyzer.analyze(doc);
            for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
                String rawText = rawPageText(doc, pageIndex);
                PdfPageLayout pageLayout = layout.page(pageIndex);
                List<String> blocks = pageLayout.multiColumn() || pageLayout.hasTable()
                        ? new ArrayList<>(pageLayout.paragraphs())
                        : splitParagraphs(rawText);

                if (DocumentMetadataSupport.countChars(blocks) < ocrProperties.getMinTextPerPage()) {
                    String ocrText = ocrPage(doc, pageIndex);
                    if (!ocrText.isBlank()) {
                        blocks = merge(blocks, splitParagraphs(ocrText));
                    }
                }
                pageBlocks.add(blocks);
            }
        } catch (IOException e) {
            log.error("解析PDF失败: {}", e.getMessage(), e);
            throw new BusinessException("PDF 文件解析失败（文件可能已损坏或格式不正确）: " + e.getMessage());
        }

        List<String> paragraphs = new ArrayList<>();
        for (List<String> page : normalizer.removeRepeatedHeaderFooter(pageBlocks)) {
            paragraphs.addAll(page);
        }
        paragraphs = new ArrayList<>(normalizer.normalize(paragraphs));

        return new ParsedDocument(paragraphs, DocumentMetadataSupport.inferTitle(paragraphs),
                pageCount, null, "binary", DocumentMetadataSupport.countChars(paragraphs));
    }

    /** 单页原始文本；异常不影响整份文档解析。 */
    private String rawPageText(PDDocument doc, int pageIndex) {
        try {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(pageIndex + 1);
            stripper.setEndPage(pageIndex + 1);
            return stripper.getText(doc);
        } catch (Exception e) {
            log.warn("PDF 第 {} 页文本抽取失败，按空页处理: {}", pageIndex + 1, e.getMessage());
            return "";
        }
    }

    /**
     * 扫描页 OCR：任何异常都返回空串，由调用方保留原文本，绝不向上抛错。
     */
    private String ocrPage(PDDocument doc, int pageIndex) {
        if (!ocrProperties.isEnabled() || ocrService == null) {
            return "";
        }
        if (pageIndex >= ocrProperties.getMaxPages()) {
            log.warn("PDF 页数超过 OCR 上限 {}，第 {} 页起回退文本抽取",
                    ocrProperties.getMaxPages(), ocrProperties.getMaxPages() + 1);
            return "";
        }
        if (!isOcrAvailable()) {
            return "";
        }
        try {
            PDFRenderer renderer = new PDFRenderer(doc);
            BufferedImage image = renderer.renderImageWithDPI(
                    pageIndex, Math.max(72, ocrProperties.getDpi()), ImageType.RGB);
            String text = ocrService.recognize(image);
            return text == null ? "" : text;
        } catch (Exception e) {
            log.warn("PDF 第 {} 页 OCR 失败，回退文本抽取: {}", pageIndex + 1, e.getMessage());
            return "";
        }
    }

    private boolean isOcrAvailable() {
        Boolean cached = ocrAvailable;
        if (cached != null) {
            return cached;
        }
        boolean available;
        try {
            available = ocrService.isAvailable();
        } catch (Exception e) {
            log.warn("OCR 引擎探测失败，按不可用处理: {}", e.getMessage());
            available = false;
        }
        ocrAvailable = available;
        if (!available) {
            log.warn("未检测到可用的 OCR 引擎，扫描页将回退为文本抽取（不影响上传）");
        }
        return available;
    }

    private List<String> merge(List<String> existing, List<String> extra) {
        if (extra.isEmpty()) {
            return existing;
        }
        List<String> merged = new ArrayList<>(existing);
        for (String block : extra) {
            if (block != null && !block.isBlank() && !merged.contains(block)) {
                merged.add(block);
            }
        }
        return merged;
    }

    private List<String> splitParagraphs(String text) {
        List<String> paragraphs = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return paragraphs;
        }
        for (String line : text.split("\\n\\s*\\n|\\r\\n\\s*\\r\\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                paragraphs.add(trimmed);
            }
        }
        if (paragraphs.isEmpty()) {
            paragraphs.add(text.trim());
        }
        return paragraphs;
    }
}

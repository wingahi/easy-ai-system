package com.wgh.rag.kb.parser;

import com.wgh.rag.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF 解析器（同时返回页数与字符数元数据）
 */
@Slf4j
@Component
public class PdfParser implements DocumentParser {

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
        List<String> paragraphs = new ArrayList<>();
        int pageCount = 0;
        try (PDDocument doc = Loader.loadPDF(file)) {
            pageCount = doc.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            // PDF文本一般按换行分段
            for (String line : text.split("\\n\\s*\\n|\\r\\n\\s*\\r\\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    paragraphs.add(trimmed);
                }
            }
            if (paragraphs.isEmpty() && !text.trim().isEmpty()) {
                paragraphs.add(text.trim());
            }
        } catch (IOException e) {
            log.error("解析PDF失败: {}", e.getMessage(), e);
            throw new BusinessException("PDF 文件解析失败（文件可能已损坏或格式不正确）: " + e.getMessage());
        }
        return new ParsedDocument(paragraphs, DocumentMetadataSupport.inferTitle(paragraphs),
                pageCount, null, "binary", DocumentMetadataSupport.countChars(paragraphs));
    }
}

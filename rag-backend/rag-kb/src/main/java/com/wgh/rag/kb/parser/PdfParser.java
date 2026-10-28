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
 * PDF 解析器
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
        List<String> paragraphs = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            // PDF文本一般按换行分段
            for (String line : text.split("\\n\\s*\\n|\\r\\n\\s*\\r\\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    paragraphs.add(trimmed);
                }
            }
            if (paragraphs.isEmpty()) {
                paragraphs.add(text.trim());
            }
        } catch (IOException e) {
            log.error("解析PDF失败: {}", e.getMessage(), e);
            throw new BusinessException("PDF解析失败: " + e.getMessage());
        }
        return paragraphs;
    }
}

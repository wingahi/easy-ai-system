package com.wgh.rag.kb.parser;

import com.wgh.rag.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * DOC（旧版 Word）解析器
 */
@Slf4j
@Component
public class DocParser implements DocumentParser {

    @Override
    public boolean supports(String extension) {
        return "doc".equals(extension);
    }

    @Override
    public List<String> parse(File file) {
        List<String> paragraphs = new ArrayList<>();
        try (InputStream is = new FileInputStream(file);
             HWPFDocument doc = new HWPFDocument(is);
             WordExtractor extractor = new WordExtractor(doc)) {
            String[] paragraphsArr = extractor.getParagraphText();
            for (String p : paragraphsArr) {
                String trimmed = p.trim();
                if (!trimmed.isEmpty()) {
                    paragraphs.add(trimmed);
                }
            }
        } catch (IOException e) {
            log.error("解析DOC失败: {}", e.getMessage(), e);
            throw new BusinessException("DOC解析失败: " + e.getMessage());
        }
        return paragraphs;
    }
}

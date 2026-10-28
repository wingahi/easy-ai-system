package com.wgh.rag.kb.parser;

import com.wgh.rag.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * DOCX 解析器：段落 + 表格
 */
@Slf4j
@Component
public class DocxParser implements DocumentParser {

    @Override
    public boolean supports(String extension) {
        return "docx".equals(extension);
    }

    @Override
    public List<String> parse(File file) {
        List<String> paragraphs = new ArrayList<>();
        try (InputStream is = new FileInputStream(file);
             XWPFDocument doc = new XWPFDocument(is)) {
            for (XWPFParagraph p : doc.getParagraphs()) {
                String text = p.getText().trim();
                if (!text.isEmpty()) {
                    paragraphs.add(text);
                }
            }
            // 也解析表格
            for (XWPFTable table : doc.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    StringBuilder sb = new StringBuilder();
                    for (XWPFTableCell cell : row.getTableCells()) {
                        if (sb.length() > 0) sb.append(" | ");
                        sb.append(cell.getText().trim());
                    }
                    if (sb.length() > 0) {
                        paragraphs.add(sb.toString());
                    }
                }
            }
        } catch (IOException e) {
            log.error("解析DOCX失败: {}", e.getMessage(), e);
            throw new BusinessException("DOCX解析失败: " + e.getMessage());
        }
        return paragraphs;
    }
}

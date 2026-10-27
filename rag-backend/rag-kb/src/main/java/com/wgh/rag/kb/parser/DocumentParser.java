package com.wgh.rag.kb.parser;

import com.wgh.rag.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 多格式文档解析器
 */
@Slf4j
@Component
public class DocumentParser {

    /**
     * 解析文件为纯文本内容列表（按段落/行切分）
     */
    public List<String> parse(File file, String fileType) {
        return switch (fileType.toLowerCase()) {
            case "pdf" -> parsePdf(file);
            case "txt", "md" -> parseText(file);
            case "csv" -> parseCsv(file);
            case "xlsx", "xls" -> parseExcel(file);
            case "docx" -> parseDocx(file);
            case "doc" -> parseDoc(file);
            default -> throw new BusinessException("不支持的文件格式: " + fileType);
        };
    }

    private List<String> parsePdf(File file) {
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

    private List<String> parseText(File file) {
        List<String> paragraphs = new ArrayList<>();
        try {
            String content = Files.readString(file.toPath());
            for (String line : content.split("\\n\\s*\\n|\\r\\n\\s*\\r\\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    paragraphs.add(trimmed);
                }
            }
            if (paragraphs.isEmpty()) {
                paragraphs.add(content.trim());
            }
        } catch (IOException e) {
            log.error("解析文本文件失败: {}", e.getMessage(), e);
            throw new BusinessException("文本解析失败: " + e.getMessage());
        }
        return paragraphs;
    }

    private List<String> parseCsv(File file) {
        List<String> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    rows.add(line.trim());
                }
            }
        } catch (IOException e) {
            log.error("解析CSV失败: {}", e.getMessage(), e);
            throw new BusinessException("CSV解析失败: " + e.getMessage());
        }
        return rows;
    }

    private List<String> parseExcel(File file) {
        List<String> rows = new ArrayList<>();
        try (InputStream is = new FileInputStream(file);
             Workbook workbook = file.getName().toLowerCase().endsWith(".xlsx") 
                     ? new XSSFWorkbook(is) : new HSSFWorkbook(is)) {
            
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                for (Row row : sheet) {
                    StringBuilder sb = new StringBuilder();
                    for (Cell cell : row) {
                        if (sb.length() > 0) sb.append(" | ");
                        sb.append(getCellValueAsString(cell));
                    }
                    if (sb.length() > 0) {
                        rows.add(sb.toString());
                    }
                }
            }
        } catch (IOException e) {
            log.error("解析Excel失败: {}", e.getMessage(), e);
            throw new BusinessException("Excel解析失败: " + e.getMessage());
        }
        return rows;
    }

    private String getCellValueAsString(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                double val = cell.getNumericCellValue();
                yield val == Math.floor(val) ? String.valueOf((long) val) : String.valueOf(val);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try { yield cell.getStringCellValue(); } 
                catch (Exception e) { yield String.valueOf(cell.getNumericCellValue()); }
            }
            default -> "";
        };
    }

    private List<String> parseDocx(File file) {
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

    private List<String> parseDoc(File file) {
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

package com.wgh.rag.kb.parser;

import com.wgh.rag.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Excel 解析基类（XLSX / XLS 共用）：逐行输出 cell1 | cell2 格式
 */
@Slf4j
abstract class AbstractExcelParser implements DocumentParser {

    /** 按格式打开工作簿 */
    protected abstract Workbook openWorkbook(InputStream is) throws IOException;

    @Override
    public List<String> parse(File file) {
        List<String> rows = new ArrayList<>();
        try (InputStream is = new FileInputStream(file);
             Workbook workbook = openWorkbook(is)) {

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
}

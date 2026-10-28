package com.wgh.rag.kb.parser;

import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

/**
 * XLSX 解析器
 */
@Component
public class XlsxParser extends AbstractExcelParser {

    @Override
    public boolean supports(String extension) {
        return "xlsx".equals(extension);
    }

    @Override
    protected Workbook openWorkbook(InputStream is) throws IOException {
        return new XSSFWorkbook(is);
    }
}

package com.wgh.rag.kb.parser;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

/**
 * XLS（旧版 Excel）解析器
 */
@Component
public class XlsParser extends AbstractExcelParser {

    @Override
    public boolean supports(String extension) {
        return "xls".equals(extension);
    }

    @Override
    protected Workbook openWorkbook(InputStream is) throws IOException {
        return new HSSFWorkbook(is);
    }
}

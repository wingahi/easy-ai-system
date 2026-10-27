package com.wgh.rag.common.constant;

/**
 * 文件类型常量
 */
public interface FileTypeConstant {
    String CSV = "csv";
    String EXCEL = "excel";
    String WORD = "word";
    String PDF = "pdf";
    String TXT = "txt";
    String MARKDOWN = "markdown";

    String[] SUPPORTED_TYPES = {CSV, EXCEL, WORD, PDF, TXT, MARKDOWN};
}

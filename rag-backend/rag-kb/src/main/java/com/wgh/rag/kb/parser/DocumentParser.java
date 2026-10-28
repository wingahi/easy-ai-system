package com.wgh.rag.kb.parser;

import java.io.File;
import java.util.List;

/**
 * 文档解析策略：每种文件格式一个实现，由 {@link DocumentParserDispatcher} 按扩展名分发
 */
public interface DocumentParser {

    /**
     * 是否支持该扩展名（小写，如 "pdf" / "docx" / "txt" / "md"）
     */
    boolean supports(String extension);

    /**
     * 解析文件为纯文本内容列表（按段落/行切分）
     */
    List<String> parse(File file);
}

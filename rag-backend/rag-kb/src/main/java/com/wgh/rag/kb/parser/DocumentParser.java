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

    /**
     * 解析文件并抽取统一元数据（标题 / 页数 / 工作表数 / 编码 / 字符数）。
     * 默认实现基于 {@link #parse(File)} 推断标题与字符数，编码按二进制格式处理；
     * 有格式特有能力（PDF 页数、Excel 工作表数、PPT 幻灯片数等）的解析器应覆写本方法。
     */
    default ParsedDocument parseDetailed(File file) {
        return ParsedDocument.infer(parse(file), "binary");
    }
}

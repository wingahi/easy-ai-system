package com.wgh.rag.kb.parser;

import com.wgh.rag.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;

/**
 * 文档解析分发器：遍历注入的 {@link DocumentParser} 列表，找到支持该扩展名的实现
 */
@Component
@RequiredArgsConstructor
public class DocumentParserDispatcher {

    private final List<DocumentParser> parsers;

    /**
     * 解析文件为纯文本内容列表（按段落/行切分）
     */
    public List<String> parse(File file, String fileType) {
        String extension = fileType.toLowerCase();
        for (DocumentParser parser : parsers) {
            if (parser.supports(extension)) {
                return parser.parse(file);
            }
        }
        throw new BusinessException("不支持的文件格式: " + fileType);
    }
}

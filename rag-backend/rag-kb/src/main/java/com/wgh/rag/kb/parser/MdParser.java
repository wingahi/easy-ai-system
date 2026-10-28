package com.wgh.rag.kb.parser;

import org.springframework.stereotype.Component;

/**
 * Markdown 解析器
 */
@Component
public class MdParser extends PlainTextParser {

    @Override
    public boolean supports(String extension) {
        return "md".equals(extension);
    }
}

package com.wgh.rag.kb.parser;

import org.springframework.stereotype.Component;

/**
 * TXT 解析器
 */
@Component
public class TxtParser extends PlainTextParser {

    @Override
    public boolean supports(String extension) {
        return "txt".equals(extension);
    }
}

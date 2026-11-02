package com.wgh.rag.kb.parser;

import com.wgh.rag.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 纯文本解析基类（TXT / MD 共用）：按空行分段
 */
@Slf4j
abstract class PlainTextParser implements DocumentParser {

    @Override
    public List<String> parse(File file) {
        return parseDetailed(file).paragraphs();
    }

    @Override
    public ParsedDocument parseDetailed(File file) {
        List<String> paragraphs = new ArrayList<>();
        try {
            String content = DocumentMetadataSupport.readText(file);
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
        return new ParsedDocument(paragraphs, DocumentMetadataSupport.inferTitle(paragraphs),
                null, null, DocumentMetadataSupport.detectEncoding(file),
                DocumentMetadataSupport.countChars(paragraphs));
    }
}

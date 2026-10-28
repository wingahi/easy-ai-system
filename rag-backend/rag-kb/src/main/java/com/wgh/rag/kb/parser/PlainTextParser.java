package com.wgh.rag.kb.parser;

import com.wgh.rag.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 纯文本解析基类（TXT / MD 共用）：按空行分段
 */
@Slf4j
abstract class PlainTextParser implements DocumentParser {

    @Override
    public List<String> parse(File file) {
        List<String> paragraphs = new ArrayList<>();
        try {
            String content = Files.readString(file.toPath());
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
        return paragraphs;
    }
}

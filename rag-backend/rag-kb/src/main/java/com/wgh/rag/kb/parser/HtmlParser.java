package com.wgh.rag.kb.parser;

import com.wgh.rag.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * HTML 解析器（Jsoup）：提取 title 与正文块级文本，忽略 script/style。
 */
@Slf4j
@Component
public class HtmlParser implements DocumentParser {

    private static final String BLOCK_SELECTOR = "h1,h2,h3,h4,h5,h6,p,li,td,th,pre,blockquote,article,section";

    @Override
    public boolean supports(String extension) {
        return "html".equals(extension) || "htm".equals(extension);
    }

    @Override
    public List<String> parse(File file) {
        return parseDetailed(file).paragraphs();
    }

    @Override
    public ParsedDocument parseDetailed(File file) {
        try {
            Document doc = Jsoup.parse(file, null);
            doc.select("script,style,noscript").remove();

            List<String> paragraphs = new ArrayList<>();
            for (Element el : doc.select(BLOCK_SELECTOR)) {
                // 跳过只包含子块元素的容器，避免重复文本
                String text = el.text().trim();
                if (!text.isEmpty() && !paragraphs.contains(text)) {
                    paragraphs.add(text);
                }
            }
            if (paragraphs.isEmpty()) {
                String body = doc.body() != null ? doc.body().text().trim() : "";
                if (!body.isEmpty()) paragraphs.add(body);
            }

            String title = doc.title();
            if (title == null || title.isBlank()) {
                title = DocumentMetadataSupport.inferTitle(paragraphs);
            }
            return new ParsedDocument(paragraphs, title, null, null,
                    doc.charset() != null ? doc.charset().name() : "UTF-8",
                    DocumentMetadataSupport.countChars(paragraphs));
        } catch (Exception e) {
            log.error("解析HTML失败: {}", e.getMessage(), e);
            throw new BusinessException("HTML解析失败: " + e.getMessage());
        }
    }
}

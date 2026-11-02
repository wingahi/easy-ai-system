package com.wgh.rag.kb.parser;

import java.util.List;
import java.util.Map;

/**
 * 文档解析结果：正文段落 + 统一抽取的元数据。
 * <p>
 * 元数据键固定为 title / pageCount / sheetCount / encoding / charCount，
 * 各解析器按格式能力填充，无法获知的字段为 null（JSON 中不输出）。
 */
public record ParsedDocument(
        List<String> paragraphs,
        String title,
        Integer pageCount,
        Integer sheetCount,
        String encoding,
        int charCount) {

    /** 统一元数据视图（供接口返回/落库使用） */
    public Map<String, Object> metadata() {
        Map<String, Object> meta = new java.util.LinkedHashMap<>();
        if (title != null && !title.isBlank()) meta.put("title", title);
        if (pageCount != null) meta.put("pageCount", pageCount);
        if (sheetCount != null) meta.put("sheetCount", sheetCount);
        if (encoding != null && !encoding.isBlank()) meta.put("encoding", encoding);
        meta.put("charCount", charCount);
        return meta;
    }

    public String text() {
        return String.join("\n\n", paragraphs);
    }

    /** 依据段落推断标题/字符数/编码（用于没有格式特有能力的基础实现）。 */
    public static ParsedDocument infer(List<String> paragraphs, String encoding) {
        return new ParsedDocument(paragraphs,
                DocumentMetadataSupport.inferTitle(paragraphs),
                null, null, encoding,
                DocumentMetadataSupport.countChars(paragraphs));
    }
}

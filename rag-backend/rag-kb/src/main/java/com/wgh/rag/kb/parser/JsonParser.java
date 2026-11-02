package com.wgh.rag.kb.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * JSON 解析器：把嵌套 JSON 扁平化为「路径: 值」段落，便于切片与检索。
 * 根为数组时每个元素序列化为一行，避免不同记录被合并。
 */
@Slf4j
@Component
public class JsonParser implements DocumentParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_DEPTH = 12;
    private static final int MAX_ENTRIES = 5000;

    @Override
    public boolean supports(String extension) {
        return "json".equals(extension);
    }

    @Override
    public List<String> parse(File file) {
        return parseDetailed(file).paragraphs();
    }

    @Override
    public ParsedDocument parseDetailed(File file) {
        try {
            JsonNode root = MAPPER.readTree(file);
            if (root == null || root.isMissingNode()) {
                throw new BusinessException("JSON 内容为空");
            }
            List<String> paragraphs = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode item : root) {
                    if (paragraphs.size() >= MAX_ENTRIES) break;
                    paragraphs.add(MAPPER.writeValueAsString(item));
                }
            } else {
                flatten("", root, paragraphs, 0);
            }
            if (paragraphs.isEmpty()) {
                paragraphs.add(MAPPER.writeValueAsString(root));
            }
            String title = root.isObject() ? textOf(root, "title", "name", "subject") : null;
            if (title == null || title.isBlank()) {
                title = DocumentMetadataSupport.inferTitle(paragraphs);
            }
            return new ParsedDocument(paragraphs, title, null, null, "UTF-8",
                    DocumentMetadataSupport.countChars(paragraphs));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("解析JSON失败: {}", e.getMessage(), e);
            throw new BusinessException("JSON解析失败（非法 JSON 或编码错误）: " + e.getMessage());
        }
    }

    private void flatten(String path, JsonNode node, List<String> out, int depth) {
        if (out.size() >= MAX_ENTRIES || depth > MAX_DEPTH) return;
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                String childPath = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
                flatten(childPath, entry.getValue(), out, depth + 1);
            }
        } else if (node.isArray()) {
            int index = 0;
            for (JsonNode item : node) {
                flatten(path + "[" + index++ + "]", item, out, depth + 1);
            }
        } else if (node.isValueNode() && !node.isNull()) {
            String value = node.isTextual() ? node.asText() : node.toString();
            if (!value.isBlank()) {
                out.add(path.isEmpty() ? value : path + ": " + value);
            }
        }
    }

    private String textOf(JsonNode root, String... keys) {
        for (String key : keys) {
            JsonNode node = root.get(key);
            if (node != null && node.isValueNode() && !node.asText().isBlank()) {
                return node.asText();
            }
        }
        return null;
    }
}

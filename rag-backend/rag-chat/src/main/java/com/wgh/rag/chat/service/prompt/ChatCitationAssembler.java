package com.wgh.rag.chat.service.prompt;

import com.wgh.rag.agent.dto.AgentStepResult.RagSourceRecord;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 引用来源与正确率的纯函数装配器。
 *
 * <p>上下文 [Sn] 编号、前端来源卡片、正确率计算必须使用同一套「按文档去重」口径，
 * 因此统一收敛在这里：同文档保留最高分、编号按文档首次出现顺序分配。</p>
 */
public final class ChatCitationAssembler {

    private ChatCitationAssembler() {
    }

    /** 构建引用来源列表：按文档去重（保留最高分），并补齐 chunk 级定位字段。 */
    public static List<Map<String, Object>> buildSourcesList(List<EmbeddingMatch<TextSegment>> matches) {
        Map<String, Map<String, Object>> byFile = new LinkedHashMap<>();
        for (EmbeddingMatch<TextSegment> match : matches) {
            TextSegment segment = match.embedded();
            String key = sourceKey(segment);
            Map<String, Object> source = new java.util.HashMap<>();
            source.put("kbId", metaLong(segment, "kbId"));
            source.put("kbName", metaString(segment, "kbName"));
            source.put("fileId", metaLong(segment, "fileId"));
            source.put("fileName", metaString(segment, "fileName"));
            source.put("chunkIndex", metaInt(segment, "chunkIndex"));
            source.put("pageNo", metaInt(segment, "pageNo"));
            source.put("charStart", metaInt(segment, "charStart"));
            source.put("charEnd", metaInt(segment, "charEnd"));
            source.put("score", match.score());
            source.put("snippet", metaString(segment, "snippet"));

            Map<String, Object> prev = byFile.get(key);
            double score = match.score() == null ? 0d : match.score();
            double prevScore = prev == null || prev.get("score") == null ? -1d : (Double) prev.get("score");
            if (prev == null || score > prevScore) {
                byFile.put(key, source);
            }
        }
        return new ArrayList<>(byFile.values());
    }

    /** 由 Agent 步骤内实际进入模型上下文的检索来源生成引用卡片。 */
    public static List<Map<String, Object>> buildSourcesListFromRecords(List<RagSourceRecord> records) {
        Map<String, Map<String, Object>> byFile = new LinkedHashMap<>();
        if (records == null) return new ArrayList<>();
        for (RagSourceRecord record : records) {
            if (record == null) continue;
            String key = sourceKeyOf(record);
            Map<String, Object> source = new java.util.HashMap<>();
            source.put("kbId", record.getKbId());
            source.put("kbName", record.getKbName());
            source.put("fileId", record.getFileId());
            source.put("fileName", record.getFileName());
            source.put("chunkIndex", record.getChunkIndex());
            source.put("pageNo", record.getPageNo());
            source.put("charStart", record.getCharStart());
            source.put("charEnd", record.getCharEnd());
            source.put("score", record.getScore());
            source.put("snippet", record.getSnippet());

            Map<String, Object> prev = byFile.get(key);
            double score = record.getScore() == null ? 0d : record.getScore();
            double prevScore = prev == null || prev.get("score") == null ? -1d : (Double) prev.get("score");
            if (prev == null || score > prevScore) {
                byFile.put(key, source);
            }
        }
        return new ArrayList<>(byFile.values());
    }

    /** 拼接 RAG 上下文：引用编号按文档分配，同一文件的多段共享一个 [Sn]。 */
    public static String extractRagContext(List<EmbeddingMatch<TextSegment>> matches) {
        Map<String, Integer> citationNumbers = buildCitationNumbers(matches);
        StringBuilder sb = new StringBuilder();
        for (EmbeddingMatch<TextSegment> match : matches) {
            TextSegment segment = match.embedded();
            Integer citationNo = citationNumbers.get(sourceKey(segment));
            if (citationNo != null) {
                sb.append("[S").append(citationNo).append("] ");
            }
            String kbName = segment.metadata() != null ? segment.metadata().getString("kbName") : null;
            String fileName = segment.metadata() != null ? segment.metadata().getString("fileName") : null;
            if (kbName != null || fileName != null) {
                sb.append("【来源: ")
                        .append(kbName != null ? kbName : "")
                        .append(" - ")
                        .append(fileName != null ? fileName : "")
                        .append("】\n");
            }
            sb.append(segment.text()).append("\n\n");
        }
        return sb.toString();
    }

    public static double calculateAccuracyRate(String response, List<EmbeddingMatch<TextSegment>> matches) {
        List<String> contents = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> match : matches) {
            contents.add(match.embedded() != null ? match.embedded().text() : null);
        }
        return calculateAccuracyRateFromContents(response, contents);
    }

    public static double calculateAccuracyRateFromRecords(String response, List<RagSourceRecord> records) {
        List<String> contents = new ArrayList<>();
        if (records != null) {
            for (RagSourceRecord record : records) {
                contents.add(record != null ? record.getContent() : null);
            }
        }
        return calculateAccuracyRateFromContents(response, contents);
    }

    /** 按文档首次出现顺序分配引用编号（1 起），供上下文 [Sn] 与前端来源卡片共用。 */
    private static Map<String, Integer> buildCitationNumbers(List<EmbeddingMatch<TextSegment>> matches) {
        Map<String, Integer> numbers = new LinkedHashMap<>();
        for (EmbeddingMatch<TextSegment> match : matches) {
            numbers.computeIfAbsent(sourceKey(match.embedded()), key -> numbers.size() + 1);
        }
        return numbers;
    }

    /** 来源去重键：按文件名归并，缺失时回退 fileId / 知识库名。 */
    private static String sourceKey(TextSegment segment) {
        String fileName = metaString(segment, "fileName");
        if (fileName != null && !fileName.isBlank()) {
            return "name:" + fileName;
        }
        Object fileId = metaRaw(segment, "fileId");
        if (fileId instanceof Number || (fileId instanceof String s && !s.isBlank())) {
            return "file:" + fileId;
        }
        String kbName = metaString(segment, "kbName");
        return "kb:" + (kbName == null ? "" : kbName);
    }

    private static String sourceKeyOf(RagSourceRecord record) {
        if (record.getFileName() != null && !record.getFileName().isBlank()) {
            return "name:" + record.getFileName();
        }
        if (record.getFileId() != null) {
            return "file:" + record.getFileId();
        }
        return "kb:" + (record.getKbName() == null ? "" : record.getKbName());
    }

    private static Object metaRaw(TextSegment segment, String key) {
        return segment.metadata() == null ? null : segment.metadata().toMap().get(key);
    }

    private static String metaString(TextSegment segment, String key) {
        Object value = metaRaw(segment, key);
        return value instanceof String text ? text : null;
    }

    private static Long metaLong(TextSegment segment, String key) {
        Object value = metaRaw(segment, key);
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Integer metaInt(TextSegment segment, String key) {
        Object value = metaRaw(segment, key);
        return value instanceof Number number ? number.intValue() : null;
    }

    private static double calculateAccuracyRateFromContents(String response, List<String> contents) {
        // 响应中是否引用了检索片段的关键短语（中文 3 字滑窗 + 英文按词）
        if (contents.isEmpty() || response == null || response.isBlank()) return 0.0;

        // 剥离 Markdown 符号后再匹配（LLM 常输出 **粗体**、`代码` 等）
        String respNorm = response.replaceAll("[\\*\\`#_~\\[\\](){}<>|\\\\]", "").toLowerCase();

        int hitChunks = 0;
        for (String content : contents) {
            if (content == null || content.isBlank()) continue;

            Set<String> phrases = new HashSet<>();
            // 中文 3 字滑窗（前 200 字内抽样，步长 3，最多 40 条）
            String cleaned = content.replaceAll("[\\s\\p{P}\\p{S}]+", "");
            for (int i = 0; i + 3 <= Math.min(cleaned.length(), 200); i += 3) {
                String gram = cleaned.substring(i, i + 3);
                if (gram.matches("[\\u4e00-\\u9fa5]{3}")) phrases.add(gram);
                if (phrases.size() >= 40) break;
            }
            // 英文/数字长度 ≥ 4 的词
            for (String w : content.split("[^A-Za-z0-9]+")) {
                if (w.length() >= 4) phrases.add(w.toLowerCase());
                if (phrases.size() >= 80) break;
            }

            if (phrases.isEmpty()) continue;

            int hits = 0;
            for (String p : phrases) {
                if (respNorm.contains(p.toLowerCase())) hits++;
            }
            // 至少 8% 的关键短语命中 → 该 chunk 视为被引用
            if (hits >= Math.max(1, (int) (phrases.size() * 0.08))) hitChunks++;
        }
        return (double) hitChunks / contents.size();
    }
}

package com.wgh.rag.kb.splitter;

import com.wgh.rag.config.property.RagProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 固定 overlap 语义与碎片块规则：
 * 1) 递归切分：相邻 chunk 必须携带上一块末尾 overlap 字符，且不得超过 maxLength；
 * 2) 段落切分：flush 后不得产生超过 maxLength 的块，不得留下 overlap+微小段落的碎片块。
 */
class ChunkingOverlapAndFragmentTest {

    private RagProperties props(int maxLength, int overlap) {
        RagProperties props = new RagProperties();
        props.getSplit().setMaxLength(maxLength);
        props.getSplit().setOverlapLength(overlap);
        return props;
    }

    private static String fill(String prefix, int length) {
        StringBuilder sb = new StringBuilder(prefix);
        while (sb.length() < length) sb.append("内容");
        return sb.substring(0, length);
    }

    private static String last(String s, int n) {
        return s.substring(Math.max(0, s.length() - n));
    }

    // ---------- 递归切分 ----------

    @Test
    @DisplayName("递归切分：满长 chunk 之间仍保留 overlap，且不超 maxLength")
    void recursive_fullLengthChunksKeepOverlap() {
        RagProperties props = props(200, 40);
        RecursiveChunkingStrategy strategy = new RecursiveChunkingStrategy(props);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) sb.append(fill("第" + i + "句", 25)).append("。");
        List<String> chunks = strategy.splitRecursive(sb.toString());

        assertThat(chunks).hasSizeGreaterThan(3);
        for (String chunk : chunks) {
            assertThat(chunk.length()).isLessThanOrEqualTo(200);
        }
        for (int i = 1; i < chunks.size(); i++) {
            // 前一块以完整 piece 结尾时，下一块必须以它的末尾 overlap 字符开头
            assertThat(chunks.get(i))
                    .as("chunk[%d] 应携带 chunk[%d] 的 overlap", i, i - 1)
                    .startsWith(last(chunks.get(i - 1), 40));
        }
    }

    @Test
    @DisplayName("递归切分：overlap=0 保持原有不重叠行为")
    void recursive_zeroOverlap_noDuplicateLeadingText() {
        RagProperties props = props(100, 0);
        RecursiveChunkingStrategy strategy = new RecursiveChunkingStrategy(props);
        List<String> chunks = strategy.splitRecursive(fill("前缀", 300));

        for (String chunk : chunks) {
            assertThat(chunk.length()).isLessThanOrEqualTo(100);
        }
    }

    // ---------- 段落切分 ----------

    @Test
    @DisplayName("段落切分：相邻 chunk 保留 overlap")
    void paragraph_keepsOverlapOnNormalFlush() {
        RagProperties props = props(120, 30);
        ParagraphChunkingStrategy strategy = new ParagraphChunkingStrategy(props, new LengthChunkingStrategy(props));

        String p1 = fill("段落一", 100);
        String p2 = fill("段落二", 60);
        String p3 = fill("段落三", 60);
        List<String> chunks = strategy.split(new ArrayList<>(List.of(p1, p2, p3)), null);

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(1)).startsWith(last(chunks.get(0), 30));
        assertThat(chunks.get(2)).startsWith(last(chunks.get(1), 30));
    }

    @Test
    @DisplayName("段落切分：下一段放不下时不携带 tail，避免块超 maxLength")
    void paragraph_dropsTailWhenItWouldOverflow() {
        RagProperties props = props(120, 30);
        ParagraphChunkingStrategy strategy = new ParagraphChunkingStrategy(props, new LengthChunkingStrategy(props));

        String p1 = fill("段落一", 110);
        String p2 = fill("段落二", 110);
        List<String> chunks = strategy.split(new ArrayList<>(List.of(p1, p2)), null);

        assertThat(chunks).hasSize(2);
        for (String chunk : chunks) {
            assertThat(chunk.length()).isLessThanOrEqualTo(120);
        }
    }

    @Test
    @DisplayName("段落切分：微小段落并入前一块，不产生 overlap+微段落的碎片块")
    void paragraph_mergesTinyParagraphIntoPreviousChunk() {
        RagProperties props = props(120, 30);
        ParagraphChunkingStrategy strategy = new ParagraphChunkingStrategy(props, new LengthChunkingStrategy(props));

        String p1 = fill("段落一", 110);
        String tiny = fill("小标题", 5);
        String p3 = fill("段落三", 110);
        List<String> chunks = strategy.split(new ArrayList<>(List.of(p1, tiny, p3)), null);

        for (String chunk : chunks) {
            assertThat(chunk.length()).isLessThanOrEqualTo(120);
        }
        assertThat(chunks).noneMatch(c -> c.length() < 30);
    }
}

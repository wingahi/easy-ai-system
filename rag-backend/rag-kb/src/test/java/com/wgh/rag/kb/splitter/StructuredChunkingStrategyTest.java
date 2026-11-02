package com.wgh.rag.kb.splitter;

import com.wgh.rag.config.property.RagProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * StructuredChunkingStrategy 单测：标题路径聚合、表格保护、超长回退
 */
@DisplayName("结构化切分策略测试")
class StructuredChunkingStrategyTest {

    private StructuredChunkingStrategy newStrategy(int maxLength) {
        RagProperties props = new RagProperties();
        props.getSplit().setMaxLength(maxLength);
        props.getSplit().setOverlapLength(10);
        return new StructuredChunkingStrategy(props, new LengthChunkingStrategy(props));
    }

    /** 标题层级聚合成块：同级标题弹栈，headingPath 形如 "第一章/1.1节"。 */
    @Test
    @DisplayName("STRUCTURED - 标题层级聚合，headingPath 正确记录层级路径")
    void headingPathAggregation() {
        StructuredChunkingStrategy strategy = newStrategy(512);
        List<String> paras = List.of(
                "# 第一章 概述",
                "本章介绍背景。",
                "## 1.1 目标",
                "目标是提升召回。",
                "## 1.2 范围",
                "覆盖检索链路。",
                "# 第二章 方案",
                "方案如下。"
        );

        List<ChunkOutput> result = strategy.splitDetailed(paras, null, null);

        assertThat(result).hasSize(4);
        assertThat(result.get(0).attributes().get("headingPath")).isEqualTo("第一章 概述");
        assertThat(result.get(1).attributes().get("headingPath")).isEqualTo("第一章 概述/1.1 目标");
        assertThat(result.get(2).attributes().get("headingPath")).isEqualTo("第一章 概述/1.2 范围");
        assertThat(result.get(3).attributes().get("headingPath")).isEqualTo("第二章 方案");
        // 标题行并入块开头，正文不被标题边界切断
        assertThat(result.get(1).text()).startsWith("## 1.1 目标").contains("目标是提升召回。");
    }

    /** 连续 | 行识别为表格并整块保留在单个 chunk 中，即使缓冲需要提前 flush。 */
    @Test
    @DisplayName("STRUCTURED - 表格整块保留不拆")
    void tableKeptIntact() {
        StructuredChunkingStrategy strategy = newStrategy(512);
        // 表格与正文粘连在同一段落（无空行），模拟 PlainTextParser 按空行分段的结果
        String para = "## 配置项\n以下是配置表：\n| 名称 | 类型 | 说明 |\n| --- | --- | --- |\n| topK | int | 召回条数 |\n| minScore | double | 相似度阈值 |\n表格后的正文。";
        List<String> paras = List.of(para);

        List<ChunkOutput> result = strategy.splitDetailed(paras, null, null);

        assertThat(result).hasSize(1);
        String text = result.get(0).text();
        assertThat(text).contains("| topK | int | 召回条数 |").contains("| minScore | double | 相似度阈值 |");
        // 表格四行连续出现在同一 chunk
        int firstRow = text.indexOf("| 名称 |");
        int lastRow = text.indexOf("| minScore |");
        assertThat(lastRow).isGreaterThan(firstRow);
        assertThat(result.get(0).attributes().get("headingPath")).isEqualTo("配置项");
    }

    /** 表格本身超过 maxLength 时回退按长度切分，切分块仍保留 headingPath。 */
    @Test
    @DisplayName("STRUCTURED - 超长表格回退长度切分且保留 headingPath")
    void oversizedTableFallsBackToLength() {
        StructuredChunkingStrategy strategy = newStrategy(60);
        StringBuilder table = new StringBuilder("# 数据表\n");
        for (int i = 0; i < 10; i++) {
            table.append("| 行").append(i).append(" | 这是一段比较长的表格单元格内容 | 值").append(i).append(" |\n");
        }

        List<ChunkOutput> result = strategy.splitDetailed(List.of(table.toString()), null, null);

        assertThat(result.size()).isGreaterThan(1);
        // 所有切分块都保留表格所在标题路径
        assertThat(result).allSatisfy(c ->
                assertThat(c.attributes().get("headingPath")).isEqualTo("数据表"));
    }

    /** 无标题文档：不产生 headingPath 属性，按 maxLength 正常聚合。 */
    @Test
    @DisplayName("STRUCTURED - 无标题文档不带 headingPath")
    void noHeadingsNoAttribute() {
        StructuredChunkingStrategy strategy = newStrategy(50);
        List<String> paras = List.of("第一段普通内容文字。", "第二段普通内容文字。");

        List<ChunkOutput> result = strategy.splitDetailed(paras, null, null);

        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(c.attributes()).isEmpty());
    }

    /** 超长正文行回退按长度切分，保留所在标题路径。 */
    @Test
    @DisplayName("STRUCTURED - 超长正文行回退长度切分")
    void oversizedLineFallsBackToLength() {
        StructuredChunkingStrategy strategy = newStrategy(40);
        String longLine = "这是超过最大长度限制的正文行内容，需要按长度切分处理才能保证每块都不超过上限。";
        List<String> paras = List.of("# 章节", longLine);

        List<ChunkOutput> result = strategy.splitDetailed(paras, null, null);

        assertThat(result.size()).isGreaterThan(1);
        assertThat(result).allSatisfy(c -> {
            assertThat(c.attributes().get("headingPath")).isEqualTo("章节");
            assertThat(c.text().length()).isLessThanOrEqualTo(40);
        });
    }

    /** split() 纯文本接口与 splitDetailed 文本一致（兼容旧调用方）。 */
    @Test
    @DisplayName("STRUCTURED - split() 与 splitDetailed 文本输出一致")
    void splitConsistentWithDetailed() {
        StructuredChunkingStrategy strategy = newStrategy(512);
        List<String> paras = List.of("# 标题", "正文内容。");

        List<String> plain = strategy.split(paras, null);
        List<String> detailed = strategy.splitDetailed(paras, null, null).stream().map(ChunkOutput::text).toList();

        assertThat(plain).isEqualTo(detailed);
    }
}

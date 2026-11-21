package com.wgh.rag.document.parser;

import com.wgh.rag.document.parser.normalize.DocumentNormalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文本规范化单测：全半角、连字符断行、乱码、重复段、页眉页脚。
 */
@DisplayName("文档文本规范化测试")
class DocumentNormalizerTest {

    private final DocumentNormalizer normalizer = new DocumentNormalizer();

    @Test
    @DisplayName("规范化 - 全角字母数字与全角空格转半角")
    void halfWidth() {
        String result = normalizer.normalizeText("ＡＢＣ１２３\u3000中文");

        assertThat(result).isEqualTo("ABC123 中文");
    }

    @Test
    @DisplayName("规范化 - 折叠多余空白与控制字符")
    void collapseWhitespace() {
        String result = normalizer.normalizeText("  a   b\t\t c\u0000\u0007  ");

        assertThat(result).isEqualTo("a b c");
    }

    @Test
    @DisplayName("规范化 - 合并连字符断行")
    void joinHyphenatedLineBreak() {
        String result = normalizer.normalizeText("docu-\nment and well-known");

        assertThat(result).isEqualTo("document and well-known");
    }

    @Test
    @DisplayName("规范化 - 修复常见 UTF-8 被按 Latin-1 解码的乱码")
    void repairMojibake() {
        String result = normalizer.normalizeText("it\u00E2\u20AC\u2122s \u00E2\u20AC\u0153quote\u00E2\u20AC\u009D");

        assertThat(result).isEqualTo("it's \"quote\"");
    }

    @Test
    @DisplayName("规范化 - 合并连续重复段落，保留非连续重复")
    void dedupeConsecutive() {
        List<String> result = normalizer.normalize(List.of("重复段", "重复段", "其他段", "重复段"));

        assertThat(result).containsExactly("重复段", "其他段", "重复段");
    }

    @Test
    @DisplayName("页眉页脚 - 跨页删除重复页眉与页码")
    void removeRepeatedHeaderFooter() {
        List<List<String>> pages = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            pages.add(new ArrayList<>(List.of("公司机密", "正文第" + i + "页内容", "第 " + i + " 页")));
        }

        List<List<String>> cleaned = normalizer.removeRepeatedHeaderFooter(pages);

        assertThat(cleaned).hasSize(4);
        for (int i = 0; i < cleaned.size(); i++) {
            assertThat(cleaned.get(i)).containsExactly("正文第" + (i + 1) + "页内容");
        }
        // 入参不被修改
        assertThat(pages.get(0)).containsExactly("公司机密", "正文第1页内容", "第 1 页");
    }

    @Test
    @DisplayName("页眉页脚 - 页数少于 3 时不做删除")
    void keepHeaderFooterWhenTooFewPages() {
        List<List<String>> pages = List.of(
                new ArrayList<>(List.of("公司机密", "正文一")),
                new ArrayList<>(List.of("公司机密", "正文二")));

        List<List<String>> cleaned = normalizer.removeRepeatedHeaderFooter(pages);

        assertThat(cleaned.get(0)).containsExactly("公司机密", "正文一");
        assertThat(cleaned.get(1)).containsExactly("公司机密", "正文二");
    }
}

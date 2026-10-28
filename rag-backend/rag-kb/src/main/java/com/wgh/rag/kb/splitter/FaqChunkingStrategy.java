package com.wgh.rag.kb.splitter;

import com.wgh.rag.config.property.RagProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * FAQ：问答对分割（识别「问N：...答：...」，一对一 chunk）；
 * 单对超长时递归切割；识别失败回退 RECURSIVE
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FaqChunkingStrategy implements ChunkingStrategy {

    private final RagProperties ragProperties;
    private final RecursiveChunkingStrategy recursiveStrategy;

    @Override
    public TextSplitter.Strategy type() {
        return TextSplitter.Strategy.FAQ;
    }

    @Override
    public int priority() {
        return 4;
    }

    @Override
    public List<String> split(List<String> paragraphs, String customConfig) {
        String text = String.join("\n", paragraphs);
        // 按「问N：」或「Q：」起始切分出问答单元（(?m) 开启多行模式使 ^ 匹配每行行首）
        String[] rawUnits = text.split("(?m)(?=^\\s*(问\\s*\\d*[：:]|Q\\d*[：:]))");
        List<String> units = new ArrayList<>();
        for (String unit : rawUnits) {
            String t = unit.trim();
            if (!t.isEmpty()) units.add(t);
        }
        // 识别失败（切不出 ≥2 个单元）→ 回退递归
        if (units.size() < 2) {
            log.info("FAQ 模式识别失败，回退 RECURSIVE");
            return recursiveStrategy.splitRecursive(text);
        }
        int maxLength = ragProperties.getSplit().getMaxLength();
        List<String> result = new ArrayList<>();
        for (String unit : units) {
            if (unit.length() > maxLength) {
                result.addAll(recursiveStrategy.cut(unit));
            } else {
                result.add(unit);
            }
        }
        return result;
    }
}

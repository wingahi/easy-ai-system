package com.wgh.rag.context;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一上下文预算分配器（纯函数，无状态）。
 *
 * <p>把系统提示词、当前问题、Agent 状态块、检索片段、工具结果、历史摘要等
 * 异构上下文统一抽象为 {@link ContextSegment}，按类型优先级在同一个预算内分配，
 * 避免各链路各自计算预算、互相挤占。
 *
 * <p>预算公式：{@code min(硬上限, floor(模型窗口 × 输入占比)) - 输出预留}。
 * 估算与裁剪策略由调用方注入（{@link TokenCounter} / {@link Trimmer}），
 * 本模块不依赖 LangChain4j 或 Spring，避免循环依赖。
 */
public final class ContextAssembler {

    /** 模型未配置窗口时的兜底窗口 */
    public static final int DEFAULT_FALLBACK_WINDOW_TOKENS = 8192;
    /** 输入侧默认占模型窗口比例 */
    public static final double DEFAULT_INPUT_WINDOW_RATIO = 0.6;
    /** 单条来源饱和判定阈值：仅保留 1 段且占用预算达到该比例 */
    private static final double SATURATION_RATIO = 0.9;

    private ContextAssembler() {
    }

    /** Token 估算策略，由调用方注入（如 CJK 加权估算）。 */
    @FunctionalInterface
    public interface TokenCounter {
        int count(String text);
    }

    /** 裁剪策略，由调用方注入；返回的文本可自带截断标记。 */
    @FunctionalInterface
    public interface Trimmer {
        String trim(String text, int maxTokens);
    }

    /**
     * 预算参数。
     *
     * @param inputWindowRatio   输入侧占模型窗口比例（0,1]，非法值回落 0.6
     * @param hardCapTokens      内容硬上限，<=0 表示不设上限
     * @param reserveOutputTokens 为模型输出预留的 Tokens，>=0
     */
    public record Options(double inputWindowRatio, int hardCapTokens, int reserveOutputTokens) {

        public static Options defaults() {
            return new Options(DEFAULT_INPUT_WINDOW_RATIO, 0, 0);
        }

        public static Options of(double inputWindowRatio, int hardCapTokens, int reserveOutputTokens) {
            return new Options(inputWindowRatio, hardCapTokens, reserveOutputTokens);
        }
    }

    /** 被保留的片段。content 为最终进入 Prompt 的文本（可能被裁剪）。 */
    public record KeptSegment(ContextSegment segment, String content,
                              int originalTokens, int keptTokens, boolean trimmed) {
        public int index() {
            return segment.index();
        }

        public SegmentType type() {
            return segment.type();
        }
    }

    /** 被丢弃的片段。 */
    public record DroppedSegment(int index, SegmentType type, int tokens, boolean trimmedToEmpty) {
    }

    /** 分配结果；kept 按入参 index 升序，便于调用方按原顺序拼接。 */
    public record Result(List<KeptSegment> kept, List<DroppedSegment> dropped,
                         int budgetTokens, int originalTokens, int keptTokens,
                         boolean singleSourceSaturated) {

        public boolean hasDropped() {
            return !dropped.isEmpty();
        }

        /** 按 index 取最终文本；未保留时返回 null。 */
        public String contentOf(int index) {
            for (KeptSegment segment : kept) {
                if (segment.index() == index) {
                    return segment.content();
                }
            }
            return null;
        }

        /** 各类型丢弃的 Tokens，便于按类型上报指标。 */
        public Map<SegmentType, Integer> droppedTokensByType() {
            Map<SegmentType, Integer> map = new LinkedHashMap<>();
            for (DroppedSegment d : dropped) {
                map.merge(d.type(), d.tokens(), Integer::sum);
            }
            return map;
        }
    }

    /**
     * 在统一预算内分配片段。
     *
     * @param modelWindowTokens 模型上下文窗口（<=0 使用兜底 8192）
     * @param options           预算参数，null 使用默认值
     * @param segments          全部候选片段
     * @param counter           Token 估算
     * @param trimmer           裁剪策略（仅对 trimmable 片段使用）
     */
    public static Result assemble(int modelWindowTokens, Options options, List<ContextSegment> segments,
                                  TokenCounter counter, Trimmer trimmer) {
        Options opts = options != null ? options : Options.defaults();
        int budget = computeBudget(modelWindowTokens, opts);
        if (segments == null || segments.isEmpty()) {
            return new Result(List.of(), List.of(), budget, 0, 0, false);
        }

        List<ContextSegment> ordered = new ArrayList<>(segments);
        ordered.sort(Comparator.comparingInt(ContextSegment::priority)
                .thenComparingInt(ContextSegment::index));

        int remaining = budget;
        int originalTokens = 0;
        List<KeptSegment> kept = new ArrayList<>();
        List<DroppedSegment> dropped = new ArrayList<>();

        for (ContextSegment segment : ordered) {
            int cost = Math.max(counter.count(segment.content()), 0);
            originalTokens += cost;
            if (cost <= remaining) {
                kept.add(new KeptSegment(segment, segment.content(), cost, cost, false));
                remaining -= cost;
                continue;
            }
            if (!segment.trimmable() || remaining <= 0) {
                dropped.add(new DroppedSegment(segment.index(), segment.type(), cost, false));
                continue;
            }
            String trimmed = trimmer != null ? trimmer.trim(segment.content(), remaining) : null;
            if (trimmed == null || trimmed.isEmpty()) {
                dropped.add(new DroppedSegment(segment.index(), segment.type(), cost, true));
                continue;
            }
            int keptCost = Math.max(counter.count(trimmed), 0);
            kept.add(new KeptSegment(segment, trimmed, cost, keptCost, true));
            remaining = Math.max(remaining - keptCost, 0);
        }

        kept.sort(Comparator.comparingInt(KeptSegment::index));
        int keptTokens = kept.stream().mapToInt(KeptSegment::keptTokens).sum();
        boolean singleSourceSaturated = kept.size() == 1
                && budget > 0
                && keptTokens >= budget * SATURATION_RATIO
                && (kept.get(0).trimmed() || !dropped.isEmpty());

        return new Result(List.copyOf(kept), List.copyOf(dropped), budget,
                originalTokens, keptTokens, singleSourceSaturated);
    }

    /** 计算本次可用预算。 */
    public static int computeBudget(int modelWindowTokens, Options options) {
        Options opts = options != null ? options : Options.defaults();
        int window = modelWindowTokens > 0 ? modelWindowTokens : DEFAULT_FALLBACK_WINDOW_TOKENS;
        double ratio = opts.inputWindowRatio() > 0 && opts.inputWindowRatio() <= 1
                ? opts.inputWindowRatio() : DEFAULT_INPUT_WINDOW_RATIO;
        int budget = (int) Math.floor(window * ratio);
        if (opts.hardCapTokens() > 0) {
            budget = Math.min(budget, opts.hardCapTokens());
        }
        budget -= Math.max(opts.reserveOutputTokens(), 0);
        return Math.max(budget, 0);
    }
}

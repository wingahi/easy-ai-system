package com.wgh.rag.llm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.llm.constant.LlmConstants;
import com.wgh.rag.llm.entity.LlmModel;
import com.wgh.rag.llm.mapper.LlmModelMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型路由：窗口指定 model_route_group 时按优先级升序形成故障转移链路；
 * 未指定时退回 llmModelId 单模型。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelRouter {

    private final LlmModelMapper llmModelMapper;

    /**
     * 解析调用链：先 route_group，再兜底 llmModelId
     */
    public List<LlmModel> resolveChain(String routeGroup, Long llmModelId) {
        return resolveChain(routeGroup, llmModelId, false);
    }

    /**
     * 解析调用链，并按能力画像做降级过滤：
     * <ul>
     *   <li>只保留能力档位不高于主模型的候选（切链不升级到更强模型，避免效果跳变与成本上升）；</li>
     *   <li>{@code requireTools=true} 时只保留支持 function calling 的候选；</li>
     *   <li>主模型本身始终保留；过滤后为空则回退原始候选，避免因画像缺失导致无链可用。</li>
     * </ul>
     */
    public List<LlmModel> resolveChain(String routeGroup, Long llmModelId, boolean requireTools) {
        List<LlmModel> chain = new ArrayList<>();
        if (routeGroup != null && !routeGroup.isBlank()) {
            List<LlmModel> candidates = llmModelMapper.selectList(
                    new LambdaQueryWrapper<LlmModel>()
                            .eq(LlmModel::getModelType, LlmConstants.MODEL_TYPE_CHAT)
                            .eq(LlmModel::getStatus, 1)
                            .eq(LlmModel::getRouteGroup, routeGroup)
                            .orderByAsc(LlmModel::getRoutePriority)
                            .orderByAsc(LlmModel::getId));
            chain.addAll(degradeCandidates(candidates, llmModelId, requireTools));
            if (chain.isEmpty()) {
                log.warn("路由组 {} 下无可用模型，退回 llmModelId={}", routeGroup, llmModelId);
            }
        }
        if (chain.isEmpty() && llmModelId != null) {
            LlmModel single = llmModelMapper.selectById(llmModelId);
            if (single != null) chain.add(single);
        }
        if (chain.isEmpty()) {
            throw new BusinessException("未找到可用的聊天模型（llmModelId=" + llmModelId
                    + ", routeGroup=" + routeGroup + "）");
        }
        return chain;
    }

    private List<LlmModel> degradeCandidates(List<LlmModel> candidates, Long llmModelId, boolean requireTools) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        int primaryTier = candidates.stream()
                .filter(m -> llmModelId != null && llmModelId.equals(m.getId()))
                .mapToInt(ModelRouter::tierOf)
                .findFirst()
                .orElseGet(() -> tierOf(candidates.get(0)));
        List<LlmModel> filtered = candidates.stream()
                .filter(m -> isPrimary(m, llmModelId) || tierOf(m) <= primaryTier)
                .filter(m -> isPrimary(m, llmModelId) || !requireTools
                        || Boolean.TRUE.equals(m.getSupportsTools()))
                .toList();
        return filtered;
    }

    private static boolean isPrimary(LlmModel model, Long llmModelId) {
        return llmModelId != null && llmModelId.equals(model.getId());
    }

    private static int tierOf(LlmModel model) {
        return model.getCapabilityTier() != null ? model.getCapabilityTier() : 3;
    }

    /**
     * 取链路中第一个模型的 ID（兼容未启用路由组的旧调用方）
     */
    public Long primaryModelId(String routeGroup, Long llmModelId) {
        return resolveChain(routeGroup, llmModelId).get(0).getId();
    }
}

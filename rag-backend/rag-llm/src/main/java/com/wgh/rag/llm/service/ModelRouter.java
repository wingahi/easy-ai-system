package com.wgh.rag.llm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wgh.rag.common.exception.BusinessException;
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
        List<LlmModel> chain = new ArrayList<>();
        if (routeGroup != null && !routeGroup.isBlank()) {
            List<LlmModel> candidates = llmModelMapper.selectList(
                    new LambdaQueryWrapper<LlmModel>()
                            .eq(LlmModel::getModelType, "CHAT")
                            .eq(LlmModel::getStatus, 1)
                            .eq(LlmModel::getRouteGroup, routeGroup)
                            .orderByAsc(LlmModel::getRoutePriority)
                            .orderByAsc(LlmModel::getId));
            chain.addAll(candidates);
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

    /**
     * 取链路中第一个模型的 ID（兼容未启用路由组的旧调用方）
     */
    public Long primaryModelId(String routeGroup, Long llmModelId) {
        return resolveChain(routeGroup, llmModelId).get(0).getId();
    }
}

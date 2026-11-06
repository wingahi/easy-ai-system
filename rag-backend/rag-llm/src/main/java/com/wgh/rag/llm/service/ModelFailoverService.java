package com.wgh.rag.llm.service;

import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.llm.entity.LlmModel;
import com.wgh.rag.llm.mapper.LlmModelMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 故障转移链解析 —— 与普通 RAG 的口径保持一致：
 * 优先使用调用方提供的 route_group（如聊天窗口配置），
 * 未提供时回退到模型自身配置的 route_group，最后回退为单模型。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelFailoverService {

    private final ModelRouter modelRouter;
    private final LlmModelMapper llmModelMapper;

    /**
     * @param modelId    主模型 / 窗口直配模型，可为 null（仅当 routeGroup 可用）
     * @param routeGroup 调用方已知的路由组（如聊天窗口的 model_route_group），可为 null
     */
    public List<LlmModel> resolveChain(Long modelId, String routeGroup) {
        if (routeGroup != null && !routeGroup.isBlank()) {
            return modelRouter.resolveChain(routeGroup, modelId);
        }
        if (modelId == null) {
            throw new BusinessException("未指定可用的聊天模型（modelId 为空）");
        }
        LlmModel model = llmModelMapper.selectById(modelId);
        if (model == null) {
            throw new BusinessException("未找到可用的聊天模型（llmModelId=" + modelId + "）");
        }
        if (model.getRouteGroup() != null && !model.getRouteGroup().isBlank()) {
            List<LlmModel> chain = modelRouter.resolveChain(model.getRouteGroup(), modelId);
            if (!chain.isEmpty()) {
                return chain;
            }
        }
        return List.of(model);
    }

    /** 仅按模型自身的 route_group 解析（无路由组时为单模型） */
    public List<LlmModel> resolveChain(Long modelId) {
        return resolveChain(modelId, null);
    }
}

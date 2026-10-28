package com.wgh.rag.workflow.engine.executor;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 节点执行器注册表 —— Spring 注入全部 NodeExecutor 实现，按 supportedType 建索引。
 */
@Component
public class NodeExecutorRegistry {

    private final Map<String, NodeExecutor> executors;

    public NodeExecutorRegistry(List<NodeExecutor> executorList) {
        this.executors = executorList.stream()
                .collect(Collectors.toMap(NodeExecutor::supportedType, Function.identity()));
    }

    /** 按节点类型取执行器；未知类型与原 executeNode default 分支行为一致 */
    public NodeExecutor get(String type) {
        NodeExecutor executor = executors.get(type);
        if (executor == null) {
            throw new IllegalStateException("不支持的节点类型：" + type);
        }
        return executor;
    }
}

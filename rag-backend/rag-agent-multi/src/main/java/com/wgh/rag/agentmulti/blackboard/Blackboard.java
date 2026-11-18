package com.wgh.rag.agentmulti.blackboard;

import java.util.Map;
import java.util.Optional;

/**
 * 多 Agent 共享黑板读写接口。
 *
 * <p>worker / manager / 汇总节点通过同一份黑板共享中间产物（检索结论、工具结果、卡片等），
 * 避免只能靠 ctx 的扁平 key 约定传递。实现可以是进程内，也可以是后续的外部存储。
 */
public interface Blackboard {

    /** 写入（覆盖同 key）。 */
    void put(String key, Object value);

    /** 读取。 */
    Optional<Object> get(String key);

    /** 按 key 前缀读取快照（用于汇总节点批量取 worker 卡片）。 */
    Map<String, Object> snapshot(String keyPrefix);

    /** 全量快照（用于持久化 contextJson）。 */
    Map<String, Object> snapshot();

    /** 清空。 */
    void clear();
}

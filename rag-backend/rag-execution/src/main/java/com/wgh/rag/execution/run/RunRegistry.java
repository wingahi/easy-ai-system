package com.wgh.rag.execution.run;

import com.wgh.rag.execution.model.ExecutionSnapshot;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 运行适配器注册表。
 *
 * <p>调度层通过本接口查找运行适配器，实现统一查询与续跑；注册表本身不含业务逻辑。
 * 多实例部署下注册表只反映当前实例已装配的模块。
 */
public interface RunRegistry {

    /**
     * 注册适配器。同一 {@code runType} 重复注册应视为配置错误。
     *
     * @param adapter 运行适配器
     */
    void register(RunAdapter adapter);

    /**
     * 按运行类型查找。
     *
     * @param runType 运行类型
     * @return 适配器；未注册时为空
     */
    Optional<RunAdapter> find(String runType);

    /**
     * 已注册的运行类型。
     *
     * @return 运行类型集合
     */
    Set<String> runTypes();

    /**
     * 列出指定租户下的活跃运行。
     *
     * @param tenantId 租户标识
     * @param runTypes 需要聚合的运行类型；为空表示全部
     * @return 活跃运行快照
     */
    List<ExecutionSnapshot> listActive(String tenantId, Set<String> runTypes);
}

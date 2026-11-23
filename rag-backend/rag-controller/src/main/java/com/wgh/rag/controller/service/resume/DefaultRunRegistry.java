package com.wgh.rag.controller.service.resume;

import com.wgh.rag.execution.model.ExecutionSnapshot;
import com.wgh.rag.execution.run.RunAdapter;
import com.wgh.rag.execution.run.RunRegistry;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 进程内运行适配器注册表。
 *
 * <p>Spring 启动时收集所有 {@link RunAdapter} Bean；重复 runType 直接失败，避免静默覆盖。
 * 注册表只反映当前实例装配的模块，跨实例能力由各实例自行装配。
 */
@Component
public class DefaultRunRegistry implements RunRegistry {

    private final Map<String, RunAdapter> adapters;

    public DefaultRunRegistry(List<RunAdapter> discovered) {
        Map<String, RunAdapter> registered = new LinkedHashMap<>();
        for (RunAdapter adapter : discovered) {
            String key = adapter.runType().trim().toUpperCase(Locale.ROOT);
            RunAdapter previous = registered.putIfAbsent(key, adapter);
            if (previous != null) {
                throw new IllegalStateException("重复的 RunAdapter runType=" + key
                        + "：" + previous.getClass().getName() + " / " + adapter.getClass().getName());
            }
        }
        this.adapters = Map.copyOf(registered);
    }

    @Override
    public void register(RunAdapter adapter) {
        throw new UnsupportedOperationException("RunAdapter 由 Spring 构造注入，运行期不再注册");
    }

    @Override
    public Optional<RunAdapter> find(String runType) {
        if (runType == null || runType.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(adapters.get(runType.trim().toUpperCase(Locale.ROOT)));
    }

    @Override
    public Set<String> runTypes() {
        return new LinkedHashSet<>(adapters.keySet());
    }

    @Override
    public List<ExecutionSnapshot> listActive(String tenantId, Set<String> runTypes) {
        // RunAdapter 当前只暴露按 runId 读取；跨域活跃列表由各域查询接口负责。
        return List.of();
    }
}

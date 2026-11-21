package com.wgh.rag.scheduler.job;

import com.wgh.rag.execution.job.JobHandler;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 作业处理器注册表。
 *
 * <p>按 Spring Bean 收集 {@link JobHandler}，调度层只按 {@code jobType} 查找，不感知具体执行域。
 * 同一 {@code jobType} 重复注册视为配置错误，启动期直接失败，避免运行时随机选中一个。
 */
@Component
public class JobHandlerRegistry {

    private final Map<String, JobHandler> handlers;

    public JobHandlerRegistry(List<JobHandler> beans) {
        Map<String, JobHandler> registered = new LinkedHashMap<>();
        for (JobHandler handler : beans) {
            String jobType = handler.jobType();
            if (jobType == null || jobType.isBlank()) {
                throw new IllegalStateException("JobHandler#jobType 不能为空: " + handler.getClass().getName());
            }
            JobHandler previous = registered.putIfAbsent(jobType, handler);
            if (previous != null) {
                throw new IllegalStateException("作业类型重复注册: " + jobType
                        + "（" + previous.getClass().getName() + " 与 " + handler.getClass().getName() + "）");
            }
        }
        this.handlers = Map.copyOf(registered);
    }

    /** 按作业类型查找。 */
    public Optional<JobHandler> find(String jobType) {
        if (jobType == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(handlers.get(jobType));
    }

    /** 已注册的作业类型。 */
    public Set<String> types() {
        return handlers.keySet();
    }

    /** 已注册处理器（只读）。 */
    public Collection<JobHandler> handlers() {
        return handlers.values();
    }
}

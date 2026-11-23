package com.wgh.rag.kb.job;

import com.wgh.rag.config.property.RagProperties;
import com.wgh.rag.execution.job.JobContext;
import com.wgh.rag.execution.job.JobHandler;
import com.wgh.rag.execution.job.RecurringJob;
import com.wgh.rag.kb.service.AgentMemoryCleaner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 长期记忆清理（{@code KB_AGENT_MEMORY_CLEANUP}，维护车道 + 周期作业）。
 *
 * <p>沿用原有 {@link AgentMemoryCleaner#cleanupExpired()}：内部仍保留进程内互斥与
 * PG 事务级咨询锁，调度中心的维护车道再提供跨实例 Redis 单飞。删除本身幂等。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KbAgentMemoryCleanupJobHandler implements JobHandler, RecurringJob {

    private final AgentMemoryCleaner agentMemoryCleaner;
    private final RagProperties ragProperties;

    @Override
    public String jobType() {
        return KbJobTypes.AGENT_MEMORY_CLEANUP;
    }

    @Override
    public Duration interval() {
        RagProperties.Memory memory = ragProperties.getMemory();
        Integer minutes = memory == null || memory.getCleanupIntervalMinutes() == null
                ? 60 : memory.getCleanupIntervalMinutes();
        return Duration.ofMinutes(Math.max(5, minutes));
    }

    @Override
    public void execute(JobContext context) {
        RagProperties.Memory memory = ragProperties.getMemory();
        if (memory == null || !Boolean.TRUE.equals(memory.getEnabled())
                || !Boolean.TRUE.equals(memory.getCleanupEnabled())) {
            log.debug("长期记忆清理已关闭，跳过本轮");
            return;
        }
        context.cancellation().throwIfCancellationRequested();
        int deleted = agentMemoryCleaner.cleanupExpired();
        log.debug("长期记忆清理完成 executionId={} deleted={}", context.executionId(), deleted);
    }
}

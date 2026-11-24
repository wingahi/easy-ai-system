package com.wgh.rag.scheduler.facade;

import com.wgh.rag.execution.api.InteractiveRunRegistry;
import com.wgh.rag.execution.model.ExecutionLane;
import com.wgh.rag.execution.model.ExecutionRequest;
import com.wgh.rag.execution.model.ExecutionStatus;
import com.wgh.rag.scheduler.config.ChatRunProperties;
import com.wgh.rag.scheduler.entity.ExecutionJob;
import com.wgh.rag.scheduler.job.JobStore;
import com.wgh.rag.scheduler.support.SchedulerIdentity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 交互式运行态登记的调度层实现。
 *
 * <p>复用 {@code wgh_execution_job} 记录运行态：{@code lane=INTERACTIVE}、{@code status=RUNNING}、
 * {@code attempt=max_attempts=1}。最后一项是刻意为之：既有租约过期回收与实例失联回收对
 * {@code attempt >= max_attempts} 的记录落 {@code DEAD} 终态，而不会被放回 INTERACTIVE 车道
 * （该车道不参与后台领取，回 PENDING 会成为无人消费的幽灵记录）。</p>
 *
 * <p>登记与注销均为尽力而为：任何异常只记日志，不影响对话主链路。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "wgh.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DefaultInteractiveRunRegistry implements InteractiveRunRegistry {

    private static final RunRegistration NOOP = (status, errorCode) -> {
    };

    private final JobStore jobStore;

    private final SchedulerIdentity identity;

    private final ChatRunProperties properties;

    public DefaultInteractiveRunRegistry(JobStore jobStore, SchedulerIdentity identity,
                                         ChatRunProperties properties) {
        this.jobStore = jobStore;
        this.identity = identity;
        this.properties = properties;
    }

    @Override
    public RunRegistration register(InteractiveRunRequest request) {
        if (request == null || !properties.isEnabled()) {
            return NOOP;
        }
        try {
            ExecutionRequest execution = ExecutionRequest.builder(request.runType(), ExecutionLane.INTERACTIVE)
                    .tenant(request.tenantId())
                    .owner(request.ownerUserId())
                    .bizKey(request.bizKey())
                    .payload(request.attributes())
                    .build();
            ExecutionJob job = jobStore.registerRunning(execution, identity.instanceId(),
                    properties.getLeaseTtl());
            return new Registration(job.getId());
        } catch (DuplicateKeyException e) {
            log.debug("交互式运行态已存在，跳过登记 runType={} bizKey={}", request.runType(), request.bizKey());
            return NOOP;
        } catch (RuntimeException e) {
            log.warn("交互式运行态登记失败（不影响执行） runType={} bizKey={} error={}",
                    request.runType(), request.bizKey(), e.getMessage());
            return NOOP;
        }
    }

    /** 注销句柄：只允许生效一次；删除条件带实例归属，避免误删被回收/接管后的记录。 */
    private final class Registration implements RunRegistration {

        private final Long id;

        private final AtomicBoolean finished = new AtomicBoolean();

        private Registration(Long id) {
            this.id = id;
        }

        @Override
        public void finish(ExecutionStatus status, String errorCode) {
            if (!finished.compareAndSet(false, true) || id == null) {
                return;
            }
            try {
                boolean removed = jobStore.finishInteractive(id, identity.instanceId());
                if (!removed) {
                    log.debug("交互式运行态已被回收或已结束 id={} status={} errorCode={}", id, status, errorCode);
                }
            } catch (RuntimeException e) {
                log.warn("交互式运行态注销失败（由租约/实例失联回收兜底） id={} error={}", id, e.getMessage());
            }
        }
    }
}

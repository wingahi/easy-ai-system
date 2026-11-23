package com.wgh.rag.kb.job;

import com.wgh.rag.execution.job.JobContext;
import com.wgh.rag.execution.job.JobHandler;
import com.wgh.rag.execution.job.RecurringJob;
import com.wgh.rag.kb.connector.ConnectorProperties;
import com.wgh.rag.kb.connector.ConnectorScheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * URL 连接器定时同步（{@code KB_CONNECTOR_SYNC}，维护车道 + 周期作业）。
 *
 * <p>保留原有默认关闭开关 {@code wgh.connector.scheduler.enabled}：关闭时作业仍会被周期提交，
 * 但执行体立即返回，不产生任何外网请求。维护车道单飞保证多副本同一时刻只跑一轮同步。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KbConnectorSyncJobHandler implements JobHandler, RecurringJob {

    private final ConnectorScheduler connectorScheduler;
    private final ConnectorProperties properties;

    @Override
    public String jobType() {
        return KbJobTypes.CONNECTOR_SYNC;
    }

    @Override
    public Duration interval() {
        return Duration.ofSeconds(Math.max(5, properties.getSchedulerIntervalSeconds()));
    }

    @Override
    public void execute(JobContext context) {
        if (!properties.isSchedulerEnabled()) {
            log.debug("URL 连接器定时同步已关闭，跳过本轮");
            return;
        }
        context.cancellation().throwIfCancellationRequested();
        connectorScheduler.tick();
    }
}

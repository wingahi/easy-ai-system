package com.wgh.rag.kb.job;

import com.wgh.rag.config.property.RagProperties;
import com.wgh.rag.data.tenant.PlatformScanTemplate;
import com.wgh.rag.execution.job.ExecutionCancellationToken;
import com.wgh.rag.execution.job.JobContext;
import com.wgh.rag.kb.connector.ConnectorProperties;
import com.wgh.rag.kb.connector.ConnectorScheduler;
import com.wgh.rag.kb.service.AgentMemoryCleaner;
import com.wgh.rag.kb.service.UploadCleanupProperties;
import com.wgh.rag.kb.service.UploadSessionCleaner;
import com.wgh.rag.kb.storage.StorageOrphanCleanupProperties;
import com.wgh.rag.kb.storage.StorageOrphanCleanupScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库维护类周期作业处理器：验证开关关闭时跳过、开启时调用原有服务入口。
 */
@ExtendWith(MockitoExtension.class)
class KbRecurringJobHandlersTest {

    @Mock
    private StorageOrphanCleanupScheduler orphanScheduler;
    @Mock
    private StorageOrphanCleanupProperties orphanProperties;
    @Mock
    private UploadSessionCleaner uploadCleaner;
    @Mock
    private UploadCleanupProperties uploadProperties;
    @Mock
    private AgentMemoryCleaner memoryCleaner;
    @Mock
    private ConnectorScheduler connectorScheduler;
    @Mock
    private ConnectorProperties connectorProperties;
    @Mock
    private JobContext context;
    @Mock
    private ExecutionCancellationToken cancellation;
    @Mock
    private PlatformScanTemplate platformScan;

    private final RagProperties ragProperties = new RagProperties();

    @BeforeEach
    void setUp() {
        lenient().when(context.cancellation()).thenReturn(cancellation);
        lenient().when(context.executionId()).thenReturn("exec-1");
        // 平台级扫描在本测试中退化为单租户执行
        lenient().when(platformScan.scan(anyBoolean(), any(), any())).thenAnswer(invocation -> {
            PlatformScanTemplate.TenantTask task = invocation.getArgument(2);
            task.run("default");
            return new PlatformScanTemplate.Result(1, 1, 0, 1, 0, false);
        });
    }

    @Test
    void orphanCleanupSkipsWhenDisabled() {
        when(orphanProperties.isEnabled()).thenReturn(false);
        new KbOrphanCleanupJobHandler(orphanScheduler, orphanProperties).execute(context);
        verify(orphanScheduler, never()).runOnce(any());
    }

    @Test
    void orphanCleanupRunsThroughOriginalEntryWhenEnabled() {
        when(orphanProperties.isEnabled()).thenReturn(true);
        when(orphanProperties.getIntervalMinutes()).thenReturn(60);
        KbOrphanCleanupJobHandler handler = new KbOrphanCleanupJobHandler(orphanScheduler, orphanProperties);

        handler.execute(context);

        verify(orphanScheduler).runOnce(any());
        assertThat(handler.interval().toMinutes()).isEqualTo(60);
    }

    @Test
    void uploadCleanupSkipsWhenDisabled() {
        when(uploadProperties.isEnabled()).thenReturn(false);
        new KbUploadCleanupJobHandler(uploadCleaner, uploadProperties, platformScan).execute(context);
        verify(uploadCleaner, never()).cleanupExpired();
    }

    @Test
    void uploadCleanupRunsWhenEnabled() {
        when(uploadProperties.isEnabled()).thenReturn(true);
        when(uploadProperties.getIntervalMinutes()).thenReturn(30);
        KbUploadCleanupJobHandler handler = new KbUploadCleanupJobHandler(uploadCleaner, uploadProperties, platformScan);

        handler.execute(context);

        verify(uploadCleaner).cleanupExpired();
        assertThat(handler.interval().toMinutes()).isEqualTo(30);
    }

    @Test
    void agentMemoryCleanupSkipsWhenCleanupDisabled() {
        ragProperties.getMemory().setCleanupEnabled(false);
        new KbAgentMemoryCleanupJobHandler(memoryCleaner, ragProperties, platformScan).execute(context);
        verify(memoryCleaner, never()).cleanupExpired();
    }

    @Test
    void agentMemoryCleanupRunsWhenEnabled() {
        ragProperties.getMemory().setCleanupEnabled(true);
        ragProperties.getMemory().setCleanupIntervalMinutes(90);
        KbAgentMemoryCleanupJobHandler handler = new KbAgentMemoryCleanupJobHandler(memoryCleaner, ragProperties, platformScan);

        handler.execute(context);

        verify(memoryCleaner).cleanupExpired();
        assertThat(handler.interval().toMinutes()).isEqualTo(90);
    }

    @Test
    void connectorSyncSkipsWhenFeatureDisabled() {
        when(connectorProperties.isSchedulerEnabled()).thenReturn(false);
        new KbConnectorSyncJobHandler(connectorScheduler, connectorProperties).execute(context);
        verify(connectorScheduler, never()).tick(any());
    }

    @Test
    void connectorSyncRunsWhenFeatureEnabled() {
        when(connectorProperties.isSchedulerEnabled()).thenReturn(true);
        when(connectorProperties.getSchedulerIntervalSeconds()).thenReturn(120);
        KbConnectorSyncJobHandler handler = new KbConnectorSyncJobHandler(connectorScheduler, connectorProperties);

        handler.execute(context);

        verify(connectorScheduler).tick(any());
        assertThat(handler.interval().toSeconds()).isEqualTo(120);
    }
}

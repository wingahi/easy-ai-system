package com.wgh.rag.quota.job;

import com.wgh.rag.common.metrics.RagMetrics;
import com.wgh.rag.execution.job.ExecutionCancellationToken;
import com.wgh.rag.execution.job.JobContext;
import com.wgh.rag.quota.constant.QuotaConstants;
import com.wgh.rag.quota.entity.QuotaChargeEntity;
import com.wgh.rag.quota.properties.QuotaProperties;
import com.wgh.rag.quota.service.QuotaChargeLedger;
import com.wgh.rag.quota.service.QuotaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 补偿作业：扫描、退款、退避重试与 DEAD 边界。
 */
@ExtendWith(MockitoExtension.class)
class QuotaRefundJobHandlerTest {

    @Mock
    private QuotaChargeLedger chargeLedger;

    @Spy
    private QuotaProperties properties = new QuotaProperties();

    @Mock
    private RagMetrics metrics;

    @InjectMocks
    private QuotaRefundJobHandler handler;

    private JobContext readyContext() {
        JobContext context = mock(JobContext.class);
        ExecutionCancellationToken cancellation = mock(ExecutionCancellationToken.class);
        when(context.executionId()).thenReturn("exec-1");
        when(context.cancellation()).thenReturn(cancellation);
        when(context.events()).thenReturn(event -> {
        });
        return context;
    }

    private QuotaChargeEntity row(String chargeId, String status, int retryCount) {
        QuotaChargeEntity entity = new QuotaChargeEntity();
        entity.setChargeId(chargeId);
        entity.setUsername("alice");
        entity.setPeriodKey("2026-11-20");
        entity.setStatus(status);
        entity.setRetryCount(retryCount);
        return entity;
    }

    @Test
    @DisplayName("已扣未决到期：退款成功")
    void refundsDueCharge() {
        when(chargeLedger.findDueRefunds(any(), any(), anyInt()))
                .thenReturn(List.of(row("charge-1", QuotaConstants.CHARGE_CHARGED, 0)));
        when(chargeLedger.refund(any())).thenReturn(QuotaService.RefundResult.REFUNDED);

        handler.execute(readyContext());

        verify(chargeLedger).refund(any());
        verify(metrics).recordQuotaRefund("refunded");
        verify(chargeLedger, never()).markRefundFailed(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("退款失败未达上限：按退避登记 REFUND_FAILED")
    void failureSchedulesRetry() {
        when(chargeLedger.findDueRefunds(any(), any(), anyInt()))
                .thenReturn(List.of(row("charge-1", QuotaConstants.CHARGE_CHARGED, 0)));
        when(chargeLedger.refund(any())).thenThrow(new RuntimeException("db down"));

        handler.execute(readyContext());

        verify(chargeLedger).markRefundFailed(eq("charge-1"), any(LocalDateTime.class), anyString());
        verify(metrics).recordQuotaRefund("failed");
        verify(chargeLedger, never()).markDead(anyString(), anyString());
    }

    @Test
    @DisplayName("退款失败达到上限：置 DEAD 等人工")
    void failureAtMaxMarksDead() {
        when(chargeLedger.findDueRefunds(any(), any(), anyInt()))
                .thenReturn(List.of(row("charge-1", QuotaConstants.CHARGE_REFUND_FAILED, 4)));
        when(chargeLedger.refund(any())).thenThrow(new RuntimeException("db down"));

        handler.execute(readyContext());

        verify(chargeLedger).markDead(eq("charge-1"), anyString());
        verify(metrics).recordQuotaRefund("dead");
        verify(chargeLedger, never()).markRefundFailed(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("关闭补偿：不扫描流水")
    void disabledSkipsScan() {
        properties.getCharge().setRefundEnabled(false);

        handler.execute(mock(JobContext.class));

        verifyNoInteractions(chargeLedger);
    }

    @Test
    @DisplayName("作业类型与提交间隔来自配额配置")
    void recurringMetadata() {
        assertThat(handler.jobType()).isEqualTo(QuotaRefundJobHandler.JOB_TYPE);
        assertThat(handler.interval()).isEqualTo(properties.getCharge().getRefundScanInterval());
    }
}

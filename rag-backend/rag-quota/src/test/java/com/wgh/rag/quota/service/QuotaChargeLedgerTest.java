package com.wgh.rag.quota.service;

import com.wgh.rag.quota.constant.QuotaConstants;
import com.wgh.rag.quota.entity.QuotaChargeEntity;
import com.wgh.rag.quota.mapper.QuotaChargeMapper;
import com.wgh.rag.quota.mapper.QuotaUsageMapper;
import com.wgh.rag.quota.properties.QuotaProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 扣费流水门面：退款原子幂等、失败登记、扫描上限。
 */
@ExtendWith(MockitoExtension.class)
class QuotaChargeLedgerTest {

    @Mock
    private QuotaChargeMapper chargeMapper;

    @Mock
    private QuotaUsageMapper usageMapper;

    @Spy
    private QuotaProperties properties = new QuotaProperties();

    @InjectMocks
    private QuotaChargeLedger ledger;

    private QuotaService.QuotaCharge charge() {
        return new QuotaService.QuotaCharge("alice", "2026-11-20", "charge-1");
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
    @DisplayName("扣费落流水：CHARGED、amount=1、带持久化 chargeId 与窗口")
    void createInsertsChargedRow() {
        QuotaService.QuotaCharge charge = ledger.create("alice", "2026-11-20", 7L);

        ArgumentCaptor<QuotaChargeEntity> captor = ArgumentCaptor.forClass(QuotaChargeEntity.class);
        verify(chargeMapper).insert(captor.capture());
        QuotaChargeEntity saved = captor.getValue();
        assertThat(saved.getChargeId()).isEqualTo(charge.chargeId());
        assertThat(saved.getUsername()).isEqualTo("alice");
        assertThat(saved.getPeriodKey()).isEqualTo("2026-11-20");
        assertThat(saved.getAmount()).isEqualTo(1);
        assertThat(saved.getStatus()).isEqualTo(QuotaConstants.CHARGE_CHARGED);
        assertThat(saved.getWindowId()).isEqualTo(7L);
        assertThat(saved.getRetryCount()).isZero();
    }

    @Test
    @DisplayName("退款：同一事务内流水置已退 + 计数条件减一")
    void refundTransitionsThenDecrements() {
        when(chargeMapper.markRefunded("charge-1")).thenReturn(1);
        when(usageMapper.decrementChatAtomically("alice", "2026-11-20")).thenReturn(1);

        assertThat(ledger.refund(charge())).isEqualTo(QuotaService.RefundResult.REFUNDED);

        verify(chargeMapper).markRefunded("charge-1");
        verify(usageMapper).decrementChatAtomically("alice", "2026-11-20");
    }

    @Test
    @DisplayName("退款：已置退返回 DUPLICATE，不重复减计数")
    void refundIsIdempotentOnLedgerStatus() {
        when(chargeMapper.markRefunded("charge-1")).thenReturn(0);
        when(chargeMapper.selectByChargeId("charge-1"))
                .thenReturn(row("charge-1", QuotaConstants.CHARGE_REFUNDED, 0));

        assertThat(ledger.refund(charge())).isEqualTo(QuotaService.RefundResult.DUPLICATE);
        verify(usageMapper, never()).decrementChatAtomically(anyString(), anyString());
    }

    @Test
    @DisplayName("退款：流水不存在返回 NOTHING_TO_REFUND")
    void refundWithoutLedgerRowIsNoop() {
        when(chargeMapper.markRefunded("charge-1")).thenReturn(0);
        when(chargeMapper.selectByChargeId("charge-1")).thenReturn(null);

        assertThat(ledger.refund(charge())).isEqualTo(QuotaService.RefundResult.NOTHING_TO_REFUND);
        verify(usageMapper, never()).decrementChatAtomically(anyString(), anyString());
    }

    @Test
    @DisplayName("退款：已结算流水不再退款")
    void refundSettledChargeIsNoop() {
        when(chargeMapper.markRefunded("charge-1")).thenReturn(0);
        when(chargeMapper.selectByChargeId("charge-1"))
                .thenReturn(row("charge-1", QuotaConstants.CHARGE_SETTLED, 0));

        assertThat(ledger.refund(charge())).isEqualTo(QuotaService.RefundResult.NOTHING_TO_REFUND);
    }

    @Test
    @DisplayName("退款：计数已为 0 仍保留已退终态")
    void refundKeepsTerminalWhenNothingToDecrement() {
        when(chargeMapper.markRefunded("charge-1")).thenReturn(1);
        when(usageMapper.decrementChatAtomically("alice", "2026-11-20")).thenReturn(0);

        assertThat(ledger.refund(charge())).isEqualTo(QuotaService.RefundResult.REFUNDED);
    }

    @Test
    @DisplayName("失败登记与 DEAD 均走条件更新")
    void markFailureAndDead() {
        LocalDateTime retryAt = LocalDateTime.now().plusMinutes(1);
        when(chargeMapper.markRefundFailed("charge-1", retryAt, "boom")).thenReturn(1);
        when(chargeMapper.markDead("charge-2", "boom")).thenReturn(1);

        assertThat(ledger.markRefundFailed("charge-1", retryAt, "boom")).isTrue();
        assertThat(ledger.markDead("charge-2", "boom")).isTrue();
    }

    @Test
    @DisplayName("扫描上限收敛到配置的批次大小")
    void findDueRefundsUsesConfiguredBatchSize() {
        properties.getCharge().setRefundBatchSize(50);
        LocalDateTime now = LocalDateTime.now();
        when(chargeMapper.selectDueRefunds(any(), any(), anyInt())).thenReturn(java.util.List.of());

        ledger.findDueRefunds(now.minusMinutes(5), now, 10_000);

        verify(chargeMapper).selectDueRefunds(any(), any(), org.mockito.ArgumentMatchers.eq(50));
    }
}

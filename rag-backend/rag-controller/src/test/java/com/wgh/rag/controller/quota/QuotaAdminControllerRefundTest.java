package com.wgh.rag.controller.quota;

import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.PageResult;
import com.wgh.rag.common.result.Result;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.quota.entity.QuotaChargeEntity;
import com.wgh.rag.quota.service.QuotaAdminGuard;
import com.wgh.rag.quota.service.QuotaService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 配额管理端退款异常流水查询：状态校验、分页参数与只读映射。
 */
@ExtendWith(MockitoExtension.class)
class QuotaAdminControllerRefundTest {

    @Mock
    private QuotaService quotaService;
    @Mock
    private QuotaAdminGuard quotaAdminGuard;

    @Test
    void listsRefundIssuesWithMappedFields() {
        QuotaChargeEntity row = new QuotaChargeEntity();
        row.setChargeId("charge-1");
        row.setUsername("alice");
        row.setPeriodKey("2026-11-22");
        row.setAmount(1);
        row.setStatus("REFUND_FAILED");
        row.setRetryCount(2);
        row.setNextRetryAt(LocalDateTime.of(2026, 11, 22, 12, 0));
        row.setLastError("quota usage row missing");

        when(quotaService.countRefundIssues("REFUND_FAILED", "alice")).thenReturn(1L);
        when(quotaService.listRefundIssues("REFUND_FAILED", "alice", 0, 20)).thenReturn(List.of(row));

        QuotaAdminController controller = new QuotaAdminController(quotaService, quotaAdminGuard);
        Result<PageResult<Map<String, Object>>> result =
                controller.listRefundIssues(1, 20, "refund_failed", "alice");

        verify(quotaAdminGuard).requireAdmin();
        PageResult<Map<String, Object>> data = result.getData();
        assertThat(data.getTotal()).isEqualTo(1L);
        assertThat(data.getRecords()).hasSize(1);
        Map<String, Object> mapped = data.getRecords().get(0);
        assertThat(mapped.get("chargeId")).isEqualTo("charge-1");
        assertThat(mapped.get("username")).isEqualTo("alice");
        assertThat(mapped.get("status")).isEqualTo("REFUND_FAILED");
        assertThat(mapped.get("retryCount")).isEqualTo(2);
        assertThat(mapped.get("lastError")).isEqualTo("quota usage row missing");
    }

    @Test
    void rejectsUnsupportedStatus() {
        QuotaAdminController controller = new QuotaAdminController(quotaService, quotaAdminGuard);

        assertThatThrownBy(() -> controller.listRefundIssues(1, 20, "CHARGED", null))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo(ResultCode.PARAM_ERROR.getCode()));
    }
}

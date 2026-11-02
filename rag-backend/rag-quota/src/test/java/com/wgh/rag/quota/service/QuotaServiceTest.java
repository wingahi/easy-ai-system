package com.wgh.rag.quota.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.exception.GlobalExceptionHandler;
import com.wgh.rag.common.result.Result;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.quota.entity.QuotaUsage;
import com.wgh.rag.quota.entity.UserQuotaConfig;
import com.wgh.rag.quota.mapper.QuotaUsageMapper;
import com.wgh.rag.quota.mapper.UserQuotaConfigMapper;
import com.wgh.rag.quota.properties.QuotaProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QuotaService 单元测试：全局默认 / 用户覆盖 / 超限拒绝 / 周期键 / 管理端配置 / 回退
 */
@ExtendWith(MockitoExtension.class)
class QuotaServiceTest {

    @Mock
    private QuotaUsageMapper quotaUsageMapper;

    @Mock
    private UserQuotaConfigMapper userQuotaConfigMapper;

    @Spy
    private QuotaProperties quotaProperties = new QuotaProperties();

    @InjectMocks
    private QuotaService quotaService;

    private QuotaUsage usage(int chatCount, long tokenUsed) {
        QuotaUsage u = new QuotaUsage();
        u.setChatCount(chatCount);
        u.setTokenUsed(tokenUsed);
        return u;
    }

    private UserQuotaConfig config(String username, String period, int chatLimit, long tokenLimit, boolean enabled) {
        UserQuotaConfig c = new UserQuotaConfig();
        c.setUsername(username);
        c.setPeriod(period);
        c.setChatLimit(chatLimit);
        c.setTokenLimit(tokenLimit);
        c.setEnabled(enabled);
        return c;
    }

    @BeforeEach
    void noUserConfigByDefault() {
        // 默认所有用户无自定义配置（各用例按需覆盖）
    }

    // ========== 1. 全局默认生效 ==========

    @Test
    @DisplayName("无自定义配置时解析为全局默认（200 条/日，200 万 token/日）")
    void globalDefaultApplies() {
        when(userQuotaConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        EffectiveQuota quota = quotaService.resolve("alice");

        assertThat(quota.getSource()).isEqualTo(EffectiveQuota.SOURCE_DEFAULT);
        assertThat(quota.getPeriod()).isEqualTo(UserQuotaConfig.PERIOD_DAY);
        assertThat(quota.getChatLimit()).isEqualTo(200);
        assertThat(quota.getTokenLimit()).isEqualTo(2_000_000L);
    }

    @Test
    @DisplayName("默认配置下未超限放行并计数")
    void defaultAllowsUnderLimit() {
        when(userQuotaConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(quotaUsageMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(usage(10, 1000));

        quotaService.checkAndIncrementChat("alice");

        String dayKey = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        verify(quotaUsageMapper).upsertIncrement(eq("alice"), eq(dayKey), eq(1), eq(0L));
    }

    // ========== 2. 用户覆盖生效 ==========

    @Test
    @DisplayName("用户自定义配置优先于全局默认")
    void userOverrideApplies() {
        when(userQuotaConfigMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(config("alice", "DAY", 3, 5000, true));

        EffectiveQuota quota = quotaService.resolve("alice");

        assertThat(quota.getSource()).isEqualTo(EffectiveQuota.SOURCE_CUSTOM);
        assertThat(quota.getChatLimit()).isEqualTo(3);
        assertThat(quota.getTokenLimit()).isEqualTo(5000L);
    }

    // ========== 3. 超限拒绝（429） ==========

    @Test
    @DisplayName("达到自定义对话上限时抛 429 且不计数")
    void chatLimitExceededReturns429() {
        when(userQuotaConfigMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(config("alice", "DAY", 3, 0, true));
        when(quotaUsageMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(usage(3, 0));

        assertThatThrownBy(() -> quotaService.checkAndIncrementChat("alice"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ResultCode.QUOTA_EXCEEDED.getCode()))
                .hasMessageContaining("对话次数已达上限");

        verify(quotaUsageMapper, never()).upsertIncrement(anyString(), anyString(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("达到 token 上限时抛 429")
    void tokenLimitExceededReturns429() {
        when(userQuotaConfigMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(config("alice", "DAY", 0, 5000, true));
        when(quotaUsageMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(usage(1, 5000));

        assertThatThrownBy(() -> quotaService.checkAndIncrementChat("alice"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ResultCode.QUOTA_EXCEEDED.getCode()))
                .hasMessageContaining("token 用量已达上限");
    }

    // ========== 4. 周期（日/月）与周期重置 ==========

    @Test
    @DisplayName("周期键：DAY=yyyy-MM-dd，MONTH=yyyy-MM")
    void periodKeys() {
        String day = quotaService.currentPeriodKey("DAY");
        String month = quotaService.currentPeriodKey("MONTH");

        assertThat(day).matches("\\d{4}-\\d{2}-\\d{2}");
        assertThat(month).matches("\\d{4}-\\d{2}");
        // 新一天/新一月产生新周期键 → 新用量行 → 计数自然重置
        assertThat(quotaService.currentPeriodKey("month")).isEqualTo(month);
    }

    @Test
    @DisplayName("月周期用户的计数落在 yyyy-MM 周期键上")
    void monthPeriodCountsOnMonthKey() {
        when(userQuotaConfigMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(config("alice", "MONTH", 100, 0, true));
        when(quotaUsageMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        quotaService.checkAndIncrementChat("alice");

        String monthKey = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        verify(quotaUsageMapper).upsertIncrement(eq("alice"), eq(monthKey), eq(1), eq(0L));
    }

    // ========== 5. 配置缺失/禁用回退 ==========

    @Test
    @DisplayName("自定义配置被禁用时回退到全局默认")
    void disabledConfigFallsBackToDefault() {
        when(userQuotaConfigMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(config("alice", "MONTH", 1, 1, false));

        EffectiveQuota quota = quotaService.resolve("alice");

        assertThat(quota.getSource()).isEqualTo(EffectiveQuota.SOURCE_DEFAULT);
        assertThat(quota.getChatLimit()).isEqualTo(200);
    }

    // ========== 6. 管理端修改/重置 ==========

    @Test
    @DisplayName("保存配置：非法周期与负数被拒绝")
    void saveUserConfigValidates() {
        assertThatThrownBy(() -> quotaService.saveUserConfig(config("alice", "WEEK", 1, 1, true)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("DAY / MONTH");
        assertThatThrownBy(() -> quotaService.saveUserConfig(config("alice", "DAY", -1, 1, true)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("请求数上限不能为负数");
        assertThatThrownBy(() -> quotaService.saveUserConfig(config("alice", "DAY", 1, -5, true)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("token 上限不能为负数");
        assertThatThrownBy(() -> quotaService.saveUserConfig(config(" ", "DAY", 1, 1, true)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("用户名不能为空");
    }

    @Test
    @DisplayName("保存配置：已存在则更新，不存在则插入")
    void saveUserConfigUpsert() {
        UserQuotaConfig existing = config("alice", "DAY", 10, 100, true);
        existing.setId(42L);
        when(userQuotaConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        quotaService.saveUserConfig(config("alice", "MONTH", 20, 200, true));

        verify(userQuotaConfigMapper).updateById(any(UserQuotaConfig.class));
        verify(userQuotaConfigMapper, never()).insert(any(UserQuotaConfig.class));
    }

    @Test
    @DisplayName("重置：删除自定义配置后回退全局默认")
    void resetUserConfigFallsBackToDefault() {
        UserQuotaConfig existing = config("alice", "DAY", 10, 100, true);
        existing.setId(42L);
        when(userQuotaConfigMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(existing)   // reset 前的 findUserConfig
                .thenReturn(null);      // reset 后 resolve 时已无配置

        boolean removed = quotaService.resetUserConfig("alice");
        assertThat(removed).isTrue();
        verify(userQuotaConfigMapper).deleteById(42L);

        EffectiveQuota quota = quotaService.resolve("alice");
        assertThat(quota.getSource()).isEqualTo(EffectiveQuota.SOURCE_DEFAULT);
    }

    @Test
    @DisplayName("重置：无自定义配置时返回 false")
    void resetUserConfigNoop() {
        when(userQuotaConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertThat(quotaService.resetUserConfig("nobody")).isFalse();
    }

    // ========== 7. 其它语义保持 ==========

    @Test
    @DisplayName("addTokens 仅对正数累加，并计入生效周期键")
    void addTokensSkipsNonPositive() {
        quotaService.addTokens("alice", 0);
        quotaService.addTokens("alice", -5);
        verify(quotaUsageMapper, never()).upsertIncrement(anyString(), anyString(), anyInt(), anyLong());

        when(userQuotaConfigMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(config("alice", "MONTH", 0, 0, true));
        quotaService.addTokens("alice", 123);

        String monthKey = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        verify(quotaUsageMapper).upsertIncrement(eq("alice"), eq(monthKey), eq(0), eq(123L));
    }

    @Test
    @DisplayName("数据库异常时不阻断主流程")
    void dbFailureIsSwallowed() {
        when(userQuotaConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(quotaUsageMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(quotaUsageMapper.upsertIncrement(anyString(), anyString(), anyInt(), anyLong()))
                .thenThrow(new RuntimeException("db down"));

        quotaService.checkAndIncrementChat("alice"); // 不抛出
        quotaService.addTokens("alice", 10); // 不抛出
    }

    // ========== 8. 周期切换 / 用量重置 ==========

    @Test
    @DisplayName("周期切换：跨日生成新键，跨月生成新键，同月保持不变")
    void periodRolloverProducesNewKeys() {
        assertThat(quotaService.currentPeriodKey("DAY", LocalDate.of(2026, 11, 1)))
                .isEqualTo("2026-11-01");
        assertThat(quotaService.currentPeriodKey("DAY", LocalDate.of(2026, 11, 2)))
                .isEqualTo("2026-11-02");
        assertThat(quotaService.currentPeriodKey("MONTH", LocalDate.of(2026, 11, 1)))
                .isEqualTo("2026-11");
        assertThat(quotaService.currentPeriodKey("MONTH", LocalDate.of(2026, 11, 30)))
                .isEqualTo("2026-11");
    }

    @Test
    @DisplayName("重置用量：按键清零当期计数，不修改配置")
    void resetUsageClearsCurrentPeriod() {
        String monthKey = quotaService.currentPeriodKey("MONTH");
        when(userQuotaConfigMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(config("alice", "MONTH", 10, 100, true));
        when(quotaUsageMapper.resetUsage("alice", monthKey)).thenReturn(1);

        boolean reset = quotaService.resetUsage("alice");

        assertThat(reset).isTrue();
        verify(quotaUsageMapper).resetUsage("alice", monthKey);
        verify(userQuotaConfigMapper, never()).deleteById(any(Long.class));
    }

    // ========== 9. 恢复默认后再次保存（逻辑删除 + 唯一键） ==========

    @Test
    @DisplayName("恢复默认后再次保存：复活逻辑删除行而不是插入新行")
    void saveAfterRestoreRevivesDeletedRow() {
        UserQuotaConfig deleted = config("alice", "DAY", 10, 100, true);
        deleted.setId(7L);
        when(userQuotaConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(userQuotaConfigMapper.selectAnyByUsername("alice")).thenReturn(deleted);

        quotaService.saveUserConfig(config("alice", "MONTH", 20, 200, true));

        verify(userQuotaConfigMapper).restoreDeleted(eq(7L), eq("MONTH"), eq(20), eq(200L), eq(1), any());
        verify(userQuotaConfigMapper, never()).insert(any(UserQuotaConfig.class));
    }

    // ========== 10. 并发计数不丢失 ==========

    @Test
    @DisplayName("并发调用：所有对话计数均落库，总量不丢失")
    void concurrentIncrementsAreNotLost() throws Exception {
        int threads = 8;
        int callsPerThread = 25;
        AtomicInteger increments = new AtomicInteger();
        when(userQuotaConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(quotaUsageMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(quotaUsageMapper.upsertIncrement(anyString(), anyString(), eq(1), eq(0L)))
                .thenAnswer(inv -> increments.incrementAndGet());

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads * callsPerThread);
        List<Throwable> errors = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < callsPerThread; j++) {
                        quotaService.checkAndIncrementChat("alice");
                        done.countDown();
                    }
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(errors).isEmpty();
        assertThat(increments.get()).isEqualTo(threads * callsPerThread);
    }

    // ========== 11. 端到端：配额耗尽 → 统一 429 响应 ==========

    @Test
    @DisplayName("端到端：用户配额耗尽 → 429 业务错误 → 全局响应体 code=429 且提示可读")
    void quotaExhaustedMapsTo429Response() {
        when(userQuotaConfigMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(config("alice", "DAY", 2, 0, true));
        when(quotaUsageMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(usage(2, 0));

        BusinessException ex = (BusinessException) org.assertj.core.api.Assertions
                .catchThrowable(() -> quotaService.checkAndIncrementChat("alice"));

        var response = new GlobalExceptionHandler().handleBusinessException(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(200); // 项目统一用 HTTP 200 + 业务码
        Result<?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(429);
        assertThat(body.getMessage()).contains("对话次数已达上限");
        verify(quotaUsageMapper, never()).upsertIncrement(anyString(), anyString(), anyInt(), anyLong());
    }
}

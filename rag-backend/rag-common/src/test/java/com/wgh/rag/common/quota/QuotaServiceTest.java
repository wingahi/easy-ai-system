package com.wgh.rag.common.quota;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wgh.rag.common.entity.QuotaUsage;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.mapper.QuotaUsageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

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
 * QuotaService 单元测试：限流判定 + 用量累加
 */
@ExtendWith(MockitoExtension.class)
class QuotaServiceTest {

    @Mock
    private QuotaUsageMapper quotaUsageMapper;

    @InjectMocks
    private QuotaService quotaService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(quotaService, "chatDailyLimit", 200);
        ReflectionTestUtils.setField(quotaService, "tokenDailyLimit", 2_000_000L);
    }

    private QuotaUsage usage(int chatCount, long tokenUsed) {
        QuotaUsage u = new QuotaUsage();
        u.setChatCount(chatCount);
        u.setTokenUsed(tokenUsed);
        return u;
    }

    @Test
    @DisplayName("无当日记录时直接放行并计数")
    void firstChatOfDayAllowed() {
        when(quotaUsageMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        quotaService.checkAndIncrementChat("alice");

        verify(quotaUsageMapper).upsertIncrement(eq("alice"), anyString(), eq(1), eq(0L));
    }

    @Test
    @DisplayName("未超限时放行并计数")
    void underLimitAllowed() {
        when(quotaUsageMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(usage(10, 1000));

        quotaService.checkAndIncrementChat("alice");

        verify(quotaUsageMapper).upsertIncrement(eq("alice"), anyString(), eq(1), eq(0L));
    }

    @Test
    @DisplayName("达到对话条数上限时抛业务异常且不计数")
    void chatLimitExceeded() {
        when(quotaUsageMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(usage(200, 1000));

        assertThatThrownBy(() -> quotaService.checkAndIncrementChat("alice"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("对话次数已达上限");

        verify(quotaUsageMapper, never()).upsertIncrement(anyString(), anyString(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("达到 token 上限时抛业务异常")
    void tokenLimitExceeded() {
        when(quotaUsageMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(usage(10, 2_000_000L));

        assertThatThrownBy(() -> quotaService.checkAndIncrementChat("alice"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("token 用量已达上限");
    }

    @Test
    @DisplayName("addTokens 仅对正数累加")
    void addTokensSkipsNonPositive() {
        quotaService.addTokens("alice", 0);
        quotaService.addTokens("alice", -5);
        verify(quotaUsageMapper, never()).upsertIncrement(anyString(), anyString(), anyInt(), anyLong());

        quotaService.addTokens("alice", 123);
        verify(quotaUsageMapper).upsertIncrement(eq("alice"), anyString(), eq(0), eq(123L));
    }

    @Test
    @DisplayName("数据库异常时不阻断主流程")
    void dbFailureIsSwallowed() {
        when(quotaUsageMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(quotaUsageMapper.upsertIncrement(anyString(), anyString(), anyInt(), anyLong()))
                .thenThrow(new RuntimeException("db down"));

        quotaService.checkAndIncrementChat("alice"); // 不抛出
        quotaService.addTokens("alice", 10); // 不抛出
    }
}

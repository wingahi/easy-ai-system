package com.wgh.rag.quota;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wgh.rag.data.mybatis.MetaObjectHandlerImpl;
import com.wgh.rag.quota.entity.QuotaUsage;
import com.wgh.rag.quota.entity.UserQuotaConfig;
import com.wgh.rag.quota.mapper.QuotaUsageMapper;
import com.wgh.rag.quota.properties.QuotaProperties;
import com.wgh.rag.quota.service.DefaultQuotaUserResolver;
import com.wgh.rag.quota.service.QuotaAdminGuard;
import com.wgh.rag.quota.service.QuotaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配额并发计数测试：多线程同时计数时用量统计不丢失。
 * <p>
 * 依赖 (username, date_key) 唯一约束 + UPDATE 先行原子累加（首插冲突回退 UPDATE）；
 * 不使用 @Transactional（线程间需真实提交）。
 * 上限置 0（不限制），专注验证计数准确性。
 */
@SpringBootTest(classes = QuotaConcurrencyTest.TestApp.class,
        properties = {"wgh.rag.quota.chat-daily-limit=0", "wgh.rag.quota.token-daily-limit=0"})
@DisplayName("配额并发计数测试")
class QuotaConcurrencyTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @MapperScan({"com.wgh.rag.quota.mapper", "com.wgh.rag.auth.mapper"})
    @EnableConfigurationProperties(QuotaProperties.class)
    @Import({
            MetaObjectHandlerImpl.class,
            com.wgh.rag.common.metrics.RagMetrics.class,
            QuotaService.class,
            DefaultQuotaUserResolver.class,
            QuotaAdminGuard.class
    })
    static class TestApp {
    }

    private static final String USER = "concurrent_user";

    @Autowired
    private QuotaService quotaService;

    @Autowired
    private QuotaUsageMapper quotaUsageMapper;

    @AfterEach
    void cleanup() {
        quotaUsageMapper.delete(new LambdaQueryWrapper<QuotaUsage>()
                .eq(QuotaUsage::getUsername, USER));
    }

    @Test
    @DisplayName("16 线程 × 10 次并发计数：chatCount 与 tokenUsed 无丢失")
    void concurrentCountingIsAccurate() throws Exception {
        int threads = 16;
        int iterations = 10;
        long tokenDelta = 7L;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    start.await(5, TimeUnit.SECONDS);
                    for (int i = 0; i < iterations; i++) {
                        quotaService.checkAndIncrementChat(USER);
                        quotaService.addTokens(USER, tokenDelta);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        QuotaUsage usage = quotaService.usageOf(USER, quotaService.todayKey());
        assertThat(usage).isNotNull();
        assertThat(usage.getChatCount()).isEqualTo(threads * iterations);
        assertThat(usage.getTokenUsed()).isEqualTo(threads * iterations * tokenDelta);
    }
}

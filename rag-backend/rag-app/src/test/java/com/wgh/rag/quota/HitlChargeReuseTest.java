package com.wgh.rag.quota;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wgh.rag.common.metrics.RagMetrics;
import com.wgh.rag.data.mybatis.MetaObjectHandlerImpl;
import com.wgh.rag.quota.entity.QuotaChargeEntity;
import com.wgh.rag.quota.entity.QuotaUsage;
import com.wgh.rag.quota.mapper.QuotaChargeMapper;
import com.wgh.rag.quota.mapper.QuotaUsageMapper;
import com.wgh.rag.quota.properties.QuotaProperties;
import com.wgh.rag.quota.service.DefaultQuotaUserResolver;
import com.wgh.rag.quota.service.QuotaChargeLedger;
import com.wgh.rag.quota.service.QuotaChargeTracker;
import com.wgh.rag.quota.service.QuotaService;
import com.wgh.rag.quota.service.QuotaUsageRowInserter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QCHG-27「再次暂停复用未决流水」的集成用例（QuotaService + QuotaChargeTracker + H2）。
 *
 * <p>钉住双 HITL 的计费口径：第一次确认新建 CHARGED → 再次暂停保持 CHARGED 不退款 →
 * 第二次确认按 {@code task_id} 复用同一 {@code charge_id} 且不新增计数 → 最终有产出只 SETTLED 一次。</p>
 *
 * <p>文件名不用 {@code *IntegrationTest} 后缀（surefire 排除该模式）；依赖 {@code @Transactional} 回滚。</p>
 */
@SpringBootTest(classes = HitlChargeReuseTest.TestApp.class)
@Transactional
@DisplayName("HITL 双暂停扣费复用集成测试（服务 + H2）")
class HitlChargeReuseTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @MapperScan("com.wgh.rag.quota.mapper")
    @EnableConfigurationProperties(QuotaProperties.class)
    @Import({
            MetaObjectHandlerImpl.class,
            RagMetrics.class,
            QuotaService.class,
            QuotaChargeLedger.class,
            QuotaUsageRowInserter.class,
            DefaultQuotaUserResolver.class,
            QuotaChargeTracker.class
    })
    static class TestApp {
    }

    private static final String USERNAME = "hitl-reuse-it";
    private static final String TASK_ID = "hitl:workflow:1498";

    @Autowired
    private QuotaService quotaService;
    @Autowired
    private QuotaChargeTracker chargeTracker;
    @Autowired
    private QuotaChargeMapper chargeMapper;
    @Autowired
    private QuotaUsageMapper usageMapper;

    @Test
    @DisplayName("两次确认复用同一 charge_id：暂停不退款、最终只结算一次")
    void doubleHitl_reusesSameCharge_andSettlesOnce() {
        // 1. 第一次确认：没有未决流水，新建 CHARGED，计数 +1
        QuotaService.ResumeChargeReservation first =
                quotaService.reserveResumeCharge(USERNAME, null, TASK_ID, null, null);
        assertThat(first.alreadySettled()).isFalse();
        assertThat(first.charge()).isNotNull();
        String chargeId = first.charge().chargeId();
        assertThat(chargeId).isNotBlank();
        assertThat(status(chargeId)).isEqualTo("CHARGED");
        assertThat(chatCount()).isEqualTo(1);
        assertThat(chargeRowsByTask()).isEqualTo(1);

        // 2. 再次停在 HITL：finishOnPause 不结算也不退款，流水保持未决且无产出标记
        QuotaChargeTracker.Handle billing = chargeTracker.track(first.charge());
        billing.finishOnPause();
        assertThat(status(chargeId)).isEqualTo("CHARGED");
        assertThat(chargeRow(chargeId).getOutputAt()).isNull();
        assertThat(chatCount()).isEqualTo(1);
        assertThat(chargeRowsByTask()).isEqualTo(1);

        // 3. 第二次确认：按 task_id 复用同一流水，不新增扣费计数
        QuotaService.ResumeChargeReservation second =
                quotaService.reserveResumeCharge(USERNAME, null, TASK_ID, null, null);
        assertThat(second.alreadySettled()).isFalse();
        assertThat(second.charge()).isNotNull();
        assertThat(second.charge().chargeId()).isEqualTo(chargeId);
        assertThat(chatCount()).isEqualTo(1);
        assertThat(chargeRowsByTask()).isEqualTo(1);

        // 4. 最终有产出：只结算一次并落 output_at；重复收口不改状态、不退款、不再计数
        billing.markOutput();
        billing.finish();
        billing.markOutput();
        assertThat(status(chargeId)).isEqualTo("SETTLED");
        assertThat(chargeRow(chargeId).getOutputAt()).isNotNull();
        assertThat(chatCount()).isEqualTo(1);
        assertThat(chargeRowsByTask()).isEqualTo(1);
    }

    private QuotaChargeEntity chargeRow(String chargeId) {
        QuotaChargeEntity row = quotaService.findCharge(chargeId);
        assertThat(row).isNotNull();
        return row;
    }

    private String status(String chargeId) {
        return chargeRow(chargeId).getStatus();
    }

    /** 该测试用户的对话计数（跨周期键累加；测试内只有当期一行）。 */
    private int chatCount() {
        return usageMapper.selectList(new LambdaQueryWrapper<QuotaUsage>()
                        .eq(QuotaUsage::getUsername, USERNAME))
                .stream()
                .mapToInt(u -> u.getChatCount() == null ? 0 : u.getChatCount())
                .sum();
    }

    /** 同一 task_id 的流水条数：复用成立时应始终为 1。 */
    private long chargeRowsByTask() {
        return chargeMapper.selectCount(new LambdaQueryWrapper<QuotaChargeEntity>()
                .eq(QuotaChargeEntity::getTaskId, TASK_ID));
    }
}

package com.wgh.rag.quota.service;

import com.wgh.rag.quota.constant.QuotaConstants;
import com.wgh.rag.quota.entity.QuotaChargeEntity;
import com.wgh.rag.quota.mapper.QuotaChargeMapper;
import com.wgh.rag.quota.mapper.QuotaUsageMapper;
import com.wgh.rag.quota.properties.QuotaProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 配额扣费流水门面：扣费落流水、退款原子幂等、补偿扫描。
 *
 * <p>退款语义：<b>同一个事务</b>内先做流水状态迁移（条件 UPDATE，影响行数为 1 才算本次退款成功），
 * 再做计数条件减一。幂等键是流水上的 {@code charge_id}，不依赖任何进程内缓存，重启不丢。</p>
 *
 * <p>事务传播：{@link #refund} 使用默认 {@code REQUIRED}——
 * 处于外层事务时加入外层事务，随外层回滚一起撤销；补偿作业与无事务调用方各自开启独立事务并提交。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuotaChargeLedger {

    private static final int MAX_ERROR_LENGTH = 500;

    private final QuotaChargeMapper chargeMapper;

    private final QuotaUsageMapper usageMapper;

    private final QuotaProperties properties;

    public QuotaService.QuotaCharge create(String username, String periodKey, Long windowId) {
        return create(username, periodKey, windowId, null);
    }

    public QuotaService.QuotaCharge create(String username, String periodKey, Long windowId, Long usageEpoch) {
        return create(username, periodKey, windowId, usageEpoch, null);
    }

    /**
     * 落一条扣费流水（状态 CHARGED），并记录扣费时的计数纪元快照。
     *
     * @param username   用户名
     * @param periodKey  扣费时的周期键
     * @param windowId   关联窗口，可为 {@code null}
     * @param usageEpoch 扣费时的计数纪元快照，可为 {@code null}（退款时不做纪元校验）
     * @param taskId     关联任务标识（HITL 确认等异步续跑用于对账），可为 {@code null}
     * @return 扣费凭据
     */
    public QuotaService.QuotaCharge create(String username, String periodKey, Long windowId, Long usageEpoch,
                                           String taskId) {
        String chargeId = UUID.randomUUID().toString();
        QuotaChargeEntity entity = new QuotaChargeEntity();
        entity.setChargeId(chargeId);
        entity.setUsername(username);
        entity.setPeriodKey(periodKey);
        entity.setAmount(1);
        entity.setStatus(QuotaConstants.CHARGE_CHARGED);
        entity.setWindowId(windowId);
        entity.setTaskId(taskId);
        entity.setRetryCount(0);
        entity.setUsageEpoch(usageEpoch);
        chargeMapper.insert(entity);
        return new QuotaService.QuotaCharge(username, periodKey, chargeId, usageEpoch);
    }

    /** 回填会话关联（失败只记日志，不影响主流程）。 */
    @Transactional
    public void attachSession(String chargeId, Long sessionId) {
        if (chargeId == null || sessionId == null) {
            return;
        }
        try {
            chargeMapper.attachSession(chargeId, sessionId);
        } catch (Exception e) {
            log.warn("扣费流水回填会话失败: chargeId={}, sessionId={}, reason={}",
                    chargeId, sessionId, e.getMessage());
        }
    }

    /**
     * 执行已开始：把流水置为 SETTLED，扣费最终生效。
     *
     * @return {@code true} 表示本次迁移成功
     */
    @Transactional
    public boolean markSettled(String chargeId) {
        return chargeId != null && chargeMapper.markSettled(chargeId) > 0;
    }

    /**
     * 退款：同一事务内「流水置已退 + 计数条件减一」。
     *
     * <p>不捕获数据库异常：异常交给事务代理回滚，由调用方决定是否登记退款失败。</p>
     *
     * @param charge 扣费凭据
     * @return 退款结果
     */
    @Transactional
    public QuotaService.RefundResult refund(QuotaService.QuotaCharge charge) {
        int transitioned = chargeMapper.markRefunded(charge.chargeId());
        if (transitioned == 0) {
            return resolveNoTransition(charge.chargeId());
        }
        decrement(charge);
        log.info("配额退款完成: username={}, periodKey={}, chargeId={}",
                charge.username(), charge.periodKey(), charge.chargeId());
        return QuotaService.RefundResult.REFUNDED;
    }

    /**
     * 人工退款：管理端确认该笔扣费应收敛为退款，允许从 DEAD 迁移。
     *
     * @param charge 扣费凭据（epoch 用于精确回退计数）
     */
    @Transactional
    public QuotaService.RefundResult refundManually(QuotaService.QuotaCharge charge) {
        int transitioned = chargeMapper.markRefundedManually(charge.chargeId());
        if (transitioned == 0) {
            return resolveNoTransition(charge.chargeId());
        }
        decrement(charge);
        return QuotaService.RefundResult.REFUNDED;
    }

    /**
     * 人工核销：确认该笔扣费有效，置 SETTLED 并结束补偿流程（不改变计数）。
     *
     * @return {@code true} 表示核销成功
     */
    @Transactional
    public boolean writeOff(String chargeId, String reason) {
        String note = reason == null || reason.isBlank() ? "MANUAL_WRITE_OFF" : "MANUAL_WRITE_OFF: " + reason;
        return chargeMapper.markWrittenOff(chargeId, truncate(note)) > 0;
    }

    private QuotaService.RefundResult resolveNoTransition(String chargeId) {
        QuotaChargeEntity existing = chargeMapper.selectByChargeId(chargeId);
        if (existing == null) {
            return QuotaService.RefundResult.NOTHING_TO_REFUND;
        }
        if (QuotaConstants.CHARGE_REFUNDED.equals(existing.getStatus())) {
            return QuotaService.RefundResult.DUPLICATE;
        }
        // SETTLED / DEAD：已生效或已耗尽补偿，不再自动退款
        return QuotaService.RefundResult.NOTHING_TO_REFUND;
    }

    /**
     * 按扣费纪元精确退回一次计数；纪元不匹配（期间被重置）时不回退，避免退掉新产生的扣费。
     */
    private void decrement(QuotaService.QuotaCharge charge) {
        int decremented = charge.usageEpoch() == null
                ? usageMapper.decrementChatAtomically(charge.username(), charge.periodKey())
                : usageMapper.decrementChatAtomicallyForEpoch(charge.username(), charge.periodKey(),
                        charge.usageEpoch());
        if (decremented == 0) {
            // 流水已置退，计数已为 0 或期间被重置过：保留幂等终态，不重复减
            log.warn("配额退款已置流水终态，但计数无可减: username={}, periodKey={}, chargeId={}, usageEpoch={}",
                    charge.username(), charge.periodKey(), charge.chargeId(), charge.usageEpoch());
        }
    }

    /**
     * 登记退款失败：置 REFUND_FAILED 并累计重试次数。
     *
     * <p>独立事务：退款事务已回滚，本登记需要单独提交；无外层事务时等价于新建事务。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markRefundFailed(String chargeId, LocalDateTime nextRetryAt, String error) {
        return chargeMapper.markRefundFailed(chargeId, nextRetryAt, truncate(error)) > 0;
    }

    /** 补偿次数耗尽：置 DEAD。独立事务，确保终态落库。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markDead(String chargeId, String error) {
        return chargeMapper.markDead(chargeId, truncate(error)) > 0;
    }

    /** 扫描待补偿流水（已扣未决 / 退款失败到期）。 */
    public List<QuotaChargeEntity> findDueRefunds(LocalDateTime protectionCutoff, LocalDateTime now, int limit) {
        int size = Math.max(1, Math.min(limit, properties.getCharge().getRefundBatchSize()));
        return chargeMapper.selectDueRefunds(protectionCutoff, now, size);
    }

    /** 管理端查询单笔流水（只读）。 */
    public QuotaChargeEntity findCharge(String chargeId) {
        return chargeMapper.selectByChargeId(chargeId);
    }

    /** 按任务标识取最近一条流水（续跑轮次识别用）。 */
    public QuotaChargeEntity findLatestByTaskId(String taskId) {
        return taskId == null || taskId.isBlank() ? null : chargeMapper.selectLatestByTaskId(taskId);
    }

    /** 取会话在指定时刻之前最近的一条流水（识别本轮对话是否已结算）。 */
    public QuotaChargeEntity findLatestBySessionBefore(Long sessionId, LocalDateTime before) {
        return sessionId == null || before == null ? null
                : chargeMapper.selectLatestBySessionBefore(sessionId, before);
    }

    /** 管理端查询退款异常流水（只读）。 */
    public List<QuotaChargeEntity> findRefundIssues(String status, String username, int offset, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        int safeOffset = Math.max(0, offset);
        return chargeMapper.selectRefundIssues(status, username, safeOffset, safeLimit);
    }

    /** 管理端退款异常流水总数。 */
    public long countRefundIssues(String status, String username) {
        return chargeMapper.countRefundIssues(status, username);
    }

    private static String truncate(String message) {
        if (message == null || message.length() <= MAX_ERROR_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_ERROR_LENGTH);
    }
}

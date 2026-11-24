package com.wgh.rag.quota.service;

import com.wgh.rag.quota.constant.QuotaConstants;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.metrics.RagMetrics;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.quota.entity.QuotaUsage;
import com.wgh.rag.quota.entity.UserQuotaConfig;
import com.wgh.rag.quota.mapper.QuotaUsageMapper;
import com.wgh.rag.quota.mapper.UserQuotaConfigMapper;
import com.wgh.rag.quota.properties.QuotaProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 配额限流：按用户 + 周期（日/月）限制聊天条数与 token 消耗。
 * <p>
 * 解析规则：用户自定义配置（wgh_user_quota_config 且启用）&gt; 全局默认
 * （wgh.rag.quota.chat-daily-limit / token-daily-limit，周期为自然日）。
 * <p>
 * 计数通过数据库条件更新原子累加（带 limit 判断），多实例并发下不会超发。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(QuotaProperties.class)
public class QuotaService {

    private final QuotaUsageMapper quotaUsageMapper;
    private final UserQuotaConfigMapper userQuotaConfigMapper;
    private final QuotaChargeLedger chargeLedger;
    private final QuotaUsageRowInserter usageRowInserter;
    private final QuotaProperties quotaProperties;
    private final RagMetrics metrics;

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    /** 产出确认后结算的最大尝试次数（事务已提交，此处只兜底瞬时数据库抖动） */
    private static final int SETTLE_ATTEMPTS = 3;
    private static final long SETTLE_RETRY_MILLIS = 50L;

    /**
     * 一次已完成的对话扣费凭据；{@code chargeId} 同时是流水表 {@code wgh_quota_charge.charge_id}
     * 的持久化标识与退款幂等键。退款必须使用扣费时的周期键，避免跨周期退错行。
     *
     * <p>{@code usageEpoch} 是扣费时的计数纪元快照：退款只回退同一纪元的计数，
     * 避免管理端「重置用量」后旧流水退掉新产生的扣费；{@code null} 表示存量流水，不做纪元校验。</p>
     */
    public record QuotaCharge(String username, String periodKey, String chargeId, Long usageEpoch) {

        public QuotaCharge(String username, String periodKey, String chargeId) {
            this(username, periodKey, chargeId, null);
        }
    }

    /** 管理员人工处理结果：退款/核销后的流水状态。 */
    public record ManualChargeResult(String chargeId, String previousStatus, String status,
                                     String result, boolean applied) {
    }

    /** 退款结果，供调用方区分「已退款」「重复退款」「无可退」「退款失败」并打点。 */
    public enum RefundResult {
        REFUNDED, DUPLICATE, NOTHING_TO_REFUND, FAILED
    }

    // ============ 周期键 ============

    /**
     * 指定周期的当期键：DAY=yyyy-MM-dd，MONTH=yyyy-MM
     */
    public String currentPeriodKey(String period) {
        return currentPeriodKey(period, LocalDate.now());
    }

    /**
     * 指定日期所在周期的键（供周期切换/测试使用）
     */
    public String currentPeriodKey(String period, LocalDate date) {
        return UserQuotaConfig.PERIOD_MONTH.equalsIgnoreCase(period)
                ? date.format(MONTH_FMT)
                : date.format(DAY_FMT);
    }

    /**
     * 当日键（yyyy-MM-dd），保留供既有统计接口使用
     */
    public String todayKey() {
        return LocalDate.now().format(DAY_FMT);
    }

    // ============ 配额解析 ============

    /**
     * 解析某用户的生效配额：自定义（启用）&gt; 全局默认
     */
    @Transactional(readOnly = true)
    public EffectiveQuota resolve(String username) {
        UserQuotaConfig cfg = findUserConfig(username);
        if (cfg != null && Boolean.TRUE.equals(cfg.getEnabled())) {
            return EffectiveQuota.of(cfg);
        }
        return EffectiveQuota.ofDefault(quotaProperties.getChatDailyLimit(), quotaProperties.getTokenDailyLimit());
    }

    @Transactional(readOnly = true)
    public UserQuotaConfig findUserConfig(String username) {
        return userQuotaConfigMapper.selectOne(new LambdaQueryWrapper<UserQuotaConfig>()
                .eq(UserQuotaConfig::getUsername, username)
                .last("LIMIT 1"));
    }

    // ============ 校验与计数 ============

    /**
     * 发送前检查并计数一次对话：超限抛 BusinessException(429)；通过则当期 chat_count +1。
     * <p>
     * 计数由数据库「带 limit 条件的原子 UPDATE」完成（{@link QuotaUsageMapper#incrementChatAtomically}），
     * 并发多实例不会因「先查后加」而超发；token 上限仍在累加前读取判断。
     * </p>
     */
    public QuotaCharge checkAndIncrementChat(String username) {
        return checkAndIncrementChat(username, null);
    }

    /**
     * 发送前检查并计数一次对话，并落一条持久化扣费流水。
     *
     * @param username 用户名
     * @param windowId 关联聊天窗口，可为 {@code null}
     */
    public QuotaCharge checkAndIncrementChat(String username, Long windowId) {
        return checkAndIncrementChat(username, windowId, null);
    }

    /**
     * 发送前检查并计数一次对话，并落一条持久化扣费流水。
     *
     * @param username 用户名
     * @param windowId 关联聊天窗口，可为 {@code null}
     * @param taskId   关联任务标识（HITL 确认等异步续跑用于对账），可为 {@code null}
     */
    public QuotaCharge checkAndIncrementChat(String username, Long windowId, String taskId) {
        String periodKey = null;
        // 计数前后都可能因 DB 故障让 PG 事务进入 aborted；先建保存点，fail-open 时回退到保存点，
        // 让调用方后续 SQL（会话/消息写入等）仍能在同一事务内继续，避免 25P02 把「放行」变成 500。
        Object savepoint = createCountSavepoint();
        try {
            EffectiveQuota quota = resolve(username);
            periodKey = currentPeriodKey(quota.getPeriod());
            String periodLabel = UserQuotaConfig.PERIOD_MONTH.equalsIgnoreCase(quota.getPeriod()) ? "本月" : "今日";

            // 用量读取（含 token 上限判断）与计数写入同属「计费服务可用性」边界：
            // 任一数据库异常都要进入 handleCountFailure，默认拒绝返回 5004，并打 result=reject|allow 指标；
            // 不能把读取放在 try 之外，否则表不可用时会绕过 5004 直接抛成通用 500。
            QuotaUsage usage = usageOf(username, periodKey);
            if (usage != null
                    && quota.getTokenLimit() != null && quota.getTokenLimit() > 0
                    && usage.getTokenUsed() != null && usage.getTokenUsed() >= quota.getTokenLimit()) {
                metrics.recordQuotaExceeded(QuotaConstants.DIMENSION_TOKEN);
                throw new BusinessException(ResultCode.QUOTA_EXCEEDED,
                        periodLabel + " token 用量已达上限（" + quota.getTokenLimit() + "），请下期再试");
            }
            int chatLimit = quota.getChatLimit() == null ? 0 : quota.getChatLimit();
            boolean allowed = chargeChatAtomically(username, periodKey, chatLimit);
            if (!allowed) {
                metrics.recordQuotaExceeded(QuotaConstants.DIMENSION_CHAT);
                log.info("配额对话计数被拒绝: username={}, periodKey={}, limit={}", username, periodKey, chatLimit);
                throw new BusinessException(ResultCode.QUOTA_EXCEEDED,
                        periodLabel + "对话次数已达上限（" + chatLimit + " 条），请下期再试");
            }
            log.debug("配额对话计数: username={}, periodKey={}, limit={}", username, periodKey, chatLimit);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            if (!rollbackToCountSavepoint(savepoint)) {
                // 保存点回退失败说明当前事务已不可用，不能再假装放行（否则后续仍会 25P02）
                metrics.recordQuotaCountFailure("reject");
                log.error("[ALERT] 配额对话计数失败且无法回退保存点，拒绝本次对话: username={}, periodKey={}",
                        username, periodKey, e);
                throw new BusinessException(ResultCode.QUOTA_SERVICE_UNAVAILABLE,
                        "计费服务暂不可用，请稍后重试");
            }
            handleCountFailure(username, periodKey, e);
            // fail-open：事务状态已恢复，本次放行且不产生扣费凭据
            return null;
        }
        Long usageEpoch = currentUsageEpoch(username, periodKey);
        try {
            return chargeLedger.create(username, periodKey, windowId, usageEpoch, taskId);
        } catch (Exception e) {
            log.error("扣费流水落库失败: username={}, periodKey={}, reason={}", username, periodKey, e.getMessage());
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                // 事务内计数与流水必须同生共死：上抛让外层整体回滚，避免「计数已加但无流水」
                throw e;
            }
            // 无事务兜底：计数已自动提交，立即按扣费纪元补偿减一，保持「配额故障不阻断聊天」语义
            try {
                if (usageEpoch == null) {
                    quotaUsageMapper.decrementChatAtomically(username, periodKey);
                } else {
                    quotaUsageMapper.decrementChatAtomicallyForEpoch(username, periodKey, usageEpoch);
                }
            } catch (Exception compensateError) {
                log.error("[ALERT] 扣费流水落库失败后的计数补偿也失败，可能出现无流水计数: username={}, periodKey={}, reason={}",
                        username, periodKey, compensateError.getMessage());
            }
            return null;
        }
    }

    /**
     * 续跑轮次的扣费决策。
     *
     * <ul>
     *   <li>原对话本轮已结算（流水 SETTLED）→ 整轮只收一次，续跑不再扣费；</li>
     *   <li>同一运行已有未决扣费（CHARGED，例如再次停在 HITL）→ 复用该流水，不再叠加；</li>
     *   <li>其余（已退款 / 从未扣费 / 无流水）→ 新扣一次。</li>
     * </ul>
     *
     * <p>判断全部落在持久化流水上，跨实例、重启后仍成立。必须在调用方事务内执行，
     * 使「决策 + 计数 + 流水」与确认状态推进同生共死。</p>
     *
     * @param chatSessionId 原对话会话 ID；非对话链路（独立工作流/多 Agent）传 {@code null}
     * @param roundStart    原对话轮次开始时间（用于定位该轮流水），可为 {@code null}
     */
    public ResumeChargeReservation reserveResumeCharge(String username, Long windowId, String taskId,
                                                       Long chatSessionId, LocalDateTime roundStart) {
        if (chatSessionId != null && isRoundSettled(chatSessionId, roundStart)) {
            log.info("原对话本轮已结算，续跑不再重复扣费: sessionId={}, taskId={}", chatSessionId, taskId);
            return new ResumeChargeReservation(null, true);
        }
        com.wgh.rag.quota.entity.QuotaChargeEntity existing = chargeLedger.findLatestByTaskId(taskId);
        if (existing != null) {
            if (QuotaConstants.CHARGE_SETTLED.equals(existing.getStatus())) {
                return new ResumeChargeReservation(null, true);
            }
            if (QuotaConstants.CHARGE_CHARGED.equals(existing.getStatus())) {
                log.info("续跑复用未决扣费流水: taskId={}, chargeId={}", taskId, existing.getChargeId());
                return new ResumeChargeReservation(new QuotaCharge(existing.getUsername(), existing.getPeriodKey(),
                        existing.getChargeId(), existing.getUsageEpoch()), false);
            }
        }
        return new ResumeChargeReservation(checkAndIncrementChat(username, windowId, taskId), false);
    }

    /**
     * 某会话在指定时刻之前最近的一条流水是否为 SETTLED（本轮对话已产生有效产出并结算）。
     * 查询失败按未结算处理（宁可补扣，也不因查询故障免费放行）。
     */
    public boolean isRoundSettled(Long sessionId, LocalDateTime before) {
        if (sessionId == null || before == null) {
            return false;
        }
        try {
            com.wgh.rag.quota.entity.QuotaChargeEntity row = chargeLedger.findLatestBySessionBefore(sessionId, before);
            return row != null && QuotaConstants.CHARGE_SETTLED.equals(row.getStatus());
        } catch (Exception e) {
            log.warn("查询本轮对话扣费状态失败，按未结算处理: sessionId={}, reason={}", sessionId, e.getMessage());
            return false;
        }
    }

    /** 续跑扣费决策结果：{@code alreadySettled=true} 表示本轮已计费、无需再扣。 */
    public record ResumeChargeReservation(QuotaCharge charge, boolean alreadySettled) {
    }

    /**
     * 原子计数：事务内先用 SAVEPOINT 安全建行，再做条件自增；无事务时沿用 upsert。
     *
     * <p>事务内不能走「catch DuplicateKeyException 后重试」：PostgreSQL 中语句失败会让整个事务进入
     * aborted（25P02），后续 UPDATE 必然失败。这里把首插包在保存点里，唯一键冲突只回滚保存点。</p>
     */
    private boolean chargeChatAtomically(String username, String periodKey, int chatLimit) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return quotaUsageMapper.incrementChatAtomically(username, periodKey, chatLimit) > 0;
        }
        if (quotaUsageMapper.countExisting(username, periodKey) == 0) {
            usageRowInserter.insertIfAbsent(username, periodKey);
        }
        if (chatLimit <= 0) {
            int updated = quotaUsageMapper.incrementExisting(username, periodKey, 1, 0);
            if (updated == 0) {
                // 极端并发下计数行缺失：补建后重试一次
                usageRowInserter.insertIfAbsent(username, periodKey);
                updated = quotaUsageMapper.incrementExisting(username, periodKey, 1, 0);
            }
            if (updated == 0) {
                throw new IllegalStateException("配额计数行不存在，无法累加");
            }
            return true;
        }
        int updated = quotaUsageMapper.incrementChatWithinLimit(username, periodKey, chatLimit);
        if (updated == 0 && quotaUsageMapper.countExisting(username, periodKey) == 0) {
            // 并发首插的对手事务回滚后行会消失：补建后重试一次，避免误判为超限
            usageRowInserter.insertIfAbsent(username, periodKey);
            updated = quotaUsageMapper.incrementChatWithinLimit(username, periodKey, chatLimit);
        }
        return updated > 0;
    }

    /**
     * 读取当前计数纪元，作为流水快照。读取失败不影响扣费，返回 {@code null}（退款时不做纪元校验）。
     */
    private Long currentUsageEpoch(String username, String periodKey) {
        try {
            QuotaUsage usage = usageOf(username, periodKey);
            return usage == null ? null : usage.getUsageEpoch();
        } catch (Exception e) {
            log.warn("读取配额计数纪元失败: username={}, periodKey={}, reason={}", username, periodKey, e.getMessage());
            return null;
        }
    }

    /**
     * 计数异常处理：显式指标 + 告警级日志，按配置决定拒绝或放行。
     * <p>指标带 {@code result=reject|allow} 标签，便于事后区分是否存在免费放行的请求。</p>
     */
    private void handleCountFailure(String username, String periodKey, Exception error) {
        boolean failOpen = quotaProperties.isFailOpenOnCountError();
        metrics.recordQuotaCountFailure(failOpen ? "allow" : "reject");
        log.error("[ALERT] 配额对话计数失败（action={}）: username={}, periodKey={}, reason={}",
                failOpen ? "allow" : "reject", username, periodKey, error.getMessage(), error);
        if (!failOpen) {
            throw new BusinessException(ResultCode.QUOTA_SERVICE_UNAVAILABLE);
        }
    }

    /** 事务内保存点创建失败时的哨兵：表示「需要恢复但无法恢复」，不能按放行处理。 */
    private static final Object SAVEPOINT_UNAVAILABLE = new Object();

    /**
     * 在当前事务内创建计数保存点。
     *
     * @return 保存点；无事务返回 {@code null}（语句失败不会污染连接）；事务内创建失败返回哨兵
     */
    private Object createCountSavepoint() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return null;
        }
        try {
            return TransactionAspectSupport.currentTransactionStatus().createSavepoint();
        } catch (RuntimeException e) {
            log.warn("创建配额计数保存点失败，fail-open 将无法在污染事务内继续: {}", e.getMessage());
            return SAVEPOINT_UNAVAILABLE;
        }
    }

    /**
     * 回退计数保存点，恢复被 PG 标记为 aborted 的事务，使 fail-open 后调用方的 SQL 仍可执行。
     *
     * @return 是否已恢复到可用状态
     */
    private boolean rollbackToCountSavepoint(Object savepoint) {
        if (savepoint == null) {
            return true;
        }
        if (savepoint == SAVEPOINT_UNAVAILABLE) {
            return false;
        }
        try {
            TransactionAspectSupport.currentTransactionStatus().rollbackToSavepoint(savepoint);
            return true;
        } catch (RuntimeException e) {
            log.warn("回退配额计数保存点失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 退回一次对话计数（原子、幂等）。
     *
     * <p>仅用于「已扣费但执行未真正开始」的补偿场景。幂等键落在流水
     * {@code wgh_quota_charge.charge_id} 上：同一事务内先做流水状态迁移，再做
     * {@code chat_count > 0} 的条件减一；进程重启后仍能识别已退款/已结算，不会重复退。</p>
     *
     * @param charge 扣费凭据；为 null 表示本次未实际扣费
     * @return 退款结果，供调用方区分成功/重复/无可退/失败
     */
    public RefundResult refundChat(QuotaCharge charge) {
        if (charge == null || charge.chargeId() == null) {
            return RefundResult.NOTHING_TO_REFUND;
        }
        try {
            return chargeLedger.refund(charge);
        } catch (Exception e) {
            log.error("配额退款失败: username={}, periodKey={}, chargeId={}, reason={}",
                    charge.username(), charge.periodKey(), charge.chargeId(), e.getMessage());
            // 外层事务会整体回滚（普通聊天入口），无需单独登记；无事务（分享入口/补偿作业）才落 REFUND_FAILED
            if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                try {
                    Duration backoff = quotaProperties.getCharge().getRefundBackoff();
                    chargeLedger.markRefundFailed(charge.chargeId(),
                            LocalDateTime.now().plus(backoff == null ? Duration.ZERO : backoff), e.getMessage());
                } catch (Exception markError) {
                    // 登记失败也不丢：流水仍是 CHARGED，补偿作业会按「已扣未决」再次扫描
                    log.error("登记退款失败状态也失败，等待补偿作业兜底: chargeId={}, reason={}",
                            charge.chargeId(), markError.getMessage());
                }
            }
            return RefundResult.FAILED;
        }
    }

    /** 管理端：分页查询退款异常流水（REFUND_FAILED / DEAD）。 */
    public List<com.wgh.rag.quota.entity.QuotaChargeEntity> listRefundIssues(String status, String username,
                                                                             int offset, int limit) {
        return chargeLedger.findRefundIssues(status, username, offset, limit);
    }

    /** 管理端：退款异常流水总数。 */
    public long countRefundIssues(String status, String username) {
        return chargeLedger.countRefundIssues(status, username);
    }

    /** 管理端：按 chargeId 查看单笔流水的完整上下文（只读）。 */
    public com.wgh.rag.quota.entity.QuotaChargeEntity findCharge(String chargeId) {
        if (chargeId == null || chargeId.isBlank()) {
            return null;
        }
        return chargeLedger.findCharge(chargeId);
    }

    /**
     * 管理端人工退款：按该笔流水对应的一次计费退回计数，允许处理 DEAD。
     *
     * <p>仅允许 CHARGED / REFUND_FAILED / DEAD；SETTLED 表示已确认产出、REFUNDED 表示已退，均拒绝。</p>
     */
    public ManualChargeResult refundManually(String chargeId, String reason) {
        com.wgh.rag.quota.entity.QuotaChargeEntity row = requireCharge(chargeId);
        String previous = row.getStatus();
        if (QuotaConstants.CHARGE_REFUNDED.equals(previous)) {
            return new ManualChargeResult(chargeId, previous, previous, RefundResult.DUPLICATE.name(), false);
        }
        if (QuotaConstants.CHARGE_SETTLED.equals(previous)) {
            throw new BusinessException(ResultCode.OPERATION_NOT_ALLOWED, "该流水已结算生效，不在人工退款范围");
        }
        QuotaCharge charge = new QuotaCharge(row.getUsername(), row.getPeriodKey(), chargeId, row.getUsageEpoch());
        RefundResult result = chargeLedger.refundManually(charge);
        metrics.recordQuotaRefund("manual");
        log.warn("管理端人工退款: chargeId={}, username={}, periodKey={}, previousStatus={}, result={}, reason={}",
                chargeId, row.getUsername(), row.getPeriodKey(), previous, result, reason);
        return new ManualChargeResult(chargeId, previous, currentStatus(chargeId), result.name(),
                result == RefundResult.REFUNDED);
    }

    /**
     * 管理端人工核销：确认该笔扣费有效（不退款），把 CHARGED / REFUND_FAILED / DEAD 置为 SETTLED。
     */
    public ManualChargeResult writeOff(String chargeId, String reason) {
        com.wgh.rag.quota.entity.QuotaChargeEntity row = requireCharge(chargeId);
        String previous = row.getStatus();
        if (QuotaConstants.CHARGE_SETTLED.equals(previous)) {
            return new ManualChargeResult(chargeId, previous, previous, "DUPLICATE", false);
        }
        if (QuotaConstants.CHARGE_REFUNDED.equals(previous)) {
            throw new BusinessException(ResultCode.OPERATION_NOT_ALLOWED, "该流水已退款，不能核销");
        }
        boolean writtenOff = chargeLedger.writeOff(chargeId, reason);
        if (writtenOff) {
            metrics.recordQuotaWriteOff();
        }
        log.warn("管理端人工核销: chargeId={}, username={}, periodKey={}, previousStatus={}, success={}, reason={}",
                chargeId, row.getUsername(), row.getPeriodKey(), previous, writtenOff, reason);
        return new ManualChargeResult(chargeId, previous, currentStatus(chargeId),
                writtenOff ? "WRITTEN_OFF" : "NOTHING_TO_REFUND", writtenOff);
    }

    private com.wgh.rag.quota.entity.QuotaChargeEntity requireCharge(String chargeId) {
        com.wgh.rag.quota.entity.QuotaChargeEntity row = findCharge(chargeId);
        if (row == null) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "扣费流水不存在");
        }
        return row;
    }

    private String currentStatus(String chargeId) {
        com.wgh.rag.quota.entity.QuotaChargeEntity row = findCharge(chargeId);
        return row == null ? null : row.getStatus();
    }

    /**
     * 产出确认后结算：把扣费流水置为 SETTLED，扣费最终生效。
     *
     * <p>调用时机为「首个有效内容增量已下发」之后，且异步任务由提交闸门保证在外层事务提交后才开始，
     * 因此流水必然可见。此处重试只兜底瞬时数据库抖动，不再需要等待事务提交。</p>
     */
    public void markChargeSettled(QuotaCharge charge) {
        if (charge == null || charge.chargeId() == null) {
            return;
        }
        for (int attempt = 1; attempt <= SETTLE_ATTEMPTS; attempt++) {
            try {
                if (chargeLedger.markSettled(charge.chargeId())) {
                    return;
                }
                // 0 行更新：若流水已是 SETTLED（产出回调与收口回调重复结算、多实例并发结算），
                // 视为幂等成功，不进入重试与 ALERT，避免把正常重复调用误报成「结算失败」。
                com.wgh.rag.quota.entity.QuotaChargeEntity current = findCharge(charge.chargeId());
                if (current != null && QuotaConstants.CHARGE_SETTLED.equals(current.getStatus())) {
                    return;
                }
            } catch (Exception e) {
                log.warn("扣费流水置为已执行失败（第 {} 次）: chargeId={}, reason={}",
                        attempt, charge.chargeId(), e.getMessage());
            }
            if (attempt < SETTLE_ATTEMPTS) {
                try {
                    Thread.sleep(SETTLE_RETRY_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        log.error("[ALERT] 已下发产出但扣费流水未能置为已结算，可能被补偿作业误退: chargeId={}", charge.chargeId());
    }

    /** 回填扣费流水与会话的关联（失败只记日志，不影响主流程）。 */
    public void attachSession(QuotaCharge charge, Long sessionId) {
        if (charge == null || charge.chargeId() == null || sessionId == null) {
            return;
        }
        chargeLedger.attachSession(charge.chargeId(), sessionId);
    }

    /**
     * 回复完成后累加 token 用量（计入该用户生效周期的周期键）。失败只记日志不影响主流程。
     */
    public void addTokens(String username, long tokenDelta) {
        if (tokenDelta <= 0) return;
        try {
            EffectiveQuota quota = resolve(username);
            quotaUsageMapper.upsertIncrement(username, currentPeriodKey(quota.getPeriod()), 0, tokenDelta);
        } catch (Exception e) {
            log.warn("配额 token 累加失败: username={}, reason={}", username, e.getMessage());
        }
    }

    // ============ 用量查询 ============

    @Transactional(readOnly = true)
    public QuotaUsage usageOf(String username, String periodKey) {
        return quotaUsageMapper.selectOne(new LambdaQueryWrapper<QuotaUsage>()
                .eq(QuotaUsage::getUsername, username)
                .eq(QuotaUsage::getDateKey, periodKey)
                .last("LIMIT 1"));
    }

    /**
     * 当日用量，保留供既有统计接口使用
     */
    public QuotaUsage todayUsage(String username) {
        return usageOf(username, todayKey());
    }

    // ============ 管理端：配置 CRUD ============

    /**
     * 新建/更新某用户的自定义配额（按 username upsert）
     */
    public UserQuotaConfig saveUserConfig(UserQuotaConfig config) {
        if (config.getUsername() == null || config.getUsername().isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "用户名不能为空");
        }
        if (config.getPeriod() == null || config.getPeriod().isBlank()) {
            config.setPeriod(UserQuotaConfig.PERIOD_DAY);
        }
        String period = config.getPeriod().toUpperCase();
        if (!UserQuotaConfig.PERIOD_DAY.equals(period) && !UserQuotaConfig.PERIOD_MONTH.equals(period)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "周期仅支持 DAY / MONTH");
        }
        config.setPeriod(period);
        if (config.getChatLimit() != null && config.getChatLimit() < 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请求数上限不能为负数");
        }
        if (config.getTokenLimit() != null && config.getTokenLimit() < 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "token 上限不能为负数");
        }
        if (config.getEnabled() == null) {
            config.setEnabled(Boolean.TRUE);
        }

        UserQuotaConfig existing = findUserConfig(config.getUsername());
        if (existing != null) {
            config.setId(existing.getId());
            userQuotaConfigMapper.updateById(config);
        } else {
            // username 有唯一约束：若存在「已恢复默认」的逻辑删除行，则复活该行而不是插入新行
            UserQuotaConfig deleted = userQuotaConfigMapper.selectAnyByUsername(config.getUsername());
            if (deleted != null) {
                userQuotaConfigMapper.restoreDeleted(deleted.getId(), config.getPeriod(),
                        config.getChatLimit(), config.getTokenLimit(),
                        Boolean.TRUE.equals(config.getEnabled()) ? 1 : 0, config.getRemark());
            } else {
                userQuotaConfigMapper.insert(config);
            }
        }
        return findUserConfig(config.getUsername());
    }

    /**
     * 恢复全局默认：逻辑删除自定义配置
     *
     * @return true 表示存在并已删除自定义配置；false 表示本来就没有自定义配置
     */
    public boolean resetUserConfig(String username) {
        UserQuotaConfig existing = findUserConfig(username);
        if (existing == null) {
            return false;
        }
        userQuotaConfigMapper.deleteById(existing.getId());
        return true;
    }

    /**
     * 重置用量：清零该用户「生效周期」内的计数（不修改配置）
     *
     * @return 是否命中并清零了用量行
     */
    public boolean resetUsage(String username) {
        EffectiveQuota quota = resolve(username);
        String periodKey = currentPeriodKey(quota.getPeriod());
        return quotaUsageMapper.resetUsage(username, periodKey) > 0;
    }

    /**
     * 批量列出配额视图：自定义配置 ∪ 有用量记录的用户，按用户名过滤。
     * 注：分页在内存层做（用户量级小）；后续用户量大时可下沉 SQL。
     */
    public List<UserQuotaConfig> listAllUserConfigs() {
        return userQuotaConfigMapper.selectList(new LambdaQueryWrapper<UserQuotaConfig>()
                .orderByAsc(UserQuotaConfig::getUsername));
    }

    public List<String> listUsernamesWithUsage() {
        return quotaUsageMapper.selectObjs(new LambdaQueryWrapper<QuotaUsage>()
                        .select(QuotaUsage::getUsername)
                        .groupBy(QuotaUsage::getUsername))
                .stream().map(String::valueOf).toList();
    }

    // ============ 兼容旧接口 ============

    public Integer getChatDailyLimit() {
        return quotaProperties.getChatDailyLimit();
    }

    public Long getTokenDailyLimit() {
        return quotaProperties.getTokenDailyLimit();
    }
}

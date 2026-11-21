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
    private final QuotaProperties quotaProperties;
    private final RagMetrics metrics;

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    /** 异步线程确认「执行已开始」时的最大重试次数（等待外层扣费事务提交可见） */
    private static final int SETTLE_ATTEMPTS = 5;
    private static final long SETTLE_RETRY_MILLIS = 40L;

    /**
     * 一次已完成的对话扣费凭据；{@code chargeId} 同时是流水表 {@code wgh_quota_charge.charge_id}
     * 的持久化标识与退款幂等键。退款必须使用扣费时的周期键，避免跨周期退错行。
     */
    public record QuotaCharge(String username, String periodKey, String chargeId) {
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
    public EffectiveQuota resolve(String username) {
        UserQuotaConfig cfg = findUserConfig(username);
        if (cfg != null && Boolean.TRUE.equals(cfg.getEnabled())) {
            return EffectiveQuota.of(cfg);
        }
        return EffectiveQuota.ofDefault(quotaProperties.getChatDailyLimit(), quotaProperties.getTokenDailyLimit());
    }

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
        EffectiveQuota quota = resolve(username);
        String periodKey = currentPeriodKey(quota.getPeriod());
        String periodLabel = UserQuotaConfig.PERIOD_MONTH.equalsIgnoreCase(quota.getPeriod()) ? "本月" : "今日";

        QuotaUsage usage = usageOf(username, periodKey);
        if (usage != null
                && quota.getTokenLimit() != null && quota.getTokenLimit() > 0
                && usage.getTokenUsed() != null && usage.getTokenUsed() >= quota.getTokenLimit()) {
            metrics.recordQuotaExceeded(QuotaConstants.DIMENSION_TOKEN);
            throw new BusinessException(ResultCode.QUOTA_EXCEEDED,
                    periodLabel + " token 用量已达上限（" + quota.getTokenLimit() + "），请下期再试");
        }
        int chatLimit = quota.getChatLimit() == null ? 0 : quota.getChatLimit();
        boolean charged = false;
        try {
            boolean allowed = quotaUsageMapper.incrementChatAtomically(username, periodKey, chatLimit) > 0;
            if (!allowed) {
                metrics.recordQuotaExceeded(QuotaConstants.DIMENSION_CHAT);
                log.info("配额对话计数被拒绝: username={}, periodKey={}, limit={}", username, periodKey, chatLimit);
                throw new BusinessException(ResultCode.QUOTA_EXCEEDED,
                        periodLabel + "对话次数已达上限（" + chatLimit + " 条），请下期再试");
            }
            charged = true;
            log.debug("配额对话计数: username={}, periodKey={}, limit={}", username, periodKey, chatLimit);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // 计数失败按既有语义放行，不产生可退款凭据
            log.warn("配额对话计数失败: username={}, periodKey={}, reason={}", username, periodKey, e.getMessage());
        }
        if (!charged) {
            return null;
        }
        try {
            return chargeLedger.create(username, periodKey, windowId);
        } catch (Exception e) {
            log.error("扣费流水落库失败: username={}, periodKey={}, reason={}", username, periodKey, e.getMessage());
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                // 事务内计数与流水必须同生共死：上抛让外层整体回滚，避免「计数已加但无流水」
                throw e;
            }
            // 无事务（分享入口）：计数已自动提交，立即补偿减一，保持既有「配额故障不阻断聊天」语义
            try {
                quotaUsageMapper.decrementChatAtomically(username, periodKey);
            } catch (Exception compensateError) {
                log.error("扣费流水落库失败后的计数补偿也失败: username={}, periodKey={}, reason={}",
                        username, periodKey, compensateError.getMessage());
            }
            return null;
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

    /**
     * 异步执行已开始：把扣费流水置为 SETTLED，避免补偿作业把运行中的请求误退。
     *
     * <p>普通聊天入口中流水与扣费在同一个外层事务里，异步线程可能先于提交运行；
     * 这里做有界重试，等待外层事务提交后流水可见。</p>
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
        log.error("扣费流水未能置为已执行，可能被补偿作业按已扣未决退款: chargeId={}", charge.chargeId());
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

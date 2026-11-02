package com.wgh.rag.quota.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wgh.rag.common.exception.BusinessException;
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

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 配额限流：按用户 + 周期（日/月）限制聊天条数与 token 消耗。
 * <p>
 * 解析规则：用户自定义配置（wgh_user_quota_config 且启用）&gt; 全局默认
 * （wgh.rag.quota.chat-daily-limit / token-daily-limit，周期为自然日）。
 * <p>
 * 计数通过 INSERT ... ON CONFLICT 原子累加，并发下用量统计不丢失。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(QuotaProperties.class)
public class QuotaService {

    private final QuotaUsageMapper quotaUsageMapper;
    private final UserQuotaConfigMapper userQuotaConfigMapper;
    private final QuotaProperties quotaProperties;

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

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
     */
    public void checkAndIncrementChat(String username) {
        EffectiveQuota quota = resolve(username);
        String periodKey = currentPeriodKey(quota.getPeriod());
        String periodLabel = UserQuotaConfig.PERIOD_MONTH.equalsIgnoreCase(quota.getPeriod()) ? "本月" : "今日";

        QuotaUsage usage = usageOf(username, periodKey);
        if (usage != null) {
            if (quota.getChatLimit() != null && quota.getChatLimit() > 0
                    && usage.getChatCount() != null && usage.getChatCount() >= quota.getChatLimit()) {
                throw new BusinessException(ResultCode.QUOTA_EXCEEDED,
                        periodLabel + "对话次数已达上限（" + quota.getChatLimit() + " 条），请下期再试");
            }
            if (quota.getTokenLimit() != null && quota.getTokenLimit() > 0
                    && usage.getTokenUsed() != null && usage.getTokenUsed() >= quota.getTokenLimit()) {
                throw new BusinessException(ResultCode.QUOTA_EXCEEDED,
                        periodLabel + " token 用量已达上限（" + quota.getTokenLimit() + "），请下期再试");
            }
        }
        try {
            quotaUsageMapper.upsertIncrement(username, periodKey, 1, 0);
        } catch (Exception e) {
            log.warn("配额对话计数失败: username={}, reason={}", username, e.getMessage());
        }
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

package com.wgh.rag.common.quota;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wgh.rag.common.entity.QuotaUsage;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.mapper.QuotaUsageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 配额限流：按用户 + 自然日限制聊天条数与 token 消耗。
 * 配置键 wgh.rag.quota.chat-daily-limit / wgh.rag.quota.token-daily-limit。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuotaService {

    private final QuotaUsageMapper quotaUsageMapper;

    /**
     * 每人每天聊天条数上限（<=0 表示不限制）
     */
    @Value("${wgh.rag.quota.chat-daily-limit:200}")
    private Integer chatDailyLimit;

    /**
     * 每人每天 token 上限（<=0 表示不限制）
     */
    @Value("${wgh.rag.quota.token-daily-limit:2000000}")
    private Long tokenDailyLimit;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public String todayKey() {
        return LocalDate.now().format(DATE_FMT);
    }

    /**
     * 发送前检查并计数一次对话：超限抛 BusinessException；通过则当日 chat_count +1。
     */
    public void checkAndIncrementChat(String username) {
        QuotaUsage usage = todayUsage(username);
        if (usage != null) {
            if (chatDailyLimit != null && chatDailyLimit > 0
                    && usage.getChatCount() != null && usage.getChatCount() >= chatDailyLimit) {
                throw new BusinessException("今日对话次数已达上限（" + chatDailyLimit + " 条），请明日再试");
            }
            if (tokenDailyLimit != null && tokenDailyLimit > 0
                    && usage.getTokenUsed() != null && usage.getTokenUsed() >= tokenDailyLimit) {
                throw new BusinessException("今日 token 用量已达上限（" + tokenDailyLimit + "），请明日再试");
            }
        }
        try {
            quotaUsageMapper.upsertIncrement(username, todayKey(), 1, 0);
        } catch (Exception e) {
            log.warn("配额对话计数失败: username={}, reason={}", username, e.getMessage());
        }
    }

    /**
     * 回复完成后累加 token 用量。失败只记日志不影响主流程。
     */
    public void addTokens(String username, long tokenDelta) {
        if (tokenDelta <= 0) return;
        try {
            quotaUsageMapper.upsertIncrement(username, todayKey(), 0, tokenDelta);
        } catch (Exception e) {
            log.warn("配额 token 累加失败: username={}, reason={}", username, e.getMessage());
        }
    }

    public QuotaUsage todayUsage(String username) {
        return quotaUsageMapper.selectOne(new LambdaQueryWrapper<QuotaUsage>()
                .eq(QuotaUsage::getUsername, username)
                .eq(QuotaUsage::getDateKey, todayKey())
                .last("LIMIT 1"));
    }

    public Integer getChatDailyLimit() {
        return chatDailyLimit;
    }

    public Long getTokenDailyLimit() {
        return tokenDailyLimit;
    }
}

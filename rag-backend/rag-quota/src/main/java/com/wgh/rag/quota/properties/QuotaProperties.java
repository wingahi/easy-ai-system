package com.wgh.rag.quota.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 配额全局默认配置（用户无自定义配置时回退到这里）
 * <p>
 * 配置键沿用历史键位，保持行为兼容：
 * wgh.rag.quota.chat-daily-limit / wgh.rag.quota.token-daily-limit
 */
@Data
@ConfigurationProperties(prefix = "wgh.rag.quota")
public class QuotaProperties {

    /**
     * 每人每天聊天条数上限（<=0 不限制），默认 200
     */
    private Integer chatDailyLimit = 200;

    /**
     * 每人每天 token 上限（<=0 不限制），默认 200 万
     */
    private Long tokenDailyLimit = 2_000_000L;
}

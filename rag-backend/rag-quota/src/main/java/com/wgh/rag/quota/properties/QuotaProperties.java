package com.wgh.rag.quota.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 配额全局默认配置（用户无自定义配置时回退到这里）
 * <p>
 * 配置键沿用历史键位，保持行为兼容：
 * wgh.rag.quota.chat-daily-limit / wgh.rag.quota.token-daily-limit
 * <p>
 * 扣费流水补偿配置位于 {@code wgh.rag.quota.charge.*}，全部字段都有代码默认值。
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

    /**
     * 对话计数异常时的放行策略。
     * <p>{@code false}（默认）：计数失败直接拒绝本次对话，返回稳定的业务错误码
     * {@code QUOTA_SERVICE_UNAVAILABLE(5004)}，避免「免费聊天」；
     * {@code true}：仅打 ERROR 告警日志与指标后放行且不扣费，用于数据库短时抖动期间人工兜底，
     * 但会放出一批无扣费请求，应仅作为临时开关。</p>
     */
    private boolean failOpenOnCountError = false;

    /** 扣费流水与退款补偿参数。 */
    private Charge charge = new Charge();

    /** 扣费流水补偿参数（{@code wgh.rag.quota.charge}）。 */
    @Data
    public static class Charge {

        /** 是否启用退款补偿作业（关闭后不扫描流水，已产生的流水保持原状）。 */
        private boolean refundEnabled = true;

        /** 已扣未决保护期：扣费后超过该时长仍未确认执行才开始补偿，避免误退运行中的请求。 */
        private Duration refundProtection = Duration.ofMinutes(5);

        /** 补偿作业提交间隔（维护车道）。 */
        private Duration refundScanInterval = Duration.ofSeconds(60);

        /** 单条流水最大退款尝试次数（含首次），耗尽后置 DEAD 等人工处理。 */
        private int refundMaxAttempts = 5;

        /** 退款重试退避基数（按尝试次数指数递增）。 */
        private Duration refundBackoff = Duration.ofSeconds(60);

        /** 退款重试退避上限。 */
        private Duration refundBackoffMax = Duration.ofMinutes(30);

        /** 单次扫描最多处理的流水条数。 */
        private int refundBatchSize = 200;
    }
}

package com.wgh.rag.publicaccess.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 免登录分享 / Widget / 开放 API 配置（wgh.public-access.*）
 */
@Data
@Component
@ConfigurationProperties(prefix = "wgh.public-access")
public class PublicAccessProperties {

    /** 分享链接允许设置的最大有效天数（默认 30） */
    private int shareMaxExpireDays = 30;

    /** 创建分享链接时未指定有效期则默认有效天数（默认 7） */
    private int shareDefaultExpireDays = 7;

    /** 分享链接每分钟请求上限（按 token + IP，默认 20），rate-limit.overrides 未命中时生效 */
    private int shareRateLimitPerMinute = 20;

    /** 开放 API 每分钟请求上限（按 API Key，默认 60），rate-limit.overrides 未命中时生效 */
    private int apiRateLimitPerMinute = 60;

    /** 单次分享/开放 API 提问允许携带的图片数量上限（默认 4） */
    private int maxImagesPerRequest = 4;

    /** 限流通用配置 */
    private RateLimit rateLimit = new RateLimit();

    /** 分享密码失败锁定配置 */
    private SharePassword sharePassword = new SharePassword();

    /** Widget 嵌入配置 */
    private Embed embed = new Embed();

    /**
     * 可信反向代理 CIDR 列表。
     *
     * <p>只有直接连接的对端地址命中该列表时，才会采信 X-Forwarded-For / X-Real-IP；
     * 默认为空，即任何代理头都不会生效，避免客户端伪造 IP。</p>
     */
    private List<String> trustedProxies = new ArrayList<>();

    /**
     * 计算某个限流维度的每分钟上限。
     *
     * <p>优先级：{@code rate-limit.overrides[dimension:path]} &gt;
     * {@code rate-limit.overrides[dimension]} &gt; 旧版 share/api 配置 &gt; 默认值。</p>
     */
    public int limitFor(String dimension, String path) {
        Map<String, Integer> overrides = rateLimit.getOverrides();
        if (overrides != null && !overrides.isEmpty()) {
            Integer value = overrides.get(dimension + ":" + path);
            if (value == null) {
                value = overrides.get(dimension);
            }
            if (value != null && value > 0) {
                return value;
            }
        }
        if (dimension.startsWith("api")) {
            return apiRateLimitPerMinute;
        }
        if (dimension.startsWith("share")) {
            return shareRateLimitPerMinute;
        }
        return rateLimit.getDefaultPerMinute();
    }

    @Data
    public static class RateLimit {
        /** 未命中覆盖配置时的默认每分钟上限 */
        private int defaultPerMinute = 60;
        /** 按维度 / 维度:路径 覆盖，例如 share: 20、api:/api/open/v1/chat: 30 */
        private Map<String, Integer> overrides = new LinkedHashMap<>();
    }

    @Data
    public static class SharePassword {
        /** 连续密码错误达到该次数后锁定 */
        private int maxFailures = 5;
        /** 锁定时长（分钟），期间即使密码正确也拒绝 */
        private int lockMinutes = 15;
        /** 验证成功后是否清零失败计数 */
        private boolean resetOnSuccess = true;
    }

    @Data
    public static class Embed {
        /** Widget 嵌入授权 grant 的有效期（分钟） */
        private int grantTtlMinutes = 30;
    }
}

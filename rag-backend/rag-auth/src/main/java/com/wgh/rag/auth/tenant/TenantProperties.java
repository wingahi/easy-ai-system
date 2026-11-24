package com.wgh.rag.auth.tenant;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 多租户运行配置。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "wgh.tenant")
public class TenantProperties {

    /**
     * 是否强制校验租户：开启后，令牌/API Key 缺失租户、租户不存在或已停用一律拒绝。
     * <p>默认关闭以兼容尚未接入租户 claim 的存量 IdP；切换多租户上线前应显式开启。</p>
     */
    private boolean enforcement = false;
}

package com.wgh.rag.data.tenant;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 平台级全租户扫描配置（{@code wgh.platform-scan.*}）。
 *
 * <p>连接器同步、对账、续跑扫描、配额退款、各类清理等「按租户遍历」的维护作业共用这套口径：
 * 开关、租户并发度、单轮租户上限与单条语句超时。默认串行、单轮 200 个租户，避免维护作业
 * 长时间占用连接池。
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "wgh.platform-scan")
public class PlatformScanProperties {

    /** 平台级租户扫描总开关；关闭后维护作业只记日志、不遍历租户。 */
    private boolean enabled = true;

    /** 租户并发度；1 表示串行。并发执行时每个租户独立线程与租户上下文。 */
    private int tenantConcurrency = 1;

    /** 单轮最多处理的租户数；超出时截断并告警，下一轮继续。 */
    private int maxTenantsPerRound = 200;

    /** 租户只读扫描语句的 {@code statement_timeout}；{@code 0} 表示不设置。 */
    private Duration statementTimeout = Duration.ofSeconds(5);
}

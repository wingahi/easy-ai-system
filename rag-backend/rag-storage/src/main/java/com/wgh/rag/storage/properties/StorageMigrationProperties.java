package com.wgh.rag.storage.properties;

import com.wgh.rag.storage.constant.StorageConstants;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 存储迁移配置：{@code wgh.rag.storage.migration.*}。
 * <p>
 * 全部使用代码默认值，不改 application.yml；需要调整时用环境变量或启动参数覆盖。
 */
@Data
@Component
@ConfigurationProperties(prefix = StorageConstants.CONFIG_PREFIX + ".migration")
public class StorageMigrationProperties {

    /** 并发搬运对象数（1 ~ 32）。 */
    private int concurrency = 4;

    /** 单个对象最大尝试次数（含首次，1 ~ 10）。 */
    private int maxAttempts = 3;

    /** 重试退避基数（毫秒），按尝试次数线性递增并封顶 5 秒。 */
    private long retryBackoffMillis = 200L;

    /** 返回报告中保留的失败对象条数上限；完整失败仍逐条写日志。 */
    private int maxFailureEntries = 200;
}

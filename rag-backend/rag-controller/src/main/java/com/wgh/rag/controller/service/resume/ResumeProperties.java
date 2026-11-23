package com.wgh.rag.controller.service.resume;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 中断续跑扫描配置（{@code wgh.resume.*}）。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "wgh.resume")
public class ResumeProperties {

    /** 是否启用中断扫描与续跑投递。 */
    private boolean enabled = true;

    /** 最后活动时间超过该分钟数视为中断候选。 */
    private long staleMinutes = 30;

    /** 每轮最多投递的续跑作业数。 */
    private int batchSize = 20;

    /** 扫描间隔。 */
    private Duration scanInterval = Duration.ofMinutes(5);
}

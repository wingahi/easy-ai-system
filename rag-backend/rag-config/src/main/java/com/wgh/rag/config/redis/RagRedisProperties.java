package com.wgh.rag.config.redis;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Redis 连接配置。
 *
 * <p>所有配置集中在 {@code wgh.rag.redis.*}，与 Spring Boot 默认的
 * {@code spring.data.redis.*} 解耦，避免多环境下的隐式默认值。</p>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "wgh.rag.redis")
public class RagRedisProperties {

    /** 是否启用 Redis；关闭时公开访问模块自动使用单机内存降级实现 */
    private boolean enabled = true;

    /** Redis 主机 */
    private String host = "localhost";

    /** Redis 端口 */
    private int port = 6379;

    /** 数据库编号 */
    private int database = 0;

    /** 命令超时时间（连接超时同样使用该值），需足够短以避免 Redis 故障拖垮接口 */
    private Duration timeout = Duration.ofSeconds(2);

    /** 访问密码；为空表示无密码 */
    private String password;

    /** 公开访问模块统一 key 前缀 */
    private String keyPrefix = "wgh:public-access";
}

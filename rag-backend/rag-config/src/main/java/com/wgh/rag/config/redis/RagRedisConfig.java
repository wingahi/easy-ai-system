package com.wgh.rag.config.redis;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 基础设施装配。
 *
 * <p>注意：Lettuce 采用懒连接，Redis 不可达时不会阻塞应用启动；
 * 实际命令失败由 {@link RedisCommandPort} 的使用方捕获并降级。</p>
 */
@Configuration
public class RagRedisConfig {

    @Bean(destroyMethod = "destroy")
    @ConditionalOnProperty(prefix = "wgh.rag.redis", name = "enabled", havingValue = "true", matchIfMissing = true)
    public LettuceConnectionFactory redisConnectionFactory(RagRedisProperties properties) {
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(properties.getHost(), properties.getPort());
        standalone.setDatabase(properties.getDatabase());
        if (properties.getPassword() != null && !properties.getPassword().isBlank()) {
            standalone.setPassword(properties.getPassword());
        }
        SocketOptions socketOptions = SocketOptions.builder()
                .connectTimeout(properties.getTimeout())
                .build();
        ClientOptions clientOptions = ClientOptions.builder()
                .socketOptions(socketOptions)
                .autoReconnect(true)
                .build();
        LettuceClientConfiguration clientConfiguration = LettuceClientConfiguration.builder()
                .clientOptions(clientOptions)
                .commandTimeout(properties.getTimeout())
                .build();
        return new LettuceConnectionFactory(standalone, clientConfiguration);
    }

    @Bean
    @ConditionalOnProperty(prefix = "wgh.rag.redis", name = "enabled", havingValue = "true", matchIfMissing = true)
    public StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory redisConnectionFactory) {
        return new StringRedisTemplate(redisConnectionFactory);
    }

    @Bean
    @ConditionalOnProperty(prefix = "wgh.rag.redis", name = "enabled", havingValue = "true", matchIfMissing = true)
    public RedisCommandPort redisCommandPort(StringRedisTemplate stringRedisTemplate, RagRedisProperties properties) {
        return new SpringRedisCommandPort(stringRedisTemplate, properties.isEnabled());
    }

    @Bean
    @ConditionalOnProperty(prefix = "wgh.rag.redis", name = "enabled", havingValue = "false")
    public RedisCommandPort disabledRedisCommandPort() {
        return RedisCommandPort.disabled();
    }
}

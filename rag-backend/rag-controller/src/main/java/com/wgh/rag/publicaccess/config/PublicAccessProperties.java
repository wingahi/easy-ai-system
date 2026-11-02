package com.wgh.rag.publicaccess.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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

    /** 分享链接每分钟请求上限（按 token + IP，默认 20） */
    private int shareRateLimitPerMinute = 20;

    /** 开放 API 每分钟请求上限（按 API Key，默认 60） */
    private int apiRateLimitPerMinute = 60;

    /** 单次分享/开放 API 提问允许携带的图片数量上限（默认 4） */
    private int maxImagesPerRequest = 4;
}

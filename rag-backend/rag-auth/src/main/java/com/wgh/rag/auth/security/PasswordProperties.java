package com.wgh.rag.auth.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 密码强度规则（可通过 {@code wgh.auth.password.*} 覆盖）。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "wgh.auth.password")
public class PasswordProperties {

    /** 最小长度，默认 8 */
    private int minLength = 8;

    /** 最大长度，默认 128（避免超长输入拖慢 BCrypt） */
    private int maxLength = 128;

    /** 是否必须包含字母 */
    private boolean requireLetter = true;

    /** 是否必须包含数字 */
    private boolean requireDigit = true;
}

package com.wgh.rag.auth.security;

import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 密码强度校验（长度、字母、数字规则可配置）。
 */
@Component
@RequiredArgsConstructor
public class PasswordPolicy {

    private final PasswordProperties properties;

    /**
     * 校验密码强度，不符合抛 {@link ResultCode#PARAM_ERROR}
     */
    public void validate(String password) {
        if (!StringUtils.hasText(password)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "密码不能为空");
        }
        if (password.length() < properties.getMinLength()) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "密码长度至少 " + properties.getMinLength() + " 位");
        }
        if (password.length() > properties.getMaxLength()) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "密码长度不能超过 " + properties.getMaxLength() + " 位");
        }
        if (properties.isRequireLetter() && !password.matches(".*[A-Za-z].*")) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "密码必须包含字母");
        }
        if (properties.isRequireDigit() && !password.matches(".*\\d.*")) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "密码必须包含数字");
        }
    }
}

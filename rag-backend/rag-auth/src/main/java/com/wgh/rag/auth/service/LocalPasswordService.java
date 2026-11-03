package com.wgh.rag.auth.service;

import com.wgh.rag.auth.security.PasswordPolicy;
import com.wgh.rag.common.entity.WghUser;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.mapper.UserMapper;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.common.security.LoginUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 本地账号口令维护：本人改密（旧密码校验 + BCrypt 落库 + 清除首登改密标记）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocalPasswordService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;

    /**
     * 当前登录者修改本人密码
     */
    public void changeOwnPassword(String oldPassword, String newPassword) {
        LoginUser loginUser = LoginUser.get();
        if (loginUser == null || loginUser.getUserId() == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "未授权访问");
        }
        WghUser user = userMapper.selectById(loginUser.getUserId());
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "账号不存在，请重新登录");
        }
        if (!StringUtils.hasText(user.getPasswordHash())) {
            throw new BusinessException(ResultCode.OPERATION_NOT_ALLOWED,
                    "外部 OIDC 账号不支持本地改密");
        }
        if (!StringUtils.hasText(oldPassword) || !passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            log.warn("改密失败：旧密码不正确 username={}", user.getUsername());
            throw new BusinessException(ResultCode.PARAM_ERROR, "旧密码不正确");
        }
        passwordPolicy.validate(newPassword);
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "新密码不能与当前密码相同");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordMustChange(0);
        userMapper.updateById(user);
        log.info("本地账号改密成功 username={}", user.getUsername());
    }
}

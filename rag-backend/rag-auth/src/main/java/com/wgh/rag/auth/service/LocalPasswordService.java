package com.wgh.rag.auth.service;

import com.wgh.rag.auth.security.PasswordPolicy;
import com.wgh.rag.auth.entity.WghUser;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.auth.mapper.UserMapper;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.common.security.LoginUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 本地账号口令维护：本人改密、管理员重置体验账号口令。
 * <p>
 * 两条路径都只写入 BCrypt 密文；审计由 {@link AccountAuditService} 显式记录（不含口令明文）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocalPasswordService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final AccountAuditService accountAuditService;

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
            accountAuditService.failure(AccountAuditService.ACTION_PASSWORD_CHANGE, user.getUsername(),
                    "外部 OIDC 账号不支持本地改密");
            throw new BusinessException(ResultCode.OPERATION_NOT_ALLOWED,
                    "外部 OIDC 账号不支持本地改密");
        }
        if (!StringUtils.hasText(oldPassword) || !passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            log.warn("改密失败：旧密码不正确 username={}", user.getUsername());
            accountAuditService.failure(AccountAuditService.ACTION_PASSWORD_CHANGE, user.getUsername(),
                    "旧密码不正确");
            throw new BusinessException(ResultCode.PARAM_ERROR, "旧密码不正确");
        }
        passwordPolicy.validate(newPassword);
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "新密码不能与当前密码相同");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordMustChange(0);
        user.bumpPasswordVersion();
        userMapper.updateById(user);
        accountAuditService.success(AccountAuditService.ACTION_PASSWORD_CHANGE, user.getUsername());
        log.info("本地账号改密成功 username={}", user.getUsername());
    }

    /**
     * 管理员重置体验账号口令（目标须为 source=local、trial=1 且非 admin，由调用方保证）
     */
    public void resetTrialPassword(WghUser target, String newPassword) {
        if (target == null) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "用户不存在");
        }
        if (!LocalLoginService.SOURCE_LOCAL.equalsIgnoreCase(
                StringUtils.hasText(target.getSource()) ? target.getSource() : LocalLoginService.SOURCE_LOCAL)) {
            throw new BusinessException(ResultCode.OPERATION_NOT_ALLOWED,
                    "外部 OIDC 用户不允许重置本地口令");
        }
        if (target.getTrial() == null || target.getTrial() != 1) {
            throw new BusinessException(ResultCode.OPERATION_NOT_ALLOWED,
                    "仅体验账号允许重置口令");
        }
        passwordPolicy.validate(newPassword);

        target.setPasswordHash(passwordEncoder.encode(newPassword));
        target.setPasswordMustChange(1);
        target.bumpPasswordVersion();
        userMapper.updateById(target);
        accountAuditService.success(AccountAuditService.ACTION_PASSWORD_RESET, target.getUsername());
        log.info("管理员重置体验账号口令 username={}，下次登录需先改密", target.getUsername());
    }
}

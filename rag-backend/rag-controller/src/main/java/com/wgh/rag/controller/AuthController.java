package com.wgh.rag.controller;

import com.wgh.rag.auth.dto.LocalLoginRequest;
import com.wgh.rag.auth.dto.LocalLoginResult;
import com.wgh.rag.auth.dto.PasswordChangeRequest;
import com.wgh.rag.auth.dto.UserResult;
import com.wgh.rag.auth.service.LocalLoginService;
import com.wgh.rag.auth.service.LocalPasswordService;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.Result;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.common.security.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口：本地账号密码登录 + 当前用户信息。
 * <p>
 * 双认证并存：本地管理员/体验账号可用账号密码登录换取内部会话凭证；
 * 外部 OIDC 用户仍由资源服务器验签后进入。
 */
@Tag(name = "认证管理")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final LocalLoginService localLoginService;
    private final LocalPasswordService localPasswordService;

    @Operation(summary = "本地账号密码登录（仅本地管理员/体验账号）")
    @PostMapping("/login")
    public Result<LocalLoginResult> login(@RequestBody LocalLoginRequest request) {
        return Result.success(localLoginService.login(request.getUsername(), request.getPassword()));
    }

    @Operation(summary = "本人修改密码（需旧密码 + 新密码；成功后清除首登改密标记）")
    @PostMapping("/password")
    public Result<Void> changePassword(@RequestBody PasswordChangeRequest request) {
        localPasswordService.changeOwnPassword(request.getOldPassword(), request.getNewPassword());
        return Result.success(null);
    }

    @Operation(summary = "获取当前用户信息（含 roles + permissions）")
    @GetMapping("/me")
    public Result<UserResult> me() {
        LoginUser loginUser = LoginUser.get();
        if (loginUser == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "未授权访问");
        }
        return Result.success(new UserResult(
                loginUser.getUserId(),
                loginUser.getUsername(),
                loginUser.getRoles(),
                loginUser.getPermissions(),
                Boolean.TRUE.equals(loginUser.getPasswordMustChange())));
    }

    @Operation(summary = "登出（无服务端会话，客户端清除凭证即可）")
    @PostMapping("/logout")
    public Result<Void> logout() {
        return Result.success(null);
    }
}

package com.wgh.rag.controller;

import com.wgh.rag.auth.dto.UserResult;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.Result;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.common.security.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前用户信息接口。
 * <p>
 * 本系统不签发 token：前端/宿主系统携带外部 IdP 签发的 Bearer token，
 * 由资源服务器过滤器验签后，这里返回 token 解析出的角色与数据库映射后的权限。
 */
@Tag(name = "认证管理")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

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
                loginUser.getPermissions()));
    }

    @Operation(summary = "登出（无服务端会话，客户端清除外部 token 即可）")
    @PostMapping("/logout")
    public Result<Void> logout() {
        return Result.success(null);
    }
}

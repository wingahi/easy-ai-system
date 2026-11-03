package com.wgh.rag.controller;

import com.wgh.rag.auth.dto.UserResult;
import com.wgh.rag.auth.security.JwtUtils;
import com.wgh.rag.auth.service.AuthRBACService;
import com.wgh.rag.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 认证接口（RBAC + OIDC 统一入口）
 */
@Tag(name = "认证管理")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final JwtUtils jwtUtils;
    private final AuthRBACService authRBACService;

    @Operation(summary = "登录（用户名/密码）")
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody Map<String, String> params) {
        String username = params.get("username");
        String password = params.get("password");

        UserResult user = authRBACService.login(username, password);
        String token = jwtUtils.generateToken(user.getUserId(), user.getUsername(), user.getRoles());

        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("userId", user.getUserId());
        data.put("username", user.getUsername());
        data.put("roles", user.getRoles());
        data.put("permissions", user.getPermissions());
        data.put("expiresIn", 86400L);

        return Result.success(data);
    }

    @Operation(summary = "获取当前用户信息（含 roles + permissions）")
    @GetMapping("/me")
    public Result<UserResult> me(@RequestAttribute("userId") Long userId) {
        UserResult user = authRBACService.buildUserResult(userId);
        return Result.success(user);
    }

    @Operation(summary = "登出（清除本地会话）")
    @PostMapping("/logout")
    public Result<Void> logout() {
        // JWT 无状态：客户端删除 token 即可；服务端后续可接 Redis 黑名单
        return Result.success(null);
    }
}

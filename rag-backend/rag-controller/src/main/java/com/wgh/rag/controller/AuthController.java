package com.wgh.rag.controller;

import com.wgh.rag.common.result.Result;
import com.wgh.rag.common.security.JwtUtils;
import com.wgh.rag.common.security.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 认证接口
 */
@Tag(name = "认证管理")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final JwtUtils jwtUtils;

    @Operation(summary = "登录")
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody Map<String, String> params) {
        String username = params.get("username");
        String password = params.get("password");

        // TODO: 实际项目中应校验密码，这里简化处理（默认 admin/admin）
        if ("admin".equals(username) && "admin".equals(password)) {
            String token = jwtUtils.generateToken(1L, username);
            
            Map<String, Object> data = new HashMap<>();
            data.put("token", token);
            data.put("username", username);
            data.put("userId", 1L);
            data.put("expiresIn", 86400L);
            
            return Result.success(data);
        }
        
        return Result.fail("用户名或密码错误");
    }

    @Operation(summary = "获取当前用户信息")
    @GetMapping("/me")
    public Result<Map<String, Object>> me() {
        Map<String, Object> data = new HashMap<>();
        data.put("userId", 1L);
        data.put("username", "admin");
        data.put("roles", new String[]{"admin"});
        return Result.success(data);
    }
}

package com.wgh.rag.auth.dto;

import lombok.Data;

/**
 * 本地账号密码登录请求（仅本地管理员 / 体验账号）
 */
@Data
public class LocalLoginRequest {

    private String username;

    private String password;
}

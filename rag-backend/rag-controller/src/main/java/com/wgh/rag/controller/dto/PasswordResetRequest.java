package com.wgh.rag.controller.dto;

import lombok.Data;

/**
 * 管理员重置体验账号口令请求
 */
@Data
public class PasswordResetRequest {

    private String newPassword;
}

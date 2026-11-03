package com.wgh.rag.auth.dto;

import lombok.Data;

/**
 * 本人修改密码请求（需校验旧密码）
 */
@Data
public class PasswordChangeRequest {

    private String oldPassword;

    private String newPassword;
}

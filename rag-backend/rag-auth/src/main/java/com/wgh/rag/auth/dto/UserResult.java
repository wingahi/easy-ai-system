package com.wgh.rag.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * 登录 / 认证结果 DTO（返回给前端或内部 Service 使用）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserResult {

    /** 用户 ID（wgh_user.id） */
    private Long userId;

    /** 登录用户名 */
    private String username;

    /** 角色编码集合（如 admin / viewer） */
    private Set<String> roles;

    /** 权限编码集合（如 kb:write / model:read） */
    private Set<String> permissions;
}

package com.wgh.rag.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * 当前用户信息 DTO（由外部 token claims + 数据库角色/权限装配）
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

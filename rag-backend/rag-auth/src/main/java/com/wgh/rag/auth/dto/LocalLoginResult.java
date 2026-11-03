package com.wgh.rag.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 本地登录结果：内部会话凭证 + 当前用户角色/权限
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LocalLoginResult {

    /** 内部会话凭证（HS256），前端按 Bearer 方式携带 */
    private String token;

    /** 凭证类型标记：local */
    private String tokenType;

    /** 有效期（秒） */
    private long expiresIn;

    /** 是否必须先修改密码（true 时前端应跳转改密页，除改密/查询自身信息外的写接口会被拒绝） */
    private boolean passwordMustChange;

    /** 当前用户信息（角色/权限实时取自数据库） */
    private UserResult user;
}

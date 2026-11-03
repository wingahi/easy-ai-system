package com.wgh.rag.controller.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 体验账号创建/修改请求（管理员接口）
 */
@Data
public class AdminUserRequest {

    /** 创建时必填；修改时忽略（以路径参数为准） */
    private String username;

    /** 创建时必填，BCrypt 入库 */
    private String password;

    /** 角色编码，默认 viewer；禁止 admin */
    private String role;

    /** 启用状态 */
    private Boolean enabled;

    /** 备注 */
    private String remark;

    /** 有效期，null 表示长期 */
    private LocalDateTime expireAt;

    /** 配额：每期对话上限（<=0 不限制）；与 tokenLimit 任一非空即写入用户配额配置 */
    private Integer chatLimit;

    /** 配额：每期 token 上限（<=0 不限制） */
    private Long tokenLimit;

    /** 配额周期：DAY / MONTH，默认 DAY */
    private String period;

    /** 是否启用该用户的自定义配额（关闭回退全局默认） */
    private Boolean quotaEnabled;
}

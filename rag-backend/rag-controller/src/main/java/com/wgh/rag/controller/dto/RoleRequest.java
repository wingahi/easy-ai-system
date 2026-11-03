package com.wgh.rag.controller.dto;

import lombok.Data;

/**
 * 角色创建/修改请求（管理端）
 */
@Data
public class RoleRequest {

    /** 创建时必填：小写字母开头，可含小写字母/数字/下划线 */
    private String roleCode;

    /** 角色名称，非空 */
    private String roleName;

    /** 角色描述 */
    private String description;
}

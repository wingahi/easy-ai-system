package com.wgh.rag.controller.dto;

import lombok.Data;

import java.util.List;

/**
 * 角色权限整体覆盖请求（管理端）
 */
@Data
public class RolePermissionRequest {

    /** 权限码列表；传空数组表示清空该角色的权限 */
    private List<String> permCodes;
}

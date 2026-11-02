package com.wgh.rag.common.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 角色-权限 关联 Mapper（操作 wgh_role_permission 关系表）
 */
@Mapper
public interface RolePermissionMapper {

    @Insert("INSERT INTO wgh_role_permission(role_id, permission_id) VALUES(#{roleId}, #{permissionId})")
    int insert(@Param("roleId") Long roleId, @Param("permissionId") Long permissionId);

    @Delete("DELETE FROM wgh_role_permission WHERE role_id = #{roleId}")
    int deleteByRoleId(@Param("roleId") Long roleId);

    @Select("SELECT p.perm_code FROM wgh_permission p JOIN wgh_role_permission rp ON rp.permission_id = p.id WHERE rp.role_id = #{roleId}")
    List<String> selectPermCodesByRoleId(@Param("roleId") Long roleId);

    @Select("SELECT p.perm_code FROM wgh_permission p " +
            "JOIN wgh_role_permission rp ON rp.permission_id = p.id " +
            "JOIN wgh_user_role ur ON ur.role_id = rp.role_id " +
            "WHERE ur.user_id = #{userId}")
    List<String> selectPermCodesByUserId(@Param("userId") Long userId);
}

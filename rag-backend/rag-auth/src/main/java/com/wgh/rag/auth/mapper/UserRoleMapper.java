package com.wgh.rag.common.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 用户-角色 关联 Mapper（操作 wgh_user_role 关系表）
 */
@Mapper
public interface UserRoleMapper {

    @Insert("INSERT INTO wgh_user_role(user_id, role_id) VALUES(#{userId}, #{roleId})")
    int insert(@Param("userId") Long userId, @Param("roleId") Long roleId);

    @Delete("DELETE FROM wgh_user_role WHERE user_id = #{userId}")
    int deleteByUserId(@Param("userId") Long userId);

    @Select("SELECT r.role_code FROM wgh_role r JOIN wgh_user_role ur ON ur.role_id = r.id WHERE ur.user_id = #{userId}")
    List<String> selectRoleCodesByUserId(@Param("userId") Long userId);

    /** 统计仍绑定该角色的未删除用户数（删除角色前的占用检查） */
    @Select("SELECT COUNT(DISTINCT ur.user_id) FROM wgh_user_role ur "
            + "JOIN wgh_user u ON u.id = ur.user_id "
            + "WHERE ur.role_id = #{roleId} AND u.deleted = 0")
    long countUsersByRoleId(@Param("roleId") Long roleId);
}

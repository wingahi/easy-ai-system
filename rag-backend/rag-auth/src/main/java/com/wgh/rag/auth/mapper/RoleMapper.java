package com.wgh.rag.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wgh.rag.common.entity.WghRole;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 角色 Mapper
 * <p>
 * 角色管理相关方法使用显式列名，避免依赖不同环境可能缺失的基础审计列（create_by/update_by）。
 */
@Mapper
public interface RoleMapper extends BaseMapper<WghRole> {

    @Select("SELECT id, role_code, role_name, description FROM wgh_role WHERE deleted = 0 ORDER BY id")
    List<WghRole> selectAllActive();

    @Select("SELECT id, role_code, role_name, description FROM wgh_role "
            + "WHERE role_code = #{roleCode} AND deleted = 0 LIMIT 1")
    WghRole selectActiveByCode(@Param("roleCode") String roleCode);

    @Insert("INSERT INTO wgh_role(role_code, role_name, description) "
            + "VALUES(#{roleCode}, #{roleName}, #{description})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertRole(WghRole role);

    @Update("UPDATE wgh_role SET role_name = #{roleName}, description = #{description} "
            + "WHERE id = #{id} AND deleted = 0")
    int updateRoleInfo(WghRole role);

    @Delete("DELETE FROM wgh_role WHERE id = #{id}")
    int deleteRoleById(@Param("id") Long id);
}

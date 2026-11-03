package com.wgh.rag.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wgh.rag.common.entity.WghPermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 权限 Mapper
 */
@Mapper
public interface PermissionMapper extends BaseMapper<WghPermission> {

    /** 全量权限项（按模块、权限码排序），仅取管理端需要的列 */
    @Select("SELECT id, perm_code, perm_name, resource_type FROM wgh_permission "
            + "ORDER BY resource_type, perm_code")
    List<WghPermission> selectAllOrdered();

    /** 按权限码批量解析权限项（用于校验"权限码必须来自已知集合"） */
    @Select("<script>" +
            "SELECT id, perm_code FROM wgh_permission WHERE perm_code IN " +
            "<foreach collection='codes' item='code' open='(' separator=',' close=')'>#{code}</foreach>" +
            "</script>")
    List<WghPermission> selectByCodes(@Param("codes") Collection<String> codes);
}

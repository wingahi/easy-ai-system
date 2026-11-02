package com.wgh.rag.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wgh.rag.common.entity.WghPermission;
import org.apache.ibatis.annotations.Mapper;

/**
 * 权限 Mapper
 */
@Mapper
public interface PermissionMapper extends BaseMapper<WghPermission> {
}

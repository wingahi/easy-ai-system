package com.wgh.rag.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wgh.rag.auth.entity.WghTenant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 租户 Mapper
 */
@Mapper
public interface TenantMapper extends BaseMapper<WghTenant> {

    /**
     * 按编码查询（含已逻辑下线的租户）。
     *
     * <p>登录/令牌解析必须能看到「已下线」的租户，否则在非严格模式下会回落到默认租户，
     * 把下线租户的凭证错误地映射到 default。
     */
    @Select("SELECT * FROM wgh_tenant WHERE code = #{code} LIMIT 1")
    WghTenant selectByCodeIncludeDeleted(@Param("code") String code);

    /**
     * 查询全部租户（含已逻辑下线的），用于用户列表展示历史归属名称。
     */
    @Select("SELECT * FROM wgh_tenant ORDER BY id ASC")
    List<WghTenant> selectAllIncludingDeleted();
}

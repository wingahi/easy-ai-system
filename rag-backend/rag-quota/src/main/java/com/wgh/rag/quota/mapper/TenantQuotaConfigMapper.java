package com.wgh.rag.quota.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wgh.rag.quota.entity.TenantQuotaConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * 租户级配额配置 Mapper
 */
@Mapper
public interface TenantQuotaConfigMapper extends BaseMapper<TenantQuotaConfig> {
}

package com.wgh.rag.auth.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wgh.rag.data.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 租户实体（一个租户 = 一个企业/组织/法律实体）。
 * <p>
 * {@code code} 是全局唯一的租户编码，也是业务表 {@code tenant_id} 的取值；
 * {@code isolation_mode} 预留给阶段 4（shared / schema / database）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wgh_tenant")
public class WghTenant extends BaseEntity {

    /** 租户编码（全局唯一，业务表 tenant_id 存该值） */
    private String code;

    /** 租户名称 */
    private String name;

    /** 状态：1-启用，0-停用（停用后其令牌与 API Key 一律拒绝） */
    private Integer status;

    /** 套餐：standard / enterprise 等，当前仅作标识 */
    private String plan;

    /** 隔离模式：shared（默认）/ schema / database（阶段 4 预留） */
    private String isolationMode;
}

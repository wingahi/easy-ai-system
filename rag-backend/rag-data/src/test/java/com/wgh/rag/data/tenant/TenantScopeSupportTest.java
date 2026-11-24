package com.wgh.rag.data.tenant;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.wgh.rag.common.security.TenantContext;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证平台共享资源（Prompt/Tool）的显式可见范围条件。
 */
class TenantScopeSupportTest {

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Row.class);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void appendsTenantOrPlatformCondition() {
        TenantContext.set("tenant-b", 1L, "bob");
        LambdaQueryWrapper<Row> wrapper = new LambdaQueryWrapper<>();

        TenantScopeSupport.applyVisibleScope(wrapper, Row::getTenantId, Row::getScope);

        String sql = wrapper.getSqlSegment();
        assertThat(sql).contains("tenant_id").contains("scope").containsIgnoringCase("or");
    }

    @Test
    void leavesWrapperUntouchedWithoutContextButUsesDefaultTenant() {
        LambdaQueryWrapper<Row> wrapper = new LambdaQueryWrapper<>();

        TenantScopeSupport.applyVisibleScope(wrapper, Row::getTenantId, Row::getScope);

        assertThat(wrapper.getSqlSegment()).contains("tenant_id");
        assertThat(wrapper.getParamNameValuePairs()).containsValue(TenantContext.DEFAULT_TENANT);
    }

    @com.baomidou.mybatisplus.annotation.TableName("t_tenant_scope_test")
    static class Row {
        @com.baomidou.mybatisplus.annotation.TableField("tenant_id")
        private String tenantId;
        @com.baomidou.mybatisplus.annotation.TableField("scope")
        private String scope;

        public String getTenantId() {
            return tenantId;
        }

        public String getScope() {
            return scope;
        }
    }
}

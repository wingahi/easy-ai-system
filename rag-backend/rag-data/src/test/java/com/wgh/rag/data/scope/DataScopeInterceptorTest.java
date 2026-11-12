package com.wgh.rag.data.scope;

import com.wgh.rag.common.constant.RoleCodes;
import com.wgh.rag.common.security.LoginUser;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 数据权限拦截器测试：只重写显式开启范围内的 5 张资源表单表查询。
 */
class DataScopeInterceptorTest {

    private final DataScopeInterceptor interceptor = new DataScopeInterceptor();

    @AfterEach
    void tearDown() {
        LoginUser.clear();
        DataScopeContext.clear();
    }

    @Test
    void addsOwnerOrSharedPredicateForScopedTable() {
        loginAs("user-a");

        BoundSql boundSql = rewriteInScope("SELECT id, name FROM wgh_knowledge_base WHERE deleted = 0");

        assertThat(boundSql.getSql()).contains("create_by = 'user-a'");
        assertThat(boundSql.getSql()).contains("visibility <> 'PRIVATE'");
    }

    @Test
    void keepsExistingWhereGroupedWhenAppending() {
        loginAs("user-a");

        BoundSql boundSql = rewriteInScope(
                "SELECT id FROM wgh_chat_window WHERE deleted = 0 AND (name LIKE '%a%' OR description LIKE '%a%')");

        assertThat(boundSql.getSql()).contains("(name LIKE '%a%' OR description LIKE '%a%')");
        assertThat(boundSql.getSql()).contains("create_by = 'user-a'");
    }

    @Test
    void doesNotRewriteWhenScopeNotActivated() throws Exception {
        loginAs("user-a");
        String sql = "SELECT id FROM wgh_knowledge_base WHERE deleted = 0";
        BoundSql boundSql = boundSql(sql);

        interceptor.beforeQuery(null, null, null, null, null, boundSql);

        assertThat(boundSql.getSql()).isEqualTo(sql);
    }

    @Test
    void adminBypassesDataScope() {
        loginAs("admin", RoleCodes.ADMIN);

        BoundSql boundSql = rewriteInScope("SELECT id FROM wgh_workflow WHERE deleted = 0");

        assertThat(boundSql.getSql()).doesNotContain("create_by");
        assertThat(DataScopeContext.isActive()).isFalse();
    }

    @Test
    void ignoresTablesOutsideScope() {
        loginAs("user-a");

        BoundSql boundSql = rewriteInScope("SELECT id FROM wgh_chat_message WHERE deleted = 0");

        assertThat(boundSql.getSql()).isEqualTo("SELECT id FROM wgh_chat_message WHERE deleted = 0");
    }

    @Test
    void supportsAliasedTable() {
        loginAs("user-a");

        BoundSql boundSql = rewriteInScope("SELECT w.id FROM wgh_workflow w WHERE w.status = 1");

        assertThat(boundSql.getSql()).contains("create_by = 'user-a'");
        assertThat(boundSql.getSql()).contains("visibility <> 'PRIVATE'");
    }

    @Test
    void rejectsJoinOnScopedTable() {
        loginAs("user-a");

        assertThatThrownBy(() -> rewriteInScope(
                "SELECT k.id FROM wgh_knowledge_base k JOIN wgh_tag t ON t.id = k.id"))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(java.sql.SQLException.class);
    }

    @Test
    void ignoresNonSelectStatements() {
        loginAs("user-a");

        BoundSql boundSql = rewriteInScope("UPDATE wgh_knowledge_base SET name = 'x' WHERE id = 1");

        assertThat(boundSql.getSql()).isEqualTo("UPDATE wgh_knowledge_base SET name = 'x' WHERE id = 1");
    }

    @Test
    void appliesPredicateOnlyOncePerBoundSql() throws Exception {
        loginAs("user-a");
        BoundSql boundSql = boundSql("SELECT id FROM wgh_tool WHERE deleted = 0");

        DataScopeContext.runWithScope(() -> {
            try {
                interceptor.beforeQuery(null, null, null, null, null, boundSql);
                interceptor.beforeQuery(null, null, null, null, null, boundSql);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return null;
        });

        assertThat(countOccurrences(boundSql.getSql(), "visibility <> 'PRIVATE'")).isEqualTo(1);
    }

    private BoundSql rewriteInScope(String sql) {
        BoundSql boundSql = boundSql(sql);
        DataScopeContext.runWithScope(() -> {
            try {
                interceptor.beforeQuery(null, null, null, null, null, boundSql);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
        return boundSql;
    }

    private BoundSql boundSql(String sql) {
        return new BoundSql(new Configuration(), sql, new ArrayList<>(), null);
    }

    private void loginAs(String username, String... roles) {
        LoginUser user = new LoginUser();
        user.setUsername(username);
        Set<String> roleSet = new HashSet<>();
        roleSet.addAll(java.util.Arrays.asList(roles));
        user.setRoles(roleSet);
        LoginUser.set(user);
    }

    private int countOccurrences(String text, String token) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }
}

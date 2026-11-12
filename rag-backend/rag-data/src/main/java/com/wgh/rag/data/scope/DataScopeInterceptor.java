package com.wgh.rag.data.scope;

import com.baomidou.mybatisplus.core.toolkit.PluginUtils;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.NotEqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.sql.SQLException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 数据权限拦截器：在 {@link DataScopeContext} 显式激活期间，为 5 张资源表的单表查询追加
 * {@code (create_by = '当前用户' OR visibility <> 'PRIVATE')}。
 * <p>
 * 安全边界：
 * <ul>
 *   <li>未激活（内部按 id 取数、调度任务、分享链路、异步线程）→ 不改写 SQL</li>
 *   <li>ADMIN / 无登录上下文 → 不改写 SQL</li>
 *   <li>只处理单表 PlainSelect；JOIN / UNION / CTE 命中白名单表时抛错（失败关闭），不静默放行</li>
 * </ul>
 */
@Slf4j
public class DataScopeInterceptor implements InnerInterceptor {

    /** 防止同一条 BoundSql 被重复追加条件 */
    private static final String APPLIED_KEY = "__wgh_data_scope_applied";

    private static final Pattern[] SCOPED_TABLE_PATTERNS = DataScopeContext.SCOPED_TABLES.stream()
            .map(t -> Pattern.compile("\\b" + Pattern.quote(t) + "\\b", Pattern.CASE_INSENSITIVE))
            .toArray(Pattern[]::new);

    @Override
    public void beforeQuery(Executor executor, MappedStatement ms, Object parameter,
                            RowBounds rowBounds, ResultHandler resultHandler,
                            BoundSql boundSql) throws SQLException {
        DataScopeContext.Scope scope = DataScopeContext.current();
        if (scope == null || boundSql == null || boundSql.getSql() == null || boundSql.getSql().isBlank()) {
            return;
        }
        if (boundSql.hasAdditionalParameter(APPLIED_KEY)) {
            return;
        }

        String sql = boundSql.getSql();
        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(sql);
        } catch (JSQLParserException e) {
            throw new SQLException("数据权限过滤解析失败: " + e.getMessage(), e);
        }
        if (!(statement instanceof Select select)) {
            return;
        }

        if ((select.getWithItemsList() != null && !select.getWithItemsList().isEmpty())
                || !(select.getSelectBody() instanceof PlainSelect plainSelect)) {
            assertNoScopedTable(sql);
            return;
        }

        if (!(plainSelect.getFromItem() instanceof Table table)) {
            return;
        }
        if (!DataScopeContext.SCOPED_TABLES.contains(simpleName(table.getName()))) {
            return;
        }
        if (plainSelect.getJoins() != null && !plainSelect.getJoins().isEmpty()) {
            throw new SQLException("数据权限过滤不支持带 JOIN 的资源表查询: " + sql);
        }

        Expression predicate = new Parenthesis(new OrExpression(
                new EqualsTo(new Column("create_by"), new StringValue(scope.username())),
                new NotEqualsTo(new Column("visibility"),
                        new StringValue(ResourceVisibility.PRIVATE.name()))));
        Expression where = plainSelect.getWhere();
        plainSelect.setWhere(where == null
                ? predicate
                : new AndExpression(new Parenthesis(where), predicate));

        PluginUtils.mpBoundSql(boundSql).sql(select.toString());
        boundSql.setAdditionalParameter(APPLIED_KEY, Boolean.TRUE);
    }

    /** 无法安全改写的语句若命中白名单表，直接失败关闭，避免越权读取。 */
    private void assertNoScopedTable(String sql) throws SQLException {
        for (Pattern pattern : SCOPED_TABLE_PATTERNS) {
            if (pattern.matcher(sql).find()) {
                throw new SQLException("数据权限过滤不支持该查询形态（UNION/CTE 等）: " + sql);
            }
        }
    }

    private static String simpleName(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            name = name.substring(dot + 1);
        }
        return name.replace("\"", "").toLowerCase(Locale.ROOT);
    }
}

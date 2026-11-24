package com.wgh.rag.data.tenant;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;

/**
 * 租户目录：平台级扫描的租户枚举来源。
 *
 * <p>直接读 {@code wgh_tenant}（平台表，无租户 RLS），不使用 MyBatis 租户插件，因此不受
 * 线程租户上下文影响。两种口径：
 * <ul>
 *   <li>{@link #listActive()}：{@code status=1} 且未逻辑删除，用于业务数据扫描；</li>
 *   <li>{@link #listAll()}：未逻辑删除的全部租户（含停用），用于对账与存储清理——
 *       停用租户的历史数据仍需被核对与回收。</li>
 * </ul>
 *
 * <p>结果缓存 60 秒：维护作业周期远大于该值，新增租户最迟一个周期内可见；
 * 需要立即生效时调用 {@link #invalidate()}。
 */
@Slf4j
@Component
public class TenantDirectory {

    private static final long CACHE_TTL_MILLIS = 60_000L;

    private static final String SQL_ALL =
            "SELECT code FROM wgh_tenant WHERE deleted = 0 ORDER BY id";

    private static final String SQL_ACTIVE =
            "SELECT code FROM wgh_tenant WHERE deleted = 0 AND status = 1 ORDER BY id";

    private final JdbcTemplate jdbc;

    private volatile Snapshot snapshot;

    public TenantDirectory(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** 启用中的租户（业务数据扫描口径）。 */
    public List<String> listActive() {
        return snapshot().active();
    }

    /** 未逻辑删除的全部租户，含停用（对账与清理口径）。 */
    public List<String> listAll() {
        return snapshot().all();
    }

    /** 清空缓存，下一次读取重新落库查询。 */
    public void invalidate() {
        snapshot = null;
    }

    private Snapshot snapshot() {
        Snapshot current = snapshot;
        long now = System.currentTimeMillis();
        if (current != null && now < current.expiresAt()) {
            return current;
        }
        synchronized (this) {
            current = snapshot;
            if (current != null && System.currentTimeMillis() < current.expiresAt()) {
                return current;
            }
            Snapshot loaded = load();
            snapshot = loaded;
            return loaded;
        }
    }

    private Snapshot load() {
        List<String> all = jdbc.queryForList(SQL_ALL, String.class);
        List<String> active = jdbc.queryForList(SQL_ACTIVE, String.class);
        log.debug("租户目录已刷新: all={} active={}", all.size(), active.size());
        return new Snapshot(List.copyOf(all), List.copyOf(active),
                System.currentTimeMillis() + CACHE_TTL_MILLIS);
    }

    private record Snapshot(List<String> all, List<String> active, long expiresAt) {
    }
}

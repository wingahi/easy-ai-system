package com.wgh.rag.quota.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wgh.rag.quota.entity.QuotaUsage;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.springframework.dao.DuplicateKeyException;

/**
 * 配额用量 Mapper
 */
@Mapper
public interface QuotaUsageMapper extends BaseMapper<QuotaUsage> {

    /**
     * 累加当期用量（原子 UPDATE，已存在行并发安全）
     */
    @Update("""
            UPDATE wgh_quota_usage
            SET chat_count = chat_count + #{chatDelta},
                token_used = token_used + #{tokenDelta},
                update_time = CURRENT_TIMESTAMP
            WHERE username = #{username} AND date_key = #{dateKey}
            """)
    int incrementExisting(@Param("username") String username, @Param("dateKey") String dateKey,
                          @Param("chatDelta") int chatDelta, @Param("tokenDelta") long tokenDelta);

    @Insert("""
            INSERT INTO wgh_quota_usage (username, date_key, chat_count, token_used)
            VALUES (#{username}, #{dateKey}, #{chatDelta}, #{tokenDelta})
            """)
    int insertRow(@Param("username") String username, @Param("dateKey") String dateKey,
                  @Param("chatDelta") int chatDelta, @Param("tokenDelta") long tokenDelta);

    /**
     * 带配额上限的条件自增：只有当前用量小于上限（或未设置上限）时才加一。
     * <p>条件与自增在同一条 UPDATE 内完成，依赖数据库行锁保证多实例并发下的原子性，
     * 消除「先查用量再累加」的超发窗口。返回 0 表示已超限或行不存在。</p>
     */
    @Update("""
            UPDATE wgh_quota_usage
               SET chat_count = chat_count + 1,
                   update_time = CURRENT_TIMESTAMP
             WHERE username = #{username}
               AND date_key = #{dateKey}
               AND chat_count < #{chatLimit}
            """)
    int incrementChatWithinLimit(@Param("username") String username, @Param("dateKey") String dateKey,
                                 @Param("chatLimit") int chatLimit);

    /** 判断当期用量行是否存在（用于区分「已超限」与「首次计数」） */
    @Select("""
            SELECT COUNT(1) FROM wgh_quota_usage
             WHERE username = #{username} AND date_key = #{dateKey}
            """)
    int countExisting(@Param("username") String username, @Param("dateKey") String dateKey);

    /**
     * 原子「检查配额并计数」。
     *
     * <p>UPDATE 先行：行存在时由带 limit 条件的 UPDATE 原子判定；
     * 行不存在时插入首行（首行计数 1 必然在 limit&gt;=1 内），并发首插冲突方
     * 回退到条件 UPDATE。返回 1 表示本次已计数，0 表示配额已满。</p>
     */
    default int incrementChatAtomically(String username, String dateKey, int chatLimit) {
        if (chatLimit <= 0) {
            // 未设置上限：沿用原子 upsert（UPDATE 先行 + 并发首插回退）
            upsertIncrement(username, dateKey, 1, 0);
            return 1;
        }
        int updated = incrementChatWithinLimit(username, dateKey, chatLimit);
        if (updated > 0) {
            return 1;
        }
        if (countExisting(username, dateKey) > 0) {
            return 0;
        }
        try {
            insertRow(username, dateKey, 1, 0);
            return 1;
        } catch (DuplicateKeyException e) {
            return incrementChatWithinLimit(username, dateKey, chatLimit) > 0 ? 1 : 0;
        }
    }

    /**
     * 累加当期用量（不存在则插入）。
     * <p>
     * UPDATE 先行保证单行原子累加；并发首插时依赖 (username, date_key) 唯一约束，
     * 冲突方回退 UPDATE，整体保证并发下计数不丢失。
     * 该实现不依赖 PostgreSQL ON CONFLICT 方言，H2/PostgreSQL 均可运行。
     */
    default int upsertIncrement(String username, String dateKey, int chatDelta, long tokenDelta) {
        int updated = incrementExisting(username, dateKey, chatDelta, tokenDelta);
        if (updated > 0) {
            return updated;
        }
        try {
            return insertRow(username, dateKey, chatDelta, tokenDelta);
        } catch (DuplicateKeyException e) {
            // 并发首插：另一个事务已插入该行，回退为原子累加
            return incrementExisting(username, dateKey, chatDelta, tokenDelta);
        }
    }

    /**
     * 清零某用户当期用量（管理端「重置用量」）
     */
    @Update("""
            UPDATE wgh_quota_usage
               SET chat_count = 0,
                   token_used = 0,
                   update_time = CURRENT_TIMESTAMP
             WHERE username = #{username}
               AND date_key = #{dateKey}
               AND deleted = 0
            """)
    int resetUsage(@Param("username") String username, @Param("dateKey") String dateKey);
}

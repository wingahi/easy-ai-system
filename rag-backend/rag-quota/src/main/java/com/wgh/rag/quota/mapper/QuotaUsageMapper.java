package com.wgh.rag.quota.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wgh.rag.quota.entity.QuotaUsage;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
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

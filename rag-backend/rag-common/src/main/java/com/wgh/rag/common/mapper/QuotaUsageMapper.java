package com.wgh.rag.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wgh.rag.common.entity.QuotaUsage;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 配额用量 Mapper
 */
@Mapper
public interface QuotaUsageMapper extends BaseMapper<QuotaUsage> {

    /**
     * 累加当日用量（不存在则插入），chatDelta 通常为 1
     */
    @Insert("""
            INSERT INTO wgh_quota_usage (username, date_key, chat_count, token_used)
            VALUES (#{username}, #{dateKey}, #{chatDelta}, #{tokenDelta})
            ON CONFLICT (username, date_key)
            DO UPDATE SET chat_count = wgh_quota_usage.chat_count + #{chatDelta},
                          token_used = wgh_quota_usage.token_used + #{tokenDelta},
                          update_time = CURRENT_TIMESTAMP
            """)
    int upsertIncrement(@Param("username") String username, @Param("dateKey") String dateKey,
                        @Param("chatDelta") int chatDelta, @Param("tokenDelta") long tokenDelta);
}

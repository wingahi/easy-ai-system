package com.wgh.rag.quota.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wgh.rag.quota.entity.UserQuotaConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 按用户配额配置 Mapper
 */
@Mapper
public interface UserQuotaConfigMapper extends BaseMapper<UserQuotaConfig> {

    /**
     * 查询某用户的配置（包含已逻辑删除记录）。
     * 用于「恢复默认」后再次保存时复用同一行，避免 username 唯一键冲突。
     */
    @Select("SELECT * FROM wgh_user_quota_config WHERE username = #{username} ORDER BY id DESC LIMIT 1")
    UserQuotaConfig selectAnyByUsername(@Param("username") String username);

    /**
     * 复活并覆盖已逻辑删除的配置行（enabled 用 0/1，避免 PostgreSQL smallint 类型不匹配）
     */
    @Update("""
            UPDATE wgh_user_quota_config
               SET deleted = 0,
                   period = #{period},
                   chat_limit = #{chatLimit},
                   token_limit = #{tokenLimit},
                   enabled = #{enabled},
                   remark = #{remark},
                   update_time = CURRENT_TIMESTAMP
             WHERE id = #{id}
            """)
    int restoreDeleted(@Param("id") Long id,
                       @Param("period") String period,
                       @Param("chatLimit") Integer chatLimit,
                       @Param("tokenLimit") Long tokenLimit,
                       @Param("enabled") Integer enabled,
                       @Param("remark") String remark);
}

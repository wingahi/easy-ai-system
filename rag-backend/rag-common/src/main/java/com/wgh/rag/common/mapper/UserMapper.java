package com.wgh.rag.common.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wgh.rag.common.entity.WghUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 用户 Mapper
 */
@Mapper
public interface UserMapper extends BaseMapper<WghUser> {

    /** 更新密码哈希（用于存量明文密码一次性迁移，避免整体覆盖其他字段） */
    @Update("UPDATE wgh_user SET password_hash = #{passwordHash}, update_time = CURRENT_TIMESTAMP WHERE id = #{id}")
    int updatePasswordHash(@Param("id") Long id, @Param("passwordHash") String passwordHash);
}

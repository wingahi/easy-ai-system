package com.wgh.rag.publicaccess.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wgh.rag.common.entity.BaseEntity;
import com.wgh.rag.common.handler.BooleanToSmallintTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 免登录分享链接
 * <p>
 * scope：
 * WINDOW  —— 访问某个聊天窗口，可匿名提问（访客各自独立会话）
 * SESSION —— 只读查看某个已存在会话的聊天记录
 * KB      —— 只读检索某个知识库
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wgh_share_link")
public class ShareLink extends BaseEntity {

    /** 链接 token（对外暴露，随机生成） */
    private String token;

    /** 链接名称（运营备注） */
    private String name;

    /** 访问范围：WINDOW / SESSION / KB */
    private String scope;

    /** 范围目标 ID：WINDOW/SESSION 对应 chat_window_id 或 session_id，KB 对应 knowledge_base_id */
    private Long targetId;

    /** 有效期截止时间（为空表示永久有效，但仍受最大有效天数约束） */
    private LocalDateTime expiresAt;

    /** 访问密码 BCrypt 哈希（为空表示免密） */
    private String passwordHash;

    /** 最大使用次数（0 或空表示不限） */
    private Integer maxUses;

    /** 已使用次数（按对话请求计） */
    private Integer useCount;

    /** 是否已吊销 */
    @TableField(typeHandler = BooleanToSmallintTypeHandler.class)
    private Boolean revoked;

    /** 创建人 */
    private String createdBy;
}

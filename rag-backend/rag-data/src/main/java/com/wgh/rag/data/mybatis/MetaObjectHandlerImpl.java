package com.wgh.rag.data.mybatis;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.wgh.rag.common.security.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * MyBatis Plus 自动填充处理器
 * <p>
 * createBy 记录真实创建人，作为资源归属（owner）依据；异步/内部线程无登录态时回落为 system。
 */
@Slf4j
@Component
public class MetaObjectHandlerImpl implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        String operator = currentOperator();
        this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, LocalDateTime.now());
        this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
        this.strictInsertFill(metaObject, "createBy", String.class, operator);
        this.strictInsertFill(metaObject, "updateBy", String.class, operator);
        this.strictInsertFill(metaObject, "deleted", Integer.class, 0);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
        this.strictUpdateFill(metaObject, "updateBy", String.class, currentOperator());
    }

    /** 当前操作人；匿名（异步线程、内部任务）回落为 system */
    private String currentOperator() {
        String username = SecurityUtils.currentUsername();
        if (!StringUtils.hasText(username) || SecurityUtils.ANONYMOUS.equals(username)) {
            return "system";
        }
        return username;
    }
}

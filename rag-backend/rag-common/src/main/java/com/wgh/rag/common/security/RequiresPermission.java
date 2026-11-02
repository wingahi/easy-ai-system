package com.wgh.rag.common.security;

import java.lang.annotation.*;

/**
 * 接口级权限注解：value 为权限编码（如 kb:write），mode=ANY/ALL（ANY 时任一权限即可）
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {

    String[] value();

    Mode mode() default Mode.ANY;

    enum Mode { ANY, ALL }
}

package com.wgh.rag.log.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 审计注解：标注在 controller 写操作方法上，由 AuditAspect 异步落审计日志
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audit {

    /**
     * 操作标识（如 KB_CREATE / MODEL_DELETE）
     */
    String action();

    /**
     * 资源类型（如 KNOWLEDGE_BASE / LLM_MODEL）
     */
    String resourceType() default "";
}

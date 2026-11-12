package com.wgh.rag.data.scope;

import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.ResultCode;

import java.util.Locale;

/**
 * 资源可见性：PRIVATE / TEAM / PUBLIC。
 * <p>
 * TEAM 与 PUBLIC 在当前无租户/团队模型下都表示“非所有者可读”，区别仅在于业务语义；
 * 写操作始终只允许所有者或 ADMIN。
 */
public enum ResourceVisibility {

    PRIVATE,
    TEAM,
    PUBLIC;

    /** 空值按 PRIVATE 处理；非法值抛出参数异常。 */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return PRIVATE.name();
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "可见性取值非法: " + raw + "（仅支持 PRIVATE / TEAM / PUBLIC）");
        }
    }

    /** 更新场景：为空表示不改动，返回 null 交给 MP 忽略该字段。 */
    public static String normalizeOptional(String raw) {
        return raw == null || raw.isBlank() ? null : normalize(raw);
    }

    /** 是否对非所有者可读（TEAM / PUBLIC）。 */
    public static boolean isShared(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String value = raw.trim();
        return PUBLIC.name().equalsIgnoreCase(value) || TEAM.name().equalsIgnoreCase(value);
    }
}

package com.wgh.rag.data.scope;

import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.ResultCode;

/**
 * 资源归属校验。
 * <p>
 * - 所有者 / ADMIN / 无登录上下文（内部调用、异步线程、单测）：放行
 * - 他人 PRIVATE 资源：按“不存在”处理（404），避免泄露存在性
 * - 他人 TEAM / PUBLIC 资源：可读，但不可写（403）
 */
public final class DataScopeGuard {

    private DataScopeGuard() {
    }

    public static void assertReadable(String owner, String visibility, String resourceName) {
        DataScopeContext.Scope user = DataScopeContext.resolveUser();
        if (user == null || user.admin()) {
            return;
        }
        if (user.username().equals(owner)) {
            return;
        }
        if (ResourceVisibility.isShared(visibility)) {
            return;
        }
        throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, resourceName + "不存在");
    }

    public static void assertWritable(String owner, String visibility, String resourceName) {
        DataScopeContext.Scope user = DataScopeContext.resolveUser();
        if (user == null || user.admin()) {
            return;
        }
        if (user.username().equals(owner)) {
            return;
        }
        if (!ResourceVisibility.isShared(visibility)) {
            throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, resourceName + "不存在");
        }
        throw new BusinessException(ResultCode.PERMISSION_DENIED, "无权操作他人共享的" + resourceName);
    }
}

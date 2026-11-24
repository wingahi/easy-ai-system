package com.wgh.rag.data.tenant;

import com.wgh.rag.common.security.TenantContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * 租户事务模板。
 *
 * <p>用于在事务边界显式指定租户，覆盖异步审计、调用日志、作业执行等
 * 「TenantContext 可能已清理或未被传播」的链路。事务开始后由
 * {@link TenantAwareDataSourceTransactionManager} 下发 GUC。
 */
@Component
public class TenantTransactionTemplate {

    private final TransactionTemplate readWrite;
    private final TransactionTemplate readOnly;

    public TenantTransactionTemplate(PlatformTransactionManager transactionManager) {
        this.readWrite = new TransactionTemplate(transactionManager);
        this.readWrite.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.readOnly.setReadOnly(true);
    }

    /** 当前上下文事务；无上下文时按默认租户执行。 */
    public <T> T execute(boolean readOnly, Supplier<T> action) {
        return execute(TenantContext.tenantIdOrDefault(), readOnly, action);
    }

    /** 指定租户事务；加入现有事务时沿用其 GUC。 */
    public <T> T execute(String tenantId, boolean readOnly, Supplier<T> action) {
        Supplier<T> scoped = () -> (readOnly ? this.readOnly : this.readWrite).execute(status -> action.get());
        if (tenantId == null || tenantId.isBlank()) {
            return scoped.get();
        }
        TenantContext.Context current = TenantContext.get();
        if (current != null && tenantId.equals(current.tenantId())) {
            return scoped.get();
        }
        return TenantContext.callWith(new TenantContext.Context(tenantId, current == null ? null : current.userId(),
                current == null ? null : current.username()), scoped);
    }

    /** 独立新事务（REQUIRES_NEW），用于主流程失败也必须落库的写入。 */
    public void executeNew(String tenantId, Runnable action) {
        TransactionTemplate template = new TransactionTemplate(readWrite.getTransactionManager());
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Supplier<Void> scoped = () -> template.execute(status -> {
            action.run();
            return null;
        });
        if (tenantId == null || tenantId.isBlank()) {
            scoped.get();
            return;
        }
        TenantContext.callWith(new TenantContext.Context(tenantId, TenantContext.currentUserId(),
                TenantContext.currentUsername()), scoped);
    }
}

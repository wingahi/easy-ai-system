package com.wgh.rag.sandbox.audit;

import com.wgh.rag.common.security.TenantContext;
import com.wgh.rag.log.entity.AuditLog;
import com.wgh.rag.log.service.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 沙箱审计：记录「谁、何时、代码摘要、退出码、耗时、隔离级别」。
 *
 * <p>绝不写入源码正文与完整输出，只写 SHA-256 摘要前缀与字节数。
 * 审计写入失败不会影响执行结果。
 */
public class SandboxAuditLogger {

    private static final Logger AUDIT = LoggerFactory.getLogger("WGH_SANDBOX_AUDIT");
    private static final String ACTION = "SANDBOX_EXECUTE";
    private static final String RESOURCE_TYPE = "SANDBOX";

    private final AuditLogService auditLogService;
    private final boolean auditToDatabase;

    public SandboxAuditLogger(AuditLogService auditLogService, boolean auditToDatabase) {
        this.auditLogService = auditLogService;
        this.auditToDatabase = auditToDatabase;
    }

    /**
     * 记录一次执行结果（成功或失败均记录）。
     */
    public void record(String username,
                       String language,
                       String codeDigest,
                       int codeBytes,
                       String isolation,
                       Integer exitCode,
                       long durationMillis,
                       boolean timedOut,
                       boolean truncated,
                       boolean success,
                       String error) {
        String status = success ? "SUCCESS" : "FAIL";
        AUDIT.info("sandbox user={} lang={} codeSha={} codeBytes={} isolation={} exit={} durationMs={} "
                        + "timedOut={} truncated={} status={} error={}",
                username, language, codeDigest, codeBytes, isolation,
                exitCode == null ? "-" : exitCode, durationMillis, timedOut, truncated, status,
                error == null ? "-" : error);

        if (!auditToDatabase || auditLogService == null) {
            return;
        }
        try {
            AuditLog log = new AuditLog();
            log.setUsername(username);
            log.setTenantId(TenantContext.tenantIdOrDefault());
            log.setAction(ACTION);
            log.setResourceType(RESOURCE_TYPE);
            log.setResourceId(codeDigest);
            log.setStatus(status);
            log.setDetail(("lang=%s codeSha=%s codeBytes=%d isolation=%s exit=%s durationMs=%d "
                    + "timedOut=%s truncated=%s error=%s")
                    .formatted(language, codeDigest, codeBytes, isolation,
                            exitCode == null ? "-" : String.valueOf(exitCode), durationMillis,
                            timedOut, truncated, error == null ? "-" : error));
            auditLogService.saveAsync(log);
        } catch (Exception ignored) {
            // 审计失败不阻断执行
        }
    }
}

package com.wgh.rag.sandbox.isolation;

import com.wgh.rag.sandbox.config.SandboxProperties;
import com.wgh.rag.sandbox.model.SandboxException;

import java.util.Locale;

/**
 * 按配置与平台能力解析隔离策略。
 *
 * <p>AUTO 优先选择强隔离；都不可用时默认拒绝执行（fail-closed），
 * 只有显式允许降级才返回 {@link DegradedIsolation}。
 */
public final class SandboxIsolations {

    private SandboxIsolations() {
    }

    public static SandboxIsolation resolve(SandboxProperties properties) {
        String mode = properties.getIsolation() == null
                ? "AUTO"
                : properties.getIsolation().trim().toUpperCase(Locale.ROOT);
        SandboxIsolation isolation = switch (mode) {
            case "AUTO" -> autoDetect();
            case "SANDBOX_EXEC" -> new SandboxExecIsolation();
            case "BWRAP" -> new BwrapIsolation();
            case "DEGRADED" -> new DegradedIsolation();
            default -> throw new SandboxException("不支持的隔离模式: " + properties.getIsolation());
        };

        if (isolation instanceof DegradedIsolation) {
            requireDegradedAllowed(properties);
        } else if (!isolation.available()) {
            if (properties.isAllowDegradedIsolation()) {
                return new DegradedIsolation();
            }
            throw new SandboxException("隔离机制不可用: " + isolation.name()
                    + "；为避免无隔离执行，已拒绝。请安装对应工具或显式允许降级隔离");
        }
        return isolation;
    }

    private static SandboxIsolation autoDetect() {
        SandboxExecIsolation sandboxExec = new SandboxExecIsolation();
        if (sandboxExec.available()) {
            return sandboxExec;
        }
        BwrapIsolation bwrap = new BwrapIsolation();
        if (bwrap.available()) {
            return bwrap;
        }
        return new DegradedIsolation();
    }

    private static void requireDegradedAllowed(SandboxProperties properties) {
        if (!properties.isAllowDegradedIsolation()) {
            throw new SandboxException("降级隔离未显式开启"
                    + "（wgh.sandbox.allow-degraded-isolation=false），已拒绝执行");
        }
    }
}

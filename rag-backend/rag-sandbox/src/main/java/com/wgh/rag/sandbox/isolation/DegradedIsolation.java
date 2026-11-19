package com.wgh.rag.sandbox.isolation;

import java.nio.file.Path;
import java.util.List;

/**
 * 降级隔离：仅依赖独立进程 + 临时工作目录 + ulimit + 硬超时。
 *
 * <p>风险等级「高」：无法阻止读取宿主文件与访问网络，默认不启用，
 * 必须显式配置 {@code wgh.sandbox.allow-degraded-isolation=true}。
 */
public class DegradedIsolation implements SandboxIsolation {

    @Override
    public String name() {
        return "degraded";
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public List<String> wrap(List<String> command, Path workDir, boolean allowNetwork) {
        return command;
    }
}

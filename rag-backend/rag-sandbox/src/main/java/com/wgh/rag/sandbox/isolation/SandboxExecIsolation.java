package com.wgh.rag.sandbox.isolation;

import com.wgh.rag.sandbox.model.SandboxException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * macOS 用户态沙箱：通过 {@code sandbox-exec} + SBPL profile 实现
 *
 * <ul>
 *   <li>默认拒绝一切操作（{@code (deny default)}）</li>
 *   <li>只读全局放行，但显式拒绝用户家目录、钥匙串、数据库等敏感路径</li>
 *   <li>只允许写本次临时工作目录与 /dev/null 等标准设备</li>
 *   <li>除非显式允许，否则拒绝所有网络操作</li>
 * </ul>
 *
 * <p>注意：sandbox-exec 已被 Apple 标记为 deprecated，属于用户态沙箱而非内核命名空间隔离，
 * 风险等级为「中」。真隔离应使用容器或 Linux bwrap。
 */
public class SandboxExecIsolation implements SandboxIsolation {

    private static final String EXECUTABLE = "/usr/bin/sandbox-exec";

    @Override
    public String name() {
        return "sandbox-exec";
    }

    @Override
    public boolean available() {
        return Files.isExecutable(Path.of(EXECUTABLE));
    }

    @Override
    public List<String> wrap(List<String> command, Path workDir, boolean allowNetwork) throws IOException {
        if (!available()) {
            throw new SandboxException("sandbox-exec 不可用");
        }
        Path realWorkDir = workDir.toRealPath();
        Path profile = realWorkDir.resolve(".wgh-sandbox.sb");
        Files.writeString(profile, buildProfile(realWorkDir, allowNetwork), StandardCharsets.UTF_8);

        List<String> wrapped = new ArrayList<>(command.size() + 3);
        wrapped.add(EXECUTABLE);
        wrapped.add("-f");
        wrapped.add(profile.toString());
        wrapped.addAll(command);
        return wrapped;
    }

    private String buildProfile(Path workDir, boolean allowNetwork) {
        String home = System.getProperty("user.home", "");
        StringBuilder sb = new StringBuilder();
        sb.append("(version 1)\n");
        sb.append("(deny default)\n");
        sb.append("(allow process-exec*)\n");
        sb.append("(allow process-fork)\n");
        sb.append("(allow sysctl-read)\n");
        sb.append("(allow mach-lookup)\n");
        sb.append("(allow file-read*)\n");
        // 读：显式屏蔽敏感目录；后续 allow 规则可再放行工作目录
        sb.append("(deny file-read*");
        appendSubpath(sb, home);
        appendSubpath(sb, "/Users");
        appendSubpath(sb, "/private/var/db");
        appendSubpath(sb, "/private/var/root");
        appendSubpath(sb, "/private/etc/ssh");
        appendSubpath(sb, "/Library/Keychains");
        appendSubpath(sb, "/Volumes");
        sb.append(")\n");
        sb.append("(allow file-read*");
        appendSubpath(sb, workDir.toString());
        sb.append(")\n");
        // 写：只允许工作目录与标准设备
        sb.append("(allow file-write*");
        appendSubpath(sb, workDir.toString());
        appendLiteral(sb, "/dev/null");
        appendLiteral(sb, "/dev/stdout");
        appendLiteral(sb, "/dev/stderr");
        appendLiteral(sb, "/dev/tty");
        sb.append(")\n");
        if (!allowNetwork) {
            sb.append("(deny network*)\n");
        }
        return sb.toString();
    }

    private void appendSubpath(StringBuilder sb, String path) {
        if (path != null && !path.isBlank()) {
            sb.append(" (subpath ").append(quote(path)).append(')');
        }
    }

    private void appendLiteral(StringBuilder sb, String path) {
        sb.append(" (literal ").append(quote(path)).append(')');
    }

    private String quote(String raw) {
        return '"' + raw.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}

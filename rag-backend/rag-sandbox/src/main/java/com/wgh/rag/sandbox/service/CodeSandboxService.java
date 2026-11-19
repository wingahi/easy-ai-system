package com.wgh.rag.sandbox.service;

import com.wgh.rag.common.security.SecurityUtils;
import com.wgh.rag.sandbox.audit.SandboxAuditLogger;
import com.wgh.rag.sandbox.config.SandboxProperties;
import com.wgh.rag.sandbox.exec.SandboxProcessRunner;
import com.wgh.rag.sandbox.exec.SandboxProcessRunner.ProcessOutcome;
import com.wgh.rag.sandbox.isolation.SandboxIsolation;
import com.wgh.rag.sandbox.isolation.SandboxIsolations;
import com.wgh.rag.sandbox.model.SandboxException;
import com.wgh.rag.sandbox.model.SandboxLanguage;
import com.wgh.rag.sandbox.model.SandboxRequest;
import com.wgh.rag.sandbox.model.SandboxResult;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

/**
 * 代码沙箱服务：校验 → 独立目录落盘 → 受限子进程执行 → 清理 → 审计。
 *
 * <p>硬约束：
 * <ul>
 *   <li>默认关闭，{@code enabled=true} 才允许执行；</li>
 *   <li>只执行语言白名单内的脚本，代码写入固定文件名，不拼进 shell；</li>
 *   <li>墙钟硬超时 + 输出上限，超限立即强杀进程树；</li>
 *   <li>每次执行使用独立临时目录，结束后递归删除；</li>
 *   <li>无强隔离机制时默认拒绝，而不是无隔离执行。</li>
 * </ul>
 */
@Slf4j
public class CodeSandboxService {

    private static final long RATE_WINDOW_MS = 60_000L;
    private static final long MIN_TIMEOUT_MS = 100L;

    private final SandboxProperties properties;
    private final SandboxAuditLogger audit;
    private final SandboxProcessRunner runner = new SandboxProcessRunner();
    private final Semaphore concurrency;
    private final Set<SandboxLanguage> allowedLanguages;
    private final Map<String, long[]> rateWindows = new HashMap<>();

    public CodeSandboxService(SandboxProperties properties, SandboxAuditLogger audit) {
        this.properties = properties;
        this.audit = audit;
        this.concurrency = new Semaphore(Math.max(1, properties.getMaxConcurrent()), true);
        List<String> configured = properties.getAllowedLanguages() == null
                ? List.of()
                : properties.getAllowedLanguages();
        this.allowedLanguages = configured.stream()
                .filter(Objects::nonNull)
                .map(SandboxLanguage::resolve)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /**
     * 执行一段代码。
     *
     * @throws SandboxException 未启用、参数非法、配额超限或隔离不可用时拒绝执行
     */
    public SandboxResult execute(SandboxRequest request) {
        String username = SecurityUtils.currentUsername();
        byte[] codeBytes = request != null && request.code() != null
                ? request.code().getBytes(StandardCharsets.UTF_8)
                : new byte[0];
        String digest = sha256Prefix(codeBytes);
        String rawLanguage = request == null ? "-" : String.valueOf(request.language());

        if (!properties.isEnabled()) {
            reject(username, rawLanguage, digest, codeBytes.length, "代码沙箱未启用");
        }
        if (request == null || request.code() == null || request.code().isBlank()) {
            reject(username, rawLanguage, digest, codeBytes.length, "代码不能为空");
        }
        if (codeBytes.length > properties.getMaxCodeBytes()) {
            reject(username, rawLanguage, digest, codeBytes.length,
                    "代码超过大小上限 " + properties.getMaxCodeBytes() + " 字节");
        }
        SandboxLanguage language = SandboxLanguage.resolve(request.language());
        if (language == null || !allowedLanguages.contains(language)) {
            reject(username, rawLanguage, digest, codeBytes.length,
                    "不支持的语言（仅允许: " + allowedLanguages + "）");
        }
        if (!tryAcquireRate(username)) {
            reject(username, language.name(), digest, codeBytes.length, "调用频率超过上限，请稍后再试");
        }
        if (!concurrency.tryAcquire()) {
            reject(username, language.name(), digest, codeBytes.length, "并发执行数已达上限，请稍后再试");
        }

        String isolationName = "-";
        try {
            SandboxIsolation isolation = SandboxIsolations.resolve(properties);
            isolationName = isolation.name();
            long timeoutMs = resolveTimeout(request.timeoutMillis());

            Path workDir = createWorkDir();
            try {
                Path source = workDir.resolve(language.sourceFileName());
                Files.writeString(source, request.code(), StandardCharsets.UTF_8);

                List<String> command = buildCommand(language, source);
                List<String> wrapped = isolation.wrap(command, workDir, properties.isAllowNetwork());
                Map<String, String> env = buildEnvironment(workDir);

                byte[] stdin = request.stdin() == null
                        ? null
                        : request.stdin().getBytes(StandardCharsets.UTF_8);
                ProcessOutcome outcome = runner.run(wrapped, workDir, env, timeoutMs,
                        properties.getMaxOutputBytes(), stdin);

                SandboxResult result = new SandboxResult(
                        language.name(), isolationName, outcome.exitCode(), outcome.timedOut(),
                        outcome.truncated(), outcome.killed(), outcome.stdout(), outcome.stderr(),
                        outcome.durationMillis(), digest);

                audit.record(username, language.name(), digest, codeBytes.length, isolationName,
                        outcome.exitCode(), outcome.durationMillis(), outcome.timedOut(),
                        outcome.truncated(), result.success(), failureMessage(outcome));

                return result;
            } finally {
                deleteRecursively(workDir);
            }
        } catch (SandboxException e) {
            audit.record(username, language.name(), digest, codeBytes.length, isolationName,
                    null, 0L, false, false, false, e.getMessage());
            throw e;
        } catch (Exception e) {
            String message = "沙箱执行失败: " + e.getClass().getSimpleName();
            log.warn("沙箱执行异常: lang={}, isolation={}", language.name(), isolationName, e);
            audit.record(username, language.name(), digest, codeBytes.length, isolationName,
                    null, 0L, false, false, false, message);
            throw new SandboxException(message, e);
        } finally {
            concurrency.release();
        }
    }

    private String failureMessage(ProcessOutcome outcome) {
        if (outcome.timedOut()) {
            return "TIMEOUT";
        }
        if (outcome.truncated()) {
            return "OUTPUT_TRUNCATED";
        }
        return outcome.exitCode() == 0 ? null : "EXIT_" + outcome.exitCode();
    }

    private void reject(String username, String language, String digest, int codeBytes, String reason) {
        audit.record(username, language, digest, codeBytes, "-", null, 0L,
                false, false, false, reason);
        throw new SandboxException(reason);
    }

    private long resolveTimeout(Long requested) {
        long timeout = requested == null ? properties.getDefaultTimeoutMs() : requested;
        long max = Math.max(properties.getMaxTimeoutMs(), MIN_TIMEOUT_MS);
        return Math.min(Math.max(timeout, MIN_TIMEOUT_MS), max);
    }

    private Path createWorkDir() throws IOException {
        String root = properties.getWorkRoot();
        Path base = (root == null || root.isBlank())
                ? Path.of(System.getProperty("java.io.tmpdir"), "wgh-sandbox")
                : Path.of(root, "wgh-sandbox");
        Files.createDirectories(base);
        try {
            return Files.createTempDirectory(base, "run-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException e) {
            // 非 POSIX 文件系统退化为默认权限
            return Files.createTempDirectory(base, "run-");
        }
    }

    /**
     * 固定 argv：sh -c 只负责套 ulimit，脚本路径通过位置参数传入，用户代码不进入命令行。
     */
    private List<String> buildCommand(SandboxLanguage language, Path source) {
        long memoryKb = (long) Math.max(properties.getMemoryMb(), 32) * 1024L;
        long fileBlocks = Math.max(properties.getMaxFileBytes() / 512L, 1L);
        String limitScript = String.format(
                "ulimit -c 0 2>/dev/null; ulimit -t %d 2>/dev/null; ulimit -v %d 2>/dev/null; "
                        + "ulimit -f %d 2>/dev/null; exec \"$@\"",
                Math.max(properties.getCpuSeconds(), 1), memoryKb, fileBlocks);

        List<String> command = new ArrayList<>(5);
        command.add("/bin/sh");
        command.add("-c");
        command.add(limitScript);
        command.add("wgh-sandbox");
        command.add(language.interpreter());
        command.add(source.toString());
        return command;
    }

    private Map<String, String> buildEnvironment(Path workDir) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PATH", "/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin");
        env.put("HOME", workDir.toString());
        env.put("TMPDIR", workDir.toString());
        env.put("LANG", "en_US.UTF-8");
        env.put("LC_ALL", "en_US.UTF-8");
        env.put("PYTHONIOENCODING", "utf-8");
        env.put("PYTHONDONTWRITEBYTECODE", "1");
        return env;
    }

    private synchronized boolean tryAcquireRate(String username) {
        int limit = properties.getMaxRequestsPerMinute();
        if (limit <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        long[] window = rateWindows.computeIfAbsent(username, k -> new long[]{now, 0L});
        if (now - window[0] >= RATE_WINDOW_MS) {
            window[0] = now;
            window[1] = 0L;
        }
        if (window[1] >= limit) {
            return false;
        }
        window[1]++;
        return true;
    }

    private String sha256Prefix(byte[] data) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                sb.append(String.format(Locale.ROOT, "%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "-";
        }
    }

    private void deleteRecursively(Path dir) {
        if (dir == null) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 清理失败仅告警，不影响结果
                }
            });
        } catch (IOException e) {
            log.warn("沙箱工作目录清理失败: {}", dir, e);
        }
    }
}

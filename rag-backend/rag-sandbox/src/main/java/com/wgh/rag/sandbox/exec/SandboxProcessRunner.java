package com.wgh.rag.sandbox.exec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 子进程执行器：负责硬超时、子孙进程强杀、输出截断与结果收集。
 *
 * <p>不做任何 shell 拼接：命令行由固定 argv 组成，工作目录由 {@link ProcessBuilder#directory} 指定。
 */
public class SandboxProcessRunner {

    /** 单次执行结果 */
    public record ProcessOutcome(int exitCode,
                                 boolean timedOut,
                                 boolean truncated,
                                 boolean killed,
                                 String stdout,
                                 String stderr,
                                 long durationMillis) {
    }

    public ProcessOutcome run(List<String> command,
                              Path workDir,
                              Map<String, String> environment,
                              long timeoutMillis,
                              int maxOutputBytes,
                              byte[] stdin) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(command));
        builder.directory(workDir.toFile());
        builder.redirectErrorStream(false);
        builder.environment().clear();
        builder.environment().putAll(environment);

        long start = System.nanoTime();
        Process process = builder.start();
        try (OutputStream os = process.getOutputStream()) {
            if (stdin != null && stdin.length > 0) {
                os.write(stdin);
            }
        } catch (IOException ignored) {
            // 进程可能已退出或不读取 stdin，忽略
        }

        int perStreamLimit = Math.max(maxOutputBytes / 2, 1024);
        StreamCollector stdout = new StreamCollector(process.getInputStream(), perStreamLimit, process);
        StreamCollector stderr = new StreamCollector(process.getErrorStream(), perStreamLimit, process);
        stdout.start();
        stderr.start();

        boolean finished;
        try {
            finished = process.waitFor(Math.max(timeoutMillis, 1), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            killTree(process);
            Thread.currentThread().interrupt();
            finished = false;
        }

        boolean timedOut = !finished;
        if (timedOut) {
            killTree(process);
        }

        stdout.await(2_000);
        stderr.await(2_000);
        if (process.isAlive()) {
            killTree(process);
            try {
                process.waitFor(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        boolean truncated = stdout.truncated || stderr.truncated;
        boolean killed = timedOut || truncated;
        int exitCode = process.isAlive() ? -1 : process.exitValue();
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        return new ProcessOutcome(exitCode, timedOut, truncated, killed,
                stdout.text(), stderr.text(), durationMs);
    }

    private void killTree(Process process) {
        try {
            process.descendants().forEach(handle -> {
                try {
                    handle.destroyForcibly();
                } catch (Exception ignored) {
                    // 忽略单个子孙进程的终止异常
                }
            });
        } catch (Exception ignored) {
            // 忽略获取进程树的异常
        }
        try {
            process.destroyForcibly();
        } catch (Exception ignored) {
            // 忽略
        }
    }

    /**
     * 有上限的输出收集器；超过上限立即标记截断并强杀进程，避免无限输出占满内存。
     */
    private static final class StreamCollector extends Thread {

        private final InputStream input;
        private final int limit;
        private final Process process;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private volatile boolean truncated;

        private StreamCollector(InputStream input, int limit, Process process) {
            this.input = input;
            this.limit = limit;
            this.process = process;
            setName("wgh-sandbox-stream");
            setDaemon(true);
        }

        @Override
        public void run() {
            byte[] chunk = new byte[8192];
            int written = 0;
            try (InputStream in = input) {
                int read;
                while ((read = in.read(chunk)) != -1) {
                    int remaining = limit - written;
                    if (remaining <= 0) {
                        truncated = true;
                        killQuietly();
                        break;
                    }
                    int toWrite = Math.min(read, remaining);
                    buffer.write(chunk, 0, toWrite);
                    written += toWrite;
                    if (toWrite < read) {
                        truncated = true;
                        killQuietly();
                        break;
                    }
                }
            } catch (IOException ignored) {
                // 进程被强杀时管道关闭属预期行为
            }
        }

        private void killQuietly() {
            try {
                process.destroyForcibly();
            } catch (Exception ignored) {
                // 忽略
            }
        }

        private void await(long millis) {
            try {
                join(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private String text() {
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }
}

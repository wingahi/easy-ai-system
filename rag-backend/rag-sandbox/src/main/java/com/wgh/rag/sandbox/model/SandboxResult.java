package com.wgh.rag.sandbox.model;

/**
 * 一次代码执行的结果。
 *
 * @param language      实际使用的语言
 * @param isolation     实际生效的隔离级别（sandbox-exec / bwrap / degraded）
 * @param exitCode      进程退出码；被强杀时可能为 137 或 -1
 * @param timedOut      是否因墙钟超时被强杀
 * @param truncated     输出是否被截断
 * @param killed        是否被强制终止（超时或输出超限）
 * @param stdout        标准输出（已截断）
 * @param stderr        标准错误（已截断）
 * @param durationMillis 执行耗时（毫秒）
 * @param codeDigest    代码 SHA-256 前 12 位，仅用于审计关联，不可还原源码
 */
public record SandboxResult(String language,
                            String isolation,
                            int exitCode,
                            boolean timedOut,
                            boolean truncated,
                            boolean killed,
                            String stdout,
                            String stderr,
                            long durationMillis,
                            String codeDigest) {

    /** 是否正常完成（退出码 0、未超时、未被终止） */
    public boolean success() {
        return exitCode == 0 && !timedOut && !killed;
    }
}

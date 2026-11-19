package com.wgh.rag.sandbox.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 代码沙箱配置（{@code wgh.sandbox}）。
 *
 * <p>所有字段均有代码默认值，application*.yml 未配置时直接使用默认值。
 * <b>默认关闭</b>：{@link #enabled} 为 false 时任何执行请求都会被拒绝。
 */
@Data
@ConfigurationProperties(prefix = "wgh.sandbox")
public class SandboxProperties {

    /** 总开关，默认关闭 */
    private boolean enabled = false;

    /**
     * 工作目录根路径；为空时使用 {@code ${java.io.tmpdir}/wgh-sandbox}。
     * 每次执行会在其下创建独立子目录并递归删除。
     */
    private String workRoot = "";

    /** 默认墙钟超时（毫秒） */
    private long defaultTimeoutMs = 5_000;

    /** 单次请求允许的最大墙钟超时（毫秒），请求值会被裁剪到该上限 */
    private long maxTimeoutMs = 30_000;

    /** stdout/stderr 合计输出上限（字节），超出立即截断并强杀 */
    private int maxOutputBytes = 65_536;

    /** 单次提交代码的最大字节数 */
    private int maxCodeBytes = 65_536;

    /** CPU 时间上限（秒，ulimit -t） */
    private int cpuSeconds = 5;

    /** 内存上限（MB，ulimit -v，尽力而为） */
    private int memoryMb = 256;

    /** 进程可写单文件上限（字节，ulimit -f） */
    private long maxFileBytes = 1_048_576;

    /** 同时执行的沙箱进程上限 */
    private int maxConcurrent = 2;

    /** 每用户每分钟最多执行次数（内存配额，不持久化） */
    private int maxRequestsPerMinute = 10;

    /** 允许的语言（名称见 {@code SandboxLanguage}） */
    private List<String> allowedLanguages = List.of("PYTHON", "JAVASCRIPT");

    /** 是否允许子进程访问网络；默认 false（拒绝网络） */
    private boolean allowNetwork = false;

    /**
     * 隔离模式：AUTO / SANDBOX_EXEC / BWRAP / DEGRADED。
     * AUTO 时按平台选择；都不可用时默认拒绝执行。
     */
    private String isolation = "AUTO";

    /**
     * 是否允许退回到「临时目录 + ulimit」的弱隔离（风险等级：高）。
     * 默认 false，宁可拒绝执行也不放宽隔离。
     */
    private boolean allowDegradedIsolation = false;

    /** 是否把审计记录异步写入 wgh_audit_log */
    private boolean auditToDatabase = true;
}

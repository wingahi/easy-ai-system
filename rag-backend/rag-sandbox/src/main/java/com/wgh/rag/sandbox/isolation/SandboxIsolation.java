package com.wgh.rag.sandbox.isolation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 隔离策略：把「解释器 + 源码文件」的原始命令包装成受限环境下的命令。
 *
 * <p>实现必须保证不把用户代码拼进命令行参数，只允许改写固定的 argv 与路径映射。
 */
public interface SandboxIsolation {

    /** 隔离级别名称（写入结果与审计） */
    String name();

    /** 当前环境是否可用 */
    boolean available();

    /**
     * 包装命令。
     *
     * @param command      原始命令（解释器 + 源码文件路径 + 固定参数）
     * @param workDir      本次执行的临时工作目录
     * @param allowNetwork 是否允许访问网络
     */
    List<String> wrap(List<String> command, Path workDir, boolean allowNetwork) throws IOException;
}

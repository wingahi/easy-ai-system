package com.wgh.rag.sandbox.model;

import java.util.Locale;

/**
 * 沙箱支持的语言白名单。
 *
 * <p>每个语言都只通过「固定入口文件名 + 固定解释器 argv」执行，
 * 用户代码始终写入文件，绝不拼接进 shell 命令行。
 */
public enum SandboxLanguage {

    PYTHON("python3", "main.py"),
    JAVASCRIPT("node", "main.js");

    private final String interpreter;
    private final String sourceFileName;

    SandboxLanguage(String interpreter, String sourceFileName) {
        this.interpreter = interpreter;
        this.sourceFileName = sourceFileName;
    }

    public String interpreter() {
        return interpreter;
    }

    public String sourceFileName() {
        return sourceFileName;
    }

    /**
     * 解析语言名（不区分大小写，兼容 python/py/js/node 等别名）。
     *
     * @return 匹配的语言；不支持时返回 null
     */
    public static SandboxLanguage resolve(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "python", "python3", "py" -> PYTHON;
            case "javascript", "js", "node", "nodejs" -> JAVASCRIPT;
            default -> null;
        };
    }
}

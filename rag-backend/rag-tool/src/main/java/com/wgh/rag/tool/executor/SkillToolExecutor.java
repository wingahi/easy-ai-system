package com.wgh.rag.tool.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SKILL 工具执行器 —— 通过 LLM 子对话实现"技能"类工具
 *
 * <h3>executorConfig schema：</h3>
 * <pre>
 * {
 *   "mode": "inline",                              // inline（默认）| directory
 *   "promptTemplate": "你是天气助手，{{city}}...", // inline：直接写模板
 *   "skillPath": "/data/skills/weather/",          // directory：运行时读磁盘
 *   "subModelId": 2,                               // 子模型 ID（优先级最高）
 *   "subModelDefault": true,                       // true → 继承 ChatWindow 的默认模型
 *   "userMessageTemplate": "查询城市 {{city}}",    // 可选：覆盖 arguments 作为 user message
 *   "includeResources": true                       // directory 模式：加载 references/ scripts/（默认 true）
 * }
 * </pre>
 *
 * <h3>directory 模式运行时加载：</h3>
 * <pre>
 * skillPath/
 * ├─ SKILL.md                 # 主 prompt（优先级 1）
 * │   ├── 可选 YAML front matter（---...--- 包裹）
 * │   ├── 可选 ---SYSTEM--- / ---USER--- 分隔符
 * ├─ prompt.md                # 次选（优先级 2）
 * ├─ references/              # 自动合并进 prompt
 * │   └─ *.md / *.txt
 * ├─ examples/                # 自动合并进 prompt
 * │   └─ *.md
 * └─ scripts/                 # 列出文件（不执行，只给 LLM 参考）
 * </pre>
 *
 * <h3>模板变量：</h3>
 * <p><code>{{varName}}</code> 或 <code>{{user.name}}</code> 点号路径替换，纯字符串替换，禁止 eval。
 */
@Slf4j
@Component
public class SkillToolExecutor implements ToolExecutor {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z_][a-zA-Z0-9_.]*)}}");

    /** 上一次 prompt 加载的具体错误消息（用于 execute 里返回给 LLM） */
    private String lastLoadError = null;

    /** YAML front matter 分隔符 */
    private static final Pattern FRONT_MATTER = Pattern.compile("^---\\s*\\n(.*?)\\n---\\s*\\n", Pattern.DOTALL);

    /** system / user 分隔符 */
    private static final String SYSTEM_SEP = "---SYSTEM---";
    private static final String USER_SEP = "---USER---";

    /** 附加资源目录（directory 模式自动合并） */
    private static final String[] RESOURCE_DIRS = {"references", "resources", "examples", "scripts", "examples"};

    private final SkillLlmClient skillLlmClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public SkillToolExecutor(SkillLlmClient skillLlmClient) {
        this.skillLlmClient = skillLlmClient;
    }

    @Override
    public String supportedToolType() {
        return "SKILL";
    }

    @Override
    public ToolExecutionResultMessage execute(Map<String, Object> executorConfig, ToolExecutionRequest request) {
        // defensive copy — 上游可能传 ImmutableMap（Map.of()），applyFrontMatterDefaults 需要 put
        Map<String, Object> config = new HashMap<>(executorConfig);

        // 1. 加载 skill prompt（inline 直接取 / directory 从磁盘读）
        SkillPromptBundle bundle = loadPromptBundle(config);
        String loadError = (bundle == null) ? lastLoadError : null;
        if (bundle == null) {
            String err = loadError != null ? loadError
                    : "Error: Skill 加载失败 — 检查 executorConfig.mode + promptTemplate/skillPath";
            log.warn("Skill 执行错误: tool={}, reason={}", request.name(), err);
            return new ToolExecutionResultMessage(request.id(), request.name(), err);
        }

        // 2. 从 front matter 提取默认配置（可被 executorConfig 显式值覆盖）
        applyFrontMatterDefaults(config, bundle.frontMatter);

        // 3. 子模型 ID 解析：显式 subModelId > 继承窗口 > 报错
        Long subModelId = resolveSubModelId(config);
        if (subModelId == null) {
            String err = "Error: Skill 缺少子模型 — executorConfig.subModelId 未设置，且 subModelDefault=true 时窗口默认模型也未配置";
            log.warn("Skill 执行错误: tool={}, reason={}", request.name(), err);
            return new ToolExecutionResultMessage(request.id(), request.name(), err);
        }

        // 4. 渲染模板
        Map<String, Object> args = parseArguments(request.arguments());
        String systemPrompt = renderTemplate(bundle.systemPrompt, args);
        String userTemplate = config.get("userMessageTemplate") != null
                ? (String) config.get("userMessageTemplate") : bundle.userTemplate;
        String userMessage = (userTemplate != null && !userTemplate.isBlank())
                ? renderTemplate(userTemplate, args)
                : (args.isEmpty() ? request.arguments() : args.toString());

        log.info("执行 Skill: tool={}, subModelId={}, systemPrompt={} chars, userMessage={} chars",
                request.name(), subModelId, systemPrompt.length(), userMessage.length());

        // 5. 调用子对话
        String result = skillLlmClient.callSubModel(subModelId, systemPrompt, userMessage);

        return new ToolExecutionResultMessage(request.id(), request.name(),
                result != null ? result : "");
    }

    // ============ Prompt 加载 ============

    /**
     * 加载 Skill prompt —— inline 直接取 / directory 从磁盘读（实时，不缓存）
     */
    private SkillPromptBundle loadPromptBundle(Map<String, Object> config) {
        lastLoadError = null;
        String mode = (String) config.getOrDefault("mode", "inline");

        if ("directory".equalsIgnoreCase(mode)) {
            String skillPath = (String) config.get("skillPath");
            if (skillPath == null || skillPath.isBlank()) {
                lastLoadError = "Error: executorConfig.skillPath 不能为空 (directory mode)";
                return null;
            }
            return loadFromDirectory(skillPath, shouldIncludeResources(config));
        } else {
            String tpl = (String) config.get("promptTemplate");
            if (tpl == null || tpl.isBlank()) {
                lastLoadError = "Error: executorConfig.promptTemplate 不能为空 (inline mode)";
                return null;
            }
            return parseSkillContent(tpl);
        }
    }

    /** directory 模式是否加载资源目录 */
    private boolean shouldIncludeResources(Map<String, Object> config) {
        Object v = config.get("includeResources");
        return v == null || Boolean.TRUE.equals(v);
    }

    /** 从 skill 目录加载 —— 主 prompt + 可选资源合并 */
    private SkillPromptBundle loadFromDirectory(String skillPath, boolean includeResources) {
        try {
            Path dir = Paths.get(skillPath);
            if (!Files.isDirectory(dir)) {
                lastLoadError = "Error: Skill 目录不存在或不是目录: " + skillPath;
                log.warn(lastLoadError);
                return null;
            }

            // 找主 prompt 文件（优先级 SKILL.md > skill.md > prompt.md > PROMPT.md > README.md）
            String[] mainFiles = {"SKILL.md", "skill.md", "prompt.md", "PROMPT.md", "README.md"};
            String mainContent = null;
            for (String fn : mainFiles) {
                Path f = dir.resolve(fn);
                if (Files.isRegularFile(f)) {
                    mainContent = Files.readString(f);
                    log.info("Skill 主 prompt 加载自: {}", f.toAbsolutePath());
                    break;
                }
            }
            if (mainContent == null) {
                lastLoadError = "Error: Skill 目录下没找到 SKILL.md / prompt.md / README.md: " + skillPath;
                log.warn(lastLoadError);
                return null;
            }

            // 先解析 front matter + system/user 分隔符
            SkillPromptBundle bundle = parseSkillContent(mainContent);

            // resources/ references/ examples/ 追加到 system prompt 末尾（而不是整个 content 末尾）
            if (includeResources) {
                String resourcesBlock = buildResourceSections(dir);
                if (!resourcesBlock.isEmpty()) {
                    String merged = bundle.systemPrompt + "\n" + resourcesBlock;
                    bundle = new SkillPromptBundle(merged, bundle.userTemplate, bundle.frontMatter);
                }
            }

            return bundle;
        } catch (IOException e) {
            log.error("读取 Skill 目录失败: {}", skillPath, e);
            return null;
        }
    }

    /** 扫描 references/ examples/ scripts/ 等子目录，返回合并后的 resources 文本（空字符串表示没有资源） */
    private String buildResourceSections(Path skillDir) {
        StringBuilder sb = new StringBuilder();
        for (String subDir : RESOURCE_DIRS) {
            Path dir = skillDir.resolve(subDir);
            if (!Files.isDirectory(dir)) continue;
            try {
                List<Path> files = Files.list(dir)
                        .filter(Files::isRegularFile)
                        .filter(p -> {
                            String name = p.getFileName().toString().toLowerCase();
                            return name.endsWith(".md") || name.endsWith(".txt");
                        })
                        .sorted()
                        .toList();
                if (files.isEmpty()) continue;
                sb.append("\n\n--- ").append(subDir.toUpperCase()).append(" ---\n");
                for (Path f : files) {
                    sb.append("\n## ").append(f.getFileName()).append("\n\n");
                    sb.append(Files.readString(f)).append("\n");
                }
                log.info("Skill {} 合并了 {} 资源文件", skillDir.getFileName(), files.size());
            } catch (IOException e) {
                log.warn("扫描 resource 目录失败: {}", dir, e);
            }
        }
        return sb.toString();
    }

    /**
     * 解析 skill 内容 —— YAML front matter + system/user 分隔符
     * <p>
     * SKILL.md 格式：
     * <pre>
     * ---
     * subModelId: 2
     * ---
     * ---SYSTEM---
     * 你是翻译助手...
     * ---USER---
     * 请翻译：{{text}}
     * </pre>
     * 或：
     * <pre>
     * 你是翻译助手，请翻译：{{text}}
     * （system 和 user 混在一起 → 全当 system prompt，user message 用 arguments）
     * </pre>
     */
    private SkillPromptBundle parseSkillContent(String rawContent) {
        String content = rawContent;
        Map<String, String> frontMatter = new HashMap<>();

        // 提取 YAML front matter（---...--- 包裹在文件开头）
        Matcher fm = FRONT_MATTER.matcher(content);
        if (fm.find()) {
            try {
                frontMatter = parseSimpleYaml(fm.group(1));
                content = content.substring(fm.end()).trim();
            } catch (Exception e) {
                log.warn("YAML front matter 解析失败", e);
            }
        }

        // system/user 分隔
        String systemPrompt = content;
        String userTemplate = null;

        int sysIdx = content.indexOf(SYSTEM_SEP);
        int usrIdx = content.indexOf(USER_SEP);

        if (sysIdx >= 0 && usrIdx >= 0) {
            // 完整分隔：SYSTEM 在 USER 之前
            if (sysIdx < usrIdx) {
                systemPrompt = content.substring(sysIdx + SYSTEM_SEP.length(), usrIdx).trim();
                userTemplate = content.substring(usrIdx + USER_SEP.length()).trim();
            } else {
                systemPrompt = content.substring(usrIdx + USER_SEP.length(), sysIdx).trim();
                userTemplate = content.substring(sysIdx + SYSTEM_SEP.length()).trim();
            }
        } else if (sysIdx >= 0) {
            systemPrompt = content.substring(sysIdx + SYSTEM_SEP.length()).trim();
        } else if (usrIdx >= 0) {
            // 只有 USER 分隔 —— 前面全当 system
            systemPrompt = content.substring(0, usrIdx).trim();
            userTemplate = content.substring(usrIdx + USER_SEP.length()).trim();
        }

        return new SkillPromptBundle(systemPrompt, userTemplate, frontMatter);
    }

    /**
     * 极简 YAML 解析 —— 只支持简单 key: value（嵌套/列表不支持）
     * 足以处理 front matter 里的 modelId/description 等简单字段
     */
    private Map<String, String> parseSimpleYaml(String yaml) {
        Map<String, String> result = new HashMap<>();
        for (String line : yaml.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int colon = trimmed.indexOf(':');
            if (colon > 0) {
                String key = trimmed.substring(0, colon).trim();
                String value = trimmed.substring(colon + 1).trim();
                // 去掉引号
                if ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                result.put(key, value);
            }
        }
        return result;
    }

    /**
     * front matter 里的值作为 executorConfig 的默认值（但 executorConfig 显式配置优先）
     */
    private void applyFrontMatterDefaults(Map<String, Object> executorConfig, Map<String, String> frontMatter) {
        if (frontMatter == null || frontMatter.isEmpty()) return;
        // subModelId front matter → executorConfig
        if (!executorConfig.containsKey("subModelId") && frontMatter.containsKey("subModelId")) {
            try {
                executorConfig.put("subModelId", Long.parseLong(frontMatter.get("subModelId")));
            } catch (NumberFormatException e) {
                log.warn("front matter subModelId 不是有效数字: {}", frontMatter.get("subModelId"));
            }
        }
        // subModelDefault front matter
        if (!executorConfig.containsKey("subModelDefault") && frontMatter.containsKey("subModelDefault")) {
            executorConfig.put("subModelDefault", Boolean.parseBoolean(frontMatter.get("subModelDefault")));
        }
    }

    /**
     * 解析子模型 ID —— 三层优先级：
     * 1. executorConfig.subModelId 显式配置
     * 2. executorConfig.subModelDefault=true 继承 $windowDefaultModelId（由 ToolExecutionEngine 注入）
     * 3. 报错（返回 null）
     */
    private Long resolveSubModelId(Map<String, Object> config) {
        // 优先级 1：显式 subModelId
        Long explicit = toLong(config.get("subModelId"));
        if (explicit != null) return explicit;

        // 优先级 2：subModelDefault=true 继承窗口默认模型
        Object subDefault = config.get("subModelDefault");
        if (Boolean.TRUE.equals(subDefault)) {
            Long windowDefault = toLong(config.get(ToolExecutionEngine.WINDOW_DEFAULT_MODEL_KEY));
            if (windowDefault != null) {
                log.info("Skill 继承窗口默认模型: {}", windowDefault);
                return windowDefault;
            }
        }
        return null;
    }

    // ============ 模板渲染 + 工具方法 ============

    private String renderTemplate(String template, Map<String, Object> args) {
        if (template == null || template.isEmpty()) return "";
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String varName = m.group(1);
            Object value = resolveVar(varName, args);
            m.appendReplacement(sb, Matcher.quoteReplacement(value != null ? value.toString() : ""));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private Object resolveVar(String varName, Map<String, Object> args) {
        if (args == null || args.isEmpty()) return null;
        if (varName.contains(".")) {
            String[] parts = varName.split("\\.");
            Object cur = args;
            for (String p : parts) {
                if (cur instanceof Map) cur = ((Map<?, ?>) cur).get(p);
                else return null;
                if (cur == null) return null;
            }
            return cur;
        }
        return args.get(varName);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) return new HashMap<>();
        try {
            return mapper.readValue(argumentsJson, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception e) {
            log.warn("arguments JSON 解析失败: {}", argumentsJson, e);
            return new HashMap<>();
        }
    }

    private Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof Long) return (Long) o;
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(o.toString()); } catch (Exception e) { return null; }
    }

    /** Prompt 解析结果 */
    static class SkillPromptBundle {
        final String systemPrompt;
        final String userTemplate;
        final Map<String, String> frontMatter;

        SkillPromptBundle(String systemPrompt, String userTemplate, Map<String, String> frontMatter) {
            this.systemPrompt = systemPrompt != null ? systemPrompt : "";
            this.userTemplate = userTemplate;
            this.frontMatter = frontMatter != null ? frontMatter : new HashMap<>();
        }
    }
}

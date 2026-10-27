package com.wgh.rag.tool.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Skill 文件系统服务 —— 目录扫描、SKILL.md 读写、路径安全
 * <p>
 * 所有路径操作都会校验目标是否在 {@code wgh.skill.base-path} 范围内，
 * 防止路径穿越（../../etc/passwd 之类）。
 */
@Slf4j
@Service
public class SkillFileService {

    /** SKILL.md 主 prompt 查找优先级（和 SkillToolExecutor 保持一致） */
    private static final String[] MAIN_FILES = {"SKILL.md", "skill.md", "prompt.md", "PROMPT.md", "README.md"};

    /** YAML front matter 正则（---...--- 包裹在文件开头） */
    private static final Pattern FRONT_MATTER = Pattern.compile("^---\\s*\\n(.*?)\\n---\\s*\\n", Pattern.DOTALL);

    @Value("${wgh.skill.base-path:./skills}")
    private String skillBasePath;

    /**
     * 归一化后的 base path（路径安全比较时使用）
     */
    private Path normalizedBase() {
        return Paths.get(skillBasePath).toAbsolutePath().normalize();
    }

    /**
     * 路径安全校验 —— 目标路径必须在 base-path 内
     *
     * @param relativeDir 相对于 base-path 的子目录名（如 "weather" 或 "finance/analyzer"）
     * @return 归一化后的绝对路径
     * @throws IllegalArgumentException 路径穿越或非法
     */
    public Path resolveSafePath(String relativeDir) {
        if (relativeDir == null || relativeDir.isBlank()) {
            throw new IllegalArgumentException("skill 目录名不能为空");
        }
        // 禁止绝对路径直接拼入
        Path relPath = Paths.get(relativeDir);
        if (relPath.isAbsolute()) {
            throw new IllegalArgumentException("禁止使用绝对路径，必须相对于 base-path");
        }
        Path target = normalizedBase().resolve(relPath).normalize();
        // startsWith 检查 —— 穿越防护
        if (!target.startsWith(normalizedBase())) {
            throw new IllegalArgumentException("路径越界：目标不在 base-path 内");
        }
        return target;
    }

    // ============ 1. 列出 base-path 下所有可用 skill 目录 ============

    /**
     * 扫描 base-path 下所有包含主 prompt 文件的子目录（只扫一层子目录，不递归）
     *
     * @return 每个 skill 目录的元信息
     */
    public List<SkillDirInfo> scanDirs() {
        Path base = normalizedBase();
        List<SkillDirInfo> results = new ArrayList<>();

        if (!Files.isDirectory(base)) {
            log.info("Skill base-path 目录不存在，自动创建: {}", base);
            try {
                Files.createDirectories(base);
            } catch (IOException e) {
                log.warn("创建 skill base-path 失败: {}", base, e);
                return results;
            }
            return results;
        }

        try (Stream<Path> stream = Files.list(base)) {
            List<Path> dirs = stream.filter(Files::isDirectory).sorted().toList();
            for (Path dir : dirs) {
                SkillDirInfo info = buildSkillDirInfo(base, dir);
                if (info != null) results.add(info);
            }
        } catch (IOException e) {
            log.error("扫描 skill 目录失败: {}", base, e);
        }
        return results;
    }

    /**
     * 构造单个 skill 目录的元信息
     */
    private SkillDirInfo buildSkillDirInfo(Path base, Path dir) {
        String mainFile = findMainFile(dir);
        if (mainFile == null) return null;  // 没有主 prompt 文件就不算 skill

        try {
            String content = Files.readString(dir.resolve(mainFile));
            Map<String, String> frontMatter = parseFrontMatter(content);
            long size = Files.walk(dir)
                    .filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try { return Files.size(p); } catch (IOException e) { return 0; }
                    }).sum();
            int fileCount = (int) Files.walk(dir).filter(Files::isRegularFile).count();

            return new SkillDirInfo(
                    base.relativize(dir).toString(),
                    dir.getFileName().toString(),
                    dir.toAbsolutePath().toString(),
                    mainFile,
                    frontMatter.getOrDefault("description", ""),
                    frontMatter.getOrDefault("subModelId", ""),
                    fileCount,
                    size,
                    Files.getLastModifiedTime(dir.resolve(mainFile)).toMillis()
            );
        } catch (IOException e) {
            log.warn("读取 skill 目录信息失败: {}", dir, e);
            return null;
        }
    }

    /**
     * 按优先级在目录中找主 prompt 文件
     */
    private String findMainFile(Path dir) {
        for (String fn : MAIN_FILES) {
            if (Files.isRegularFile(dir.resolve(fn))) return fn;
        }
        return null;
    }

    // ============ 2. 读取某个 skill 目录的完整内容 ============

    /**
     * 读取指定 skill 的 SKILL.md 内容 + 目录文件树 + front matter
     *
     * @param relativeDir 目录名（相对于 base-path）
     * @return SkillContentInfo
     * @throws IOException 读文件失败
     */
    public SkillContentInfo readSkillContent(String relativeDir) throws IOException {
        Path dir = resolveSafePath(relativeDir);
        if (!Files.isDirectory(dir)) {
            throw new IOException("Skill 目录不存在: " + dir);
        }
        String mainFile = findMainFile(dir);
        if (mainFile == null) {
            throw new IOException("目录下没有 SKILL.md / prompt.md / README.md");
        }

        String content = Files.readString(dir.resolve(mainFile));
        Map<String, String> frontMatter = parseFrontMatter(content);

        // 文件树（相对路径列表）
        List<Map<String, Object>> fileTree = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(p -> !Files.isDirectory(p))
                    .forEach(p -> {
                        Map<String, Object> node = new LinkedHashMap<>();
                        node.put("name", p.getFileName().toString());
                        node.put("path", dir.relativize(p).toString());
                        node.put("size", p.toFile().length());
                        node.put("isMain", p.getFileName().toString().equals(mainFile));
                        fileTree.add(node);
                    });
        }

        return new SkillContentInfo(
                relativeDir,
                mainFile,
                content,
                frontMatter,
                fileTree
        );
    }

    // ============ 3. 保存 SKILL.md 内容 ============

    /**
     * 保存 SKILL.md 内容到磁盘（自动创建目录）
     *
     * @param relativeDir  目录名
     * @param content      SKILL.md 完整内容（含 front matter 可选）
     * @param createIfAbsent 如果目录不存在是否自动创建
     */
    public void saveSkillContent(String relativeDir, String content, boolean createIfAbsent) throws IOException {
        saveSkillContent(relativeDir, content, createIfAbsent, null, null);
    }

    /**
     * 保存 SKILL.md，并把前端编辑面板的 subModelId / description 回写进 front matter
     *
     * @param subModelId  null=不动 front matter 里的该 key；""=删除该 key；其他=写入/更新
     * @param description 同上
     */
    public void saveSkillContent(String relativeDir, String content, boolean createIfAbsent,
                                 String subModelId, String description) throws IOException {
        if (subModelId != null || description != null) {
            Map<String, String> updates = new LinkedHashMap<>();
            if (subModelId != null) updates.put("subModelId", subModelId);
            if (description != null) updates.put("description", description);
            content = upsertFrontMatter(content, updates);
        }
        Path dir = resolveSafePath(relativeDir);
        if (!Files.isDirectory(dir)) {
            if (createIfAbsent) {
                Files.createDirectories(dir);
            } else {
                throw new IOException("Skill 目录不存在: " + dir);
            }
        }
        // 优先写 SKILL.md（如果目录已有其他主文件，也统一改写成 SKILL.md 覆盖它）
        String existingMain = findMainFile(dir);
        Path targetFile = dir.resolve(existingMain != null ? existingMain : "SKILL.md");
        Files.writeString(targetFile, content);
        log.info("Skill 内容已保存: {}", targetFile);
    }

    /**
     * 更新/插入/删除 front matter 里的 key。
     * content 无 front matter 时自动在文件头创建 --- 块；value 为空字符串时删除该 key。
     */
    public String upsertFrontMatter(String content, Map<String, String> updates) {
        Matcher fm = FRONT_MATTER.matcher(content);
        Map<String, String> existing = new LinkedHashMap<>(parseFrontMatter(content));
        updates.forEach((k, v) -> {
            if (v == null || v.isBlank()) existing.remove(k);
            else existing.put(k, v);
        });

        StringBuilder yaml = new StringBuilder();
        existing.forEach((k, v) -> yaml.append(k).append(": ").append(v).append("\n"));

        String body = fm.find() ? content.substring(fm.end()) : content;
        if (existing.isEmpty()) return body;   // front matter 被清空 → 去掉整个块
        return "---\n" + yaml + "---\n" + body;
    }

    // ============ 4. 创建新 skill 目录 ============

    /**
     * 创建一个空的 skill 目录（含初始 SKILL.md 模板）
     *
     * @param relativeDir 目录名
     * @param displayName  显示名称（写入初始 SKILL.md 里）
     * @param description  描述（写入 front matter）
     * @param subModelId   子模型 ID（可选，非空才写入 front matter）
     */
    public Path createSkillDir(String relativeDir, String displayName, String description,
                               String subModelId) throws IOException {
        Path dir = resolveSafePath(relativeDir);
        if (Files.exists(dir)) {
            throw new IOException("目录已存在: " + dir);
        }
        Files.createDirectories(dir);

        String subModelLine = (subModelId != null && !subModelId.isBlank())
                ? "subModelId: " + subModelId.trim() + "\n" : "";
        String initialContent = """
                ---
                description: %s
                %s---
                ---SYSTEM---
                你是一个 %s。

                ---USER---
                {{message}}
                """.formatted(
                description != null ? description : displayName,
                subModelLine,
                displayName != null ? displayName : relativeDir
        );
        Files.writeString(dir.resolve("SKILL.md"), initialContent);
        log.info("Skill 目录已创建: {}", dir);
        return dir;
    }

    /**
     * 删除 skill 目录（危险操作，给后端保护）
     */
    public void deleteSkillDir(String relativeDir) throws IOException {
        Path dir = resolveSafePath(relativeDir);
        if (!Files.isDirectory(dir)) {
            throw new IOException("目录不存在: " + dir);
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
        log.info("Skill 目录已删除: {}", dir);
    }

    // ============ 工具方法 ============

    /** 极简 YAML front matter 解析（和 SkillToolExecutor.parseSimpleYaml 保持一致） */
    public Map<String, String> parseFrontMatter(String content) {
        Map<String, String> result = new HashMap<>();
        Matcher fm = FRONT_MATTER.matcher(content);
        if (fm.find()) {
            String yaml = fm.group(1);
            for (String line : yaml.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int colon = trimmed.indexOf(':');
                if (colon > 0) {
                    String key = trimmed.substring(0, colon).trim();
                    String value = trimmed.substring(colon + 1).trim();
                    if ((value.startsWith("\"") && value.endsWith("\""))
                            || (value.startsWith("'") && value.endsWith("'"))) {
                        value = value.substring(1, value.length() - 1);
                    }
                    result.put(key, value);
                }
            }
        }
        return result;
    }

    /** 返回 base-path（给前端显示用） */
    public String getBasePath() {
        return normalizedBase().toString();
    }

    // ============ DTO ============

    /** 子目录概览 */
    public record SkillDirInfo(
            String relativePath,   // 相对 base-path 的目录名，如 "weather"
            String dirName,        // 目录名本身
            String absolutePath,   // 绝对路径（executorConfig.skillPath 用这个）
            String mainFile,       // 主 prompt 文件名
            String description,    // front matter 里的 description
            String subModelId,     // front matter 里的 subModelId
            int fileCount,         // 目录下文件总数
            long totalSize,        // 字节
            long lastModified      // 主 prompt 文件修改时间（epoch ms）
    ) {}

    /** 完整内容读取结果 */
    public record SkillContentInfo(
            String relativePath,
            String mainFile,
            String content,        // 完整 SKILL.md 内容
            Map<String, String> frontMatter,
            List<Map<String, Object>> fileTree
    ) {}
}

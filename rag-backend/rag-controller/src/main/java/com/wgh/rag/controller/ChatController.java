package com.wgh.rag.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.chat.service.ChatWindowService;
import com.wgh.rag.chat.service.RagChatService;
import com.wgh.rag.chat.entity.ChatMessage;
import com.wgh.rag.chat.entity.ChatSession;
import com.wgh.rag.chat.entity.ChatWindow;
import com.wgh.rag.tool.entity.ToolConfig;
import com.wgh.rag.tool.executor.ToolExecutionEngine;
import com.wgh.rag.tool.service.SkillFileService;
import com.wgh.rag.tool.service.ToolConfigService;
import com.wgh.rag.common.result.PageResult;
import com.wgh.rag.common.result.Result;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.common.exception.BusinessException;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.springframework.security.core.context.SecurityContextHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天管理 - 管理端 + SSE 接口
 */
@Tag(name = "聊天管理")
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatWindowService windowService;
    private final RagChatService ragChatService;
    private final ToolConfigService toolConfigService;
    private final SkillFileService skillFileService;
    private final ToolExecutionEngine toolExecutionEngine;

    // ===== 管理端：聊天窗口 =====

    @Operation(summary = "分页查询聊天窗口")
    @GetMapping("/windows")
    public Result<PageResult<ChatWindow>> pageWindows(
            @RequestParam(defaultValue = "1") Long page,
            @RequestParam(defaultValue = "10") Long size,
            @RequestParam(required = false) String keyword) {
        return Result.success(windowService.page(page, size, keyword));
    }

    @Operation(summary = "获取窗口详情")
    @GetMapping("/windows/{id}")
    public Result<ChatWindow> getWindow(@PathVariable Long id) {
        return Result.success(windowService.getById(id));
    }

    @Operation(summary = "创建聊天窗口")
    @PostMapping("/windows")
    public Result<ChatWindow> createWindow(@Valid @RequestBody ChatWindow window) {
        return Result.success(windowService.create(window));
    }

    @Operation(summary = "编辑聊天窗口")
    @PutMapping("/windows")
    public Result<ChatWindow> updateWindow(@Valid @RequestBody ChatWindow window) {
        return Result.success(windowService.update(window));
    }

    @Operation(summary = "删除聊天窗口（软删除）")
    @DeleteMapping("/windows/{id}")
    public Result<Void> deleteWindow(@PathVariable Long id) {
        windowService.delete(id);
        return Result.success();
    }

    @Operation(summary = "停用聊天窗口")
    @PostMapping("/windows/{id}/disable")
    public Result<Void> disableWindow(@PathVariable Long id) {
        windowService.disable(id);
        return Result.success();
    }

    @Operation(summary = "启用聊天窗口")
    @PostMapping("/windows/{id}/enable")
    public Result<Void> enableWindow(@PathVariable Long id) {
        windowService.enable(id);
        return Result.success();
    }

    @Operation(summary = "启用分享")
    @PostMapping("/windows/{id}/share/enable")
    public Result<ChatWindow> enableShare(@PathVariable Long id) {
        return Result.success(windowService.enableShare(id));
    }

    @Operation(summary = "禁用分享")
    @PostMapping("/windows/{id}/share/disable")
    public Result<ChatWindow> disableShare(@PathVariable Long id) {
        return Result.success(windowService.disableShare(id));
    }

    @Operation(summary = "重新生成分享链接（旧 token 立即失效，历史会话保留）")
    @PostMapping("/windows/{id}/share/regenerate")
    public Result<ChatWindow> regenerateShare(@PathVariable Long id) {
        return Result.success(windowService.regenerateShareToken(id));
    }

    @Operation(summary = "分页查询聊天消息")
    @GetMapping("/windows/{windowId}/messages")
    public Result<PageResult<ChatMessage>> pageMessages(
            @PathVariable Long windowId,
            @RequestParam(defaultValue = "1") Long page,
            @RequestParam(defaultValue = "20") Long size,
            @RequestParam(required = false) Long sessionId,
            @RequestParam(defaultValue = "default_user") String userIdentifier) {
        return Result.success(windowService.pageMessages(windowId, userIdentifier, sessionId, page, size));
    }

    @Operation(summary = "列出窗口下的历史会话")
    @GetMapping("/windows/{windowId}/sessions")
    public Result<List<ChatSession>> listSessions(
            @PathVariable Long windowId,
            @RequestParam(defaultValue = "default_user") String userIdentifier) {
        return Result.success(windowService.listSessions(windowId, userIdentifier));
    }

    @Operation(summary = "新建会话")
    @PostMapping("/windows/{windowId}/sessions")
    public Result<ChatSession> createSession(
            @PathVariable Long windowId,
            @RequestParam(defaultValue = "default_user") String userIdentifier) {
        return Result.success(windowService.createSession(windowId, userIdentifier));
    }

    @Operation(summary = "删除会话（软删会话 + 清空消息）")
    @DeleteMapping("/windows/{windowId}/sessions/{sessionId}")
    public Result<Void> deleteSession(
            @PathVariable Long windowId,
            @PathVariable Long sessionId,
            @RequestParam(defaultValue = "default_user") String userIdentifier) {
        windowService.deleteSession(sessionId, userIdentifier);
        return Result.success();
    }

    // ===== SSE 流式聊天 =====

    @Operation(summary = "SSE 流式聊天（管理端）")
    @PostMapping(value = "/windows/{windowId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(
            @PathVariable Long windowId,
            @RequestBody Map<String, Object> params) {
        Long sessionId = params.get("sessionId") != null ? 
                Long.parseLong(params.get("sessionId").toString()) : null;
        String message = (String) params.get("message");
        return ragChatService.streamChat(windowId, sessionId, message);
    }

    // ===== 分享页面（需登录；userIdentifier 取当前登录用户名，与 shareToken 解耦） =====

    /**
     * 从 SecurityContext 获取当前登录用户名；分享链路下会话按 username + windowId 归属
     */
    private String currentUsername() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "未登录");
        }
        return auth.getName();
    }

    @Operation(summary = "获取分享窗口信息（需登录）")
    @GetMapping("/share/{shareToken}")
    public Result<ChatWindow> getShareWindow(@PathVariable String shareToken) {
        return Result.success(windowService.getByShareToken(shareToken));
    }

    @Operation(summary = "SSE 流式聊天（分享页面，需登录）")
    @PostMapping(value = "/share/{shareToken}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamShareChat(
            @PathVariable String shareToken,
            @RequestBody Map<String, Object> params) {
        String message = (String) params.get("message");
        Long sessionId = params.get("sessionId") != null
                ? Long.parseLong(params.get("sessionId").toString()) : null;
        return ragChatService.streamShareChat(shareToken, message, currentUsername(), sessionId);
    }

    @Operation(summary = "分享页面：列出当前登录用户的历史会话")
    @GetMapping("/share/{shareToken}/sessions")
    public Result<List<ChatSession>> shareSessions(@PathVariable String shareToken) {
        ChatWindow window = windowService.getByShareToken(shareToken);
        return Result.success(windowService.listSessions(window.getId(), currentUsername()));
    }

    @Operation(summary = "分享页面：新建会话")
    @PostMapping("/share/{shareToken}/sessions")
    public Result<ChatSession> shareCreateSession(@PathVariable String shareToken) {
        ChatWindow window = windowService.getByShareToken(shareToken);
        return Result.success(windowService.createSession(window.getId(), currentUsername()));
    }

    @Operation(summary = "分享页面：删除会话")
    @DeleteMapping("/share/{shareToken}/sessions/{sessionId}")
    public Result<Void> shareDeleteSession(
            @PathVariable String shareToken,
            @PathVariable Long sessionId) {
        ChatWindow window = windowService.getByShareToken(shareToken);
        windowService.deleteSession(sessionId, currentUsername());
        return Result.success();
    }

    @Operation(summary = "分享页面：分页查询会话消息")
    @GetMapping("/share/{shareToken}/messages")
    public Result<PageResult<ChatMessage>> shareMessages(
            @PathVariable String shareToken,
            @RequestParam(required = false) Long sessionId,
            @RequestParam(defaultValue = "1") Long page,
            @RequestParam(defaultValue = "20") Long size) {
        ChatWindow window = windowService.getByShareToken(shareToken);
        return Result.success(windowService.pageMessages(window.getId(), currentUsername(), sessionId, page, size));
    }

    // ===== 工具管理 =====

    @Operation(summary = "分页查询工具配置")
    @GetMapping("/tools")
    public Result<PageResult<ToolConfig>> pageTools(
            @RequestParam(defaultValue = "1") Long page,
            @RequestParam(defaultValue = "10") Long size,
            @RequestParam(required = false) String toolType,
            @RequestParam(required = false) String keyword) {
        return Result.success(toolConfigService.page(page, size, toolType, keyword));
    }

    @Operation(summary = "新增工具")
    @PostMapping("/tools")
    public Result<ToolConfig> createTool(@Valid @RequestBody ToolConfig tool) {
        return Result.success(toolConfigService.create(tool));
    }

    /**
     * 调试工具：直接调用 ToolExecutionEngine 执行指定工具，返回原始结果
     * <p>
     * 入参：
     * <pre>{ "arguments": { "city": "北京", "userId": "U1001" } }</pre>
     * 出参：
     * <pre>{ "success": true, "resultText": "...", "durationMs": 12, "errorMessage": null }</pre>
     * <p>
     * 注：SKILL 类型会走完整 LLM 链路（包括子模型继承），需要窗口默认模型兜底；
     * 调试时不依赖具体窗口，因此 $windowDefaultModelId 注入 null，由 executor 降级。
     */
    @Operation(summary = "调试工具（直接执行，返回原始结果）")
    @PostMapping("/tools/{id}/debug")
    public Result<Map<String, Object>> debugTool(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body) {
        ToolConfig tool = toolConfigService.getById(id);
        if (tool == null) throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "工具不存在");
        Map<String, Object> args = body != null && body.get("arguments") instanceof Map
                ? (Map<String, Object>) body.get("arguments")
                : Map.of();

        // 构造 ToolExecutionRequest（不走 LLM 链路，直接执行）
        String toolName;
        try {
            var fnNode = com.wgh.rag.tool.definition.ToolDefinitionParser.extractFunctionNode(tool.getDefinition());
            toolName = (String) fnNode.getOrDefault("name", "debug_tool");
        } catch (Exception e) {
            toolName = "debug_tool";
        }
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("debug-" + System.currentTimeMillis())
                .name(toolName)
                .arguments(toJson(args))
                .build();

        // 单元素注册表 —— 用工具自身 definition 里的 function.name 作为 key
        Map<String, ToolConfig> registry = Map.of(toolName, tool);

        long t0 = System.currentTimeMillis();
        ToolExecutionResultMessage resultMsg = toolExecutionEngine.execute(request, registry, null);
        long duration = System.currentTimeMillis() - t0;

        String resultText = resultMsg.text();
        boolean success = resultText != null && !resultText.startsWith("Error:") && !resultText.startsWith("HTTP 4") && !resultText.startsWith("HTTP 5");
        Map<String, Object> out = new HashMap<>();
        out.put("success", success);
        out.put("resultText", resultText);
        out.put("durationMs", duration);
        out.put("toolName", toolName);
        out.put("toolType", tool.getToolType());
        return Result.success(out);
    }

    private static String toJson(Object o) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }

    @Operation(summary = "编辑工具")
    @PutMapping("/tools")
    public Result<ToolConfig> updateTool(@Valid @RequestBody ToolConfig tool) {
        return Result.success(toolConfigService.update(tool));
    }

    @Operation(summary = "删除工具")
    @DeleteMapping("/tools/{id}")
    public Result<Void> deleteTool(@PathVariable Long id) {
        toolConfigService.delete(id);
        return Result.success();
    }

    @Operation(summary = "按类型列出工具（toolType 省略则返回全部启用工具）")
    @GetMapping("/tools/list")
    public Result<List<ToolConfig>> listToolsByType(@RequestParam(required = false) String toolType) {
        return Result.success(toolConfigService.listByType(toolType));
    }

    // ===== Skill 专用 API =====

    @Operation(summary = "列出 skill base-path 下所有可用 skill 目录")
    @GetMapping("/tools/skill/dirs")
    public Result<Map<String, Object>> scanSkillDirs() {
        Map<String, Object> res = new java.util.LinkedHashMap<>();
        res.put("basePath", skillFileService.getBasePath());
        res.put("skills", skillFileService.scanDirs());
        return Result.success(res);
    }

    @Operation(summary = "读取某个 skill 的完整 SKILL.md 内容 + front matter + 文件树")
    @GetMapping("/tools/skill/content")
    public Result<SkillFileService.SkillContentInfo> readSkillContent(
            @RequestParam String dir) throws java.io.IOException {
        return Result.success(skillFileService.readSkillContent(dir));
    }

    @Operation(summary = "保存 SKILL.md 内容到磁盘（在线编辑，可选回写 front matter）")
    @PostMapping("/tools/skill/save")
    public Result<Void> saveSkillContent(@RequestBody Map<String, Object> body) throws java.io.IOException {
        String dir = (String) body.get("dir");
        String content = (String) body.get("content");
        Boolean createIfAbsent = (Boolean) body.getOrDefault("createIfAbsent", false);
        // 可选：前端编辑面板的子模型/描述 → 回写进 SKILL.md front matter
        // key 存在但为空串 = 删除该 front matter 键；key 不存在 = 不动
        String subModelId = body.containsKey("subModelId") ? (String) body.get("subModelId") : null;
        String description = body.containsKey("description") ? (String) body.get("description") : null;
        skillFileService.saveSkillContent(dir, content, Boolean.TRUE.equals(createIfAbsent),
                subModelId, description);
        return Result.success();
    }

    @Operation(summary = "创建新的空 skill 目录（带初始 SKILL.md 模板）")
    @PostMapping("/tools/skill/create-dir")
    public Result<Map<String, Object>> createSkillDir(@RequestBody Map<String, String> body) throws java.io.IOException {
        String dir = body.get("dir");
        String displayName = body.get("displayName");
        String description = body.get("description");
        java.nio.file.Path created = skillFileService.createSkillDir(dir, displayName, description,
                body.get("subModelId"));
        Map<String, Object> res = new java.util.LinkedHashMap<>();
        res.put("absolutePath", created.toAbsolutePath().toString());
        return Result.success(res);
    }

    @Operation(summary = "删除 skill 目录（危险操作）")
    @DeleteMapping("/tools/skill/dir")
    public Result<Void> deleteSkillDir(@RequestParam String dir) throws java.io.IOException {
        skillFileService.deleteSkillDir(dir);
        return Result.success();
    }

    private final ObjectMapper om = new ObjectMapper();

    @Operation(summary = "将磁盘 skill 目录导入为 ToolConfig DB 记录（同名时跳过，并返回 duplicated=true）")
    @PostMapping("/tools/skill/import")
    public Result<Map<String, Object>> importSkill(@RequestBody Map<String, String> body) throws java.io.IOException {
        String dir = body.get("dir");
        String displayName = body.get("name");  // 可选：覆盖数据库工具名
        Long subModelId = null;
        String subModelIdStr = body.get("subModelId");
        if (subModelIdStr != null && !subModelIdStr.isBlank()) {
            subModelId = Long.parseLong(subModelIdStr);
        }

        // 1. 从磁盘读取 skill 内容，构造 ToolConfig
        SkillFileService.SkillDirInfo dirInfo = skillFileService.scanDirs().stream()
                .filter(d -> d.relativePath().equals(dir))
                .findFirst()
                .orElse(null);
        if (dirInfo == null) {
            // 即使 scanDirs 没扫到（比如目录刚好被删了），也尝试读取一下
            var content = skillFileService.readSkillContent(dir);
            dirInfo = new SkillFileService.SkillDirInfo(dir, dir, dir,
                    content.mainFile(),
                    content.frontMatter().getOrDefault("description", ""),
                    content.frontMatter().getOrDefault("subModelId", ""),
                    0, 0, 0);
        }

        // 2. 同名（name + toolType=SKILL + 未删除）记录已存在 → 跳过导入，直接返回提示
        String finalName = displayName != null ? displayName : dirInfo.dirName();
        ToolConfig existing = toolConfigService.findSkillByName(finalName);
        if (existing != null) {
            Map<String, Object> dup = new HashMap<>();
            dup.put("duplicated", true);
            dup.put("tool", existing);
            dup.put("hint", "数据库已存在同名 skill「" + finalName + "」(id=" + existing.getId() + ")，本次未做导入或更新");
            return Result.success(dup);
        }

        // 3. 解析 front matter 里的 subModelId（如果调用方没显式传）
        if (subModelId == null && !dirInfo.subModelId().isBlank()) {
            try { subModelId = Long.parseLong(dirInfo.subModelId()); } catch (NumberFormatException ignored) {}
        }

        // 4. 构造 executorConfig JSON（directory 模式）
        Map<String, Object> executor = new java.util.LinkedHashMap<>();
        executor.put("mode", "directory");
        executor.put("skillPath", dirInfo.absolutePath());
        executor.put("includeResources", true);
        if (subModelId != null) executor.put("subModelId", subModelId);

        // 5. 构造 ToolConfig（definition 里放 OpenAI function 格式描述）
        ToolConfig tool = new ToolConfig();
        tool.setName(finalName);
        tool.setToolType("SKILL");
        tool.setDescription(dirInfo.description().isBlank() ? dirInfo.dirName() + " skill" : dirInfo.description());
        tool.setExecutorConfig(om.writeValueAsString(executor));
        tool.setStatus(1);
        tool.setIsExample(false);

        // definition —— 简单的 OpenAI function 占位，实际 definition 由用户后续在 UI 补
        Map<String, Object> def = new java.util.LinkedHashMap<>();
        def.put("type", "function");
        Map<String, Object> func = new java.util.LinkedHashMap<>();
        func.put("name", dirInfo.dirName());
        func.put("description", tool.getDescription());
        func.put("parameters", Map.of("type", "object", "properties", Map.of()));
        def.put("function", func);
        tool.setDefinition(om.writeValueAsString(def));

        ToolConfig created = toolConfigService.create(tool);
        Map<String, Object> ok = new HashMap<>();
        ok.put("duplicated", false);
        ok.put("tool", created);
        return Result.success(ok);
    }
}

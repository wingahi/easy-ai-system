package com.wgh.rag.controller.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.auth.constant.AuthConstants;
import com.wgh.rag.controller.support.RunAccessGuard;
import com.wgh.rag.controller.support.SseExecutionStreamSink;
import com.wgh.rag.controller.support.SseReplaySupport;
import com.wgh.rag.chat.constant.ChatConstants;
import com.wgh.rag.chat.service.ChatImageService;
import com.wgh.rag.chat.service.ChatCancellationStore;
import com.wgh.rag.chat.service.ChatWindowService;
import com.wgh.rag.chat.service.ChatWorkflowService;
import com.wgh.rag.chat.service.FeedbackService;
import com.wgh.rag.chat.service.RagChatService;
import com.wgh.rag.chat.service.stream.ChatStreamHandle;
import com.wgh.rag.chat.service.stream.replay.StreamReplayService;
import com.wgh.rag.chat.entity.ChatMessage;
import com.wgh.rag.chat.entity.ChatSession;
import com.wgh.rag.chat.entity.ChatWindow;
import com.wgh.rag.tool.entity.ToolConfig;
import com.wgh.rag.tool.executor.ToolExecutionEngine;
import com.wgh.rag.tool.service.SkillFileService;
import com.wgh.rag.tool.service.ToolConfigService;
import com.wgh.rag.log.annotation.Audit;
import com.wgh.rag.common.result.PageResult;
import com.wgh.rag.common.result.Result;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.security.LoginUser;
import com.wgh.rag.common.security.RequiresPermission;
import com.wgh.rag.execution.event.ExecutionStreamSink;
import com.wgh.rag.workflow.service.WorkflowService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.springframework.security.core.context.SecurityContextHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
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
    private final ChatWorkflowService chatWorkflowService;
    private final FeedbackService feedbackService;
    private final ChatImageService chatImageService;
    private final WorkflowService workflowService;
    private final ToolConfigService toolConfigService;
    private final SkillFileService skillFileService;
    private final ToolExecutionEngine toolExecutionEngine;
    private final RunAccessGuard runAccessGuard;
    /** 跨实例取消标记：显式停止写入 Redis，由流式实例轮询感知 */
    private final ChatCancellationStore chatCancellationStore;
    /** SSE 事件重放：按 Last-Event-ID 从共享缓冲补齐缺失事件 */
    private final StreamReplayService streamReplayService;

    // ===== 管理端：聊天窗口 =====

    @Operation(summary = "分页查询聊天窗口")
    @GetMapping("/windows")
    @RequiresPermission(AuthConstants.PERM_CHAT_MANAGE)
    public Result<PageResult<ChatWindow>> pageWindows(
            @RequestParam(defaultValue = "1") Long page,
            @RequestParam(defaultValue = "10") Long size,
            @RequestParam(required = false) String keyword) {
        return Result.success(windowService.page(page, size, keyword));
    }

    @Operation(summary = "获取窗口详情")
    @GetMapping("/windows/{id}")
    public Result<ChatWindow> getWindow(@PathVariable Long id) {
        return Result.success(windowService.getVisible(id));
    }

    @Operation(summary = "修改聊天窗口可见性")
    @PatchMapping("/windows/{id}/visibility")
    @Audit(action = "WINDOW_VISIBILITY_UPDATE", resourceType = "CHAT_WINDOW")
    @RequiresPermission(AuthConstants.PERM_CHAT_MANAGE)
    public Result<ChatWindow> updateWindowVisibility(@PathVariable Long id,
                                                     @RequestBody Map<String, String> body) {
        return Result.success(windowService.updateVisibility(id, body.get("visibility")));
    }

    @Operation(summary = "创建聊天窗口")
    @PostMapping("/windows")
    @Audit(action = "WINDOW_CREATE", resourceType = "CHAT_WINDOW")
    @RequiresPermission(AuthConstants.PERM_CHAT_MANAGE)
    public Result<ChatWindow> createWindow(@Valid @RequestBody ChatWindow window) {
        return Result.success(windowService.create(window));
    }

    @Operation(summary = "编辑聊天窗口")
    @PutMapping("/windows")
    @Audit(action = "WINDOW_UPDATE", resourceType = "CHAT_WINDOW")
    @RequiresPermission(AuthConstants.PERM_CHAT_MANAGE)
    public Result<ChatWindow> updateWindow(@Valid @RequestBody ChatWindow window) {
        return Result.success(windowService.update(window));
    }

    @Operation(summary = "删除聊天窗口（软删除）")
    @DeleteMapping("/windows/{id}")
    @Audit(action = "WINDOW_DELETE", resourceType = "CHAT_WINDOW")
    @RequiresPermission(AuthConstants.PERM_CHAT_MANAGE)
    public Result<Void> deleteWindow(@PathVariable Long id) {
        windowService.delete(id);
        return Result.success();
    }

    @Operation(summary = "停用聊天窗口")
    @PostMapping("/windows/{id}/disable")
    @RequiresPermission(AuthConstants.PERM_CHAT_MANAGE)
    public Result<Void> disableWindow(@PathVariable Long id) {
        windowService.disable(id);
        return Result.success();
    }

    @Operation(summary = "启用聊天窗口")
    @PostMapping("/windows/{id}/enable")
    @RequiresPermission(AuthConstants.PERM_CHAT_MANAGE)
    public Result<Void> enableWindow(@PathVariable Long id) {
        windowService.enable(id);
        return Result.success();
    }

    @Operation(summary = "启用分享")
    @PostMapping("/windows/{id}/share/enable")
    @RequiresPermission(AuthConstants.PERM_CHAT_MANAGE)
    public Result<ChatWindow> enableShare(@PathVariable Long id) {
        return Result.success(windowService.enableShare(id));
    }

    @Operation(summary = "禁用分享")
    @PostMapping("/windows/{id}/share/disable")
    @RequiresPermission(AuthConstants.PERM_CHAT_MANAGE)
    public Result<ChatWindow> disableShare(@PathVariable Long id) {
        return Result.success(windowService.disableShare(id));
    }

    @Operation(summary = "重新生成分享链接（旧 token 立即失效，历史会话保留）")
    @PostMapping("/windows/{id}/share/regenerate")
    @RequiresPermission(AuthConstants.PERM_CHAT_MANAGE)
    public Result<ChatWindow> regenerateShare(@PathVariable Long id) {
        return Result.success(windowService.regenerateShareToken(id));
    }

    @Operation(summary = "分页查询聊天消息")
    @GetMapping("/windows/{windowId}/messages")
    public Result<PageResult<ChatMessage>> pageMessages(
            @PathVariable Long windowId,
            @RequestParam(defaultValue = "1") Long page,
            @RequestParam(defaultValue = "20") Long size,
            @RequestParam(required = false) Long sessionId) {
        return Result.success(windowService.pageMessages(windowId, currentUsername(), sessionId, page, size));
    }

    @Operation(summary = "提交消息反馈（1 赞 / -1 踩 / 0 取消，仅助手消息）")
    @PostMapping("/messages/{id}/feedback")
    public Result<Void> submitFeedback(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        Integer feedback = body.get("feedback") != null
                ? Integer.parseInt(body.get("feedback").toString()) : null;
        String comment = body.get("comment") != null ? body.get("comment").toString() : null;
        feedbackService.submitFeedback(id, feedback, comment);
        return Result.success();
    }

    @Operation(summary = "窗口满意度统计（赞/踩/满意率）")
    @GetMapping("/windows/{windowId}/feedback/stats")
    @RequiresPermission(AuthConstants.PERM_CHAT_MANAGE)
    public Result<Map<String, Object>> feedbackStats(@PathVariable Long windowId) {
        return Result.success(feedbackService.windowStats(windowId));
    }

    @Operation(summary = "列出窗口下的历史会话")
    @GetMapping("/windows/{windowId}/sessions")
    public Result<List<ChatSession>> listSessions(@PathVariable Long windowId) {
        return Result.success(windowService.listSessions(windowId, currentUsername()));
    }

    @Operation(summary = "新建会话")
    @PostMapping("/windows/{windowId}/sessions")
    public Result<ChatSession> createSession(@PathVariable Long windowId) {
        return Result.success(windowService.createSession(windowId, currentUsername()));
    }

    @Operation(summary = "删除会话（软删会话 + 清空消息）")
    @DeleteMapping("/windows/{windowId}/sessions/{sessionId}")
    public Result<Void> deleteSession(
            @PathVariable Long windowId,
            @PathVariable Long sessionId) {
        windowService.deleteSession(windowId, sessionId, currentUsername());
        return Result.success();
    }

    // ===== SSE 流式聊天 =====

    @Operation(summary = "上传聊天图片（多模态问答，返回 data URL）")
    @PostMapping("/upload")
    @Audit(action = "CHAT_IMAGE_UPLOAD", resourceType = "CHAT")
    public Result<Map<String, String>> uploadImage(@RequestParam("file") MultipartFile file) {
        String dataUrl = chatImageService.store(file);
        return Result.success(Map.of("dataUrl", dataUrl, "name", file.getOriginalFilename() != null ? file.getOriginalFilename() : "image"));
    }

    @Operation(summary = "SSE 流式聊天（管理端）")
    @PostMapping(value = "/windows/{windowId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(
            @PathVariable Long windowId,
            @RequestHeader(value = SseReplaySupport.HEADER_LAST_EVENT_ID, required = false) String lastEventIdHeader,
            @RequestBody Map<String, Object> params) {
        Long sessionId = params.get("sessionId") != null ?
                Long.parseLong(params.get("sessionId").toString()) : null;
        String lastEventId = StreamReplayService.resolveLastEventId(lastEventIdHeader, params.get("lastEventId"));
        if (lastEventId != null) {
            return resumeChatStream(windowId, sessionId, lastEventId);
        }
        String message = (String) params.get("message");
        List<String> imageUrls = parseImageUrls(params.get("imageUrls"));
        SseEmitter emitter = newEmitter();
        ChatStreamHandle handle = ragChatService.streamChat(windowId, sessionId, message, false, imageUrls,
                new SseExecutionStreamSink(emitter));
        wireLifecycle(emitter, handle);
        return emitter;
    }

    /**
     * 按 {@code Last-Event-ID} 重连对话流：只回放/跟随已有事件，不重新生成、不重复扣费。
     * 流不存在/已过期/已被新生成取代时返回明确错误，由客户端回退到会话历史。
     */
    private SseEmitter resumeChatStream(Long windowId, Long sessionId, String lastEventId) {
        if (sessionId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "重连需要提供 sessionId");
        }
        windowService.getById(windowId);
        windowService.getOwnedSession(windowId, sessionId, currentUsername());
        SseEmitter emitter = newEmitter();
        streamReplayService.resume(StreamReplayService.KIND_CHAT, sessionId, lastEventId,
                new SseExecutionStreamSink(emitter), SseReplaySupport.wireCloseFlag(emitter));
        return emitter;
    }

    @SuppressWarnings("unchecked")
    private List<String> parseImageUrls(Object raw) {
        if (raw instanceof List<?> list) {
            return (List<String>) list;
        }
        return null;
    }

    // ===== 显式停止生成（多实例） =====

    @Operation(summary = "停止会话当前的流式生成（显式停止，多实例生效）")
    @PostMapping("/windows/{windowId}/sessions/{sessionId}/stop")
    public Result<Void> stopSession(@PathVariable Long windowId, @PathVariable Long sessionId) {
        windowService.getById(windowId);
        windowService.getOwnedSession(windowId, sessionId, currentUsername());
        chatCancellationStore.cancel(ChatCancellationStore.sessionKey(sessionId), "explicit");
        return Result.success();
    }

    @Operation(summary = "分享页面：停止会话当前的流式生成（显式停止，多实例生效）")
    @PostMapping("/share/{shareToken}/sessions/{sessionId}/stop")
    public Result<Void> shareStopSession(@PathVariable String shareToken, @PathVariable Long sessionId) {
        ChatWindow window = windowService.getByShareToken(shareToken);
        windowService.getOwnedSession(window.getId(), sessionId, currentUsername());
        chatCancellationStore.cancel(ChatCancellationStore.sessionKey(sessionId), "explicit");
        return Result.success();
    }

    // ===== 分享页面（需登录；userIdentifier 取当前登录用户名，与 shareToken 解耦） =====

    /**
     * 从 SecurityContext 获取当前登录用户名；分享链路下会话按 username + windowId 归属
     */
    private String currentUsername() {
        return com.wgh.rag.common.security.SecurityUtils.currentUsername();
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
            @RequestHeader(value = SseReplaySupport.HEADER_LAST_EVENT_ID, required = false) String lastEventIdHeader,
            @RequestBody Map<String, Object> params) {
        String message = (String) params.get("message");
        Long sessionId = params.get("sessionId") != null
                ? Long.parseLong(params.get("sessionId").toString()) : null;
        String lastEventId = StreamReplayService.resolveLastEventId(lastEventIdHeader, params.get("lastEventId"));
        if (lastEventId != null) {
            if (sessionId == null) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "重连需要提供 sessionId");
            }
            ChatWindow shareWindow = windowService.getByShareToken(shareToken);
            windowService.getOwnedSession(shareWindow.getId(), sessionId, currentUsername());
            SseEmitter resumeEmitter = newEmitter();
            streamReplayService.resume(StreamReplayService.KIND_CHAT, sessionId, lastEventId,
                    new SseExecutionStreamSink(resumeEmitter), SseReplaySupport.wireCloseFlag(resumeEmitter));
            return resumeEmitter;
        }
        List<String> imageUrls = parseImageUrls(params.get("imageUrls"));
        SseEmitter emitter = newEmitter();
        ChatStreamHandle handle = ragChatService.streamShareChat(shareToken, message, currentUsername(),
                sessionId, imageUrls, new SseExecutionStreamSink(emitter));
        wireLifecycle(emitter, handle);
        return emitter;
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

    // ===== 工作流运行（聊天窗口绑定工作流） =====

    @Operation(summary = "查询会话最近一次工作流运行状态（调试页刷新恢复）")
    @GetMapping("/windows/{windowId}/sessions/{sessionId}/workflow-state")
    public Result<Map<String, Object>> workflowState(
            @PathVariable Long windowId, @PathVariable Long sessionId) {
        windowService.getById(windowId);
        windowService.getOwnedSession(windowId, sessionId, currentUsername());
        return Result.success(chatWorkflowService.getSessionRunState(sessionId));
    }

    @Operation(summary = "工作流 HITL 确认（调试页，SSE）")
    @PostMapping(value = "/windows/{windowId}/workflow-runs/{runId}/hitl-confirm",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter workflowHitlConfirm(
            @PathVariable Long windowId,
            @PathVariable Long runId,
            @RequestHeader(value = SseReplaySupport.HEADER_LAST_EVENT_ID, required = false) String lastEventIdHeader,
            @RequestBody(required = false) Map<String, Object> params) {
        windowService.getById(windowId);
        runAccessGuard.assertAccessible(runId);
        Long sessionId = parseSessionId(params);
        if (sessionId != null) {
            windowService.getOwnedSession(windowId, sessionId, currentUsername());
        }
        String lastEventId = StreamReplayService.resolveLastEventId(lastEventIdHeader,
                params == null ? null : params.get("lastEventId"));
        if (lastEventId != null) {
            return resumeHitlStream(StreamReplayService.KIND_WORKFLOW_HITL, runId, lastEventId);
        }
        SseEmitter emitter = newEmitter();
        ExecutionStreamSink sink = streamReplayService.open(StreamReplayService.KIND_WORKFLOW_HITL,
                runId, new SseExecutionStreamSink(emitter));
        chatWorkflowService.hitlConfirm(runId, sessionId, extractUserInput(params), false, sink);
        return emitter;
    }

    /** 按 {@code Last-Event-ID} 重连执行流：只回放/跟随，不重复执行 HITL 确认。 */
    private SseEmitter resumeHitlStream(String kind, Long subjectId, String lastEventId) {
        SseEmitter emitter = newEmitter();
        streamReplayService.resume(kind, subjectId, lastEventId,
                new SseExecutionStreamSink(emitter), SseReplaySupport.wireCloseFlag(emitter));
        return emitter;
    }

    @Operation(summary = "取消工作流运行（调试页）")
    @PostMapping("/windows/{windowId}/workflow-runs/{runId}/cancel")
    public Result<Void> workflowCancel(@PathVariable Long windowId, @PathVariable Long runId) {
        windowService.getById(windowId);
        runAccessGuard.assertAccessible(runId);
        workflowService.cancel(runId);
        return Result.success();
    }

    @Operation(summary = "分享页面：查询会话最近一次工作流运行状态")
    @GetMapping("/share/{shareToken}/sessions/{sessionId}/workflow-state")
    public Result<Map<String, Object>> shareWorkflowState(
            @PathVariable String shareToken, @PathVariable Long sessionId) {
        ChatWindow window = windowService.getByShareToken(shareToken);
        windowService.getOwnedSession(window.getId(), sessionId, currentUsername());
        return Result.success(chatWorkflowService.getSessionRunState(sessionId));
    }

    @Operation(summary = "分享页面：工作流 HITL 确认（安全模式，SSE）")
    @PostMapping(value = "/share/{shareToken}/workflow-runs/{runId}/hitl-confirm",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter shareWorkflowHitlConfirm(
            @PathVariable String shareToken,
            @PathVariable Long runId,
            @RequestHeader(value = SseReplaySupport.HEADER_LAST_EVENT_ID, required = false) String lastEventIdHeader,
            @RequestBody(required = false) Map<String, Object> params) {
        ChatWindow window = windowService.getByShareToken(shareToken);
        Long sessionId = parseSessionId(params);
        if (sessionId != null) {
            windowService.getOwnedSession(window.getId(), sessionId, currentUsername());
        }
        String lastEventId = StreamReplayService.resolveLastEventId(lastEventIdHeader,
                params == null ? null : params.get("lastEventId"));
        if (lastEventId != null) {
            return resumeHitlStream(StreamReplayService.KIND_WORKFLOW_HITL, runId, lastEventId);
        }
        SseEmitter emitter = newEmitter();
        ExecutionStreamSink sink = streamReplayService.open(StreamReplayService.KIND_WORKFLOW_HITL,
                runId, new SseExecutionStreamSink(emitter));
        chatWorkflowService.hitlConfirm(runId, sessionId, extractUserInput(params), true, sink);
        return emitter;
    }

    @Operation(summary = "分享页面：取消工作流运行")
    @PostMapping("/share/{shareToken}/workflow-runs/{runId}/cancel")
    public Result<Void> shareWorkflowCancel(
            @PathVariable String shareToken,
            @PathVariable Long runId,
            @RequestParam(required = false) Long sessionId) {
        ChatWindow window = windowService.getByShareToken(shareToken);
        if (sessionId != null) {
            windowService.getOwnedSession(window.getId(), sessionId, currentUsername());
        }
        workflowService.cancel(runId);
        return Result.success();
    }

    private Long parseSessionId(Map<String, Object> params) {
        if (params == null || params.get("sessionId") == null) return null;
        String raw = String.valueOf(params.get("sessionId"));
        if (raw.isBlank() || "null".equals(raw)) return null;
        return Long.parseLong(raw);
    }

    /** 传输层构造 SSE 通道；执行域只接收 sink，不感知 SseEmitter。 */
    private static SseEmitter newEmitter() {
        return new SseEmitter(ChatConstants.SSE_TIMEOUT_MS);
    }

    /** 断连/超时/完成时取消生成，语义与重构前在服务内注册的回调一致。 */
    private static void wireLifecycle(SseEmitter emitter, ChatStreamHandle handle) {
        emitter.onTimeout(handle::onTimeout);
        emitter.onError(handle::onError);
        emitter.onCompletion(handle::onCompletion);
    }

    private String extractUserInput(Map<String, Object> params) {
        if (params == null || params.get("userInput") == null) return "";
        return String.valueOf(params.get("userInput"));
    }

    // ===== 工具管理 =====

    @Operation(summary = "分页查询工具配置")
    @GetMapping("/tools")
    @RequiresPermission(AuthConstants.PERM_TOOL_USE)
    public Result<PageResult<ToolConfig>> pageTools(
            @RequestParam(defaultValue = "1") Long page,
            @RequestParam(defaultValue = "10") Long size,
            @RequestParam(required = false) String toolType,
            @RequestParam(required = false) String keyword) {
        PageResult<ToolConfig> result = toolConfigService.page(page, size, toolType, keyword);
        maskExecutorConfig(result.getRecords());
        return Result.success(result);
    }

    @Operation(summary = "新增工具")
    @PostMapping("/tools")
    @Audit(action = "TOOL_CREATE", resourceType = "TOOL")
    @RequiresPermission(AuthConstants.PERM_CHAT_MANAGE)
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
    @RequiresPermission(AuthConstants.PERM_TOOL_USE)
    public Result<Map<String, Object>> debugTool(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body) {
        ToolConfig tool = toolConfigService.getVisible(id);
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
    @Audit(action = "TOOL_UPDATE", resourceType = "TOOL")
    @RequiresPermission(AuthConstants.PERM_CHAT_MANAGE)
    public Result<ToolConfig> updateTool(@Valid @RequestBody ToolConfig tool) {
        return Result.success(toolConfigService.update(tool));
    }

    @Operation(summary = "修改工具可见性")
    @PatchMapping("/tools/{id}/visibility")
    @Audit(action = "TOOL_VISIBILITY_UPDATE", resourceType = "TOOL")
    @RequiresPermission(AuthConstants.PERM_CHAT_MANAGE)
    public Result<ToolConfig> updateToolVisibility(@PathVariable Long id,
                                                   @RequestBody Map<String, String> body) {
        return Result.success(toolConfigService.updateVisibility(id, body.get("visibility")));
    }

    @Operation(summary = "删除工具")
    @DeleteMapping("/tools/{id}")
    @Audit(action = "TOOL_DELETE", resourceType = "TOOL")
    @RequiresPermission(AuthConstants.PERM_CHAT_MANAGE)
    public Result<Void> deleteTool(@PathVariable Long id) {
        toolConfigService.delete(id);
        return Result.success();
    }

    @Operation(summary = "按类型列出工具（toolType 省略则返回全部启用工具）")
    @GetMapping("/tools/list")
    @RequiresPermission(AuthConstants.PERM_TOOL_USE)
    public Result<List<ToolConfig>> listToolsByType(@RequestParam(required = false) String toolType) {
        List<ToolConfig> tools = toolConfigService.listByType(toolType);
        maskExecutorConfig(tools);
        return Result.success(tools);
    }

    /**
     * executorConfig 可能包含内部地址与调用凭证，仅对具备管理权限的账号回显；
     * 普通使用者在列表中拿到 null，不影响其对工具的调用。
     */
    static void maskExecutorConfig(List<ToolConfig> tools) {
        if (tools == null || tools.isEmpty()) {
            return;
        }
        LoginUser user = LoginUser.get();
        if (user != null && user.getPermissions() != null
                && user.getPermissions().contains(AuthConstants.PERM_CHAT_MANAGE)) {
            return;
        }
        tools.forEach(tool -> tool.setExecutorConfig(null));
    }

    // ===== Skill 专用 API =====

    @Operation(summary = "列出 skill base-path 下所有可用 skill 目录")
    @GetMapping("/tools/skill/dirs")
    @RequiresPermission(AuthConstants.PERM_TOOL_USE)
    public Result<Map<String, Object>> scanSkillDirs() {
        Map<String, Object> res = new java.util.LinkedHashMap<>();
        res.put("basePath", skillFileService.getBasePath());
        res.put("skills", skillFileService.scanDirs());
        return Result.success(res);
    }

    @Operation(summary = "读取某个 skill 的完整 SKILL.md 内容 + front matter + 文件树")
    @GetMapping("/tools/skill/content")
    @RequiresPermission(AuthConstants.PERM_TOOL_USE)
    public Result<SkillFileService.SkillContentInfo> readSkillContent(
            @RequestParam String dir) throws java.io.IOException {
        return Result.success(skillFileService.readSkillContent(dir));
    }

    @Operation(summary = "保存 SKILL.md 内容到磁盘（在线编辑，可选回写 front matter）")
    @PostMapping("/tools/skill/save")
    @RequiresPermission(AuthConstants.PERM_CHAT_MANAGE)
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
    @RequiresPermission(AuthConstants.PERM_CHAT_MANAGE)
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
    @RequiresPermission(AuthConstants.PERM_CHAT_MANAGE)
    public Result<Void> deleteSkillDir(@RequestParam String dir) throws java.io.IOException {
        skillFileService.deleteSkillDir(dir);
        return Result.success();
    }

    private final ObjectMapper om = new ObjectMapper();

    @Operation(summary = "将磁盘 skill 目录导入为 ToolConfig DB 记录（同名时跳过，并返回 duplicated=true）")
    @PostMapping("/tools/skill/import")
    @RequiresPermission(AuthConstants.PERM_CHAT_MANAGE)
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

package com.wgh.rag.publicaccess.controller;

import com.wgh.rag.chat.entity.ChatSession;
import com.wgh.rag.chat.entity.ChatWindow;
import com.wgh.rag.chat.mapper.ChatSessionMapper;
import com.wgh.rag.chat.service.ChatWindowService;
import com.wgh.rag.chat.service.RagChatService;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.PageResult;
import com.wgh.rag.common.result.Result;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.kb.entity.KnowledgeBase;
import com.wgh.rag.kb.mapper.KnowledgeBaseMapper;
import com.wgh.rag.kb.service.RagRetrieveService;
import com.wgh.rag.kb.service.RetrievalOptions;
import com.wgh.rag.publicaccess.config.PublicAccessProperties;
import com.wgh.rag.publicaccess.entity.ShareLink;
import com.wgh.rag.publicaccess.service.ClientIpResolver;
import com.wgh.rag.publicaccess.service.EmbedAccessService;
import com.wgh.rag.publicaccess.service.PublicAccessRateLimiter;
import com.wgh.rag.publicaccess.service.ShareLinkService;
import com.wgh.rag.publicaccess.service.SharePasswordGuard;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 免登录分享访问接口（匿名访客）
 * <p>
 * 统一约定：
 * - 密码通过请求头 X-Share-Password 传递（简化实现：每次请求校验，不签发会话票据）
 * - 访客标识通过 X-Visitor-Id 传递（前端 localStorage 生成，用于隔离匿名会话）
 * - 错误通过统一 Result 包装返回 code + message（无效 / 过期 / 吊销 / 越权 / 次数用尽均有明确文案）
 */
@Tag(name = "免登录分享")
@RestController
@RequestMapping("/api/public/share")
@RequiredArgsConstructor
public class PublicShareController {

    private static final String PASSWORD_HEADER = "X-Share-Password";
    private static final String VISITOR_HEADER = "X-Visitor-Id";
    private static final Pattern VISITOR_PATTERN = Pattern.compile("[A-Za-z0-9_-]{6,64}");

    private final ShareLinkService shareLinkService;
    private final PublicAccessRateLimiter rateLimiter;
    private final PublicAccessProperties properties;
    private final ChatWindowService windowService;
    private final ChatSessionMapper sessionMapper;
    private final RagChatService ragChatService;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final RagRetrieveService retrieveService;
    private final ClientIpResolver clientIpResolver;
    private final EmbedAccessService embedAccessService;
    private final SharePasswordGuard passwordGuard;

    @Operation(summary = "分享链接元信息（免登录）")
    @GetMapping("/{token}")
    public Result<Map<String, Object>> info(
            @PathVariable String token,
            @RequestHeader(value = PASSWORD_HEADER, required = false) String password,
            HttpServletRequest request) {
        String clientIp = clientIpResolver.resolve(request);
        rateLimit(token, clientIp, request);
        ShareLink link = shareLinkService.resolveActive(token);
        embedAccessService.validate(link, request);

        long lockRemainingSeconds = passwordGuard.lockRemainingSeconds(link.getToken(), clientIp);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", link.getToken());
        data.put("name", link.getName());
        data.put("scope", link.getScope());
        data.put("targetId", link.getTargetId());
        data.put("expiresAt", link.getExpiresAt());
        data.put("passwordRequired", link.getPasswordHash() != null && !link.getPasswordHash().isBlank());
        data.put("passwordLocked", lockRemainingSeconds > 0);
        data.put("lockRemainingSeconds", lockRemainingSeconds);
        data.put("passwordVerified", lockRemainingSeconds <= 0 && shareLinkService.matchesPassword(link, password));
        data.put("embedEnabled", Boolean.TRUE.equals(link.getEmbedEnabled()));
        data.put("maxUses", link.getMaxUses());
        data.put("useCount", link.getUseCount());
        data.put("readOnly", !ShareLinkService.SCOPE_WINDOW.equals(link.getScope()));

        if (ShareLinkService.SCOPE_KB.equals(link.getScope())) {
            KnowledgeBase kb = knowledgeBaseMapper.selectById(link.getTargetId());
            if (kb == null) throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "知识库不存在");
            data.put("kbId", kb.getId());
            data.put("kbName", kb.getName());
            data.put("title", kb.getName());
            data.put("description", kb.getDescription());
            data.put("mode", "kb");
        } else {
            ChatWindow window = loadTargetWindow(link);
            data.put("windowId", window.getId());
            data.put("windowName", window.getName());
            data.put("description", window.getDescription());
            data.put("title", window.getName());
            data.put("mode", ShareLinkService.SCOPE_SESSION.equals(link.getScope()) ? "session" : "chat");
            if (ShareLinkService.SCOPE_SESSION.equals(link.getScope())) {
                ChatSession session = sessionMapper.selectById(link.getTargetId());
                if (session == null) throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "会话不存在");
                data.put("sessionId", session.getId());
                data.put("sessionTitle", session.getTitle());
            }
        }
        return Result.success(data);
    }

    @Operation(summary = "校验访问密码（免登录）")
    @PostMapping("/{token}/unlock")
    public Result<Void> unlock(
            @PathVariable String token,
            @RequestHeader(value = PASSWORD_HEADER, required = false) String password,
            HttpServletRequest request) {
        String clientIp = clientIpResolver.resolve(request);
        rateLimit(token, clientIp, request);
        ShareLink link = shareLinkService.resolveActive(token);
        embedAccessService.validate(link, request);
        shareLinkService.requirePassword(link, password, clientIp);
        return Result.success();
    }

    @Operation(summary = "Widget 嵌入授权（服务端校验 Origin/Referer 与 IP 白名单）")
    @GetMapping("/{token}/embed/authorize")
    public Result<Map<String, Object>> authorizeEmbed(
            @PathVariable String token,
            HttpServletRequest request) {
        String clientIp = clientIpResolver.resolve(request);
        rateLimiter.acquire("share-embed", clientIp, request.getRequestURI());
        ShareLink link = shareLinkService.resolveActive(token);
        String grant = embedAccessService.authorize(link, request);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("grant", grant);
        data.put("expiresIn", embedAccessService.grantTtlSeconds());
        return Result.success(data);
    }

    @Operation(summary = "分享链接：列出访客会话（WINDOW 范围）")
    @GetMapping("/{token}/sessions")
    public Result<List<ChatSession>> sessions(
            @PathVariable String token,
            @RequestHeader(value = PASSWORD_HEADER, required = false) String password,
            @RequestHeader(value = VISITOR_HEADER, required = false) String visitorId,
            HttpServletRequest request) {
        ShareLink link = access(token, password, ShareLinkService.SCOPE_WINDOW, request);
        ChatWindow window = loadTargetWindow(link);
        return Result.success(windowService.listSessions(window.getId(), visitorIdentifier(visitorId)));
    }

    @Operation(summary = "分享链接：新建访客会话（WINDOW 范围）")
    @PostMapping("/{token}/sessions")
    public Result<ChatSession> createSession(
            @PathVariable String token,
            @RequestHeader(value = PASSWORD_HEADER, required = false) String password,
            @RequestHeader(value = VISITOR_HEADER, required = false) String visitorId,
            HttpServletRequest request) {
        ShareLink link = access(token, password, ShareLinkService.SCOPE_WINDOW, request);
        ChatWindow window = loadTargetWindow(link);
        return Result.success(windowService.createSession(window.getId(), visitorIdentifier(visitorId)));
    }

    @Operation(summary = "分享链接：删除访客会话（WINDOW 范围）")
    @DeleteMapping("/{token}/sessions/{sessionId}")
    public Result<Void> deleteSession(
            @PathVariable String token,
            @PathVariable Long sessionId,
            @RequestHeader(value = PASSWORD_HEADER, required = false) String password,
            @RequestHeader(value = VISITOR_HEADER, required = false) String visitorId,
            HttpServletRequest request) {
        ShareLink link = access(token, password, ShareLinkService.SCOPE_WINDOW, request);
        windowService.deleteSession(sessionId, visitorIdentifier(visitorId));
        return Result.success();
    }

    @Operation(summary = "分享链接：分页查询消息（WINDOW 访客会话 / SESSION 只读记录）")
    @GetMapping("/{token}/messages")
    public Result<PageResult<com.wgh.rag.chat.entity.ChatMessage>> messages(
            @PathVariable String token,
            @RequestHeader(value = PASSWORD_HEADER, required = false) String password,
            @RequestHeader(value = VISITOR_HEADER, required = false) String visitorId,
            @RequestParam(required = false) Long sessionId,
            @RequestParam(defaultValue = "1") Long page,
            @RequestParam(defaultValue = "20") Long size,
            HttpServletRequest request) {
        ShareLink link = access(token, password, null, request);
        ChatWindow window = loadTargetWindow(link);
        if (ShareLinkService.SCOPE_SESSION.equals(link.getScope())) {
            ChatSession session = sessionMapper.selectById(link.getTargetId());
            if (session == null) throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "会话不存在");
            return Result.success(windowService.pageMessages(session.getChatWindowId(),
                    session.getUserIdentifier(), session.getId(), page, size));
        }
        return Result.success(windowService.pageMessages(window.getId(),
                visitorIdentifier(visitorId), sessionId, page, size));
    }

    @Operation(summary = "分享链接：匿名流式问答（WINDOW 范围，复用既有 RAG 链路与配额）")
    @PostMapping(value = "/{token}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @PathVariable String token,
            @RequestHeader(value = PASSWORD_HEADER, required = false) String password,
            @RequestHeader(value = VISITOR_HEADER, required = false) String visitorId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        ShareLink link = access(token, password, ShareLinkService.SCOPE_WINDOW, request);
        rateLimiter.acquire("share-chat", token + ":" + clientIpResolver.resolve(request),
                request.getRequestURI());
        shareLinkService.consumeUse(link);

        ChatWindow window = loadTargetWindow(link);
        String identifier = visitorIdentifier(visitorId);
        Long sessionId = parseLong(body.get("sessionId"));
        if (sessionId == null) {
            sessionId = windowService.createSession(window.getId(), identifier).getId();
        } else {
            windowService.getOwnedSession(window.getId(), sessionId, identifier);
        }

        String message = body.get("message") == null ? "" : String.valueOf(body.get("message")).trim();
        if (message.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "消息内容不能为空");
        }
        List<String> images = parseImages(body.get("imageUrls"));

        // 匿名身份注入：让既有 RagChatService 复用配额（按访客统计），不改动其内部实现
        final Long chatSessionId = sessionId;
        return asVisitor(identifier, () ->
                ragChatService.streamChat(window.getId(), chatSessionId, message, true, images));
    }

    @Operation(summary = "分享链接：知识库只读检索（KB 范围）")
    @PostMapping("/{token}/kb/search")
    public Result<Map<String, Object>> kbSearch(
            @PathVariable String token,
            @RequestHeader(value = PASSWORD_HEADER, required = false) String password,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        ShareLink link = access(token, password, ShareLinkService.SCOPE_KB, request);
        rateLimiter.acquire("share-kb", token + ":" + clientIpResolver.resolve(request),
                request.getRequestURI());
        shareLinkService.consumeUse(link);

        String query = body.get("query") == null ? "" : String.valueOf(body.get("query")).trim();
        if (query.isEmpty()) throw new BusinessException(ResultCode.PARAM_ERROR, "query 不能为空");
        Integer topK = parseTopK(body.get("topK"), 5);
        List<EmbeddingMatch<TextSegment>> matches = retrieveService.searchMultiple(
                List.of(link.getTargetId()), query,
                new RetrievalOptions(null, false, null, null, topK)).matches();

        List<Map<String, Object>> results = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> m : matches) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("score", m.score());
            item.put("content", m.embedded() != null ? m.embedded().text() : null);
            if (m.embedded() != null && m.embedded().metadata() != null) {
                item.put("fileName", m.embedded().metadata().getString("fileName"));
                item.put("kbName", m.embedded().metadata().getString("kbName"));
            }
            results.add(item);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", query);
        data.put("results", results);
        data.put("total", results.size());
        return Result.success(data);
    }

    // ===== 内部方法 =====

    private ShareLink access(String token, String password, String requiredScope, HttpServletRequest request) {
        String clientIp = clientIpResolver.resolve(request);
        rateLimit(token, clientIp, request);
        ShareLink link = shareLinkService.resolveActive(token);
        embedAccessService.validate(link, request);
        shareLinkService.requirePassword(link, password, clientIp);
        shareLinkService.requireScope(link, requiredScope);
        return link;
    }

    /**
     * 分享访问按「token + IP」与「IP」两个维度分别限流，均包含接口路径。
     */
    private void rateLimit(String token, String clientIp, HttpServletRequest request) {
        String path = request.getRequestURI();
        rateLimiter.acquire("share-token", token + ":" + clientIp, path);
        rateLimiter.acquire("share-ip", clientIp, path);
    }

    private ChatWindow loadTargetWindow(ShareLink link) {
        Long windowId = link.getTargetId();
        if (ShareLinkService.SCOPE_SESSION.equals(link.getScope())) {
            ChatSession session = sessionMapper.selectById(link.getTargetId());
            if (session == null) throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "会话不存在");
            windowId = session.getChatWindowId();
        }
        ChatWindow window = windowService.getById(windowId);
        if (window.getStatus() == null || window.getStatus() == 0) {
            throw new BusinessException(ResultCode.OPERATION_NOT_ALLOWED, "聊天窗口已停用");
        }
        return window;
    }

    private String visitorIdentifier(String visitorId) {
        if (visitorId == null || !VISITOR_PATTERN.matcher(visitorId).matches()) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "缺少或非法的访客标识（X-Visitor-Id 需为 6-64 位字母/数字/下划线/短横线）");
        }
        return "share_" + visitorId;
    }

    private <T> T asVisitor(String identifier, java.util.function.Supplier<T> action) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext temp = SecurityContextHolder.createEmptyContext();
        temp.setAuthentication(new UsernamePasswordAuthenticationToken(
                identifier, null, List.of(new SimpleGrantedAuthority("ROLE_SHARE_VISITOR"))));
        SecurityContextHolder.setContext(temp);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> parseImages(Object raw) {
        if (raw instanceof List<?> list) {
            List<String> images = new ArrayList<>();
            for (Object o : list) {
                if (o != null) images.add(String.valueOf(o));
            }
            int max = properties.getMaxImagesPerRequest();
            if (images.size() > max) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "单次最多上传 " + max + " 张图片");
            }
            return images;
        }
        return List.of();
    }

    private Long parseLong(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank() || "null".equals(String.valueOf(raw))) return null;
        return Long.parseLong(String.valueOf(raw));
    }

    private Integer parseTopK(Object raw, int fallback) {
        if (raw == null) return fallback;
        int v = Integer.parseInt(String.valueOf(raw));
        return Math.min(Math.max(v, 1), 20);
    }
}

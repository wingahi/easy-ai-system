package com.wgh.rag.controller.scheduler;

import com.wgh.rag.common.constant.RoleCodes;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.Result;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.common.security.LoginUser;
import com.wgh.rag.common.security.SecurityUtils;
import com.wgh.rag.execution.api.ExecutionFacade;
import com.wgh.rag.execution.event.ExecutionEvent;
import com.wgh.rag.execution.model.ExecutionSnapshot;
import io.swagger.v3.oas.annotations.Operation;import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 执行状态与事件重放（后台化后的统一查询/订阅入口）。
 *
 * <p>用途：评测、单 Agent、多 Agent、聊天工作流后台化后，前端按 executionId 轮询状态或订阅 SSE；
 * 断线重连通过 {@code Last-Event-ID}（或 {@code from}）从事件表补齐，支持跨副本。
 *
 * <p>权限：仅能访问自己发起的执行；管理员可查看全部。系统任务（无 ownerUserId）仅管理员可访问。
 */
@Slf4j
@Tag(name = "执行中心")
@RestController
@RequestMapping("/api/executions")
@RequiredArgsConstructor
public class ExecutionEventController {

    private static final long SSE_TIMEOUT_MILLIS = 30L * 60 * 1000;

    private final ObjectProvider<ExecutionFacade> executionFacadeProvider;

    @Operation(summary = "执行状态快照（后台作业轮询）")
    @GetMapping("/{executionId}")
    public Result<ExecutionSnapshot> status(@PathVariable String executionId) {
        ExecutionSnapshot snapshot = requireFacade().query(executionId)
                .orElseThrow(() -> new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "执行不存在或已过期"));
        assertAccessible(snapshot.ownerUserId());
        return Result.success(snapshot);
    }

    @Operation(summary = "执行事件 SSE（支持 Last-Event-ID 断点重连）")
    @GetMapping(value = "/{executionId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String executionId,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                             @RequestParam(value = "from", required = false) Long from) {
        ExecutionFacade facade = requireFacade();
        ExecutionSnapshot snapshot = facade.query(executionId)
                .orElseThrow(() -> new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "执行不存在或已过期"));
        assertAccessible(snapshot.ownerUserId());

        long fromSeq = parseFromSeq(lastEventId, from);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        AtomicReference<AutoCloseable> handle = new AtomicReference<>();
        AtomicBoolean closed = new AtomicBoolean();

        Runnable cleanup = () -> {
            if (closed.compareAndSet(false, true)) {
                AutoCloseable attached = handle.get();
                if (attached != null) {
                    try {
                        attached.close();
                    } catch (Exception e) {
                        log.debug("关闭执行事件订阅失败 executionId={} error={}", executionId, e.getMessage());
                    }
                }
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());

        try {
            handle.set(facade.attach(executionId, fromSeq, event -> send(emitter, event, closed)));
        } catch (RuntimeException e) {
            cleanup.run();
            throw new BusinessException(ResultCode.FAIL, "订阅执行事件失败: " + e.getMessage());
        }

        // 已终态的执行：重放完历史即可关闭，不必等待超时
        ExecutionSnapshot latest = facade.query(executionId).orElse(snapshot);
        if (latest.status() != null && latest.status().isTerminal()) {
            emitter.complete();
        }
        return emitter;
    }

    private void send(SseEmitter emitter, ExecutionEvent event, AtomicBoolean closed) {
        if (closed.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                    .id(String.valueOf(event.seq()))
                    .name(event.type())
                    .data(event.payload(), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            if (closed.compareAndSet(false, true)) {
                log.debug("执行事件 SSE 已断开 executionId={} seq={}", event.executionId(), event.seq());
            }
        }
    }

    private void assertAccessible(String ownerUserId) {
        String username = SecurityUtils.currentUsername();
        if (ownerUserId == null || ownerUserId.equals(username) || isAdmin()) {
            return;
        }
        throw new BusinessException(ResultCode.PERMISSION_DENIED, "无权访问该执行");
    }

    private boolean isAdmin() {
        LoginUser user = LoginUser.get();
        return user != null && user.getRoles() != null && user.getRoles().contains(RoleCodes.ADMIN);
    }

    private long parseFromSeq(String lastEventId, Long from) {
        if (lastEventId != null && !lastEventId.isBlank()) {
            try {
                return Math.max(0L, Long.parseLong(lastEventId.trim()));
            } catch (NumberFormatException e) {
                log.debug("忽略非法 Last-Event-ID: {}", lastEventId);
            }
        }
        return from == null ? 0L : Math.max(0L, from);
    }

    private ExecutionFacade requireFacade() {
        ExecutionFacade facade = executionFacadeProvider.getIfAvailable();
        if (facade == null) {
            throw new BusinessException(ResultCode.FAIL, "调度中心未启用");
        }
        return facade;
    }
}

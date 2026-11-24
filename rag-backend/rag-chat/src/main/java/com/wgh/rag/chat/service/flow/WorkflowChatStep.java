package com.wgh.rag.chat.service.flow;

import com.wgh.rag.chat.service.ChatWorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 工作流路线：窗口绑定工作流时，每条用户消息触发一次完整工作流运行。
 */
@Component
@RequiredArgsConstructor
final class WorkflowChatStep implements ChatTurnStep {

    private final ChatWorkflowService chatWorkflowService;

    @Override
    public int order() {
        return 100;
    }

    @Override
    public boolean execute(ChatTurnContext ctx) {
        if (ctx.window.getWorkflowId() == null) {
            return false;
        }
        ctx.metricCtx.route().set("workflow");
        // 工作流运行在线程池异步执行：本轮收口交给工作流终态事件，
        // 编排线程返回后不得提前判定「无产出」
        ctx.deferCompletion();
        try {
            chatWorkflowService.stream(ctx.window, ctx.session, ctx.userMessage, ctx.events, ctx.shareMode,
                    ctx::markOutput, ctx::completeTurn);
        } catch (RuntimeException e) {
            // 提交运行即同步失败：本轮不可能再有产出，由本处收口
            ctx.completeTurn();
            throw e;
        }
        return true;
    }
}

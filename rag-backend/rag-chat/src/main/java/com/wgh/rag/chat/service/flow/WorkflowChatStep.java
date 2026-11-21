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
        chatWorkflowService.stream(ctx.window, ctx.session, ctx.userMessage, ctx.events, ctx.shareMode);
        return true;
    }
}

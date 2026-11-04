package com.wgh.rag.controller.support;

import com.wgh.rag.chat.entity.ChatSession;
import com.wgh.rag.chat.mapper.ChatSessionMapper;
import com.wgh.rag.common.constant.RoleCodes;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.common.security.LoginUser;
import com.wgh.rag.common.security.SecurityUtils;
import com.wgh.rag.workflow.entity.WorkflowRun;
import com.wgh.rag.workflow.service.WorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 登录链路运行态归属校验（与分享链路 {@code WorkflowService#assertRunOfWorkflow} 同一思路）：
 * 优先按聊天会话归属校验；无会话的直跑记录按创建人匹配；无归属的存量记录仅管理员可访问。
 */
@Component
@RequiredArgsConstructor
public class RunAccessGuard {

    private final WorkflowService workflowService;
    private final ChatSessionMapper chatSessionMapper;

    public void assertAccessible(Long runId) {
        WorkflowRun run = workflowService.getRun(runId);
        String username = SecurityUtils.currentUsername();
        if (run.getSessionId() != null) {
            ChatSession session = chatSessionMapper.selectById(run.getSessionId());
            if (session != null && username.equals(session.getUserIdentifier())) {
                return;
            }
            throw new BusinessException(ResultCode.PERMISSION_DENIED, "无权访问该运行记录");
        }
        if (username.equals(run.getCreateBy()) || isAdmin()) {
            return;
        }
        throw new BusinessException(ResultCode.PERMISSION_DENIED, "无权访问该运行记录");
    }

    private boolean isAdmin() {
        LoginUser user = LoginUser.get();
        return user != null && user.getRoles() != null && user.getRoles().contains(RoleCodes.ADMIN);
    }
}

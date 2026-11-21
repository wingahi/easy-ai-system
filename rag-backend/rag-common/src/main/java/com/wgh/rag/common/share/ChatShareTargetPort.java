package com.wgh.rag.common.share;

import java.util.Optional;

/**
 * 分享链接对聊天域的只读端口。
 *
 * <p>端口定义放在中立模块 {@code rag-common}，由 rag-chat 提供实现、rag-public-access 只依赖本接口，
 * 避免执行域反向依赖适配层。端口只暴露分享校验需要的最小信息：会话归属窗口、会话状态、窗口是否存在。</p>
 */
public interface ChatShareTargetPort {

    /**
     * 查询会话分享目标。
     *
     * @param sessionId 会话标识
     * @return 会话目标；不存在时为空
     */
    Optional<SessionTarget> findSession(Long sessionId);

    /**
     * 聊天窗口是否存在（软删除口径与聊天域查询一致）。
     *
     * @param windowId 窗口标识
     * @return 是否存在
     */
    boolean windowExists(Long windowId);

    /**
     * 会话分享目标。
     *
     * @param windowId 会话所属窗口
     * @param status   会话状态（如 ACTIVE）
     */
    record SessionTarget(Long windowId, String status) {
    }
}

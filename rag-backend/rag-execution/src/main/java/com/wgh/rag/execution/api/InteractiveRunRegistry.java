package com.wgh.rag.execution.api;

import com.wgh.rag.execution.model.ExecutionStatus;

import java.util.Map;

/**
 * 交互式运行态登记。
 *
 * <p>交互式执行仍在进程内完成（低延迟、不经过作业领取），这里只把「正在执行」这一事实登记为
 * 跨副本可查询的运行态记录，供管理端「进行中任务」按 {@code CHAT} 等类型检索。登记与注销必须成对：
 * 正常结束、用户停止、执行异常都应在 {@code finally} 中注销，避免留下永久进行中的幽灵记录；
 * 进程被强制终止时由调度层既有的租约/实例失联回收兜底。</p>
 *
 * <p>实现位于调度层；执行域只依赖本接口，避免反向依赖 {@code rag-scheduler}。</p>
 */
public interface InteractiveRunRegistry {

    /** 普通对话的运行类型标识。 */
    String RUN_TYPE_CHAT = "CHAT";

    /**
     * 登记一条交互式运行态。
     *
     * <p>实现必须自行吞掉登记失败（数据库不可用等），返回可安全注销的句柄；
     * 登记只用于可观测，不允许影响交互式主链路。</p>
     *
     * @param request 登记内容
     * @return 注销句柄；未登记时返回空操作句柄
     */
    RunRegistration register(InteractiveRunRequest request);

    /** 运行态注销句柄，幂等。 */
    interface RunRegistration {

        /**
         * 注销运行态。
         *
         * @param status    终态：成功、取消或失败
         * @param errorCode 失败错误码，可为 {@code null}
         */
        void finish(ExecutionStatus status, String errorCode);
    }

    /**
     * 运行态登记内容。
     *
     * @param runType     运行类型，如 {@link #RUN_TYPE_CHAT}
     * @param bizKey      业务键（会话/消息维度），用于唯一标识一次交互式运行
     * @param tenantId    租户标识，可为 {@code null}（使用默认租户）
     * @param ownerUserId 发起人，可为 {@code null}
     * @param attributes  展示属性（会话、窗口、标题等），不允许包含 {@code null} 值
     */
    record InteractiveRunRequest(String runType,
                                 String bizKey,
                                 String tenantId,
                                 String ownerUserId,
                                 Map<String, Object> attributes) {

        public InteractiveRunRequest {
            if (runType == null || runType.isBlank()) {
                throw new IllegalArgumentException("runType 不能为空");
            }
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }
}

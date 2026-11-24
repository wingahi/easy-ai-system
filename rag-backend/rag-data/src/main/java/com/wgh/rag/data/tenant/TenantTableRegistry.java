package com.wgh.rag.data.tenant;

import java.util.Locale;
import java.util.Set;

/**
 * 租户表白名单。
 * <p>
 * 只有列入本清单的业务表才会被 {@link TenantLineHandlerImpl} 自动追加 {@code tenant_id} 条件；
 * 平台级共享表（用户/角色/权限/模型/价格/租户目录/调度实例）必须留在清单之外，避免把共享数据误隔离。
 * <p>
 * <b>维护约束</b>：新增本清单中的表时，必须同时执行
 * {@code sql/migrations/20261124/06_tenant_data_plane.sql} 与
 * {@code sql/schema/01-schema.sql} 对应的 {@code tenant_id} 建列，否则查询会因缺列失败。
 */
public final class TenantTableRegistry {

    private static final Set<String> TENANT_TABLES = Set.of(
            // 知识库域
            "wgh_knowledge_base",
            "wgh_knowledge_file",
            "wgh_knowledge_file_version",
            "wgh_kb_chunk",
            "wgh_kb_connector",
            "wgh_kb_connector_run",
            "wgh_kb_ground_truth",
            "wgh_upload_session",
            "wgh_tag",
            "wgh_kb_tag_rel",
            "wgh_file_tag_rel",
            // 对话域
            "wgh_chat_window",
            "wgh_chat_session",
            "wgh_chat_message",
            "wgh_semantic_cache",
            // 评测域
            "wgh_eval_dataset",
            "wgh_eval_case",
            "wgh_eval_result",
            // 单 Agent / 多 Agent
            "wgh_agent_memory",
            "wgh_agent_plan",
            "wgh_agent_step",
            "wgh_m_agent_crew",
            "wgh_m_agent_run",
            "wgh_m_agent_step",
            // 工作流
            "wgh_workflow",
            "wgh_workflow_version",
            "wgh_workflow_run",
            "wgh_workflow_node_run",
            "wgh_workflow_trigger",
            // 公开访问与配额
            "wgh_share_link",
            "wgh_api_key",
            "wgh_api_call_log",
            "wgh_audit_log",
            "wgh_user_quota_config",
            "wgh_quota_usage",
            "wgh_quota_charge",
            // 提示词与工具（租户可自建）
            "wgh_prompt_template",
            "wgh_tool"
    );

    private TenantTableRegistry() {
    }

    /** 是否需要租户过滤（忽略 schema 前缀与引号）。 */
    public static boolean isTenantTable(String rawTableName) {
        if (rawTableName == null || rawTableName.isBlank()) {
            return false;
        }
        String name = rawTableName.trim();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            name = name.substring(dot + 1);
        }
        name = name.replace("\"", "").toLowerCase(Locale.ROOT);
        return TENANT_TABLES.contains(name);
    }

    public static Set<String> tables() {
        return TENANT_TABLES;
    }
}

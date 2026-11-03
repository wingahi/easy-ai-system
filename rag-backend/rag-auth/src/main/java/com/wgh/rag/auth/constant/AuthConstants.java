package com.wgh.rag.auth.constant;

/**
 * 鉴权相关常量：权限编码、角色前缀、token claim 名。
 * 权限编码字符串为对外契约（注解、开放 API scope），禁止改动。
 */
public interface AuthConstants {

    // ============ 权限编码 ============
    String PERM_KB_READ = "kb:read";
    String PERM_KB_WRITE = "kb:write";
    String PERM_KB_DELETE = "kb:delete";
    String PERM_AGENT_USE = "agent:use";
    String PERM_WORKFLOW_USE = "workflow:use";
    String PERM_WORKFLOW_EDIT = "workflow:edit";
    String PERM_TOOL_USE = "tool:use";
    String PERM_PROMPT_EDIT = "prompt:edit";
    String PERM_MODEL_MANAGE = "model:manage";
    String PERM_AUDIT_VIEW = "audit:view";
    String PERM_CHAT_WRITE = "chat:write";
    String PERM_USAGE_READ = "usage:read";

    // ============ Spring Security 角色前缀 ============
    String ROLE_PREFIX = "ROLE_";
    String ROLE_USER = "ROLE_USER";

    // ============ token claim ============
    String CLAIM_USER_ID = "userId";
}

package com.wgh.rag.sandbox.config;

import com.wgh.rag.log.service.AuditLogService;
import com.wgh.rag.sandbox.audit.SandboxAuditLogger;
import com.wgh.rag.sandbox.service.CodeSandboxService;
import com.wgh.rag.sandbox.tool.SandboxToolExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 沙箱装配：默认创建但关闭，只有 {@code enabled=true} 时才真正执行代码。
 *
 * <p>不在此处解析隔离策略，避免无隔离工具的环境因启动失败而影响主应用。
 */
@Configuration
@EnableConfigurationProperties(SandboxProperties.class)
public class SandboxConfiguration {

    @Bean
    public SandboxAuditLogger sandboxAuditLogger(SandboxProperties properties,
                                                 ObjectProvider<AuditLogService> auditLogService) {
        return new SandboxAuditLogger(auditLogService.getIfAvailable(),
                properties.isAuditToDatabase());
    }

    @Bean
    public CodeSandboxService codeSandboxService(SandboxProperties properties,
                                                 SandboxAuditLogger audit) {
        return new CodeSandboxService(properties, audit);
    }

    /**
     * 接入现有 ToolExecutor 机制；工具是否暴露给模型由 wgh_tool 配置决定，
     * 默认没有 SANDBOX 类型的记录，因此模型默认看不到该工具。
     */
    @Bean
    public SandboxToolExecutor sandboxToolExecutor(CodeSandboxService sandbox) {
        return new SandboxToolExecutor(sandbox);
    }
}

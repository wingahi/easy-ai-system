package com.wgh.rag.scheduler.support;

import com.wgh.rag.scheduler.config.SchedulerProperties;
import org.springframework.stereotype.Component;

/**
 * 实例标识解析。
 *
 * <p>租约持有者、单飞锁归属与日志排查都依赖实例标识：优先取配置
 * {@code wgh.scheduler.instance-id}，未配置时退化为 {@code HOSTNAME-pid}。
 * 追加 pid 是为了避免同一台机器上启动多个进程时租约归属相同。
 */
@Component
public class SchedulerIdentity {

    private final String instanceId;

    public SchedulerIdentity(SchedulerProperties properties) {
        String configured = properties.getInstanceId();
        if (configured != null && !configured.isBlank()) {
            this.instanceId = sanitize(configured.trim());
            return;
        }
        String host = System.getenv("HOSTNAME");
        if (host == null || host.isBlank()) {
            host = System.getenv("COMPUTERNAME");
        }
        if (host == null || host.isBlank()) {
            host = "local";
        }
        this.instanceId = sanitize(host + "-" + ProcessHandle.current().pid());
    }

    /** 当前实例标识（最长 64 字符，与 {@code lease_owner} 列宽一致）。 */
    public String instanceId() {
        return instanceId;
    }

    private static String sanitize(String raw) {
        String value = raw.replaceAll("[^A-Za-z0-9._-]", "-");
        return value.length() <= 64 ? value : value.substring(0, 64);
    }
}

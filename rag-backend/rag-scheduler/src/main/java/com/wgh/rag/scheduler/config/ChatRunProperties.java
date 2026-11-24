package com.wgh.rag.scheduler.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 普通对话运行态登记配置（{@code wgh.scheduler.chat-run}）。
 *
 * <p>交互式对话不经过作业领取，但会以 {@code job_type=CHAT, lane=INTERACTIVE} 的作业行登记「进行中」，
 * 供管理端跨副本查询。登记行在对话结束即删除；崩溃残留由既有租约/实例失联回收为终态后按保留期清理。
 */
@Data
@Component
@ConfigurationProperties(prefix = "wgh.scheduler.chat-run")
public class ChatRunProperties {

    /** 是否登记对话运行态；关闭后「进行中任务」的 CHAT 页签保持为空。 */
    private boolean enabled = true;

    /**
     * 登记行租约 TTL。
     *
     * <p>对话没有周期心跳，取单次流式生成上限加余量：租约到期即视为异常退出，由回收器落终态。
     */
    private Duration leaseTtl = Duration.ofMinutes(35);

    /** 终态登记行（崩溃残留）保留时长，超期由调度循环清理。 */
    private Duration terminalRetention = Duration.ofHours(24);
}

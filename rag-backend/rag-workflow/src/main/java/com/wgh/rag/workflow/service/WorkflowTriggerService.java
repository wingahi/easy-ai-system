package com.wgh.rag.workflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.result.ResultCode;
import com.wgh.rag.workflow.entity.WorkflowRun;
import com.wgh.rag.workflow.entity.WorkflowTrigger;
import com.wgh.rag.workflow.mapper.WorkflowTriggerMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 工作流触发器服务 —— CRUD + CRON 动态调度 + Webhook 触发。
 *
 * 调度模型：triggerId → ScheduledFuture 注册表；启动时加载所有启用的 CRON 触发器，
 * 增删改/启停时增量刷新。触发后调用 WorkflowService.runHeadless 静默运行并更新统计。
 */
@Slf4j
@Service
public class WorkflowTriggerService {

    private final WorkflowTriggerMapper triggerMapper;
    private final WorkflowService workflowService;
    private final ThreadPoolTaskScheduler triggerScheduler;

    /** triggerId → 已注册的调度任务 */
    private final Map<Long, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    public WorkflowTriggerService(WorkflowTriggerMapper triggerMapper,
                                  WorkflowService workflowService,
                                  @Qualifier("workflowTriggerScheduler") ThreadPoolTaskScheduler triggerScheduler) {
        this.triggerMapper = triggerMapper;
        this.workflowService = workflowService;
        this.triggerScheduler = triggerScheduler;
    }

    /** 启动时加载所有启用的 CRON 触发器。 */
    @PostConstruct
    public void init() {
        try {
            List<WorkflowTrigger> triggers = triggerMapper.selectList(new LambdaQueryWrapper<WorkflowTrigger>()
                    .eq(WorkflowTrigger::getTriggerType, "CRON")
                    .eq(WorkflowTrigger::getStatus, 1));
            triggers.forEach(this::schedule);
            log.info("工作流定时触发器加载完成，共 {} 个", triggers.size());
        } catch (Exception e) {
            log.error("工作流定时触发器加载失败", e);
        }
    }

    @PreDestroy
    public void destroy() {
        scheduledTasks.values().forEach(f -> f.cancel(false));
    }

    // ==================== CRUD ====================

    public List<WorkflowTrigger> listByWorkflow(Long workflowId) {
        return triggerMapper.selectList(new LambdaQueryWrapper<WorkflowTrigger>()
                .eq(WorkflowTrigger::getWorkflowId, workflowId)
                .orderByDesc(WorkflowTrigger::getId));
    }

    @Transactional
    public WorkflowTrigger create(Long workflowId, WorkflowTrigger payload) {
        workflowService.getById(workflowId);
        payload.setId(null);
        payload.setWorkflowId(workflowId);
        payload.setStatus(payload.getStatus() != null ? payload.getStatus() : 1);
        payload.setFireCount(0);
        normalizeAndValidate(payload);
        triggerMapper.insert(payload);
        schedule(payload);
        return payload;
    }

    @Transactional
    public WorkflowTrigger update(Long triggerId, WorkflowTrigger payload) {
        WorkflowTrigger existing = getById(triggerId);
        if (payload.getTriggerType() != null) existing.setTriggerType(payload.getTriggerType());
        if (payload.getCronExpression() != null) existing.setCronExpression(payload.getCronExpression());
        if (payload.getInputText() != null) existing.setInputText(payload.getInputText());
        if (payload.getStatus() != null) existing.setStatus(payload.getStatus());
        normalizeAndValidate(existing);
        existing.setUpdateTime(LocalDateTime.now());
        triggerMapper.updateById(existing);
        schedule(existing);
        return existing;
    }

    @Transactional
    public void setStatus(Long triggerId, int status) {
        WorkflowTrigger t = getById(triggerId);
        t.setStatus(status);
        t.setUpdateTime(LocalDateTime.now());
        triggerMapper.updateById(t);
        if (status == 1) schedule(t);
        else cancelSchedule(triggerId);
    }

    @Transactional
    public void delete(Long triggerId) {
        cancelSchedule(triggerId);
        triggerMapper.deleteById(triggerId);
    }

    public WorkflowTrigger getById(Long triggerId) {
        WorkflowTrigger t = triggerMapper.selectById(triggerId);
        if (t == null) throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "触发器不存在");
        return t;
    }

    // ==================== 触发 ====================

    /** Webhook 触发（公网入口，凭 token 调用）。 */
    public WorkflowRun fireWebhook(String token, String inputOverride) {
        WorkflowTrigger t = triggerMapper.selectOne(new LambdaQueryWrapper<WorkflowTrigger>()
                .eq(WorkflowTrigger::getWebhookToken, token)
                .eq(WorkflowTrigger::getTriggerType, "WEBHOOK"));
        if (t == null) throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND, "Webhook 不存在");
        if (t.getStatus() == null || t.getStatus() != 1) {
            throw new BusinessException("触发器已停用");
        }
        return fire(t, inputOverride);
    }

    /** 执行触发：静默运行工作流并更新触发统计。 */
    private WorkflowRun fire(WorkflowTrigger t, String inputOverride) {
        String input = inputOverride != null ? inputOverride
                : (t.getInputText() == null ? "" : t.getInputText());
        WorkflowRun run = workflowService.runHeadless(t.getWorkflowId(), input);

        t.setLastFireTime(LocalDateTime.now());
        t.setFireCount(t.getFireCount() == null ? 1 : t.getFireCount() + 1);
        triggerMapper.updateById(t);
        log.info("工作流触发成功 triggerId={} type={} runId={}", t.getId(), t.getTriggerType(), run.getId());
        return run;
    }

    // ==================== 调度 ====================

    /** 注册/刷新 CRON 调度（非 CRON 或停用则仅取消旧任务）。 */
    private void schedule(WorkflowTrigger t) {
        cancelSchedule(t.getId());
        if (!"CRON".equals(t.getTriggerType()) || t.getStatus() == null || t.getStatus() != 1) {
            return;
        }
        try {
            ScheduledFuture<?> future = triggerScheduler.schedule(
                    () -> fireSafely(t.getId()), new CronTrigger(t.getCronExpression()));
            if (future != null) {
                scheduledTasks.put(t.getId(), future);
            }
            log.info("注册定时触发器 triggerId={} cron={}", t.getId(), t.getCronExpression());
        } catch (Exception e) {
            log.error("注册定时触发器失败 triggerId={}", t.getId(), e);
        }
    }

    private void cancelSchedule(Long triggerId) {
        ScheduledFuture<?> future = scheduledTasks.remove(triggerId);
        if (future != null) {
            future.cancel(false);
        }
    }

    /** 定时任务入口：重新读取最新状态，避免使用过期快照。 */
    private void fireSafely(Long triggerId) {
        try {
            WorkflowTrigger t = triggerMapper.selectById(triggerId);
            if (t == null || t.getStatus() == null || t.getStatus() != 1) {
                return;
            }
            fire(t, null);
        } catch (Exception e) {
            log.error("定时触发工作流失败 triggerId={}", triggerId, e);
        }
    }

    /** 类型规范化与字段校验。 */
    private void normalizeAndValidate(WorkflowTrigger t) {
        if (t.getTriggerType() == null ||
                (!"CRON".equals(t.getTriggerType()) && !"WEBHOOK".equals(t.getTriggerType()))) {
            throw new BusinessException("触发类型仅支持 CRON / WEBHOOK");
        }
        if ("CRON".equals(t.getTriggerType())) {
            if (t.getCronExpression() == null || t.getCronExpression().isBlank()) {
                throw new BusinessException("CRON 触发器必须填写 cron 表达式");
            }
            if (!CronExpression.isValidExpression(t.getCronExpression())) {
                throw new BusinessException("cron 表达式非法（6 段：秒 分 时 日 月 周）");
            }
            t.setWebhookToken(null);
        } else {
            if (t.getWebhookToken() == null || t.getWebhookToken().isBlank()) {
                t.setWebhookToken(UUID.randomUUID().toString().replace("-", ""));
            }
            t.setCronExpression(null);
        }
    }
}

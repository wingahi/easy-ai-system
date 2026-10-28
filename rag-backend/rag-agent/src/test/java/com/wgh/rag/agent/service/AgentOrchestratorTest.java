package com.wgh.rag.agent.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.agent.StepExecutor;
import com.wgh.rag.agent.config.AgentProperties;
import com.wgh.rag.agent.dto.AgentStepResult;
import com.wgh.rag.agent.dto.PlanDTO;
import com.wgh.rag.agent.dto.PlanDTO.StepDTO;
import com.wgh.rag.agent.entity.AgentPlan;
import com.wgh.rag.agent.entity.AgentStep;
import com.wgh.rag.agent.exception.AgentException;
import com.wgh.rag.agent.mapper.AgentPlanMapper;
import com.wgh.rag.agent.mapper.AgentStepMapper;
import com.wgh.rag.agent.planner.Planner;
import com.wgh.rag.agent.reflection.ReflectionEngine;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentOrchestrator 重构前行为基线测试。
 * 用内存 store 模拟 AgentPlanMapper / AgentStepMapper（LambdaQueryWrapper 按参数值匹配），
 * 用桩 PlatformTransactionManager 让 TransactionTemplate 直接同步执行。
 * 覆盖不变式：拓扑顺序、10 类 SSE 事件名/顺序、HITL 暂停恢复状态机、
 * 先落库再发 SSE、SSE 失败仅告警、latencyMs 为实际耗时、反思阈值语义。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentOrchestratorTest {

    private static final Set<String> STEP_STATUSES =
            Set.of("PENDING", "SUCCESS", "FAILED", "HITL_PENDING", "SKIPPED", "RUNNING");
    private static final Set<String> SKIPPABLE = Set.of("RUNNING", "PENDING", "HITL_PENDING");

    @Mock
    private Planner planner;
    @Mock
    private StepExecutor stepExecutor;
    @Mock
    private ReflectionEngine reflectionEngine;
    @Mock
    private AgentPlanMapper planMapper;
    @Mock
    private AgentStepMapper stepMapper;

    private AgentProperties agentProperties;
    private ObjectMapper objectMapper;
    private PlatformTransactionManager txManager;

    /** 内存 plan/step 存储 + 全局事件日志（DB 写入与 SSE 发送按序记录，用于断言先后顺序）。 */
    private InMemDb db;
    private List<String> events;
    private Map<String, List<Map<String, Object>>> payloadsByName;
    private BiConsumer<String, Map<String, Object>> sse;

    private AgentOrchestrator orchestrator;

    @BeforeAll
    static void initMybatisPlusTableInfo() {
        // LambdaQueryWrapper 的列名解析依赖 TableInfo 缓存，无 Spring 容器时需手工初始化
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        if (TableInfoHelper.getTableInfo(AgentPlan.class) == null) {
            TableInfoHelper.initTableInfo(assistant, AgentPlan.class);
        }
        if (TableInfoHelper.getTableInfo(AgentStep.class) == null) {
            TableInfoHelper.initTableInfo(assistant, AgentStep.class);
        }
    }

    @BeforeEach
    void setUp() {
        agentProperties = new AgentProperties();
        objectMapper = new ObjectMapper();
        txManager = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };

        db = new InMemDb();
        events = new ArrayList<>();
        payloadsByName = new LinkedHashMap<>();
        sse = (name, data) -> {
            events.add(name);
            payloadsByName.computeIfAbsent(name, k -> new ArrayList<>()).add(data);
            String suffix = data.containsKey("stepId") ? ":step=" + data.get("stepId")
                    : data.containsKey("planId") ? ":plan=" + data.get("planId") : "";
            db.log.add("SSE:" + name + suffix);
        };

        wireMappers();

        // 默认：执行成功、反思 8 分通过（阈值默认 7.0）
        lenient().when(stepExecutor.execute(any(), anyString(), any(), any(), any(),
                        anyInt(), anyBoolean(), anyString(), any()))
                .thenAnswer(inv -> {
                    db.executedQueries.add(inv.getArgument(7, String.class));
                    return successResult("完成:" + inv.getArgument(7, String.class));
                });
        lenient().when(reflectionEngine.evaluate(anyString(), any(), any()))
                .thenReturn(Map.entry(8.0, "通过"));

        orchestrator = newOrchestrator();
    }

    /** 组装被测对象（重构后只需改这里）。 */
    private AgentOrchestrator newOrchestrator() {
        AgentEventPublisher eventPublisher = new AgentEventPublisher();
        PlanLifecycleService lifecycle = new PlanLifecycleService(
                planMapper, stepMapper, agentProperties, objectMapper, txManager);
        HitlGate hitlGate = new HitlGate(lifecycle, eventPublisher);
        return new AgentOrchestrator(planner, stepExecutor, reflectionEngine,
                lifecycle, new StepScheduler(), hitlGate, eventPublisher, agentProperties);
    }

    // ==================== 用例 ====================

    /** 按拓扑序执行步骤，SSE 事件顺序正确，且每步结果先落库再发 SSE。 */
    @Test
    void orchestrate_executesInTopologicalOrder_eventsOrderedAndPersistBeforeSse() {
        PlanDTO dto = planOf("测试计划",
                step("s1", 0),
                step("s2", 1, "s1"),
                step("s3", 2, "s2"));

        AgentStepResult result = runOrchestrate(dto, true, 7.0, 3);

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("SUCCESS");

        // 拓扑链 s1→s2→s3 保证唯一执行顺序
        assertThat(db.executedQueries).containsExactly("desc-s1", "desc-s2", "desc-s3");

        // SSE 事件名与顺序
        assertThat(events).containsExactly(
                "plan_created",
                "step_start", "step_reflection", "step_success",
                "step_start", "step_reflection", "step_success",
                "step_start", "step_reflection", "step_success",
                "plan_complete");

        Map<String, Object> created = payloads("plan_created").get(0);
        assertThat(created.get("planId")).isEqualTo(101L);
        assertThat(created.get("planSummary")).isEqualTo("测试计划");
        assertThat(created.get("totalSteps")).isEqualTo(3);
        assertThat(payloads("plan_complete").get(0).get("totalSteps")).isEqualTo(3);

        // 不变式：plan 落库早于 plan_created；每步结果落库早于 step_success；COMPLETED 落库早于 plan_complete
        assertLogOrder("DB:plan_insert:101", "SSE:plan_created:plan=101");
        for (String sid : List.of("s1", "s2", "s3")) {
            int dbIdx = db.log.lastIndexOf("DB:step_update:" + sid + ":SUCCESS");
            int sseIdx = db.log.indexOf("SSE:step_success:step=" + sid);
            assertThat(dbIdx).as("步骤 %s 结果应先落库", sid).isGreaterThan(-1);
            assertThat(sseIdx).as("step_success 应在落库之后: %s", sid).isGreaterThan(dbIdx);
        }
        assertLogOrder("DB:plan_update:101:COMPLETED", "SSE:plan_complete:plan=101");

        // 终态
        AgentPlan plan = db.plans.get(101L);
        assertThat(plan.getStatus()).isEqualTo("COMPLETED");
        assertThat(plan.getFinishTime()).isNotNull();
        assertThat(db.steps.values()).allMatch(s -> "SUCCESS".equals(s.getStatus()));

        // 默认 maxSteps（15）传给 Planner
        verify(planner).generate(eq(3L), eq("用户目标"), eq(List.of()), eq(false), eq(15));
    }

    /** 空计划不执行任何步骤，直接完成并只发 plan_created / plan_complete。 */
    @Test
    void orchestrate_emptyPlan_completesDirectly() {
        PlanDTO dto = planOf("空计划");
        dto.setSteps(new ArrayList<>());

        AgentStepResult result = runOrchestrate(dto, true, 7.0, 3);

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(result.getFinalText()).isEmpty();
        assertThat(events).containsExactly("plan_created", "plan_complete");
        assertThat(db.plans.get(101L).getStatus()).isEqualTo("COMPLETED");
        assertThat(db.steps).isEmpty();
    }

    /** Planner 抛异常时发出 plan_create_failed 事件并向上抛出异常，不落库计划。 */
    @Test
    void orchestrate_plannerThrows_emitsPlanCreateFailedAndRethrows() {
        when(planner.generate(any(), any(), any(), anyBoolean(), anyInt()))
                .thenThrow(new AgentException("LLM 超时"));

        assertThatThrownBy(() -> orchestrator.orchestrate(1L, 2L, 3L, 3L, "用户目标",
                List.of(), List.of(), Map.of(), false, List.of(), 7.0, 3, true, sse))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("LLM 超时");

        assertThat(events).containsExactly("plan_create_failed");
        assertThat(payloads("plan_create_failed").get(0).get("reason")).isEqualTo("LLM 超时");
        assertThat(db.plans).isEmpty();
    }

    /** 遇到 HITL 步骤时暂停计划，发出 step_hitl_pending 与 plan_paused 事件。 */
    @Test
    void orchestrate_hitlStep_pausesPlanAndWaitsForConfirm() {
        StepDTO hitlStep = step("s1", 0);
        hitlStep.setHitl(true);
        hitlStep.setExpectedInput("请确认是否继续");
        PlanDTO dto = planOf("HITL计划", hitlStep, step("s2", 1, "s1"));

        AgentStepResult result = runOrchestrate(dto, true, 7.0, 3);

        assertThat(result).isNull(); // 线程退出
        assertThat(events).containsExactly("plan_created", "step_hitl_pending", "plan_paused");

        Map<String, Object> hitlPayload = payloads("step_hitl_pending").get(0);
        assertThat(hitlPayload.get("stepId")).isEqualTo("s1");
        assertThat(hitlPayload.get("stepIndex")).isEqualTo(0);
        assertThat(hitlPayload.get("stepTitle")).isEqualTo("标题-s1");
        assertThat(hitlPayload.get("expectedInput")).isEqualTo("请确认是否继续");
        assertThat(hitlPayload.get("description")).isEqualTo("desc-s1");
        assertThat(hitlPayload.get("id")).isNotNull();
        assertThat(payloads("plan_paused").get(0).get("reason")).isEqualTo("等待用户确认");

        // 状态机：step=HITL_PENDING, plan=PAUSED，且先落库再发 SSE
        AgentStep s1 = db.stepByStepId("s1");
        assertThat(s1.getStatus()).isEqualTo("HITL_PENDING");
        assertThat(s1.getThought()).contains("HITL 节点等待用户确认");
        assertThat(db.plans.get(101L).getStatus()).isEqualTo("PAUSED");
        assertLogOrder("DB:step_update:s1:HITL_PENDING", "SSE:step_hitl_pending:step=s1");
        assertLogOrder("DB:plan_update:101:PAUSED", "SSE:plan_paused:plan=101");
    }

    /** HITL 开关关闭时，HITL 步骤照常执行并完成计划。 */
    @Test
    void orchestrate_hitlStepButSwitchOff_executesNormally() {
        StepDTO hitlStep = step("s1", 0);
        hitlStep.setHitl(true);

        AgentStepResult result = runOrchestrate(planOf("计划", hitlStep), false, 7.0, 3);

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(events).containsExactly(
                "plan_created", "step_start", "step_reflection", "step_success", "plan_complete");
        assertThat(db.plans.get(101L).getStatus()).isEqualTo("COMPLETED");
    }

    /** HITL 确认后状态迁移为 SUCCESS 并继续执行后续步骤，非 PAUSED 状态拒绝 resume。 */
    @Test
    void resume_afterHitlConfirm_migratesStateAndContinuesRemainingSteps() {
        StepDTO hitlStep = step("s1", 0);
        hitlStep.setHitl(true);
        hitlStep.setExpectedInput("请确认是否继续");
        PlanDTO dto = planOf("HITL计划", hitlStep, step("s2", 1, "s1"));
        runOrchestrate(dto, true, 7.0, 3);
        assertThat(db.plans.get(101L).getStatus()).isEqualTo("PAUSED");

        AgentStepResult result = orchestrator.resume(101L, "用户补充输入", 7.0, 3, sse);

        // 最终返回最后一个 SUCCESS 步骤结果
        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(result.getFinalText()).isEqualTo("完成:desc-s2");

        // HITL 步骤：用户输入写入 resultSummary，状态 → SUCCESS；plan → COMPLETED
        AgentStep s1 = db.stepByStepId("s1");
        assertThat(s1.getStatus()).isEqualTo("SUCCESS");
        assertThat(s1.getResultSummary()).isEqualTo("用户补充输入");
        assertThat(db.plans.get(101L).getStatus()).isEqualTo("COMPLETED");

        // 事件序列：plan_resumed → s2 的完整执行事件 → plan_complete
        int resumeIdx = events.indexOf("plan_resumed");
        assertThat(resumeIdx).isGreaterThan(-1);
        assertThat(events.subList(resumeIdx, events.size())).containsExactly(
                "plan_resumed", "step_start", "step_reflection", "step_success", "plan_complete");
        Map<String, Object> resumed = payloads("plan_resumed").get(0);
        assertThat(resumed.get("planId")).isEqualTo(101L);
        assertThat(resumed.get("fromStepId")).isEqualTo("s1");

        // 不变式：确认结果先落库，再发 plan_resumed
        assertLogOrder("DB:step_update:s1:SUCCESS", "SSE:plan_resumed:plan=101");

        // 非 PAUSED 状态不允许 resume
        assertThatThrownBy(() -> orchestrator.resume(101L, "x", 7.0, 3, sse))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("非 PAUSED");
    }

    /** 反思分数低于阈值时带反馈重试，达标后落库反思分数与重试次数。 */
    @Test
    void orchestrate_reflectionBelowThreshold_retriesWithFeedbackAndPersistsScoreWhenPassed() {
        when(reflectionEngine.evaluate(anyString(), any(), any()))
                .thenReturn(Map.entry(5.0, "需要改进"))
                .thenReturn(Map.entry(8.0, "已达标"));
        List<String> prompts = new ArrayList<>();
        // 注意：对已打桩 mock 重打桩须用 doAnswer().when()，否则 when(mock.call()) 会先执行旧 answer 造成幻影调用
        doAnswer(inv -> {
            prompts.add(inv.getArgument(1, String.class));
            db.executedQueries.add(inv.getArgument(7, String.class));
            return successResult("完成:" + inv.getArgument(7, String.class));
        }).when(stepExecutor).execute(any(), anyString(), any(), any(), any(),
                anyInt(), anyBoolean(), anyString(), any());

        AgentStepResult result = runOrchestrate(planOf("计划", step("s1", 0)), true, 7.0, 1);

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(prompts).hasSize(2);
        // 第二次执行注入了反思反馈
        assertThat(prompts.get(1)).contains("Reflection 反馈（请改进）").contains("需要改进");

        assertThat(events).containsExactly(
                "plan_created", "step_start", "step_reflection", "step_retry",
                "step_reflection", "step_success", "plan_complete");
        List<Map<String, Object>> reflections = payloads("step_reflection");
        assertThat(reflections.get(0).get("pass")).isEqualTo(false);
        assertThat(reflections.get(0).get("retryCount")).isEqualTo(0);
        assertThat(reflections.get(0).get("score")).isEqualTo(5.0);
        assertThat(reflections.get(1).get("pass")).isEqualTo(true);
        assertThat(reflections.get(1).get("retryCount")).isEqualTo(1);
        assertThat(payloads("step_retry").get(0).get("retryCount")).isEqualTo(1);

        // 达标的反思分数与重试次数落库
        AgentStep s1 = db.stepByStepId("s1");
        assertThat(s1.getReflectionScore()).isEqualTo(8.0);
        assertThat(s1.getReflectionFeedback()).isEqualTo("已达标");
        assertThat(s1.getRetryCount()).isEqualTo(1);
    }

    /** step_success 事件的 latencyMs 为步骤实际耗时，而非当前时间戳。 */
    @Test
    void orchestrate_stepSuccessEventLatencyMsIsActualStepDuration() {
        doAnswer(inv -> {
            Thread.sleep(60);
            return successResult("完成:" + inv.getArgument(7, String.class));
        }).when(stepExecutor).execute(any(), anyString(), any(), any(), any(),
                anyInt(), anyBoolean(), anyString(), any());

        runOrchestrate(planOf("计划", step("s1", 0)), true, 7.0, 3);

        Object latencyMs = payloads("step_success").get(0).get("latencyMs");
        assertThat(latencyMs).isInstanceOf(Long.class);
        // 实际耗时（>= 睡眠量），而非当前时间戳（~1.7e12）
        assertThat((Long) latencyMs).isGreaterThanOrEqualTo(50L).isLessThan(60_000L);

        AgentStep s1 = db.stepByStepId("s1");
        assertThat(s1.getStartTime()).isNotNull();
        assertThat(s1.getEndTime()).isNotNull();
    }

    /** SSE 发送失败仅记录告警，不抛出、不降级，计划照常完成。 */
    @Test
    void orchestrate_sseSendFails_warnsOnlyWithoutAffectingExecution() {
        BiConsumer<String, Map<String, Object>> flakySse = (name, data) -> {
            if ("step_success".equals(name)) {
                throw new RuntimeException("客户端已断开");
            }
            sse.accept(name, data);
        };
        when(planner.generate(any(), any(), any(), anyBoolean(), anyInt()))
                .thenReturn(planOf("计划", step("s1", 0)));

        AgentStepResult result = orchestrator.orchestrate(1L, 2L, 3L, 3L, "用户目标",
                List.of(), List.of(), Map.of(), false, List.of(), 7.0, 3, true, flakySse);

        // 不抛出、不降级，计划照常完成
        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(db.plans.get(101L).getStatus()).isEqualTo("COMPLETED");
        assertThat(events).contains("plan_created", "plan_complete");
    }

    /** 取消计划后未完成步骤标记为 SKIPPED，不存在的计划静默返回。 */
    @Test
    void cancel_planCancelled_marksUnfinishedStepsSkipped() {
        StepDTO hitlStep = step("s1", 0);
        hitlStep.setHitl(true);
        hitlStep.setExpectedInput("请确认是否继续");
        runOrchestrate(planOf("HITL计划", hitlStep, step("s2", 1, "s1")), true, 7.0, 3);

        orchestrator.cancel(101L, sse);

        AgentPlan plan = db.plans.get(101L);
        assertThat(plan.getStatus()).isEqualTo("CANCELLED");
        assertThat(plan.getFinishTime()).isNotNull();
        assertThat(db.stepByStepId("s1").getStatus()).isEqualTo("SKIPPED");
        assertThat(db.stepByStepId("s2").getStatus()).isEqualTo("SKIPPED");
        assertThat(events).contains("plan_cancelled");
        assertThat(payloads("plan_cancelled").get(0).get("planId")).isEqualTo(101L);

        // 不存在的 plan：静默返回；sse 为 null 也不报错
        orchestrator.cancel(999L, null);
    }

    /** 重试 FAILED 步骤时重置其状态并重新执行，成功后计划转为 COMPLETED。 */
    @Test
    void retryStep_resetsFailedStepAndReruns() {
        StepDTO s2 = step("s2", 1, "s1");
        doAnswer(inv -> {
            String query = inv.getArgument(7, String.class);
            db.executedQueries.add(query);
            return "desc-s2".equals(query) ? failedResult("模拟失败") : successResult("完成:" + query);
        }).when(stepExecutor).execute(any(), anyString(), any(), any(), any(),
                anyInt(), anyBoolean(), anyString(), any());
        runOrchestrate(planOf("计划", step("s1", 0), s2), true, 7.0, 3);
        assertThat(db.plans.get(101L).getStatus()).isEqualTo("FAILED");
        assertThat(events).contains("plan_failed");

        // 修复后重试
        doAnswer(inv -> {
            db.executedQueries.add(inv.getArgument(7, String.class));
            return successResult("完成:" + inv.getArgument(7, String.class));
        }).when(stepExecutor).execute(any(), anyString(), any(), any(), any(),
                anyInt(), anyBoolean(), anyString(), any());
        int marker = events.size();

        AgentStepResult result = orchestrator.retryStep(101L, "s2", 7.0, 3, sse);

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        AgentStep dbS2 = db.stepByStepId("s2");
        assertThat(dbS2.getStatus()).isEqualTo("SUCCESS");
        assertThat(dbS2.getRetryCount()).isEqualTo(0); // 重试前已重置
        assertThat(dbS2.getResultSummary()).isEqualTo("摘要:desc-s2");
        assertThat(db.plans.get(101L).getStatus()).isEqualTo("COMPLETED");
        assertThat(events.subList(marker, events.size())).containsExactly(
                "step_start", "step_reflection", "step_success", "plan_complete");
    }

    /** 步骤失败且仍有 PENDING 步骤时触发重规划，本轮继续执行原列表剩余步骤。 */
    @Test
    void orchestrate_stepFailsWithRemainingPending_triggersReplanAndKeepsOriginalSteps() {
        StepDTO s2 = step("s2", 1, "s1");
        doAnswer(inv -> {
            String query = inv.getArgument(7, String.class);
            db.executedQueries.add(query);
            return "desc-s2".equals(query) ? failedResult("执行异常") : successResult("完成:" + query);
        }).when(stepExecutor).execute(any(), anyString(), any(), any(), any(),
                anyInt(), anyBoolean(), anyString(), any());
        StepDTO s2b = step("s2b", 0);
        when(planner.replan(any(), any(), any(), any(), anyBoolean(), anyInt(), any(), any()))
                .thenReturn(planOf("重规划", s2b));

        AgentStepResult result = runOrchestrate(
                planOf("计划", step("s1", 0), s2, step("s3", 2, "s2")), true, 7.0, 3);

        // 现有行为：replan 新步骤入库，但当前循环继续执行原列表剩余步骤（s3），s2b 不在本轮执行
        assertThat(db.executedQueries).containsExactly("desc-s1", "desc-s2", "desc-s3");
        assertThat(result.getFinalText()).isEqualTo("完成:desc-s3");

        AgentStep dbS2b = db.stepByStepId("s2b");
        assertThat(dbS2b).isNotNull();
        assertThat(dbS2b.getStatus()).isEqualTo("PENDING");
        assertThat(dbS2b.getStepIndex()).isEqualTo(2);

        assertThat(events).contains("step_failed", "plan_updated", "plan_complete");
        assertThat(events).doesNotContain("plan_failed");
        Map<String, Object> updated = payloads("plan_updated").get(0);
        assertThat(updated.get("addedStepCount")).isEqualTo(1);
        assertThat(updated.get("reason")).isEqualTo("step_failed_replan");
        Map<String, Object> failed = payloads("step_failed").get(0);
        assertThat(failed.get("stepId")).isEqualTo("s2");
        assertThat(failed.get("error")).isEqualTo("执行异常");

        // plan_json 合并：移除原 PENDING/FAILED（s2、s3），追加 replanned（s2b）
        AgentPlan plan = db.plans.get(101L);
        assertThat(plan.getStatus()).isEqualTo("COMPLETED");
        PlanDTO merged = readPlanJson(plan);
        assertThat(merged.getSteps()).extracting(StepDTO::getStepId)
                .containsExactlyInAnyOrder("s1", "s2b");
    }

    /** 步骤失败且无剩余 PENDING 步骤时计划失败，发出 plan_failed 事件。 */
    @Test
    void orchestrate_stepFailsWithoutRemainingPending_planFails() {
        doAnswer(inv -> {
            db.executedQueries.add(inv.getArgument(7, String.class));
            return failedResult("执行异常");
        }).when(stepExecutor).execute(any(), anyString(), any(), any(), any(),
                anyInt(), anyBoolean(), anyString(), any());

        AgentStepResult result = runOrchestrate(planOf("计划", step("s1", 0)), true, 7.0, 3);

        assertThat(result).isNull();
        assertThat(events).containsExactly("plan_created", "step_start", "step_failed", "plan_failed");
        assertThat(payloads("plan_failed").get(0).get("reason")).isEqualTo("no_pending_after_failed");
        AgentPlan plan = db.plans.get(101L);
        assertThat(plan.getStatus()).isEqualTo("FAILED");
        assertThat(plan.getFinishTime()).isNotNull();
    }

    /** 跳过步骤后标记 SKIPPED 并发出 step_skipped 事件，不存在的步骤静默返回。 */
    @Test
    void skipStep_marksSkippedAndNotifies() {
        StepDTO hitlStep = step("s1", 0);
        hitlStep.setHitl(true);
        hitlStep.setExpectedInput("请确认是否继续");
        runOrchestrate(planOf("HITL计划", hitlStep, step("s2", 1, "s1")), true, 7.0, 3);

        orchestrator.skipStep(101L, "s1", sse);

        assertThat(db.stepByStepId("s1").getStatus()).isEqualTo("SKIPPED");
        assertThat(events).contains("step_skipped");
        Map<String, Object> skipped = payloads("step_skipped").get(0);
        assertThat(skipped.get("stepId")).isEqualTo("s1");
        assertThat(skipped.get("reason")).isEqualTo("manual skip");

        // 不存在的步骤：静默返回
        int size = events.size();
        orchestrator.skipStep(101L, "不存在", sse);
        assertThat(events).hasSize(size);
    }

    // ==================== 测试基建 ====================

    private AgentStepResult runOrchestrate(PlanDTO dto, boolean hitlEnabled,
                                           double threshold, int maxRetries) {
        when(planner.generate(any(), any(), any(), anyBoolean(), anyInt())).thenReturn(dto);
        return orchestrator.orchestrate(1L, 2L, 3L, 3L, "用户目标",
                List.of(), List.of(), Map.of(), false, List.of(),
                threshold, maxRetries, hitlEnabled, sse);
    }

    private PlanDTO readPlanJson(AgentPlan plan) {
        try {
            return objectMapper.readValue(plan.getPlanJson(), PlanDTO.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void assertLogOrder(String first, String second) {
        int i1 = db.log.indexOf(first);
        int i2 = db.log.indexOf(second);
        assertThat(i1).as("日志应包含 %s，实际: %s", first, db.log).isGreaterThan(-1);
        assertThat(i2).as("日志应包含 %s，实际: %s", second, db.log).isGreaterThan(-1);
        assertThat(i1).as("%s 应先于 %s", first, second).isLessThan(i2);
    }

    private List<Map<String, Object>> payloads(String name) {
        return payloadsByName.getOrDefault(name, List.of());
    }

    private static PlanDTO planOf(String summary, StepDTO... steps) {
        return PlanDTO.builder()
                .planId("plan_test")
                .summary(summary)
                .steps(new ArrayList<>(List.of(steps)))
                .build();
    }

    private static StepDTO step(String id, int index, String... deps) {
        return StepDTO.builder()
                .stepId(id)
                .stepIndex(index)
                .title("标题-" + id)
                .description("desc-" + id)
                .dependsOn(deps.length == 0 ? List.of() : List.of(deps))
                .requiresTools(List.of())
                .requiresRAG(false)
                .outputType("DATA")
                .hitl(false)
                .build();
    }

    private static AgentStepResult successResult(String text) {
        return AgentStepResult.builder()
                .status("SUCCESS")
                .finalText(text)
                .resultSummary("摘要:" + text.substring("完成:".length()))
                .thoughts(List.of())
                .toolInvocations(List.of())
                .ragSources(List.of())
                .tokenCount(0)
                .build();
    }

    private static AgentStepResult failedResult(String error) {
        return AgentStepResult.builder()
                .status("FAILED")
                .finalText("")
                .errorMessage(error)
                .thoughts(List.of())
                .toolInvocations(List.of())
                .ragSources(List.of())
                .tokenCount(0)
                .build();
    }

    private void wireMappers() {
        lenient().when(planMapper.insert(any(AgentPlan.class))).thenAnswer(inv -> {
            AgentPlan p = inv.getArgument(0);
            p.setId(db.planSeq.incrementAndGet());
            db.plans.put(p.getId(), p);
            db.log.add("DB:plan_insert:" + p.getId());
            return 1;
        });
        lenient().when(planMapper.updateById(any(AgentPlan.class))).thenAnswer(inv -> {
            AgentPlan p = inv.getArgument(0);
            db.plans.put(p.getId(), p);
            db.log.add("DB:plan_update:" + p.getId() + ":" + p.getStatus());
            return 1;
        });
        lenient().when(planMapper.selectById(any())).thenAnswer(inv -> db.plans.get(inv.getArgument(0)));

        lenient().when(stepMapper.insert(any(AgentStep.class))).thenAnswer(inv -> {
            AgentStep s = inv.getArgument(0);
            s.setId(db.stepSeq.incrementAndGet());
            db.steps.put(s.getId(), s);
            db.log.add("DB:step_insert:" + s.getStepId());
            return 1;
        });
        lenient().when(stepMapper.updateById(any(AgentStep.class))).thenAnswer(inv -> {
            AgentStep s = inv.getArgument(0);
            db.steps.put(s.getId(), s);
            db.log.add("DB:step_update:" + s.getStepId() + ":" + s.getStatus());
            return 1;
        });
        lenient().when(stepMapper.selectById(any())).thenAnswer(inv -> db.steps.get(inv.getArgument(0)));
        // selectOne(planId + stepId + LIMIT 1)：按 wrapper 参数值匹配
        lenient().when(stepMapper.selectOne(any())).thenAnswer(inv -> {
            Collection<Object> vals = wrapperParams(inv.getArgument(0));
            Long planId = firstOf(vals, Long.class);
            Set<String> strs = stringsOf(vals);
            return db.steps.values().stream()
                    .filter(s -> planId == null || planId.equals(s.getPlanId()))
                    .filter(s -> strs.contains(s.getStepId()))
                    .findFirst().orElse(null);
        });
        // selectList：按 planId / 状态集合 / stepIndex 上限过滤，按 stepIndex 升序
        lenient().when(stepMapper.selectList(any())).thenAnswer(inv -> {
            Collection<Object> vals = wrapperParams(inv.getArgument(0));
            Long planId = firstOf(vals, Long.class);
            Set<String> statusFilter = stringsOf(vals).stream()
                    .filter(STEP_STATUSES::contains).collect(Collectors.toSet());
            Integer ltIndex = vals.stream()
                    .filter(Integer.class::isInstance).map(Integer.class::cast)
                    .filter(i -> i != 0).findFirst().orElse(null);
            return db.steps.values().stream()
                    .filter(s -> planId == null || planId.equals(s.getPlanId()))
                    .filter(s -> statusFilter.isEmpty() || statusFilter.contains(s.getStatus()))
                    .filter(s -> ltIndex == null || (s.getStepIndex() != null && s.getStepIndex() < ltIndex))
                    .sorted(Comparator.comparing(s -> s.getStepIndex() == null ? 0 : s.getStepIndex()))
                    .collect(Collectors.toList());
        });
        // cancel 的批量更新：update(null, wrapper)
        lenient().when(stepMapper.update(isNull(), any())).thenAnswer(inv -> {
            Collection<Object> vals = wrapperParams(inv.getArgument(1));
            Long planId = firstOf(vals, Long.class);
            db.steps.values().forEach(s -> {
                if ((planId == null || planId.equals(s.getPlanId())) && SKIPPABLE.contains(s.getStatus())) {
                    s.setStatus("SKIPPED");
                }
            });
            db.log.add("DB:steps_skip:" + planId);
            return 1;
        });
    }

    private static Collection<Object> wrapperParams(Object wrapper) {
        if (wrapper instanceof AbstractWrapper<?, ?, ?> aw) {
            // MP 3.5.x 延迟注册参数：需先触发 SQL 生成才会填充 paramNameValuePairs
            try {
                aw.getSqlSegment();
            } catch (Exception ignored) {
            }
            if (wrapper instanceof com.baomidou.mybatisplus.core.conditions.update.Update<?, ?> u) {
                try {
                    u.getSqlSet();
                } catch (Exception ignored) {
                }
            }
            return aw.getParamNameValuePairs().values();
        }
        return List.of();
    }

    private static <T> T firstOf(Collection<Object> vals, Class<T> type) {
        return vals.stream().filter(type::isInstance).map(type::cast).findFirst().orElse(null);
    }

    private static Set<String> stringsOf(Collection<Object> vals) {
        return vals.stream().filter(String.class::isInstance).map(String.class::cast)
                .collect(Collectors.toSet());
    }

    private static class InMemDb {
        final Map<Long, AgentPlan> plans = new LinkedHashMap<>();
        final Map<Long, AgentStep> steps = new LinkedHashMap<>();
        final AtomicLong planSeq = new AtomicLong(100);
        final AtomicLong stepSeq = new AtomicLong(1000);
        final List<String> log = new ArrayList<>();
        final List<String> executedQueries = new ArrayList<>();

        AgentStep stepByStepId(String stepId) {
            return steps.values().stream()
                    .filter(s -> stepId.equals(s.getStepId()))
                    .findFirst().orElse(null);
        }
    }
}

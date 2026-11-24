package com.wgh.rag.workflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.workflow.constant.WorkflowConstants;
import com.wgh.rag.workflow.dto.DebugStepResult;
import com.wgh.rag.workflow.engine.GraphValidator;
import com.wgh.rag.workflow.engine.WorkflowEngine;
import com.wgh.rag.workflow.entity.Workflow;
import com.wgh.rag.workflow.entity.WorkflowRun;
import com.wgh.rag.workflow.mapper.WorkflowMapper;
import com.wgh.rag.workflow.mapper.WorkflowNodeRunMapper;
import com.wgh.rag.workflow.mapper.WorkflowRunMapper;
import com.wgh.rag.workflow.mapper.WorkflowVersionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 单步调试开关：默认关闭时直接拒绝；开启后委托引擎并创建调试运行记录。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("工作流单步调试开关")
class WorkflowServiceDebugTest {

    @Mock
    private WorkflowMapper workflowMapper;
    @Mock
    private WorkflowVersionMapper versionMapper;
    @Mock
    private WorkflowRunMapper runMapper;
    @Mock
    private WorkflowNodeRunMapper nodeRunMapper;
    @Mock
    private GraphValidator graphValidator;
    @Mock
    private WorkflowEngine engine;
    @Mock
    private ThreadPoolTaskExecutor workflowExecutor;
    @Mock
    private com.wgh.rag.quota.service.QuotaService quotaService;
    @Mock
    private com.wgh.rag.quota.service.QuotaChargeTracker chargeTracker;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private WorkflowService service() {
        return new WorkflowService(workflowMapper, versionMapper, runMapper, nodeRunMapper,
                graphValidator, engine, objectMapper, workflowExecutor, quotaService, chargeTracker);
    }

    private Workflow workflow() {
        Workflow wf = new Workflow();
        wf.setId(1L);
        wf.setName("调试流");
        wf.setStatus(1);
        wf.setVersion(1);
        wf.setGraphJson("{\"nodes\":[{\"id\":\"s\",\"type\":\"START\"},"
                + "{\"id\":\"e\",\"type\":\"END\",\"config\":{\"output\":\"{{input}}\"}}],"
                + "\"edges\":[{\"source\":\"s\",\"target\":\"e\"}]}");
        return wf;
    }

    @Test
    @DisplayName("默认关闭：直接拒绝且不触碰数据库/引擎")
    void disabledByDefault_rejects() {
        assertThatThrownBy(() -> service().debugStep(1L, "输入", null, 1, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("单步调试未启用");
        verifyNoInteractions(workflowMapper, runMapper, nodeRunMapper, engine);
    }

    @Test
    @DisplayName("开启后：首次调用创建调试运行并委托引擎推进")
    void enabled_delegatesAndCreatesRun() {
        WorkflowService service = service();
        ReflectionTestUtils.setField(service, "debugStepEnabled", true);
        when(workflowMapper.selectById(1L)).thenReturn(workflow());
        when(graphValidator.validate(any())).thenReturn(new GraphValidator.GraphValidationResult(
                List.of(), Set.of(), Map.of("s", 0, "e", 1), Map.of("s", 0, "e", 1), null));
        DebugStepResult expected = new DebugStepResult();
        expected.setStatus(WorkflowConstants.STATUS_DEBUG_PAUSED);
        when(engine.debugStep(any(), any(), any(), any(), anyInt(), any())).thenReturn(expected);

        DebugStepResult actual = service.debugStep(1L, "输入", null, 1, "s");

        assertThat(actual).isSameAs(expected);
        ArgumentCaptor<WorkflowRun> runCaptor = ArgumentCaptor.forClass(WorkflowRun.class);
        verify(runMapper).insert(runCaptor.capture());
        assertThat(runCaptor.getValue().getWorkflowId()).isEqualTo(1L);
        assertThat(runCaptor.getValue().getInputText()).isEqualTo("输入");
    }

    @Test
    @DisplayName("已结束的调试会话不允许继续推进")
    void finishedRun_rejectsNextStep() {
        WorkflowService service = service();
        ReflectionTestUtils.setField(service, "debugStepEnabled", true);
        when(workflowMapper.selectById(1L)).thenReturn(workflow());
        when(graphValidator.validate(any())).thenReturn(new GraphValidator.GraphValidationResult(
                List.of(), Set.of(), Map.of("s", 0, "e", 1), Map.of("s", 0, "e", 1), null));
        WorkflowRun finished = new WorkflowRun();
        finished.setId(7L);
        finished.setWorkflowId(1L);
        finished.setStatus(WorkflowConstants.STATUS_COMPLETED);
        finished.setContextJson("{}");
        when(runMapper.selectById(7L)).thenReturn(finished);

        assertThatThrownBy(() -> service.debugStep(1L, "", 7L, 1, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("调试会话已结束");
    }
}

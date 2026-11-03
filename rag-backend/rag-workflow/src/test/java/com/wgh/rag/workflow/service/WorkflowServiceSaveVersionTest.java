package com.wgh.rag.workflow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.workflow.engine.GraphValidator;
import com.wgh.rag.workflow.engine.WorkflowEngine;
import com.wgh.rag.workflow.entity.Workflow;
import com.wgh.rag.workflow.entity.WorkflowVersion;
import com.wgh.rag.workflow.mapper.WorkflowMapper;
import com.wgh.rag.workflow.mapper.WorkflowNodeRunMapper;
import com.wgh.rag.workflow.mapper.WorkflowRunMapper;
import com.wgh.rag.workflow.mapper.WorkflowVersionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工作流保存版本测试 —— 一次保存只产生一次版本递增与一条版本快照。
 */
@ExtendWith(MockitoExtension.class)
class WorkflowServiceSaveVersionTest {

    private static final String GRAPH = "{\"nodes\":[{\"id\":\"start\",\"type\":\"START\",\"name\":\"开始\"}"
            + ",{\"id\":\"end\",\"type\":\"END\",\"name\":\"结束\",\"config\":{\"output\":\"{{input}}\"}}]"
            + ",\"edges\":[{\"id\":\"e1\",\"source\":\"start\",\"target\":\"end\",\"sourceHandle\":\"default\"}]}";

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

    private WorkflowService service() {
        return new WorkflowService(workflowMapper, versionMapper, runMapper, nodeRunMapper,
                graphValidator, engine, new ObjectMapper(), workflowExecutor);
    }

    private Workflow workflow(int version) {
        Workflow wf = new Workflow();
        wf.setId(1L);
        wf.setName("审批流");
        wf.setStatus(1);
        wf.setVersion(version);
        wf.setGraphJson(GRAPH);
        return wf;
    }

    private void passValidation() {
        when(graphValidator.validate(any())).thenAnswer(inv ->
                new GraphValidator.GraphValidationResult(List.of(), Set.of(), Map.of(), Map.of(), inv.getArgument(0)));
    }

    /** 正常：一次保存只递增一个版本，且只写入一条版本快照 */
    @Test
    void update_once_incrementsVersionByExactlyOne() {
        when(workflowMapper.selectById(1L)).thenReturn(workflow(2));
        passValidation();

        Workflow payload = new Workflow();
        payload.setName("审批流");
        payload.setGraphJson(GRAPH);

        Workflow result = service().update(1L, payload);

        assertThat(result.getVersion()).isEqualTo(3);
        verify(workflowMapper, times(1)).updateById(any(Workflow.class));
        ArgumentCaptor<WorkflowVersion> captor = ArgumentCaptor.forClass(WorkflowVersion.class);
        verify(versionMapper, times(1)).insert(captor.capture());
        assertThat(captor.getValue().getVersion()).isEqualTo(3);
    }

    /** 正常：两次不同的保存各产生一次递增，累计版本连续且快照数一致 */
    @Test
    void update_twice_eachSaveProducesOneSnapshot() {
        Workflow existing = workflow(3);
        when(workflowMapper.selectById(1L)).thenReturn(existing);
        passValidation();

        Workflow first = new Workflow();
        first.setGraphJson(GRAPH);
        Workflow second = new Workflow();
        second.setGraphJson(GRAPH.replace("\"开始\"", "\"开始2\""));

        service().update(1L, first);
        service().update(1L, second);

        assertThat(existing.getVersion()).isEqualTo(5);
        verify(workflowMapper, times(2)).updateById(any(Workflow.class));
        verify(versionMapper, times(2)).insert(any(WorkflowVersion.class));
    }

    /** 正常：校验前端草稿只读取草稿，不写库、不产生版本 */
    @Test
    void validateGraph_withDraft_doesNotPersist() {
        when(workflowMapper.selectById(1L)).thenReturn(workflow(3));
        passValidation();

        List<String> errors = service().validateGraph(1L, GRAPH);

        assertThat(errors).isEmpty();
        verify(workflowMapper, never()).updateById(any(Workflow.class));
        verify(versionMapper, never()).insert(any(WorkflowVersion.class));
    }

    /** 边界：不传草稿时回退校验已保存定义，同样不产生版本 */
    @Test
    void validateGraph_withoutDraft_validatesSavedGraph() {
        when(workflowMapper.selectById(1L)).thenReturn(workflow(3));
        passValidation();

        service().validateGraph(1L, null);

        verify(graphValidator).validate(any());
        verify(versionMapper, never()).insert(any(WorkflowVersion.class));
    }
}

package com.wgh.rag.workflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.workflow.engine.GraphValidator;
import com.wgh.rag.workflow.engine.WorkflowEngine;
import com.wgh.rag.workflow.entity.Workflow;
import com.wgh.rag.workflow.entity.WorkflowRun;
import com.wgh.rag.workflow.mapper.WorkflowMapper;
import com.wgh.rag.workflow.mapper.WorkflowNodeRunMapper;
import com.wgh.rag.workflow.mapper.WorkflowRunMapper;
import com.wgh.rag.workflow.mapper.WorkflowVersionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工作流分享运行页测试 —— 分享开关、令牌轮换、令牌查询、运行归属校验（越权防护）。
 */
@ExtendWith(MockitoExtension.class)
class WorkflowServiceShareTest {

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

    private Workflow workflow(Long id, int status) {
        Workflow wf = new Workflow();
        wf.setId(id);
        wf.setName("审批流");
        wf.setStatus(status);
        wf.setVersion(1);
        wf.setGraphJson("{\"nodes\":[],\"edges\":[]}");
        return wf;
    }

    /** 正常：启用状态的工作流开启分享后生成 32 位令牌并持久化 */
    @Test
    void enableShare_enabledWorkflow_generatesToken() {
        Workflow wf = workflow(1L, 1);
        when(workflowMapper.selectById(1L)).thenReturn(wf);

        Workflow result = service().enableShare(1L);

        assertThat(result.getShareEnabled()).isTrue();
        assertThat(result.getShareToken()).hasSize(32);
        verify(workflowMapper).updateById(wf);
    }

    /** 边界：重复开启分享保留原令牌，链接不变 */
    @Test
    void enableShare_existingToken_keepsOriginalToken() {
        Workflow wf = workflow(1L, 1);
        wf.setShareToken("existing-token");
        when(workflowMapper.selectById(1L)).thenReturn(wf);

        Workflow result = service().enableShare(1L);

        assertThat(result.getShareToken()).isEqualTo("existing-token");
    }

    /** 异常：停用状态的工作流禁止开启分享 */
    @Test
    void enableShare_disabledWorkflow_throws() {
        when(workflowMapper.selectById(1L)).thenReturn(workflow(1L, 0));

        assertThatThrownBy(() -> service().enableShare(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已停用");
    }

    /** 正常：关闭分享后标志位清除、令牌保留（重新开启可恢复原链接） */
    @Test
    void disableShare_clearsFlagKeepsToken() {
        Workflow wf = workflow(1L, 1);
        wf.setShareEnabled(true);
        wf.setShareToken("keep-me");
        when(workflowMapper.selectById(1L)).thenReturn(wf);

        Workflow result = service().disableShare(1L);

        assertThat(result.getShareEnabled()).isFalse();
        assertThat(result.getShareToken()).isEqualTo("keep-me");
        verify(workflowMapper).updateById(wf);
    }

    /** 正常：已开启分享时重新生成会轮换令牌 */
    @Test
    void regenerateShareToken_whenEnabled_rotatesToken() {
        Workflow wf = workflow(1L, 1);
        wf.setShareEnabled(true);
        wf.setShareToken("old-token");
        when(workflowMapper.selectById(1L)).thenReturn(wf);

        Workflow result = service().regenerateShareToken(1L);

        assertThat(result.getShareToken()).isNotEqualTo("old-token").hasSize(32);
    }

    /** 异常：未开启分享时禁止重新生成令牌 */
    @Test
    void regenerateShareToken_whenNotEnabled_throws() {
        Workflow wf = workflow(1L, 1);
        wf.setShareEnabled(false);
        when(workflowMapper.selectById(1L)).thenReturn(wf);

        assertThatThrownBy(() -> service().regenerateShareToken(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未开启分享");
    }

    /** 正常：有效令牌可查询到工作流 */
    @Test
    void getByShareToken_found_returnsWorkflow() {
        Workflow wf = workflow(1L, 1);
        wf.setShareEnabled(true);
        wf.setShareToken("valid-token");
        when(workflowMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(wf);

        assertThat(service().getByShareToken("valid-token")).isSameAs(wf);
    }

    /** 异常：令牌无效 / 已关闭分享 / 已停用时抛出"分享链接已失效" */
    @Test
    void getByShareToken_notFound_throws() {
        when(workflowMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertThatThrownBy(() -> service().getByShareToken("bad-token"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("分享链接已失效");
    }

    /** 正常：运行记录属于该工作流时校验通过 */
    @Test
    void assertRunOfWorkflow_matchingRun_passes() {
        WorkflowRun run = new WorkflowRun();
        run.setId(9L);
        run.setWorkflowId(1L);
        when(runMapper.selectById(9L)).thenReturn(run);

        service().assertRunOfWorkflow(1L, 9L);
    }

    /** 安全：运行记录不存在或属于其他工作流时拒绝访问（防越权读取他人运行） */
    @Test
    void assertRunOfWorkflow_mismatchOrMissing_throws() {
        WorkflowRun other = new WorkflowRun();
        other.setId(9L);
        other.setWorkflowId(2L);
        when(runMapper.selectById(9L)).thenReturn(other);
        when(runMapper.selectById(10L)).thenReturn(null);

        assertThatThrownBy(() -> service().assertRunOfWorkflow(1L, 9L))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service().assertRunOfWorkflow(1L, 10L))
                .isInstanceOf(BusinessException.class);
    }
}

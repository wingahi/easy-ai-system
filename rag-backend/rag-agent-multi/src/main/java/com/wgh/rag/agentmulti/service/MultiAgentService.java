package com.wgh.rag.agentmulti.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wgh.rag.agentmulti.entity.MultiAgentRun;
import com.wgh.rag.agentmulti.entity.MultiAgentStep;
import com.wgh.rag.agentmulti.mapper.MultiAgentRunMapper;
import com.wgh.rag.agentmulti.mapper.MultiAgentStepMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MultiAgent 持久化服务 —— 把 DB 读写集中在此，消除 MultiAgentRuntime ↔ Coordinator 的循环依赖。
 * <p>
 * Coordinator 只依赖 Service，Service 不依赖 Runtime；Runtime 也依赖 Service，形成单向链路。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultiAgentService {

    private final MultiAgentRunMapper runMapper;
    private final MultiAgentStepMapper stepMapper;

    // ==================== Run 持久化 ====================

    public void saveRun(MultiAgentRun run) {
        runMapper.updateById(run);
    }

    public MultiAgentRun loadRun(String runId) {
        return runMapper.selectOne(
                new LambdaQueryWrapper<MultiAgentRun>()
                        .eq(MultiAgentRun::getRunId, runId)
                        .last("LIMIT 1"));
    }

    public MultiAgentRun getRunById(Long id) {
        return runMapper.selectById(id);
    }

    public void finishRun(MultiAgentRun run, String status, String error) {
        run.setStatus(status);
        run.setErrorMessage(error);
        run.setFinishTime(LocalDateTime.now());
        if (run.getStartTime() != null) {
            run.setDurationMs(java.time.Duration.between(run.getStartTime(), LocalDateTime.now()).toMillis());
        }
        runMapper.updateById(run);
    }

    // ==================== Step 持久化 ====================

    public void insertStep(MultiAgentStep step) {
        stepMapper.insert(step);
    }

    public void updateStep(MultiAgentStep step) {
        stepMapper.updateById(step);
    }

    public List<MultiAgentStep> listSteps(String runId) {
        return stepMapper.selectList(
                new LambdaQueryWrapper<MultiAgentStep>()
                        .eq(MultiAgentStep::getRunId, runId)
                        .orderByAsc(MultiAgentStep::getStepIndex));
    }

    // ==================== 组合查询 ====================

    public Map<String, Object> getRunState(String runId) {
        MultiAgentRun run = loadRun(runId);
        List<MultiAgentStep> steps = listSteps(runId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("run", run);
        result.put("steps", steps);
        return result;
    }
}

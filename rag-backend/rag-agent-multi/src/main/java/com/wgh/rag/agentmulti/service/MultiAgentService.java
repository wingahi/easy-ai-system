package com.wgh.rag.agentmulti.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wgh.rag.agentmulti.dto.RunCreateRequest;
import com.wgh.rag.agentmulti.entity.MultiAgentCrew;
import com.wgh.rag.agentmulti.entity.MultiAgentRun;
import com.wgh.rag.agentmulti.entity.MultiAgentStep;
import com.wgh.rag.agentmulti.mapper.MultiAgentCrewMapper;
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
    private final MultiAgentCrewMapper crewMapper;

    // ==================== Run 装配 ====================

    /**
     * 装配并持久化一个 MultiAgentRun。
     * 规则：crewId 模板补齐（crewConfigJson 为空回退 graphJson，executionMode 模板优先）；
     * 显式 agentsJson/crewConfigJson 覆盖模板；DB NOT NULL 字段兜底默认值。
     */
    public MultiAgentRun createRun(RunCreateRequest req) {
        MultiAgentRun run = new MultiAgentRun();
        run.setRunId(req.getRunId());
        // session_id / chat_window_id 在库中为 NOT NULL —— 独立运行调试时无会话，兜底 0
        run.setSessionId(req.getSessionId() != null ? req.getSessionId() : 0L);
        run.setChatWindowId(req.getChatWindowId() != null ? req.getChatWindowId() : 0L);
        run.setExecutionMode(req.getExecutionMode());
        run.setUserGoal(req.getUserGoal());
        run.setInputText(req.getInput());
        run.setStatus("PLANNING");
        run.setCurrentRound(0);
        run.setMaxRounds(req.getMaxRounds() != null ? req.getMaxRounds() : 10);
        run.setDynamicRouterModelId(req.getDynamicRouterModelId());
        run.setDynamicRouterPrompt(req.getDynamicRouterPrompt());
        run.setTerminationCriteria(req.getTerminationCriteria());
        run.setSummarizerAgentId(req.getSummarizerAgentId());
        run.setStartTime(LocalDateTime.now());

        if (req.getCrewId() != null) {
            MultiAgentCrew crew = crewMapper.selectById(req.getCrewId());
            if (crew != null) {
                run.setCrewConfigJson(crew.getCrewConfigJson() != null ? crew.getCrewConfigJson() : crew.getGraphJson());
                run.setAgentsJson(crew.getAgentsJson());
                if (crew.getExecutionMode() != null) run.setExecutionMode(crew.getExecutionMode());
            }
        }

        if (req.getCrewConfigJson() != null) run.setCrewConfigJson(req.getCrewConfigJson());
        if (req.getAgentsJson() != null) run.setAgentsJson(req.getAgentsJson());

        // 必须有默认值（DB NOT NULL）
        if (run.getCrewConfigJson() == null) run.setCrewConfigJson("{}");
        if (run.getAgentsJson() == null) run.setAgentsJson("[]");

        runMapper.insert(run);
        return run;
    }

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

package com.wgh.rag.agent.service;

import com.wgh.rag.agent.dto.PlanDTO.StepDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 步骤调度器 —— 负责计划步骤的拓扑排序（就绪步骤选取）。
 */
@Slf4j
@Component
public class StepScheduler {

    /**
     * 按 dependsOn 做拓扑排序；存在环路时将剩余步骤按原顺序追加到末尾。
     */
    public List<StepDTO> topoSort(List<StepDTO> steps) {
        if (steps == null || steps.isEmpty()) return List.of();
        Map<String, StepDTO> byId = new LinkedHashMap<>();
        for (StepDTO s : steps) byId.put(s.getStepId(), s);

        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> graph = new HashMap<>();
        for (StepDTO s : steps) { indegree.put(s.getStepId(), 0); graph.put(s.getStepId(), new ArrayList<>()); }
        for (StepDTO s : steps) {
            if (s.getDependsOn() != null) for (String dep : s.getDependsOn()) {
                if (byId.containsKey(dep)) { graph.get(dep).add(s.getStepId()); indegree.merge(s.getStepId(), 1, Integer::sum); }
            }
        }

        Queue<String> queue = indegree.entrySet().stream()
                .filter(e -> e.getValue() == 0).map(Map.Entry::getKey)
                .collect(Collectors.toCollection(ArrayDeque::new));
        List<StepDTO> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            String id = queue.poll();
            sorted.add(byId.get(id));
            for (String next : graph.get(id)) {
                indegree.merge(next, -1, Integer::sum);
                if (indegree.get(next) == 0) queue.offer(next);
            }
        }
        if (sorted.size() != steps.size()) {
            log.warn("拓扑排序存在环路，剩余 {} 个追加到末尾", steps.size() - sorted.size());
            Set<String> sortedIds = sorted.stream().map(StepDTO::getStepId).collect(Collectors.toSet());
            for (StepDTO s : steps) if (!sortedIds.contains(s.getStepId())) sorted.add(s);
        }
        return sorted;
    }
}

package com.wgh.rag.agent.service;

import com.wgh.rag.agent.dto.PlanDTO.StepDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** StepScheduler 拓扑排序单元测试。 */
class StepSchedulerTest {

    private final StepScheduler scheduler = new StepScheduler();

    @Test
    void 空与null输入_返回空列表() {
        assertThat(scheduler.topoSort(null)).isEmpty();
        assertThat(scheduler.topoSort(List.of())).isEmpty();
    }

    @Test
    void 无依赖_保持相对可执行() {
        List<StepDTO> sorted = scheduler.topoSort(List.of(step("a"), step("b"), step("c")));
        assertThat(sorted).extracting(StepDTO::getStepId)
                .containsExactlyInAnyOrder("a", "b", "c");
    }

    @Test
    void 链式依赖_严格按依赖序() {
        List<StepDTO> sorted = scheduler.topoSort(List.of(
                step("s3", "s2"), step("s1"), step("s2", "s1")));
        assertThat(sorted).extracting(StepDTO::getStepId)
                .containsExactly("s1", "s2", "s3");
    }

    @Test
    void 菱形依赖_依赖先于被依赖() {
        List<StepDTO> sorted = scheduler.topoSort(List.of(
                step("d", "b", "c"), step("b", "a"), step("c", "a"), step("a")));
        List<String> ids = sorted.stream().map(StepDTO::getStepId).toList();
        assertThat(ids).containsExactlyInAnyOrder("a", "b", "c", "d");
        assertThat(ids.indexOf("a")).isLessThan(ids.indexOf("b"));
        assertThat(ids.indexOf("a")).isLessThan(ids.indexOf("c"));
        assertThat(ids.indexOf("b")).isLessThan(ids.indexOf("d"));
        assertThat(ids.indexOf("c")).isLessThan(ids.indexOf("d"));
    }

    @Test
    void 未知依赖_被忽略() {
        List<StepDTO> sorted = scheduler.topoSort(List.of(step("a", "ghost"), step("b")));
        assertThat(sorted).extracting(StepDTO::getStepId)
                .containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void 环路_剩余步骤追加到末尾() {
        List<StepDTO> sorted = scheduler.topoSort(List.of(
                step("a", "b"), step("b", "a"), step("c")));
        // c 无依赖先出；a、b 成环追加到末尾且保持原顺序
        assertThat(sorted).extracting(StepDTO::getStepId)
                .containsExactly("c", "a", "b");
    }

    private static StepDTO step(String id, String... deps) {
        return StepDTO.builder()
                .stepId(id)
                .title("t-" + id)
                .dependsOn(deps.length == 0 ? List.of() : List.of(deps))
                .build();
    }
}

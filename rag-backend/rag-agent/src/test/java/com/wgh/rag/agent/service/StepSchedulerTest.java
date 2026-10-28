package com.wgh.rag.agent.service;

import com.wgh.rag.agent.dto.PlanDTO.StepDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** StepScheduler 拓扑排序单元测试。 */
class StepSchedulerTest {

    private final StepScheduler scheduler = new StepScheduler();

    /** 空输入与 null 输入均返回空列表。 */
    @Test
    void topoSort_emptyOrNullInput_returnsEmptyList() {
        assertThat(scheduler.topoSort(null)).isEmpty();
        assertThat(scheduler.topoSort(List.of())).isEmpty();
    }

    /** 无依赖的步骤保持原相对顺序，全部可执行。 */
    @Test
    void topoSort_noDependencies_keepsRelativeOrder() {
        List<StepDTO> sorted = scheduler.topoSort(List.of(step("a"), step("b"), step("c")));
        assertThat(sorted).extracting(StepDTO::getStepId)
                .containsExactlyInAnyOrder("a", "b", "c");
    }

    /** 链式依赖严格按依赖顺序输出。 */
    @Test
    void topoSort_chainDependencies_respectsDependencyOrder() {
        List<StepDTO> sorted = scheduler.topoSort(List.of(
                step("s3", "s2"), step("s1"), step("s2", "s1")));
        assertThat(sorted).extracting(StepDTO::getStepId)
                .containsExactly("s1", "s2", "s3");
    }

    /** 菱形依赖中每个依赖都排在其被依赖步骤之前。 */
    @Test
    void topoSort_diamondDependencies_dependenciesBeforeDependents() {
        List<StepDTO> sorted = scheduler.topoSort(List.of(
                step("d", "b", "c"), step("b", "a"), step("c", "a"), step("a")));
        List<String> ids = sorted.stream().map(StepDTO::getStepId).toList();
        assertThat(ids).containsExactlyInAnyOrder("a", "b", "c", "d");
        assertThat(ids.indexOf("a")).isLessThan(ids.indexOf("b"));
        assertThat(ids.indexOf("a")).isLessThan(ids.indexOf("c"));
        assertThat(ids.indexOf("b")).isLessThan(ids.indexOf("d"));
        assertThat(ids.indexOf("c")).isLessThan(ids.indexOf("d"));
    }

    /** 依赖了不存在的步骤 ID 时该依赖被忽略，不影响排序。 */
    @Test
    void topoSort_unknownDependencies_ignored() {
        List<StepDTO> sorted = scheduler.topoSort(List.of(step("a", "ghost"), step("b")));
        assertThat(sorted).extracting(StepDTO::getStepId)
                .containsExactlyInAnyOrder("a", "b");
    }

    /** 存在环路时，环内步骤追加到末尾并保持原顺序。 */
    @Test
    void topoSort_cycle_remainingStepsAppendedAtEnd() {
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

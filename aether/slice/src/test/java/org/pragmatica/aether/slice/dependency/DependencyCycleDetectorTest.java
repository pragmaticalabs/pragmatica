package org.pragmatica.aether.slice.dependency;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DependencyCycleDetectorTest {

    @Test
    void no_cycle_in_simple_chain() {
        // A -> B -> C
        var dependencies = Map.of(
                "A", List.of("B"),
                "B", List.of("C"),
                "C", List.<String>of()
                                 );

        DependencyCycleDetector.checkForCycles(dependencies)
                               .onFailureRun(Assertions::fail);
    }

    @Test
    void no_cycle_in_diamond() {
        // A -> B -> D
        //   -> C -> D
        var dependencies = Map.of(
                "A", List.of("B", "C"),
                "B", List.of("D"),
                "C", List.of("D"),
                "D", List.<String>of()
                                 );

        DependencyCycleDetector.checkForCycles(dependencies)
                               .onFailureRun(Assertions::fail);
    }

    @Test
    void no_cycle_in_forest() {
        // A -> B
        // C -> D
        var dependencies = Map.of(
                "A", List.of("B"),
                "B", List.<String>of(),
                "C", List.of("D"),
                "D", List.<String>of()
                                 );

        DependencyCycleDetector.checkForCycles(dependencies)
                               .onFailureRun(Assertions::fail);
    }

    @Test
    void detects_self_cycle() {
        // A -> A
        var dependencies = Map.of(
                "A", List.of("A")
                                 );

        DependencyCycleDetector.checkForCycles(dependencies)
                               .onSuccessRun(Assertions::fail)
                               .onFailure(cause -> {
                                   assertThat(cause.message()).contains("Circular dependency");
                                   assertThat(cause.message()).contains("A -> A");
                               });
    }

    @Test
    void detects_simple_cycle() {
        // A -> B -> A
        var dependencies = Map.of(
                "A", List.of("B"),
                "B", List.of("A")
                                 );

        DependencyCycleDetector.checkForCycles(dependencies)
                               .onSuccessRun(Assertions::fail)
                               .onFailure(cause -> {
                                   assertThat(cause.message()).contains("Circular dependency");
                                   // Cycle can be reported as A -> B -> A or B -> A -> B
                                   assertThat(cause.message()).matches(".*([AB] -> [AB] -> [AB]).*");
                               });
    }

    @Test
    void detects_longer_cycle() {
        // A -> B -> C -> D -> B
        var dependencies = Map.of(
                "A", List.of("B"),
                "B", List.of("C"),
                "C", List.of("D"),
                "D", List.of("B")
                                 );

        DependencyCycleDetector.checkForCycles(dependencies)
                               .onSuccessRun(Assertions::fail)
                               .onFailure(cause -> {
                                   assertThat(cause.message()).contains("Circular dependency");
                                   assertThat(cause.message()).contains(" -> ");
                               });
    }

    @Test
    void detects_cycle_in_complex_graph() {
        // A -> B -> C
        //   -> D -> E -> C (cycle: E -> C -> D -> E)
        // F -> G
        var dependencies = Map.of(
                "A", List.of("B", "D"),
                "B", List.of("C"),
                "C", List.of("D"),
                "D", List.of("E"),
                "E", List.of("C"),
                "F", List.of("G"),
                "G", List.<String>of()
                                 );

        DependencyCycleDetector.checkForCycles(dependencies)
                               .onSuccessRun(Assertions::fail)
                               .onFailure(cause -> {
                                   assertThat(cause.message()).contains("Circular dependency");
                               });
    }

    @Test
    void empty_graph_has_no_cycles() {
        var dependencies = Map.<String, List<String>>of();

        DependencyCycleDetector.checkForCycles(dependencies)
                               .onFailureRun(Assertions::fail);
    }

    @Test
    void single_node_no_deps_has_no_cycles() {
        var dependencies = Map.of(
                "A", List.<String>of()
                                 );

        DependencyCycleDetector.checkForCycles(dependencies)
                               .onFailureRun(Assertions::fail);
    }

    @Test
    void detects_cycle_when_dependency_not_in_map() {
        // A -> B (B not in map, treated as no dependencies)
        // C -> D -> C
        var dependencies = Map.of(
                "A", List.of("B"),
                "C", List.of("D"),
                "D", List.of("C")
                                 );

        DependencyCycleDetector.checkForCycles(dependencies)
                               .onSuccessRun(Assertions::fail)
                               .onFailure(cause -> {
                                   assertThat(cause.message()).contains("Circular dependency");
                                   assertThat(cause.message()).contains("C");
                                   assertThat(cause.message()).contains("D");
                               });
    }
}

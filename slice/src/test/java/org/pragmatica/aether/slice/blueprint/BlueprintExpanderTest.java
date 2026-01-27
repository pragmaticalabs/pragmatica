package org.pragmatica.aether.slice.blueprint;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.lang.Promise;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BlueprintExpanderTest {

    private Blueprint testBlueprint;
    private MockDependencyLoader loader;

    @BeforeEach
    void setup() {
        var blueprintId = BlueprintId.blueprintId("org.example:test-blueprint:1.0.0").unwrap();
        var sliceA = SliceSpec.sliceSpec(
                Artifact.artifact("org.example:slice-a:1.0.0").unwrap(),
                3
                                        ).unwrap();

        testBlueprint = Blueprint.blueprint(blueprintId, List.of(sliceA)).unwrap();
        loader = new MockDependencyLoader();
    }

    @Nested
    class NoDependencies {
        @Test
        void expand_succeeds_withSingleSliceNoDependencies() {
            loader.addSlice("org.example:slice-a:1.0.0", Set.of());

            BlueprintExpander.expand(testBlueprint, loader)
                             .await()
                             .onFailure(cause -> Assertions.fail(cause.message()))
                             .onSuccess(expanded -> {
                                 assertThat(expanded.id()).isEqualTo(testBlueprint.id());
                                 assertThat(expanded.loadOrder()).hasSize(1);

                                 var resolved = expanded.loadOrder().getFirst();
                                 assertThat(resolved.artifact().asString()).isEqualTo("org.example:slice-a:1.0.0");
                                 assertThat(resolved.instances()).isEqualTo(3);
                                 assertThat(resolved.isDependency()).isFalse();
                             });
        }

        @Test
        void expand_succeeds_withMultipleSlicesNoDependencies() {
            var blueprintId = BlueprintId.blueprintId("org.example:multi-slice:1.0.0").unwrap();
            var sliceA = SliceSpec.sliceSpec(
                    Artifact.artifact("org.example:slice-a:1.0.0").unwrap(),
                    2
                                            ).unwrap();
            var sliceB = SliceSpec.sliceSpec(
                    Artifact.artifact("org.example:slice-b:1.0.0").unwrap(),
                    1
                                            ).unwrap();

            var blueprint = Blueprint.blueprint(blueprintId, List.of(sliceA, sliceB)).unwrap();

            loader.addSlice("org.example:slice-a:1.0.0", Set.of());
            loader.addSlice("org.example:slice-b:1.0.0", Set.of());

            BlueprintExpander.expand(blueprint, loader)
                             .await()
                             .onFailure(cause -> Assertions.fail(cause.message()))
                             .onSuccess(expanded -> {
                                 assertThat(expanded.loadOrder()).hasSize(2);

                                 var artifacts = expanded.loadOrder().stream()
                                                         .map(r -> r.artifact().asString())
                                                         .toList();
                                 assertThat(artifacts).containsExactlyInAnyOrder(
                                         "org.example:slice-a:1.0.0",
                                         "org.example:slice-b:1.0.0"
                                                                                );
                             });
        }
    }

    @Nested
    class WithDependencies {
        @Test
        void expand_succeeds_withDirectDependency() {
            loader.addSlice("org.example:slice-a:1.0.0", Set.of(
                    Artifact.artifact("org.example:slice-b:1.0.0").unwrap()
                                                               ));
            loader.addSlice("org.example:slice-b:1.0.0", Set.of());

            BlueprintExpander.expand(testBlueprint, loader)
                             .await()
                             .onFailure(cause -> Assertions.fail(cause.message()))
                             .onSuccess(expanded -> {
                                 assertThat(expanded.loadOrder()).hasSize(2);

                                 // Dependency (SliceB) must come before dependent (SliceA)
                                 var loadOrder = expanded.loadOrder();
                                 assertThat(loadOrder.get(0).artifact().asString()).isEqualTo(
                                         "org.example:slice-b:1.0.0");
                                 assertThat(loadOrder.get(1).artifact().asString()).isEqualTo(
                                         "org.example:slice-a:1.0.0");

                                 // SliceA is explicit (from blueprint)
                                 assertThat(loadOrder.get(1).instances()).isEqualTo(3);
                                 assertThat(loadOrder.get(1).isDependency()).isFalse();

                                 // SliceB is transitive dependency
                                 assertThat(loadOrder.get(0).instances()).isEqualTo(1);
                                 assertThat(loadOrder.get(0).isDependency()).isTrue();
                             });
        }

        @Test
        void expand_succeeds_withTransitiveDependencies() {
            loader.addSlice("org.example:slice-a:1.0.0", Set.of(
                    Artifact.artifact("org.example:slice-b:1.0.0").unwrap()
                                                               ));
            loader.addSlice("org.example:slice-b:1.0.0", Set.of(
                    Artifact.artifact("org.example:slice-c:1.0.0").unwrap()
                                                               ));
            loader.addSlice("org.example:slice-c:1.0.0", Set.of());

            BlueprintExpander.expand(testBlueprint, loader)
                             .await()
                             .onFailure(cause -> Assertions.fail(cause.message()))
                             .onSuccess(expanded -> {
                                 assertThat(expanded.loadOrder()).hasSize(3);

                                 var loadOrder = expanded.loadOrder();
                                 // C must come before B, B before A
                                 assertThat(loadOrder.get(0).artifact().asString()).isEqualTo(
                                         "org.example:slice-c:1.0.0");
                                 assertThat(loadOrder.get(1).artifact().asString()).isEqualTo(
                                         "org.example:slice-b:1.0.0");
                                 assertThat(loadOrder.get(2).artifact().asString()).isEqualTo(
                                         "org.example:slice-a:1.0.0");

                                 // Only SliceA is explicit
                                 assertThat(loadOrder.get(2).isDependency()).isFalse();
                                 assertThat(loadOrder.get(1).isDependency()).isTrue();
                                 assertThat(loadOrder.get(0).isDependency()).isTrue();
                             });
        }

        @Test
        void expand_succeeds_withSharedDependency() {
            var blueprintId = BlueprintId.blueprintId("org.example:shared-dep:1.0.0").unwrap();
            var sliceA = SliceSpec.sliceSpec(
                    Artifact.artifact("org.example:slice-a:1.0.0").unwrap()
                                            ).unwrap();
            var sliceB = SliceSpec.sliceSpec(
                    Artifact.artifact("org.example:slice-b:1.0.0").unwrap()
                                            ).unwrap();

            var blueprint = Blueprint.blueprint(blueprintId, List.of(sliceA, sliceB)).unwrap();

            // Both A and B depend on C
            loader.addSlice("org.example:slice-a:1.0.0", Set.of(
                    Artifact.artifact("org.example:slice-c:1.0.0").unwrap()
                                                               ));
            loader.addSlice("org.example:slice-b:1.0.0", Set.of(
                    Artifact.artifact("org.example:slice-c:1.0.0").unwrap()
                                                               ));
            loader.addSlice("org.example:slice-c:1.0.0", Set.of());

            BlueprintExpander.expand(blueprint, loader)
                             .await()
                             .onFailure(cause -> Assertions.fail(cause.message()))
                             .onSuccess(expanded -> {
                                 assertThat(expanded.loadOrder()).hasSize(3);

                                 // C must come before both A and B (no duplicates)
                                 var artifacts = expanded.loadOrder().stream()
                                                         .map(r -> r.artifact().asString())
                                                         .toList();
                                 assertThat(artifacts).containsExactlyInAnyOrder(
                                         "org.example:slice-a:1.0.0",
                                         "org.example:slice-b:1.0.0",
                                         "org.example:slice-c:1.0.0"
                                                                                );

                                 // C comes first
                                 assertThat(expanded.loadOrder().get(0).artifact().asString())
                                         .isEqualTo("org.example:slice-c:1.0.0");
                             });
        }

        @Test
        void expand_preservesExplicitInstanceCounts() {
            var blueprintId = BlueprintId.blueprintId("org.example:explicit-counts:1.0.0").unwrap();
            var sliceA = SliceSpec.sliceSpec(
                    Artifact.artifact("org.example:slice-a:1.0.0").unwrap(),
                    5
                                            ).unwrap();
            var sliceB = SliceSpec.sliceSpec(
                    Artifact.artifact("org.example:slice-b:1.0.0").unwrap(),
                    2
                                            ).unwrap();

            var blueprint = Blueprint.blueprint(blueprintId, List.of(sliceA, sliceB)).unwrap();

            // A and B both depend on C
            loader.addSlice("org.example:slice-a:1.0.0", Set.of(
                    Artifact.artifact("org.example:slice-c:1.0.0").unwrap()
                                                               ));
            loader.addSlice("org.example:slice-b:1.0.0", Set.of(
                    Artifact.artifact("org.example:slice-c:1.0.0").unwrap()
                                                               ));
            loader.addSlice("org.example:slice-c:1.0.0", Set.of());

            BlueprintExpander.expand(blueprint, loader)
                             .await()
                             .onFailure(cause -> Assertions.fail(cause.message()))
                             .onSuccess(expanded -> {
                                 var loadOrder = expanded.loadOrder();

                                 // Find each slice in load order
                                 var sliceAResolved = loadOrder.stream()
                                                               .filter(r -> r.artifact()
                                                                             .asString()
                                                                             .equals("org.example:slice-a:1.0.0"))
                                                               .findFirst()
                                                               .orElseThrow();
                                 var sliceBResolved = loadOrder.stream()
                                                               .filter(r -> r.artifact()
                                                                             .asString()
                                                                             .equals("org.example:slice-b:1.0.0"))
                                                               .findFirst()
                                                               .orElseThrow();
                                 var sliceCResolved = loadOrder.stream()
                                                               .filter(r -> r.artifact()
                                                                             .asString()
                                                                             .equals("org.example:slice-c:1.0.0"))
                                                               .findFirst()
                                                               .orElseThrow();

                                 // Explicit slices keep their counts
                                 assertThat(sliceAResolved.instances()).isEqualTo(5);
                                 assertThat(sliceAResolved.isDependency()).isFalse();

                                 assertThat(sliceBResolved.instances()).isEqualTo(2);
                                 assertThat(sliceBResolved.isDependency()).isFalse();

                                 // Transitive dependency gets instances=1
                                 assertThat(sliceCResolved.instances()).isEqualTo(1);
                                 assertThat(sliceCResolved.isDependency()).isTrue();
                             });
        }
    }

    @Nested
    class ErrorCases {
        @Test
        void expand_fails_onCircularDependency() {
            loader.addSlice("org.example:slice-a:1.0.0", Set.of(
                    Artifact.artifact("org.example:slice-b:1.0.0").unwrap()
                                                               ));
            loader.addSlice("org.example:slice-b:1.0.0", Set.of(
                    Artifact.artifact("org.example:slice-a:1.0.0").unwrap()
                                                               ));

            BlueprintExpander.expand(testBlueprint, loader)
                             .await()
                             .onSuccessRun(Assertions::fail)
                             .onFailure(cause -> {
                                 assertThat(cause.message()).contains("Circular dependency");
                             });
        }

        @Test
        void expand_fails_whenArtifactNotFound() {
            loader.addSlice("org.example:slice-a:1.0.0", Set.of(
                    Artifact.artifact("org.example:non-existent:1.0.0").unwrap()
                                                               ));

            BlueprintExpander.expand(testBlueprint, loader)
                             .await()
                             .onSuccessRun(Assertions::fail)
                             .onFailure(cause -> {
                                 assertThat(cause.message()).contains("not found");
                             });
        }
    }

    /**
     * Mock dependency loader for testing.
     */
    static class MockDependencyLoader implements DependencyLoader {
        private final Map<String, Set<Artifact>> dependencies = new HashMap<>();

        void addSlice(String artifactStr, Set<Artifact> deps) {
            dependencies.put(artifactStr, deps);
        }

        @Override
        public Promise<Set<Artifact>> loadDependencies(Artifact artifact) {
            var deps = dependencies.get(artifact.asString());
            if (deps == null) {
                var cause = org.pragmatica.lang.utils.Causes.cause("Artifact not found: " + artifact.asString());
                return cause.promise();
            }
            return Promise.success(deps);
        }
    }
}

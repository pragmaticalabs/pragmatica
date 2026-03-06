package org.pragmatica.aether.deployment.cluster;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.blueprint.ResolvedSlice;
import org.pragmatica.lang.Option;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.ClusterDeploymentState.Active.InFlightBlueprint;
import static org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;

class BlueprintAtomicityTest {
    private static final Version V1 = Version.version("1.0.0").unwrap();
    private static final Version V2 = Version.version("2.0.0").unwrap();

    private static Artifact artifact(String name, Version version) {
        return Artifact.artifact("com.example:" + name + ":" + version.bareVersion()).unwrap();
    }

    private static ExpandedBlueprint createBlueprint(String name, Version version, String... sliceNames) {
        var blueprintId = BlueprintId.blueprintId("com.example:" + name + ":" + version.bareVersion()).unwrap();
        var slices = Arrays.stream(sliceNames)
                           .map(sn -> ResolvedSlice.resolvedSlice(artifact(sn, version), 3, false).unwrap())
                           .toList();
        return ExpandedBlueprint.expandedBlueprint(blueprintId, slices);
    }

    @Nested
    class InFlightBlueprintTests {
        @Test
        void inFlightBlueprint_creation_tracksPendingSlices() {
            var bp = createBlueprint("app", V1, "slice-a", "slice-b", "slice-c");
            var inflight = InFlightBlueprint.inFlightBlueprint(bp.id(), bp, Option.empty());

            assertThat(inflight.pendingSlices()).hasSize(3);
            assertThat(inflight.activeSlices()).isEmpty();
            assertThat(inflight.previousBlueprint().isEmpty()).isTrue();
        }

        @Test
        void inFlightBlueprint_sliceActivation_movesPendingToActive() {
            var bp = createBlueprint("app", V1, "slice-a", "slice-b");
            var inflight = InFlightBlueprint.inFlightBlueprint(bp.id(), bp, Option.empty());
            var sliceA = artifact("slice-a", V1);

            assertThat(inflight.pendingSlices().remove(sliceA)).isTrue();
            inflight.activeSlices().add(sliceA);

            assertThat(inflight.pendingSlices()).hasSize(1);
            assertThat(inflight.activeSlices()).hasSize(1);
        }

        @Test
        void inFlightBlueprint_allSlicesActive_pendingEmpty() {
            var bp = createBlueprint("app", V1, "slice-a", "slice-b");
            var inflight = InFlightBlueprint.inFlightBlueprint(bp.id(), bp, Option.empty());
            var sliceA = artifact("slice-a", V1);
            var sliceB = artifact("slice-b", V1);

            inflight.pendingSlices().remove(sliceA);
            inflight.activeSlices().add(sliceA);
            inflight.pendingSlices().remove(sliceB);
            inflight.activeSlices().add(sliceB);

            assertThat(inflight.pendingSlices()).isEmpty();
            assertThat(inflight.activeSlices()).hasSize(2);
        }

        @Test
        void inFlightBlueprint_withPreviousBlueprint_tracksPrevious() {
            var prevBp = createBlueprint("app", V1, "slice-a", "slice-b");
            var newBp = createBlueprint("app", V2, "slice-a", "slice-b", "slice-c");
            var inflight = InFlightBlueprint.inFlightBlueprint(newBp.id(), newBp, Option.some(prevBp));

            assertThat(inflight.previousBlueprint().isPresent()).isTrue();
            inflight.previousBlueprint().onPresent(prev ->
                assertThat(prev.loadOrder()).hasSize(2)
            );
        }
    }

    @Nested
    class DeploymentAtomicityParsingTests {
        @Test
        void parse_allOrNothing_kebabCase() {
            assertThat(DeploymentAtomicity.parse("all-or-nothing"))
                .isEqualTo(DeploymentAtomicity.ALL_OR_NOTHING);
        }

        @Test
        void parse_allOrNothing_snakeCase() {
            assertThat(DeploymentAtomicity.parse("ALL_OR_NOTHING"))
                .isEqualTo(DeploymentAtomicity.ALL_OR_NOTHING);
        }

        @Test
        void parse_bestEffort_kebabCase() {
            assertThat(DeploymentAtomicity.parse("best-effort"))
                .isEqualTo(DeploymentAtomicity.BEST_EFFORT);
        }

        @Test
        void parse_bestEffort_snakeCase() {
            assertThat(DeploymentAtomicity.parse("BEST_EFFORT"))
                .isEqualTo(DeploymentAtomicity.BEST_EFFORT);
        }

        @Test
        void parse_null_defaultsToBestEffort() {
            assertThat(DeploymentAtomicity.parse(null))
                .isEqualTo(DeploymentAtomicity.BEST_EFFORT);
        }

        @Test
        void parse_empty_defaultsToBestEffort() {
            assertThat(DeploymentAtomicity.parse(""))
                .isEqualTo(DeploymentAtomicity.BEST_EFFORT);
        }

        @Test
        void parse_unknown_defaultsToBestEffort() {
            assertThat(DeploymentAtomicity.parse("unknown"))
                .isEqualTo(DeploymentAtomicity.BEST_EFFORT);
        }
    }
}

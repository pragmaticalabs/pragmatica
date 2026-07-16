// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.controller.ClusterController.ArtifactLoad;
import org.pragmatica.aether.controller.ClusterController.Blueprint;
import org.pragmatica.aether.controller.ClusterController.BlueprintChange;
import org.pragmatica.aether.controller.ClusterController.ControlContext;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionTreeControllerTest {

    private static final Artifact TEST_ARTIFACT = Artifact.artifact("org.test:my-slice:1.0.0").unwrap();
    private static final Artifact ARTIFACT_A = Artifact.artifact("org.test:slice-a:1.0.0").unwrap();
    private static final Artifact ARTIFACT_B = Artifact.artifact("org.test:slice-b:1.0.0").unwrap();

    private DecisionTreeController controller;

    @BeforeEach
    void setUp() {
        controller = DecisionTreeController.decisionTreeController();
    }

    @Nested
    class PerArtifactRules {
        @Test
        void evaluate_highLoad_returnsScaleUp() {
            var context = context(Map.of(TEST_ARTIFACT, load(2.0)),
                                  Map.of(TEST_ARTIFACT, blueprint(2, 1)));

            var decisions = controller.evaluate(context).await().unwrap();

            assertThat(decisions.changes()).hasSize(1);
            assertThat(decisions.changes().getFirst()).isInstanceOf(BlueprintChange.ScaleUp.class);
        }

        @Test
        void evaluate_lowLoadWithMultipleInstances_returnsScaleDown() {
            var context = context(Map.of(TEST_ARTIFACT, load(0.2)),
                                  Map.of(TEST_ARTIFACT, blueprint(2, 1)));

            var decisions = controller.evaluate(context).await().unwrap();

            assertThat(decisions.changes()).hasSize(1);
            assertThat(decisions.changes().getFirst()).isInstanceOf(BlueprintChange.ScaleDown.class);
        }

        @Test
        void evaluate_lowLoadAtMinInstances_returnsNoChanges() {
            var context = context(Map.of(TEST_ARTIFACT, load(0.2)),
                                  Map.of(TEST_ARTIFACT, blueprint(3, 3)));

            var decisions = controller.evaluate(context).await().unwrap();

            assertThat(decisions.changes()).isEmpty();
        }

        @Test
        void evaluate_normalLoad_returnsNoChanges() {
            var context = context(Map.of(TEST_ARTIFACT, load(1.0)),
                                  Map.of(TEST_ARTIFACT, blueprint(2, 1)));

            var decisions = controller.evaluate(context).await().unwrap();

            assertThat(decisions.changes()).isEmpty();
        }

        @Test
        void evaluate_windowNotReady_returnsNoChanges() {
            var notReady = ArtifactLoad.artifactLoad(2.0, false, false, Map.of());
            var context = context(Map.of(TEST_ARTIFACT, notReady),
                                  Map.of(TEST_ARTIFACT, blueprint(2, 1)));

            var decisions = controller.evaluate(context).await().unwrap();

            assertThat(decisions.changes()).isEmpty();
        }

        @Test
        void evaluate_highErrorRate_gatesScaleUp() {
            var errorGated = ArtifactLoad.artifactLoad(2.0, true, true, Map.of());
            var context = context(Map.of(TEST_ARTIFACT, errorGated),
                                  Map.of(TEST_ARTIFACT, blueprint(2, 1)));

            var decisions = controller.evaluate(context).await().unwrap();

            assertThat(decisions.changes()).isEmpty();
        }

        @Test
        void evaluate_noLoadForBlueprint_returnsNoChanges() {
            var context = context(Map.of(),
                                  Map.of(TEST_ARTIFACT, blueprint(2, 1)));

            var decisions = controller.evaluate(context).await().unwrap();

            assertThat(decisions.changes()).isEmpty();
        }

        @Test
        void evaluate_perSliceScaleUpOverrideBelowClusterDefault_scalesUp() {
            // Cluster default scaleUpThreshold = 1.5 (productionDefaults); a composite of 1.0 does not
            // trip the cluster tier, but the per-slice override 0.8 does.
            var clusterDefault = context(Map.of(TEST_ARTIFACT, load(1.0)),
                                         Map.of(TEST_ARTIFACT, blueprint(2, 1)));

            assertThat(controller.evaluate(clusterDefault).await().unwrap().changes()).isEmpty();

            var perSliceOverride = context(Map.of(TEST_ARTIFACT, load(1.0)),
                                           Map.of(TEST_ARTIFACT, blueprintWithScaleUpOverride(2, 1, 0.8)));
            var decisions = controller.evaluate(perSliceOverride).await().unwrap();

            assertThat(decisions.changes()).hasSize(1);
            assertThat(decisions.changes().getFirst()).isInstanceOf(BlueprintChange.ScaleUp.class);
        }
    }

    /// #422: a hot method on artifact B must scale ONLY B, never the idle artifact A. Before the
    /// per-slice rewrite the call-rate rule scanned all nodes' `method.*.calls` and emitted a
    /// ScaleUp for whichever artifact was under evaluation, so load on B amplified A.
    @Nested
    class Attribution {
        @Test
        void evaluate_hotArtifactB_scalesOnlyB_notIdleA() {
            var context = context(Map.of(ARTIFACT_A, load(1.0), ARTIFACT_B, load(2.0)),
                                  Map.of(ARTIFACT_A, blueprint(2, 1), ARTIFACT_B, blueprint(2, 1)));

            var decisions = controller.evaluate(context).await().unwrap();

            assertThat(decisions.changes()).hasSize(1);
            var change = decisions.changes().getFirst();
            assertThat(change).isInstanceOf(BlueprintChange.ScaleUp.class);
            assertThat(change.artifact()).isEqualTo(ARTIFACT_B);
        }

        @Test
        void evaluate_idleArtifactWithNoMetrics_neverScales() {
            var context = context(Map.of(ARTIFACT_B, load(2.0)),
                                  Map.of(ARTIFACT_A, blueprint(2, 1), ARTIFACT_B, blueprint(2, 1)));

            var decisions = controller.evaluate(context).await().unwrap();

            assertThat(decisions.changes()).allSatisfy(change -> assertThat(change.artifact()).isEqualTo(ARTIFACT_B));
        }
    }

    @Nested
    class Configuration {
        @Test
        void configuration_returnsCurrentConfig() {
            var config = controller.configuration();

            assertThat(config).isEqualTo(ControllerConfig.DEFAULT);
        }

        @Test
        void updateConfiguration_updatesTheConfig() {
            var newConfig = ControllerConfig.DEFAULT.withCpuScaleUpThreshold(0.95);

            controller.updateConfiguration(newConfig);

            assertThat(controller.configuration().cpuScaleUpThreshold()).isEqualTo(0.95);
        }
    }

    @Nested
    class FactoryMethods {
        @Test
        void decisionTreeController_defaultFactory_createsWithDefaultConfig() {
            var ctrl = DecisionTreeController.decisionTreeController();

            assertThat(ctrl.configuration()).isEqualTo(ControllerConfig.DEFAULT);
        }

        @Test
        void decisionTreeController_validatedFactory_returnsSuccess() {
            var result = DecisionTreeController.decisionTreeController(0.9, 0.1, 500);

            result.onFailure(c -> org.junit.jupiter.api.Assertions.fail(c.message()))
                  .onSuccess(ctrl -> assertThat(ctrl.configuration().cpuScaleUpThreshold()).isEqualTo(0.9));
        }

        @Test
        void decisionTreeController_configFactory_usesProvidedConfig() {
            var config = ControllerConfig.DEFAULT.withCpuScaleUpThreshold(0.7);
            var ctrl = DecisionTreeController.decisionTreeController(config);

            assertThat(ctrl.configuration().cpuScaleUpThreshold()).isEqualTo(0.7);
        }
    }

    // === Helpers ===

    private static ControlContext context(Map<Artifact, ArtifactLoad> loads, Map<Artifact, Blueprint> blueprints) {
        return new ControlContext(loads, blueprints, List.of(NodeId.randomNodeId()));
    }

    private static ArtifactLoad load(double compositeScore) {
        return ArtifactLoad.artifactLoad(compositeScore, true, false, Map.of());
    }

    private static Blueprint blueprint(int instances, int minInstances) {
        return Blueprint.blueprint(TEST_ARTIFACT, instances, minInstances);
    }

    private static Blueprint blueprintWithScaleUpOverride(int instances, int minInstances, double scaleUpThreshold) {
        return new Blueprint(TEST_ARTIFACT,
                             instances,
                             minInstances,
                             Option.none(),
                             Option.some(scaleUpThreshold),
                             Option.none());
    }
}

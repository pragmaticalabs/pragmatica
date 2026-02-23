package org.pragmatica.aether.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.controller.ClusterController.Blueprint;
import org.pragmatica.aether.controller.ClusterController.BlueprintChange;
import org.pragmatica.aether.controller.ClusterController.ControlContext;
import org.pragmatica.aether.metrics.MetricsCollector;
import org.pragmatica.consensus.NodeId;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionTreeControllerTest {

    private static final Artifact TEST_ARTIFACT = Artifact.artifact("org.test:my-slice:1.0.0").unwrap();

    private DecisionTreeController controller;

    @BeforeEach
    void setUp() {
        controller = DecisionTreeController.decisionTreeController();
    }

    @Nested
    class CpuRules {
        @Test
        void evaluate_highCpu_returnsScaleUp() {
            var context = contextWithCpu(0.9, 2);

            var decisions = controller.evaluate(context).await().unwrap();

            assertThat(decisions.changes()).hasSize(1);
            assertThat(decisions.changes().getFirst()).isInstanceOf(BlueprintChange.ScaleUp.class);
        }

        @Test
        void evaluate_lowCpuWithMultipleInstances_returnsScaleDown() {
            var context = contextWithCpu(0.1, 2);

            var decisions = controller.evaluate(context).await().unwrap();

            assertThat(decisions.changes()).hasSize(1);
            assertThat(decisions.changes().getFirst()).isInstanceOf(BlueprintChange.ScaleDown.class);
        }

        @Test
        void evaluate_lowCpuWithSingleInstance_returnsNoChanges() {
            var context = contextWithCpu(0.1, 1);

            var decisions = controller.evaluate(context).await().unwrap();

            assertThat(decisions.changes()).isEmpty();
        }

        @Test
        void evaluate_normalCpu_returnsNoChanges() {
            var context = contextWithCpu(0.5, 2);

            var decisions = controller.evaluate(context).await().unwrap();

            assertThat(decisions.changes()).isEmpty();
        }

        @Test
        void evaluate_lowCpuAtMinInstances_returnsNoChanges() {
            var context = contextWithCpuAndMinInstances(0.1, 3, 3);

            var decisions = controller.evaluate(context).await().unwrap();

            assertThat(decisions.changes()).isEmpty();
        }

        @Test
        void evaluate_lowCpuAboveMinInstances_returnsScaleDown() {
            var context = contextWithCpuAndMinInstances(0.1, 5, 3);

            var decisions = controller.evaluate(context).await().unwrap();

            assertThat(decisions.changes()).hasSize(1);
            assertThat(decisions.changes().getFirst()).isInstanceOf(BlueprintChange.ScaleDown.class);
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

    private static ControlContext contextWithCpu(double cpuValue, int instances) {
        var nodeId = NodeId.randomNodeId();
        var metrics = Map.of(nodeId, Map.of(MetricsCollector.CPU_USAGE, cpuValue));
        var blueprints = Map.of(TEST_ARTIFACT, new Blueprint(TEST_ARTIFACT, instances, 1));
        return new ControlContext(metrics, blueprints, List.of(nodeId));
    }

    private static ControlContext contextWithCpuAndMinInstances(double cpuValue, int instances, int minInstances) {
        var nodeId = NodeId.randomNodeId();
        var metrics = Map.of(nodeId, Map.of(MetricsCollector.CPU_USAGE, cpuValue));
        var blueprints = Map.of(TEST_ARTIFACT, new Blueprint(TEST_ARTIFACT, instances, minInstances));
        return new ControlContext(metrics, blueprints, List.of(nodeId));
    }
}

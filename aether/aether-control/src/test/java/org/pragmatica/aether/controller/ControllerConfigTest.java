package org.pragmatica.aether.controller;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ControllerConfigTest {

    @Nested
    class DefaultValues {
        @Test
        void DEFAULT_hasValidValues() {
            var config = ControllerConfig.DEFAULT;

            assertThat(config.cpuScaleUpThreshold()).isEqualTo(0.8);
            assertThat(config.cpuScaleDownThreshold()).isEqualTo(0.2);
            assertThat(config.callRateScaleUpThreshold()).isEqualTo(2000.0);
            assertThat(config.evaluationIntervalMs()).isEqualTo(1000);
            assertThat(config.warmUpPeriodMs()).isEqualTo(30000);
            assertThat(config.sliceCooldownMs()).isEqualTo(10000);
            assertThat(config.scalingConfig()).isNotNull();
        }
    }

    @Nested
    class FactoryValidation {
        @Test
        void controllerConfig_validParams_returnsSuccess() {
            var result = ControllerConfig.controllerConfig(0.9, 0.1, 500, 2000);

            result.onFailure(c -> org.junit.jupiter.api.Assertions.fail(c.message()))
                  .onSuccess(config -> assertThat(config.cpuScaleUpThreshold()).isEqualTo(0.9))
                  .onSuccess(config -> assertThat(config.cpuScaleDownThreshold()).isEqualTo(0.1));
        }

        @Test
        void controllerConfig_cpuScaleUpThresholdAboveOne_returnsFailure() {
            var result = ControllerConfig.controllerConfig(1.5, 0.2, 500, 1000);

            result.onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"));
            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void controllerConfig_cpuScaleUpThresholdBelowZero_returnsFailure() {
            var result = ControllerConfig.controllerConfig(-0.1, 0.2, 500, 1000);

            result.onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"));
            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void controllerConfig_cpuScaleDownThresholdOutOfRange_returnsFailure() {
            var result = ControllerConfig.controllerConfig(0.8, 1.5, 500, 1000);

            result.onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"));
            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void controllerConfig_scaleUpThresholdNotGreaterThanScaleDown_returnsFailure() {
            var result = ControllerConfig.controllerConfig(0.3, 0.3, 500, 1000);

            result.onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"));
            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void controllerConfig_callRateNotPositive_returnsFailure() {
            var result = ControllerConfig.controllerConfig(0.8, 0.2, 0, 1000);

            result.onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"));
            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void controllerConfig_evaluationIntervalNotPositive_returnsFailure() {
            var result = ControllerConfig.controllerConfig(0.8, 0.2, 500, 0);

            result.onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"));
            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void controllerConfig_negativeWarmUpPeriod_returnsFailure() {
            var result = ControllerConfig.controllerConfig(0.8, 0.2, 500, 1000, -1, 5000);

            result.onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"));
            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void controllerConfig_negativeSliceCooldown_returnsFailure() {
            var result = ControllerConfig.controllerConfig(0.8, 0.2, 500, 1000, 5000, -1);

            result.onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"));
            assertThat(result.isFailure()).isTrue();
        }
    }

    @Nested
    class CopyMethods {
        @Test
        void withCpuScaleUpThreshold_createsCorrectCopy() {
            var original = ControllerConfig.DEFAULT;
            var updated = original.withCpuScaleUpThreshold(0.95);

            assertThat(updated.cpuScaleUpThreshold()).isEqualTo(0.95);
            assertThat(updated.cpuScaleDownThreshold()).isEqualTo(original.cpuScaleDownThreshold());
        }

        @Test
        void withCpuScaleDownThreshold_createsCorrectCopy() {
            var original = ControllerConfig.DEFAULT;
            var updated = original.withCpuScaleDownThreshold(0.15);

            assertThat(updated.cpuScaleDownThreshold()).isEqualTo(0.15);
            assertThat(updated.cpuScaleUpThreshold()).isEqualTo(original.cpuScaleUpThreshold());
        }

        @Test
        void withCallRateScaleUpThreshold_createsCorrectCopy() {
            var original = ControllerConfig.DEFAULT;
            var updated = original.withCallRateScaleUpThreshold(999);

            assertThat(updated.callRateScaleUpThreshold()).isEqualTo(999);
        }

        @Test
        void withEvaluationIntervalMs_createsCorrectCopy() {
            var original = ControllerConfig.DEFAULT;
            var updated = original.withEvaluationIntervalMs(5000);

            assertThat(updated.evaluationIntervalMs()).isEqualTo(5000);
        }

        @Test
        void withWarmUpPeriodMs_createsCorrectCopy() {
            var original = ControllerConfig.DEFAULT;
            var updated = original.withWarmUpPeriodMs(60000);

            assertThat(updated.warmUpPeriodMs()).isEqualTo(60000);
        }

        @Test
        void withSliceCooldownMs_createsCorrectCopy() {
            var original = ControllerConfig.DEFAULT;
            var updated = original.withSliceCooldownMs(20000);

            assertThat(updated.sliceCooldownMs()).isEqualTo(20000);
        }
    }

    @Nested
    class SemanticProfiles {
        @Test
        void forgeDefaults_usesForgeScalingConfig() {
            var forge = ControllerConfig.forgeDefaults();

            assertThat(forge.scalingConfig().weight(ScalingMetric.CPU)).isEqualTo(0.0);
            assertThat(forge.scalingConfig().weight(ScalingMetric.ACTIVE_INVOCATIONS)).isEqualTo(0.6);
        }
    }

    @Nested
    class JsonSerialization {
        @Test
        void toJson_producesValidJsonString() {
            var json = ControllerConfig.DEFAULT.toJson();

            assertThat(json).contains("\"cpuScaleUpThreshold\":0.8");
            assertThat(json).contains("\"cpuScaleDownThreshold\":0.2");
            assertThat(json).contains("\"evaluationIntervalMs\":1000");
            assertThat(json).startsWith("{");
            assertThat(json).endsWith("}");
        }
    }
}

package org.pragmatica.aether.controller;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScalingConfigTest {

    @Nested
    class ProductionDefaults {
        @Test
        void productionDefaults_hasCorrectWeights() {
            var config = ScalingConfig.productionDefaults();

            assertThat(config.weight(ScalingMetric.CPU)).isEqualTo(0.4);
            assertThat(config.weight(ScalingMetric.ACTIVE_INVOCATIONS)).isEqualTo(0.4);
            assertThat(config.weight(ScalingMetric.P95_LATENCY)).isEqualTo(0.2);
            assertThat(config.weight(ScalingMetric.ERROR_RATE)).isEqualTo(0.0);
        }
    }

    @Nested
    class ForgeDefaults {
        @Test
        void forgeDefaults_hasCorrectWeights() {
            var config = ScalingConfig.forgeDefaults();

            assertThat(config.weight(ScalingMetric.CPU)).isEqualTo(0.0);
            assertThat(config.weight(ScalingMetric.ACTIVE_INVOCATIONS)).isEqualTo(0.6);
            assertThat(config.weight(ScalingMetric.P95_LATENCY)).isEqualTo(0.4);
            assertThat(config.weight(ScalingMetric.ERROR_RATE)).isEqualTo(0.0);
        }
    }

    @Nested
    class FactoryValidation {
        @Test
        void scalingConfig_validParams_returnsSuccess() {
            var weights = validWeights();

            var result = ScalingConfig.scalingConfig(10, 5000, 1.5, 0.5, weights);

            result.onFailure(c -> org.junit.jupiter.api.Assertions.fail(c.message()))
                  .onSuccess(config -> assertThat(config.windowSize()).isEqualTo(10));
        }

        @Test
        void scalingConfig_zeroWindowSize_returnsFailure() {
            var result = ScalingConfig.scalingConfig(0, 5000, 1.5, 0.5, validWeights());

            result.onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"));
            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void scalingConfig_negativeScaleUpThreshold_returnsFailure() {
            var result = ScalingConfig.scalingConfig(10, 5000, -1.0, 0.5, validWeights());

            result.onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"));
            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void scalingConfig_zeroScaleDownThreshold_returnsFailure() {
            var result = ScalingConfig.scalingConfig(10, 5000, 1.5, 0, validWeights());

            result.onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"));
            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void scalingConfig_scaleUpNotGreaterThanScaleDown_returnsFailure() {
            var result = ScalingConfig.scalingConfig(10, 5000, 0.5, 0.5, validWeights());

            result.onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"));
            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void scalingConfig_negativeWeight_returnsFailure() {
            var weights = new EnumMap<ScalingMetric, Double>(ScalingMetric.class);
            weights.put(ScalingMetric.CPU, -0.1);

            var result = ScalingConfig.scalingConfig(10, 5000, 1.5, 0.5, weights);

            result.onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"));
            assertThat(result.isFailure()).isTrue();
        }
    }

    @Nested
    class WeightLookup {
        @Test
        void weight_unconfiguredMetric_returnsZero() {
            var config = ScalingConfig.scalingConfig(10, 5000, 1.5, 0.5, Map.of()).unwrap();

            assertThat(config.weight(ScalingMetric.CPU)).isEqualTo(0.0);
        }
    }

    @Nested
    class CopyMethods {
        @Test
        void withWeight_returnsValidResultConfig() {
            var config = ScalingConfig.productionDefaults();
            var result = config.withWeight(ScalingMetric.CPU, 0.6);

            result.onFailure(c -> org.junit.jupiter.api.Assertions.fail(c.message()))
                  .onSuccess(updated -> assertThat(updated.weight(ScalingMetric.CPU)).isEqualTo(0.6));
        }

        @Test
        void withWindowSize_returnsValidResultConfig() {
            var config = ScalingConfig.productionDefaults();
            var result = config.withWindowSize(20);

            result.onFailure(c -> org.junit.jupiter.api.Assertions.fail(c.message()))
                  .onSuccess(updated -> assertThat(updated.windowSize()).isEqualTo(20));
        }
    }

    private static Map<ScalingMetric, Double> validWeights() {
        var weights = new EnumMap<ScalingMetric, Double>(ScalingMetric.class);
        weights.put(ScalingMetric.CPU, 0.4);
        weights.put(ScalingMetric.ACTIVE_INVOCATIONS, 0.4);
        weights.put(ScalingMetric.P95_LATENCY, 0.2);
        return weights;
    }
}

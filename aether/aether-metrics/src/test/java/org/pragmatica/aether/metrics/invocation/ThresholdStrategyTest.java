package org.pragmatica.aether.metrics.invocation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.MethodName;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ThresholdStrategyTest {

    private MethodName method;

    @BeforeEach
    void setUp() {
        method = MethodName.methodName("testMethod").unwrap();
    }

    @Nested
    class FixedStrategyTests {
        @Test
        void isSlow_durationAboveThreshold_returnsTrue() {
            var strategy = ThresholdStrategy.fixed(100); // 100ms

            assertThat(strategy.isSlow(method, 101_000_000L)).isTrue();
        }

        @Test
        void isSlow_durationBelowThreshold_returnsFalse() {
            var strategy = ThresholdStrategy.fixed(100); // 100ms

            assertThat(strategy.isSlow(method, 99_000_000L)).isFalse();
        }

        @Test
        void isSlow_durationExactlyAtThreshold_returnsFalse() {
            var strategy = ThresholdStrategy.fixed(100); // 100ms

            // 100ms = 100_000_000 ns, which is NOT > 100_000_000 ns
            assertThat(strategy.isSlow(method, 100_000_000L)).isFalse();
        }

        @Test
        void thresholdNs_returnsConfiguredValue() {
            var strategy = ThresholdStrategy.fixed(50); // 50ms

            assertThat(strategy.thresholdNs(method)).isEqualTo(50_000_000L);
        }
    }

    @Nested
    class AdaptiveStrategyTests {
        @Test
        void isSlow_fewerThan10Observations_usesMinThreshold() {
            var strategy = ThresholdStrategy.adaptive(10, 1000); // 10ms min, 1000ms max

            // With < 10 observations, uses minThreshold (10ms = 10_000_000ns)
            assertThat(strategy.isSlow(method, 11_000_000L)).isTrue();
            assertThat(strategy.isSlow(method, 9_000_000L)).isFalse();
        }

        @Test
        void thresholdNs_fewerThan10Observations_returnsMinThreshold() {
            var strategy = ThresholdStrategy.adaptive(10, 1000);

            assertThat(strategy.thresholdNs(method)).isEqualTo(10_000_000L);
        }

        @Test
        void isSlow_afterSufficientObservations_adaptsThreshold() {
            var strategy = ThresholdStrategy.adaptive(1, 10000, 3.0);

            // Observe 15 invocations averaging ~5ms (5_000_000 ns)
            for (int i = 0; i < 15; i++) {
                strategy.observe(method, 5_000_000L);
            }

            // Threshold should be around 3x average = ~15ms = 15_000_000ns
            // 20ms should be considered slow
            assertThat(strategy.isSlow(method, 20_000_000L)).isTrue();
            // 10ms should NOT be slow (below 3x average)
            assertThat(strategy.isSlow(method, 10_000_000L)).isFalse();
        }

        @Test
        void thresholdNs_afterObservations_adaptsBasedOnEMA() {
            var strategy = ThresholdStrategy.adaptive(1, 10000, 3.0);

            for (int i = 0; i < 15; i++) {
                strategy.observe(method, 5_000_000L);
            }

            long threshold = strategy.thresholdNs(method);

            // Should be approximately 3x the EMA of 5_000_000
            assertThat(threshold).isGreaterThan(10_000_000L);
            assertThat(threshold).isLessThan(20_000_000L);
        }
    }

    @Nested
    class PerMethodStrategyTests {
        @Test
        void isSlow_unconfiguredMethod_usesDefaultThreshold() {
            var strategy = ThresholdStrategy.perMethod(100); // 100ms default

            assertThat(strategy.isSlow(method, 101_000_000L)).isTrue();
            assertThat(strategy.isSlow(method, 99_000_000L)).isFalse();
        }

        @Test
        void isSlow_configuredMethod_usesSpecificThreshold() {
            var strategy = ThresholdStrategy.perMethod(100); // 100ms default
            strategy.withThreshold(method, 50); // 50ms for testMethod

            assertThat(strategy.isSlow(method, 51_000_000L)).isTrue();
            assertThat(strategy.isSlow(method, 49_000_000L)).isFalse();
        }

        @Test
        void thresholdNs_unconfiguredMethod_returnsDefaultThreshold() {
            var strategy = ThresholdStrategy.perMethod(100);

            assertThat(strategy.thresholdNs(method)).isEqualTo(100_000_000L);
        }

        @Test
        void thresholdNs_configuredMethod_returnsSpecificThreshold() {
            var strategy = ThresholdStrategy.perMethod(100);
            strategy.withThreshold(method, 50);

            assertThat(strategy.thresholdNs(method)).isEqualTo(50_000_000L);
        }

        @Test
        void withThreshold_addsConfiguration() {
            var strategy = ThresholdStrategy.perMethod(100);

            strategy.withThreshold(method, 200);

            assertThat(strategy.thresholdNs(method)).isEqualTo(200_000_000L);
        }

        @Test
        void removeThreshold_revertsToDefault() {
            var strategy = ThresholdStrategy.perMethod(100);
            strategy.withThreshold(method, 50);

            strategy.removeThreshold(method);

            assertThat(strategy.thresholdNs(method)).isEqualTo(100_000_000L);
        }
    }

    @Nested
    class CompositeStrategyTests {
        @Test
        void isSlow_configuredMethod_usesExplicitThreshold() {
            var strategy = ThresholdStrategy.composite(
                Map.of(method, 50L), // 50ms for testMethod
                10, 1000); // 10ms min, 1000ms max default

            assertThat(strategy.isSlow(method, 51_000_000L)).isTrue();
            assertThat(strategy.isSlow(method, 49_000_000L)).isFalse();
        }

        @Test
        void isSlow_unconfiguredMethod_fallsBackToAdaptive() {
            var otherMethod = MethodName.methodName("otherMethod").unwrap();
            var strategy = ThresholdStrategy.composite(
                Map.of(method, 50L),
                10, 1000);

            // otherMethod not configured, falls back to adaptive which uses minThreshold initially
            assertThat(strategy.isSlow(otherMethod, 11_000_000L)).isTrue();
            assertThat(strategy.isSlow(otherMethod, 9_000_000L)).isFalse();
        }

        @Test
        void thresholdNs_configuredMethod_returnsExplicitValue() {
            var strategy = ThresholdStrategy.composite(
                Map.of(method, 50L),
                10, 1000);

            assertThat(strategy.thresholdNs(method)).isEqualTo(50_000_000L);
        }

        @Test
        void thresholdNs_unconfiguredMethod_returnsFallbackValue() {
            var otherMethod = MethodName.methodName("otherMethod").unwrap();
            var strategy = ThresholdStrategy.composite(
                Map.of(method, 50L),
                10, 1000);

            assertThat(strategy.thresholdNs(otherMethod)).isEqualTo(10_000_000L);
        }

        @Test
        void observe_configuredMethod_doesNotObserveFallback() {
            var strategy = ThresholdStrategy.composite(
                Map.of(method, 50L),
                10, 1000);

            // Observing a configured method shouldn't change the fallback
            strategy.observe(method, 100_000_000L);

            // Threshold should remain at explicit value
            assertThat(strategy.thresholdNs(method)).isEqualTo(50_000_000L);
        }
    }
}

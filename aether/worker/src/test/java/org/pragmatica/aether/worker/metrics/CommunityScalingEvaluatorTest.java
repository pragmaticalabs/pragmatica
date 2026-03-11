package org.pragmatica.aether.worker.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.worker.metrics.CommunityScalingEvaluator.*;
import static org.pragmatica.aether.worker.metrics.WindowSample.windowSample;

class CommunityScalingEvaluatorTest {
    private static final String COMMUNITY_ID = "test-community";
    private static final NodeId GOVERNOR_ID = NodeId.randomNodeId();
    private static final int MEMBER_COUNT = 3;
    private static final int PREFILL_COUNT = DEFAULT_SUSTAINED_COUNT - 1;

    private CommunityScalingEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = communityScalingEvaluator(
            DEFAULT_SCALE_UP_CPU_THRESHOLD,
            DEFAULT_SCALE_DOWN_CPU_THRESHOLD,
            DEFAULT_SCALE_UP_P95_THRESHOLD_MS,
            DEFAULT_SCALE_UP_ERROR_RATE_THRESHOLD,
            DEFAULT_COOLDOWN_MS,
            DEFAULT_WINDOW_SIZE,
            DEFAULT_SUSTAINED_COUNT
        );
    }

    @Nested
    class WindowNotFull {
        @Test
        void evaluate_windowNotFull_returnsEmpty() {
            var sample = normalSample(1000);
            var result = evaluator.evaluate(COMMUNITY_ID, GOVERNOR_ID, MEMBER_COUNT, sample);

            assertThat(result.isEmpty()).isTrue();
        }
    }

    @Nested
    class ScaleUp {
        @Test
        void evaluate_cpuAboveThresholdSustained_returnsScaleUpRequest() {
            feedSamples(evaluator, PREFILL_COUNT, 0.90, 100.0, 0.01);

            var result = evaluator.evaluate(COMMUNITY_ID, GOVERNOR_ID, MEMBER_COUNT, highCpuSample(4000));

            assertThat(result.isEmpty()).isFalse();
            result.onPresent(req -> assertThat(req.direction()).isEqualTo("UP"));
            result.onPresent(req -> assertThat(req.requestedInstances()).isEqualTo(MEMBER_COUNT + 1));
        }

        @Test
        void evaluate_p95AboveThresholdSustained_returnsScaleUpRequest() {
            feedSamples(evaluator, PREFILL_COUNT, 0.50, 600.0, 0.01);

            var result = evaluator.evaluate(COMMUNITY_ID, GOVERNOR_ID, MEMBER_COUNT, highP95Sample(4000));

            assertThat(result.isEmpty()).isFalse();
            result.onPresent(req -> assertThat(req.direction()).isEqualTo("UP"));
        }

        @Test
        void evaluate_errorRateAboveThresholdSustained_returnsScaleUpRequest() {
            feedSamples(evaluator, PREFILL_COUNT, 0.50, 100.0, 0.15);

            var result = evaluator.evaluate(COMMUNITY_ID, GOVERNOR_ID, MEMBER_COUNT, highErrorSample(4000));

            assertThat(result.isEmpty()).isFalse();
            result.onPresent(req -> assertThat(req.direction()).isEqualTo("UP"));
        }
    }

    @Nested
    class ScaleDown {
        @Test
        void evaluate_cpuBelowThresholdSustained_returnsScaleDownRequest() {
            feedSamples(evaluator, PREFILL_COUNT, 0.10, 100.0, 0.01);

            var result = evaluator.evaluate(COMMUNITY_ID, GOVERNOR_ID, MEMBER_COUNT, lowCpuSample(4000));

            assertThat(result.isEmpty()).isFalse();
            result.onPresent(req -> assertThat(req.direction()).isEqualTo("DOWN"));
            result.onPresent(req -> assertThat(req.requestedInstances()).isEqualTo(MEMBER_COUNT - 1));
        }
    }

    @Nested
    class Cooldown {
        @Test
        void evaluate_withinCooldown_returnsEmpty() {
            feedSamples(evaluator, PREFILL_COUNT, 0.90, 100.0, 0.01);

            var first = evaluator.evaluate(COMMUNITY_ID, GOVERNOR_ID, MEMBER_COUNT, highCpuSample(4000));
            assertThat(first.isEmpty()).isFalse();

            var second = evaluator.evaluate(COMMUNITY_ID, GOVERNOR_ID, MEMBER_COUNT, highCpuSample(5000));
            assertThat(second.isEmpty()).isTrue();
        }

        @Test
        void evaluate_afterCooldownExpiry_returnsRequest() {
            var zeroCooldownEvaluator = communityScalingEvaluator(
                DEFAULT_SCALE_UP_CPU_THRESHOLD,
                DEFAULT_SCALE_DOWN_CPU_THRESHOLD,
                DEFAULT_SCALE_UP_P95_THRESHOLD_MS,
                DEFAULT_SCALE_UP_ERROR_RATE_THRESHOLD,
                0L,
                DEFAULT_WINDOW_SIZE,
                DEFAULT_SUSTAINED_COUNT
            );

            feedSamples(zeroCooldownEvaluator, PREFILL_COUNT, 0.90, 100.0, 0.01);

            var first = zeroCooldownEvaluator.evaluate(COMMUNITY_ID, GOVERNOR_ID, MEMBER_COUNT, highCpuSample(4000));
            assertThat(first.isEmpty()).isFalse();

            var second = zeroCooldownEvaluator.evaluate(COMMUNITY_ID, GOVERNOR_ID, MEMBER_COUNT, highCpuSample(5000));
            assertThat(second.isEmpty()).isFalse();
        }
    }

    @Nested
    class StateManagement {
        @Test
        void reset_clearsWindowAndCooldowns() {
            feedSamples(evaluator, PREFILL_COUNT, 0.90, 100.0, 0.01);
            evaluator.evaluate(COMMUNITY_ID, GOVERNOR_ID, MEMBER_COUNT, highCpuSample(4000));

            evaluator.reset();

            assertThat(evaluator.slidingWindow()).isEmpty();

            // After reset, window not full again — should return empty
            var result = evaluator.evaluate(COMMUNITY_ID, GOVERNOR_ID, MEMBER_COUNT, highCpuSample(5000));
            assertThat(result.isEmpty()).isTrue();
        }

        @Test
        void slidingWindow_returnsCurrentSamples() {
            var s1 = normalSample(1000);
            var s2 = normalSample(2000);
            evaluator.evaluate(COMMUNITY_ID, GOVERNOR_ID, MEMBER_COUNT, s1);
            evaluator.evaluate(COMMUNITY_ID, GOVERNOR_ID, MEMBER_COUNT, s2);

            var samples = evaluator.slidingWindow();
            assertThat(samples).hasSize(2);
            assertThat(samples.get(0).timestampMs()).isEqualTo(1000);
            assertThat(samples.get(1).timestampMs()).isEqualTo(2000);
        }
    }

    // --- Helper methods ---

    private static void feedSamples(CommunityScalingEvaluator eval, int count, double cpu, double p95, double errorRate) {
        for (int i = 0; i < count; i++) {
            eval.evaluate(COMMUNITY_ID, GOVERNOR_ID, MEMBER_COUNT, windowSample(cpu, 0.50, 10, p95, errorRate, 1000L + i));
        }
    }

    private static WindowSample normalSample(long timestampMs) {
        return windowSample(0.50, 0.50, 10, 100.0, 0.01, timestampMs);
    }

    private static WindowSample highCpuSample(long timestampMs) {
        return windowSample(0.90, 0.50, 10, 100.0, 0.01, timestampMs);
    }

    private static WindowSample lowCpuSample(long timestampMs) {
        return windowSample(0.10, 0.50, 10, 100.0, 0.01, timestampMs);
    }

    private static WindowSample highP95Sample(long timestampMs) {
        return windowSample(0.50, 0.50, 10, 600.0, 0.01, timestampMs);
    }

    private static WindowSample highErrorSample(long timestampMs) {
        return windowSample(0.50, 0.50, 10, 100.0, 0.15, timestampMs);
    }
}

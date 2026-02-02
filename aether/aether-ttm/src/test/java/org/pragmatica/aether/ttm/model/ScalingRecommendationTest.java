package org.pragmatica.aether.ttm.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScalingRecommendationTest {

    @Test
    void noAction_stable_existsAsEnumValue() {
        var action = ScalingRecommendation.NoAction.STABLE;

        assertThat(action).isInstanceOf(ScalingRecommendation.class);
    }

    @Test
    void noAction_lowConfidence_existsAsEnumValue() {
        var action = ScalingRecommendation.NoAction.LOW_CONFIDENCE;

        assertThat(action).isInstanceOf(ScalingRecommendation.class);
    }

    @Test
    void noAction_insufficientData_existsAsEnumValue() {
        var action = ScalingRecommendation.NoAction.INSUFFICIENT_DATA;

        assertThat(action).isInstanceOf(ScalingRecommendation.class);
    }

    @Test
    void preemptiveScaleUp_holdsCorrectData() {
        var scaleUp = new ScalingRecommendation.PreemptiveScaleUp(0.85f, 120.5f, 3);

        assertThat(scaleUp.predictedCpuPeak()).isEqualTo(0.85f);
        assertThat(scaleUp.predictedLatency()).isEqualTo(120.5f);
        assertThat(scaleUp.suggestedInstances()).isEqualTo(3);
        assertThat(scaleUp).isInstanceOf(ScalingRecommendation.class);
    }

    @Test
    void preemptiveScaleDown_holdsCorrectData() {
        var scaleDown = new ScalingRecommendation.PreemptiveScaleDown(0.25f, 1);

        assertThat(scaleDown.predictedCpuTrough()).isEqualTo(0.25f);
        assertThat(scaleDown.suggestedInstances()).isEqualTo(1);
        assertThat(scaleDown).isInstanceOf(ScalingRecommendation.class);
    }

    @Test
    void adjustThresholds_holdsCorrectData() {
        var adjust = new ScalingRecommendation.AdjustThresholds(0.75, 0.25);

        assertThat(adjust.newCpuScaleUpThreshold()).isEqualTo(0.75);
        assertThat(adjust.newCpuScaleDownThreshold()).isEqualTo(0.25);
        assertThat(adjust).isInstanceOf(ScalingRecommendation.class);
    }

    @Test
    void patternMatching_worksCorrectly() {
        ScalingRecommendation recommendation = new ScalingRecommendation.PreemptiveScaleUp(0.9f, 100f, 2);

        var result = switch (recommendation) {
            case ScalingRecommendation.NoAction noAction -> "none";
            case ScalingRecommendation.PreemptiveScaleUp scaleUp -> "up-" + scaleUp.suggestedInstances();
            case ScalingRecommendation.PreemptiveScaleDown scaleDown -> "down-" + scaleDown.suggestedInstances();
            case ScalingRecommendation.AdjustThresholds adjust -> "adjust";
        };

        assertThat(result).isEqualTo("up-2");
    }
}

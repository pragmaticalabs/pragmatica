package org.pragmatica.aether.ttm.model;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.TTMConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class TTMConfigTest {

    @Test
    void defaults_providesValidConfig() {
        var config = TTMConfig.defaults();

        assertThat(config.enabled()).isFalse(); // Disabled by default - requires explicit opt-in
        assertThat(config.inputWindowMinutes()).isEqualTo(60);
        assertThat(config.predictionHorizon()).isEqualTo(1);
        assertThat(config.evaluationIntervalMs()).isEqualTo(60_000L);
        assertThat(config.confidenceThreshold()).isEqualTo(0.7);
    }

    @Test
    void disabled_returnsDisabledConfig() {
        var config = TTMConfig.disabled();

        assertThat(config.enabled()).isFalse();
    }

    @Test
    void ttmConfig_succeeds_withValidParameters() {
        TTMConfig.ttmConfig("models/test.onnx", 30, 2, 30_000L, 0.8, true)
                 .onFailureRun(() -> fail("Expected success"))
                 .onSuccess(config -> {
                     assertThat(config.modelPath()).isEqualTo("models/test.onnx");
                     assertThat(config.inputWindowMinutes()).isEqualTo(30);
                     assertThat(config.predictionHorizon()).isEqualTo(2);
                     assertThat(config.evaluationIntervalMs()).isEqualTo(30_000L);
                     assertThat(config.confidenceThreshold()).isEqualTo(0.8);
                     assertThat(config.enabled()).isTrue();
                 });
    }

    @Test
    void ttmConfig_fails_withBlankModelPathWhenEnabled() {
        TTMConfig.ttmConfig("", 60, 1, 60_000L, 0.7, true)
                 .onSuccessRun(() -> fail("Expected failure"))
                 .onFailure(cause -> assertThat(cause.message()).contains("modelPath"));
    }

    @Test
    void ttmConfig_succeeds_withBlankModelPathWhenDisabled() {
        TTMConfig.ttmConfig("", 60, 1, 60_000L, 0.7, false)
                 .onFailureRun(() -> fail("Expected success"))
                 .onSuccess(config -> assertThat(config.enabled()).isFalse());
    }

    @Test
    void ttmConfig_fails_withInvalidInputWindowMinutes() {
        TTMConfig.ttmConfig("model.onnx", 0, 1, 60_000L, 0.7, true)
                 .onSuccessRun(() -> fail("Expected failure"))
                 .onFailure(cause -> assertThat(cause.message()).contains("inputWindowMinutes"));

        TTMConfig.ttmConfig("model.onnx", 121, 1, 60_000L, 0.7, true)
                 .onSuccessRun(() -> fail("Expected failure"))
                 .onFailure(cause -> assertThat(cause.message()).contains("inputWindowMinutes"));
    }

    @Test
    void ttmConfig_fails_withInvalidPredictionHorizon() {
        TTMConfig.ttmConfig("model.onnx", 60, 0, 60_000L, 0.7, true)
                 .onSuccessRun(() -> fail("Expected failure"))
                 .onFailure(cause -> assertThat(cause.message()).contains("predictionHorizon"));

        TTMConfig.ttmConfig("model.onnx", 60, 11, 60_000L, 0.7, true)
                 .onSuccessRun(() -> fail("Expected failure"))
                 .onFailure(cause -> assertThat(cause.message()).contains("predictionHorizon"));
    }

    @Test
    void ttmConfig_fails_withInvalidEvaluationInterval() {
        TTMConfig.ttmConfig("model.onnx", 60, 1, 5_000L, 0.7, true)
                 .onSuccessRun(() -> fail("Expected failure"))
                 .onFailure(cause -> assertThat(cause.message()).contains("evaluationIntervalMs"));

        TTMConfig.ttmConfig("model.onnx", 60, 1, 400_000L, 0.7, true)
                 .onSuccessRun(() -> fail("Expected failure"))
                 .onFailure(cause -> assertThat(cause.message()).contains("evaluationIntervalMs"));
    }

    @Test
    void ttmConfig_fails_withInvalidConfidenceThreshold() {
        TTMConfig.ttmConfig("model.onnx", 60, 1, 60_000L, -0.1, true)
                 .onSuccessRun(() -> fail("Expected failure"))
                 .onFailure(cause -> assertThat(cause.message()).contains("confidenceThreshold"));

        TTMConfig.ttmConfig("model.onnx", 60, 1, 60_000L, 1.1, true)
                 .onSuccessRun(() -> fail("Expected failure"))
                 .onFailure(cause -> assertThat(cause.message()).contains("confidenceThreshold"));
    }

    @Test
    void withModelPath_returnsNewConfigWithUpdatedPath() {
        var original = TTMConfig.defaults();
        var updated = original.withModelPath("new/model.onnx");

        assertThat(updated.modelPath()).isEqualTo("new/model.onnx");
        assertThat(updated.inputWindowMinutes()).isEqualTo(original.inputWindowMinutes());
    }

    @Test
    void withEnabled_returnsNewConfigWithUpdatedEnabled() {
        var original = TTMConfig.defaults();
        var updated = original.withEnabled(false);

        assertThat(updated.enabled()).isFalse();
        assertThat(updated.modelPath()).isEqualTo(original.modelPath());
    }
}

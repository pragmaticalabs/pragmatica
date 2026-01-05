package org.pragmatica.aether.config;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

/**
 * Configuration for TTM (Tiny Time Mixers) predictive scaling.
 *
 * @param modelPath            Path to ONNX model file
 * @param inputWindowMinutes   Number of minutes of historical data for prediction (default: 60)
 * @param predictionHorizon    Minutes ahead to predict (default: 1)
 * @param evaluationIntervalMs Interval between TTM evaluations in milliseconds (default: 60000)
 * @param confidenceThreshold  Minimum confidence for applying predictions (0.0-1.0, default: 0.7)
 * @param enabled              Whether TTM is enabled (default: false)
 */
public record TTMConfig(
 String modelPath,
 int inputWindowMinutes,
 int predictionHorizon,
 long evaluationIntervalMs,
 double confidenceThreshold,
 boolean enabled) {
    private static final TTMConfig DEFAULT = new TTMConfig("models/ttm-aether.onnx", 60, 1, 60_000L, 0.7, true);

    private static final TTMConfig DISABLED = new TTMConfig("", 60, 1, 60_000L, 0.7, false);

    public static TTMConfig defaults() {
        return DEFAULT;
    }

    public static TTMConfig disabled() {
        return DISABLED;
    }

    /**
     * Factory method following JBCT naming convention.
     */
    public static Result<TTMConfig> ttmConfig(String modelPath,
                                              int inputWindowMinutes,
                                              int predictionHorizon,
                                              long evaluationIntervalMs,
                                              double confidenceThreshold,
                                              boolean enabled) {
        if (enabled) {
            if (modelPath == null || modelPath.isBlank()) {
                return InvalidTTMConfig.invalidConfig("modelPath cannot be blank when TTM is enabled")
                                       .result();
            }
        }
        if (inputWindowMinutes < 1 || inputWindowMinutes > 120) {
            return InvalidTTMConfig.invalidConfig("inputWindowMinutes must be 1-120")
                                   .result();
        }
        if (predictionHorizon < 1 || predictionHorizon > 10) {
            return InvalidTTMConfig.invalidConfig("predictionHorizon must be 1-10")
                                   .result();
        }
        if (evaluationIntervalMs < 10_000L || evaluationIntervalMs > 300_000L) {
            return InvalidTTMConfig.invalidConfig("evaluationIntervalMs must be 10000-300000")
                                   .result();
        }
        if (confidenceThreshold < 0.0 || confidenceThreshold > 1.0) {
            return InvalidTTMConfig.invalidConfig("confidenceThreshold must be 0.0-1.0")
                                   .result();
        }
        return Result.success(new TTMConfig(modelPath,
                                            inputWindowMinutes,
                                            predictionHorizon,
                                            evaluationIntervalMs,
                                            confidenceThreshold,
                                            enabled));
    }

    public TTMConfig withModelPath(String modelPath) {
        return new TTMConfig(modelPath,
                             inputWindowMinutes,
                             predictionHorizon,
                             evaluationIntervalMs,
                             confidenceThreshold,
                             enabled);
    }

    public TTMConfig withEnabled(boolean enabled) {
        return new TTMConfig(modelPath,
                             inputWindowMinutes,
                             predictionHorizon,
                             evaluationIntervalMs,
                             confidenceThreshold,
                             enabled);
    }

    public TTMConfig withInputWindowMinutes(int inputWindowMinutes) {
        return new TTMConfig(modelPath,
                             inputWindowMinutes,
                             predictionHorizon,
                             evaluationIntervalMs,
                             confidenceThreshold,
                             enabled);
    }

    public TTMConfig withEvaluationIntervalMs(long evaluationIntervalMs) {
        return new TTMConfig(modelPath,
                             inputWindowMinutes,
                             predictionHorizon,
                             evaluationIntervalMs,
                             confidenceThreshold,
                             enabled);
    }

    public TTMConfig withConfidenceThreshold(double confidenceThreshold) {
        return new TTMConfig(modelPath,
                             inputWindowMinutes,
                             predictionHorizon,
                             evaluationIntervalMs,
                             confidenceThreshold,
                             enabled);
    }

    /**
     * Configuration error for TTM.
     */
    public record InvalidTTMConfig(String detail) implements Cause {
        public static InvalidTTMConfig invalidConfig(String detail) {
            return new InvalidTTMConfig(detail);
        }

        @Override
        public String message() {
            return "Invalid TTM configuration: " + detail;
        }
    }
}

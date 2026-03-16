package org.pragmatica.aether.config;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Configuration for TTM (Tiny Time Mixers) predictive scaling.
///
/// @param modelPath            Path to ONNX model file
/// @param inputWindowMinutes   Number of minutes of historical data for prediction (default: 60)
/// @param predictionHorizon    Minutes ahead to predict (default: 1)
/// @param evaluationInterval   Interval between TTM evaluations (default: 60s)
/// @param confidenceThreshold  Minimum confidence for applying predictions (0.0-1.0, default: 0.7)
/// @param enabled              Whether TTM is enabled (default: false)
@SuppressWarnings({"JBCT-ZONE-02", "JBCT-SEQ-01"})
public record TtmConfig(String modelPath,
                        int inputWindowMinutes,
                        int predictionHorizon,
                        TimeSpan evaluationInterval,
                        double confidenceThreshold,
                        boolean enabled) {
    private static final TtmConfig DEFAULT = ttmConfig("models/ttm-aether.onnx",
                                                       60,
                                                       1,
                                                       timeSpan(60).seconds(),
                                                       0.7,
                                                       false).unwrap();

    /// Default TTM configuration (disabled).
    public static TtmConfig ttmConfig() {
        return DEFAULT;
    }

    /// Factory method with validation following JBCT naming convention.
    public static Result<TtmConfig> ttmConfig(String modelPath,
                                              int inputWindowMinutes,
                                              int predictionHorizon,
                                              TimeSpan evaluationInterval,
                                              double confidenceThreshold,
                                              boolean enabled) {
        return checkModelAndTiming(modelPath, enabled, inputWindowMinutes, predictionHorizon).flatMap(_ -> checkIntervalAndConfidence(evaluationInterval,
                                                                                                                                      confidenceThreshold))
                                  .map(_ -> new TtmConfig(modelPath,
                                                          inputWindowMinutes,
                                                          predictionHorizon,
                                                          evaluationInterval,
                                                          confidenceThreshold,
                                                          enabled));
    }

    public TtmConfig withModelPath(String modelPath) {
        return ttmConfig(modelPath,
                         inputWindowMinutes,
                         predictionHorizon,
                         evaluationInterval,
                         confidenceThreshold,
                         enabled)
        .unwrap();
    }

    public TtmConfig withEnabled(boolean enabled) {
        return ttmConfig(modelPath,
                         inputWindowMinutes,
                         predictionHorizon,
                         evaluationInterval,
                         confidenceThreshold,
                         enabled)
        .unwrap();
    }

    public TtmConfig withInputWindowMinutes(int inputWindowMinutes) {
        return ttmConfig(modelPath,
                         inputWindowMinutes,
                         predictionHorizon,
                         evaluationInterval,
                         confidenceThreshold,
                         enabled)
        .unwrap();
    }

    public TtmConfig withEvaluationInterval(TimeSpan evaluationInterval) {
        return ttmConfig(modelPath,
                         inputWindowMinutes,
                         predictionHorizon,
                         evaluationInterval,
                         confidenceThreshold,
                         enabled)
        .unwrap();
    }

    public TtmConfig withConfidenceThreshold(double confidenceThreshold) {
        return ttmConfig(modelPath,
                         inputWindowMinutes,
                         predictionHorizon,
                         evaluationInterval,
                         confidenceThreshold,
                         enabled)
        .unwrap();
    }

    private static Result<Integer> checkModelAndTiming(String modelPath,
                                                       boolean enabled,
                                                       int inputWindowMinutes,
                                                       int predictionHorizon) {
        return checkModelPath(modelPath, enabled).flatMap(_ -> checkWindow(inputWindowMinutes))
                             .flatMap(_ -> checkHorizon(predictionHorizon));
    }

    private static Result<String> checkModelPath(String modelPath, boolean enabled) {
        return ! enabled || isNotBlank(modelPath)
               ? success(modelPath)
               : TtmConfigError.InvalidTtmConfig.invalidTtmConfig("modelPath cannot be blank when TTM is enabled")
                               .result();
    }

    private static boolean isNotBlank(String value) {
        return option(value).map(String::trim)
                     .filter(s -> !s.isEmpty())
                     .isPresent();
    }

    private static Result<Integer> checkWindow(int inputWindowMinutes) {
        return inputWindowMinutes >= 1 && inputWindowMinutes <= 120
               ? success(inputWindowMinutes)
               : TtmConfigError.InvalidTtmConfig.invalidTtmConfig("inputWindowMinutes must be 1-120")
                               .result();
    }

    private static Result<Integer> checkHorizon(int predictionHorizon) {
        return predictionHorizon >= 1 && predictionHorizon <= 10
               ? success(predictionHorizon)
               : TtmConfigError.InvalidTtmConfig.invalidTtmConfig("predictionHorizon must be 1-10")
                               .result();
    }

    private static Result<Double> checkIntervalAndConfidence(TimeSpan evaluationInterval, double confidenceThreshold) {
        return checkInterval(evaluationInterval).flatMap(_ -> checkConfidence(confidenceThreshold));
    }

    private static Result<TimeSpan> checkInterval(TimeSpan evaluationInterval) {
        var millis = evaluationInterval.millis();
        return millis >= 10_000L && millis <= 300_000L
               ? success(evaluationInterval)
               : TtmConfigError.InvalidTtmConfig.invalidTtmConfig("evaluationInterval must be 10s-300s")
                               .result();
    }

    private static Result<Double> checkConfidence(double confidenceThreshold) {
        return confidenceThreshold >= 0.0 && confidenceThreshold <= 1.0
               ? success(confidenceThreshold)
               : TtmConfigError.InvalidTtmConfig.invalidTtmConfig("confidenceThreshold must be 0.0-1.0")
                               .result();
    }

    /// Error hierarchy for TTM configuration failures.
    public sealed interface TtmConfigError extends Cause {
        record unused() implements TtmConfigError {
            @Override
            public String message() {
                return "unused";
            }
        }

        /// Configuration error for TTM.
        record InvalidTtmConfig(String detail) implements TtmConfigError {
            /// Factory method following JBCT naming convention.
            public static Result<InvalidTtmConfig> invalidTtmConfig(String detail, boolean validated) {
                return success(new InvalidTtmConfig(detail));
            }

            public static InvalidTtmConfig invalidTtmConfig(String detail) {
                return invalidTtmConfig(detail, true).unwrap();
            }

            @Override
            public String message() {
                return "Invalid TTM configuration: " + detail;
            }
        }
    }
}

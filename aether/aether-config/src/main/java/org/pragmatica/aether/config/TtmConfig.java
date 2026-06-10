// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


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

    public static TtmConfig ttmConfig() {
        return DEFAULT;
    }

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
               : TtmConfigError.InvalidTtmConfig.invalidTtmConfig("modelPath cannot be blank when TTM is enabled").result();
    }

    private static boolean isNotBlank(String value) {
        return option(value).map(String::trim)
                     .filter(s -> !s.isEmpty())
                     .isPresent();
    }

    private static Result<Integer> checkWindow(int inputWindowMinutes) {
        return inputWindowMinutes >= 1 && inputWindowMinutes <= 120
               ? success(inputWindowMinutes)
               : TtmConfigError.InvalidTtmConfig.invalidTtmConfig("inputWindowMinutes must be 1-120").result();
    }

    private static Result<Integer> checkHorizon(int predictionHorizon) {
        return predictionHorizon >= 1 && predictionHorizon <= 10
               ? success(predictionHorizon)
               : TtmConfigError.InvalidTtmConfig.invalidTtmConfig("predictionHorizon must be 1-10").result();
    }

    private static Result<Double> checkIntervalAndConfidence(TimeSpan evaluationInterval, double confidenceThreshold) {
        return checkInterval(evaluationInterval).flatMap(_ -> checkConfidence(confidenceThreshold));
    }

    private static Result<TimeSpan> checkInterval(TimeSpan evaluationInterval) {
        var millis = evaluationInterval.millis();

        return millis >= 10_000L && millis <= 300_000L
               ? success(evaluationInterval)
               : TtmConfigError.InvalidTtmConfig.invalidTtmConfig("evaluationInterval must be 10s-300s").result();
    }

    private static Result<Double> checkConfidence(double confidenceThreshold) {
        return confidenceThreshold >= 0.0 && confidenceThreshold <= 1.0
               ? success(confidenceThreshold)
               : TtmConfigError.InvalidTtmConfig.invalidTtmConfig("confidenceThreshold must be 0.0-1.0").result();
    }

    public sealed interface TtmConfigError extends Cause {
        record unused() implements TtmConfigError {
            @Override
            public String message() {
                return "unused";
            }
        }

        record InvalidTtmConfig(String detail) implements TtmConfigError {
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

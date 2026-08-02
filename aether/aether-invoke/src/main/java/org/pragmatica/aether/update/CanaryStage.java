// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.update;

import java.util.List;

import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;


public record CanaryStage(int trafficPercent, int observationMinutes) {
    /// 400 — both are out-of-range values in a caller-supplied canary stage (#569). They were bare
    /// `Causes.cause(...)` constants, which `ProblemResponses.resolveStatus` cannot distinguish from a
    /// node fault, so a canary deploy with `trafficPercent: 500` answered `500 Internal Server Error`.
    /// Status is stored per constant rather than returned as a shared literal so a future variant with
    /// different semantics has to state its own code instead of silently inheriting 400.
    enum CanaryStageError implements Cause, HttpStatusAware {
        INVALID_TRAFFIC("Traffic percent must be between 1 and 100", HttpStatus.BAD_REQUEST),
        NEGATIVE_OBSERVATION("Observation minutes must be non-negative", HttpStatus.BAD_REQUEST);
        private final String msg;
        private final HttpStatus status;
        CanaryStageError(String msg, HttpStatus status) {
            this.msg = msg;
            this.status = status;
        }
        @Override
        public String message() {
            return msg;
        }
        @Override
        public HttpStatus httpStatus() {
            return status;
        }
    }

    private static final Cause INVALID_TRAFFIC = CanaryStageError.INVALID_TRAFFIC;
    private static final Cause NEGATIVE_OBSERVATION = CanaryStageError.NEGATIVE_OBSERVATION;

    public static Result<CanaryStage> canaryStage(int trafficPercent, int observationMinutes) {
        if (trafficPercent < 1 || trafficPercent > 100) {
            return INVALID_TRAFFIC.result();
        }

        if (observationMinutes < 0) {
            return NEGATIVE_OBSERVATION.result();
        }

        return Result.success(new CanaryStage(trafficPercent, observationMinutes));
    }

    public VersionRouting toRouting() {
        return new VersionRouting(trafficPercent, 100 - trafficPercent);
    }

    public static List<CanaryStage> defaultStages() {
        return List.of(new CanaryStage(1, 5),
                       new CanaryStage(5, 5),
                       new CanaryStage(25, 10),
                       new CanaryStage(50, 10),
                       new CanaryStage(100, 0));
    }
}

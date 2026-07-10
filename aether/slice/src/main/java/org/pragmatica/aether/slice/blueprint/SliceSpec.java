// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Result.success;


@SuppressWarnings("JBCT-UTIL-02")
public record SliceSpec(Artifact artifact,
                        int instances,
                        int minAvailable,
                        Option<Integer> maxInstances,
                        Option<Double> scaleUpThreshold,
                        Option<Double> scaleDownThreshold) {
    private static final Fn1<Cause, Integer> INVALID_INSTANCES = Causes.forOneValue("Instance count must be positive: %s");

    private static final Fn1<Cause, String> INVALID_MIN_AVAILABLE = Causes.forOneValue("minAvailable must be >= 1 and <= instances: %s");

    private static final Fn1<Cause, String> INVALID_MAX_INSTANCES = Causes.forOneValue("maxInstances must be >= instances: %s");

    public SliceSpec {
        if (maxInstances == null) {
            maxInstances = none();
        }

        if (scaleUpThreshold == null) {
            scaleUpThreshold = none();
        }

        if (scaleDownThreshold == null) {
            scaleDownThreshold = none();
        }
    }

    public static Result<SliceSpec> sliceSpec(Artifact artifact,
                                              int instances,
                                              int minAvailable,
                                              Option<Integer> maxInstances,
                                              Option<Double> scaleUpThreshold,
                                              Option<Double> scaleDownThreshold) {
        if (instances <= 0) {
            return INVALID_INSTANCES.apply(instances).result();
        }

        if (minAvailable < 1 || minAvailable > instances) {
            return INVALID_MIN_AVAILABLE.apply("minAvailable=" + minAvailable + ", instances=" + instances).result();
        }

        if (maxInstances.filter(max -> max < instances).isPresent()) {
            return INVALID_MAX_INSTANCES.apply("maxInstances=" + maxInstances.or(instances) + ", instances=" + instances).result();
        }

        return success(new SliceSpec(artifact,
                                     instances,
                                     minAvailable,
                                     maxInstances,
                                     scaleUpThreshold,
                                     scaleDownThreshold));
    }

    public static Result<SliceSpec> sliceSpec(Artifact artifact, int instances, int minAvailable) {
        return sliceSpec(artifact, instances, minAvailable, none(), none(), none());
    }

    public static Result<SliceSpec> sliceSpec(Artifact artifact, int instances) {
        return sliceSpec(artifact, instances, Math.ceilDiv(instances, 2));
    }

    public static Result<SliceSpec> sliceSpec(Artifact artifact) {
        return sliceSpec(artifact, 1, 1);
    }
}

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
import org.pragmatica.serialization.Codec;

import java.util.Set;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Verify.Is;
import static org.pragmatica.lang.Verify.ensure;


@Codec
@SuppressWarnings({"JBCT-NAM-01", "JBCT-UTIL-02"})
public record ResolvedSlice(Artifact artifact,
                            int instances,
                            int minAvailable,
                            boolean isDependency,
                            Set<Artifact> dependencies,
                            Option<Integer> maxInstances,
                            Option<Double> scaleUpThreshold,
                            Option<Double> scaleDownThreshold) {
    private static final Cause NULL_ARTIFACT = Causes.cause("Artifact cannot be null");

    private static final Fn1<Cause, Integer> INVALID_INSTANCES = Causes.forOneValue("Instances must be positive, got: %s");

    public ResolvedSlice {
        dependencies = dependencies == null
                       ? Set.of()
                       : Set.copyOf(dependencies);
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

    public static Result<ResolvedSlice> resolvedSlice(Artifact artifact,
                                                      int instances,
                                                      int minAvailable,
                                                      boolean isDependency,
                                                      Set<Artifact> dependencies,
                                                      Option<Integer> maxInstances,
                                                      Option<Double> scaleUpThreshold,
                                                      Option<Double> scaleDownThreshold) {
        return ensure(artifact, Is::notNull, NULL_ARTIFACT).filter(INVALID_INSTANCES.apply(instances),
                                                                   _ -> instances > 0)
                     .map(a -> toResolvedSlice(a,
                                               instances,
                                               minAvailable,
                                               isDependency,
                                               dependencies,
                                               maxInstances,
                                               scaleUpThreshold,
                                               scaleDownThreshold));
    }

    private static ResolvedSlice toResolvedSlice(Artifact artifact,
                                                 int instances,
                                                 int minAvailable,
                                                 boolean isDependency,
                                                 Set<Artifact> dependencies,
                                                 Option<Integer> maxInstances,
                                                 Option<Double> scaleUpThreshold,
                                                 Option<Double> scaleDownThreshold) {
        return new ResolvedSlice(artifact,
                                 instances,
                                 minAvailable,
                                 isDependency,
                                 dependencies,
                                 maxInstances,
                                 scaleUpThreshold,
                                 scaleDownThreshold);
    }

    public static Result<ResolvedSlice> resolvedSlice(Artifact artifact,
                                                      int instances,
                                                      int minAvailable,
                                                      boolean isDependency,
                                                      Set<Artifact> dependencies) {
        return resolvedSlice(artifact, instances, minAvailable, isDependency, dependencies, none(), none(), none());
    }

    public static Result<ResolvedSlice> resolvedSlice(Artifact artifact,
                                                      int instances,
                                                      boolean isDependency,
                                                      Set<Artifact> dependencies) {
        return resolvedSlice(artifact, instances, Math.ceilDiv(instances, 2), isDependency, dependencies);
    }

    public static Result<ResolvedSlice> resolvedSlice(Artifact artifact, int instances, boolean isDependency) {
        return resolvedSlice(artifact, instances, isDependency, Set.of());
    }
}

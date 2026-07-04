// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.pragmatica.aether.invoke.AdaptiveSampler;
import org.pragmatica.aether.invoke.InvocationTraceStore;
import org.pragmatica.aether.slice.ObservabilityStrategyCell.InvocationStrategy;
import org.pragmatica.lang.Option;

// The absence-default composition for the #277 variant-C posture: an injection point with NO config at
// method, artifact, or global scope resolves to the BASELINE strategy, not identity ("off means baseline,
// not blind"). This parameter object is the construction-time seam for the fleet collaborators the
// baseline will use once the ambient facet bodies (failure-log, depth-leveled logging, depth-0 sampled
// tracing) move here in increment 5: the adaptive sampler drives depth-0 sampled tracing, the trace store
// captures spans, and the `org.pragmatica.aether.trace` logger carries the log lines. The registry holds
// ONE ObservabilityBaseline and threads it through every baseline composition, so increment 5 fills the
// facet layering in one place without touching the scope-resolution logic.
//
// For this increment the baseline is counting-only: `countingOnly()` carries no collaborators and
// `decorate` returns the shared counting inner untouched, so an unconfigured cell counts by default (the
// same embryonic metrics counter an explicit non-off config yields). Increment 5 adds a `fleet(sampler,
// traceStore)` factory and fills `decorate`, making the AetherNode wiring a one-liner:
// `ObservabilityConfigRegistry.observabilityConfigRegistry(clusterNode, kvStore,
// ObservabilityBaseline.fleet(sampler, traceStore))`.
public record ObservabilityBaseline(Option<AdaptiveSampler> sampler, Option<InvocationTraceStore> traceStore) {
    // The no-collaborator baseline: counting only. The default until increment 5 injects the fleet
    // collaborators through the write-side registry's construction seam.
    public static ObservabilityBaseline countingOnly() {
        return new ObservabilityBaseline(Option.none(), Option.none());
    }

    /// Composes the baseline strategy around the shared `counting` inner (the metrics-facet embryo the
    /// registry plants per cell). Increment 5 layers the fleet facets — failure-log, depth-leveled
    /// logging, depth-0 sampled tracing — using the injected collaborators; until then the baseline IS
    /// the counting inner, so absence resolves to counting-by-default.
    public InvocationStrategy decorate(InvocationStrategy counting) {
        return counting;
    }
}

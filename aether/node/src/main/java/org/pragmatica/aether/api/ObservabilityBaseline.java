// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.pragmatica.aether.invoke.AdaptiveSampler;
import org.pragmatica.aether.invoke.InvocationContext;
import org.pragmatica.aether.invoke.InvocationNode;
import org.pragmatica.aether.invoke.InvocationNode.Outcome;
import org.pragmatica.aether.invoke.InvocationTraceStore;
import org.pragmatica.aether.invoke.ObservabilityConfig;
import org.pragmatica.aether.slice.ObservabilityStrategyCell.InvocationStrategy;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.aether.invoke.InvocationNode.invocationNode;


// The shared observation-facet composer for the #277 variant-C posture. Since increment 5a the
// strategy-cell system is the ONE observability engine; this type owns the facet BODIES the retired
// ObservabilityInterceptor used, and both the absence-default baseline and the explicit per-injection
// configured path compose the SAME bodies from here (increment 5b) — the only difference is WHICH facets
// are selected:
//   - baseline (no config at any scope) = ALL ambient facets on: logging + metrics(counting) + tracing,
//     spans off ("off means baseline, not blind"),
//   - configured (explicit non-off config) = the facets the config's toggles select,
//   - explicit all-off config = identity (one volatile read, surgical darkening).
//
// `compose(inner, callee, depthThreshold, logging, tracing)` layers the ambient facets around a supplied
// `inner` strategy (the metrics/counting facet the registry plants per cell, or identity when metrics is
// off). When neither logging nor tracing is selected it returns `inner` untouched (no sampler tick, no
// allocation). Otherwise, when the fleet collaborators are present, it returns a one-closure strategy that
// per call: ticks the adaptive sampler; captures the InvocationContext tracing spine (requestId / depth /
// isSampled) once before the async boundary; runs the inner; then on result branches sampled/unsampled
// exactly as the interceptor did (a success is observed when the context is already sampled OR the call is
// a depth-0 entry the sampler selects; a failure is always observed). Within an observed result the
// selected facets fire: the tracing facet records an InvocationNode into the SAME InvocationTraceStore the
// TRACES_* routes read; the logging facet logs through the SAME `org.pragmatica.aether.trace` logger with
// the interceptor's verbatim formats and depth-leveled INFO/DEBUG/TRACE ladder (success) / ERROR (failure).
// Logging and tracing share the single sampling decision, so a logging-only config logs sampled successes
// plus all failures — identical to the baseline's logging facet, preserving the absorbed semantics.
//
// Depth semantics: the strategy reads requestId/depth/isSampled from InvocationContext at call time (the
// single tracing spine at the dispatch seam). The logging ladder's per-injection depth threshold is
// resolved by the registry from the effective AspectObservabilityConfig (else this baseline's
// `defaultDepth`, which mirrors the depth-store default = ObservabilityConfig.DEFAULT.depthThreshold())
// and passed into `compose`. The `local` flag is the observable constant `true`: all four absorbed
// east-west dispatch sites passed local=true to the interceptor. `spans` is a reserved toggle with no
// body yet (#304).
//
// `countingOnly()` carries no collaborators; `compose` cannot layer the ambient facets and returns the
// `inner` untouched, so a node wired with observability disabled (depthThreshold < 0) or any stub counts
// only, with no fleet emission.
public record ObservabilityBaseline(Option<AdaptiveSampler> sampler,
                                    Option<InvocationTraceStore> traceStore,
                                    String nodeId,
                                    int defaultDepth) {
    private static final Logger traceLog = LoggerFactory.getLogger("org.pragmatica.aether.trace");
    private static final String ENTRY_CALLER = "HTTP";
    private static final String UNKNOWN_CALLER = "unknown";
    // All four absorbed east-west dispatch sites passed local=true to the interceptor; the baseline keeps
    // that observable constant.
    private static final boolean LOCAL = true;
    private static final String SUCCESS_FORMAT = "[trace] [requestId={}] {} depth={} duration={}ms";
    private static final String FAILURE_FORMAT = "[trace] [requestId={}] FAILURE {}/{} depth={} duration={}ms error={}";

    // The no-collaborator baseline: counting only, no fleet emission. Used when observability is disabled
    // (depthThreshold < 0) and by every stub.
    public static ObservabilityBaseline countingOnly() {
        return new ObservabilityBaseline(Option.none(), Option.none(), "", ObservabilityConfig.DEFAULT.depthThreshold());
    }

    // The fleet baseline: carries the collaborators the absorbed interceptor's ambient facets (sampling,
    // tracing, depth-leveled logging) need to run around the counting inner.
    public static ObservabilityBaseline fleet(AdaptiveSampler sampler,
                                              InvocationTraceStore traceStore,
                                              String nodeId,
                                              int defaultDepth) {
        return new ObservabilityBaseline(Option.some(sampler), Option.some(traceStore), nodeId, defaultDepth);
    }

    /// Composes the observation facets selected by (`logging`, `tracing`) around the supplied `inner`
    /// strategy (the metrics/counting facet, or identity when metrics is off). `callee` is the injection
    /// point's `artifactBase/methodName` identity (the exact callee string the interceptor formatted);
    /// `depthThreshold` is the registry-resolved per-injection depth for the logging ladder. The baseline
    /// path passes (logging=true, tracing=true) — "all ambient facets on"; the configured path passes the
    /// config's own toggles. When neither facet is selected, or the fleet collaborators are absent
    /// (counting-only baseline), the `inner` is returned untouched.
    public InvocationStrategy compose(InvocationStrategy inner,
                                      String callee,
                                      int depthThreshold,
                                      boolean logging,
                                      boolean tracing) {
        return logging || tracing
               ? ambientOrInner(inner, callee, depthThreshold, logging, tracing)
               : inner;
    }

    private InvocationStrategy ambientOrInner(InvocationStrategy inner,
                                              String callee,
                                              int depthThreshold,
                                              boolean logging,
                                              boolean tracing) {
        return Option.all(sampler, traceStore)
                     .map((s, ts) -> ambientStrategy(inner, callee, depthThreshold, logging, tracing, s, ts))
                     .or(inner);
    }

    private InvocationStrategy ambientStrategy(InvocationStrategy inner,
                                               String callee,
                                               int depthThreshold,
                                               boolean logging,
                                               boolean tracing,
                                               AdaptiveSampler sampler,
                                               InvocationTraceStore traceStore) {
        var context = new FleetContext(inner, callee, depthThreshold, logging, tracing, sampler, traceStore);

        return proceed -> observe(context, proceed);
    }

    private Promise<?> observe(FleetContext context, Fn0<Promise<?>> proceed) {
        context.sampler().recordInvocation();
        var capture = capture(context);
        var inner = context.inner().around(proceed);

        return capture.traced()
               ? inner.onSuccess(_ -> onSuccess(context, capture))
                      .onFailure(cause -> onFailure(context,
                                                    capture,
                                                    cause.message()))
               : inner.onFailure(cause -> onFailure(context, capture, cause.message()));
    }

    private static ObservationCapture capture(FleetContext context) {
        var depth = InvocationContext.currentDepth();
        var traced = InvocationContext.isSampled() || (depth == 0 && context.sampler().shouldSample());

        return new ObservationCapture(InvocationContext.getOrGenerateRequestId(), depth, System.nanoTime(), traced);
    }

    private Unit onSuccess(FleetContext context, ObservationCapture capture) {
        var node = buildNode(context, capture, Outcome.SUCCESS, Option.empty());

        return emit(context, node, () -> logAtDepth(context.depthThreshold(), node));
    }

    private Unit onFailure(FleetContext context, ObservationCapture capture, String errorMessage) {
        var node = buildNode(context, capture, Outcome.FAILURE, Option.option(errorMessage));

        return emit(context, node, () -> logFailure(context.callee(), capture, node, errorMessage));
    }

    // Shared facet dispatch for an observed result: the tracing facet records the node into the trace
    // store; the logging facet runs the depth-leveled log action. Both bodies are the same the baseline
    // uses; the config's toggles decide which fire.
    private static Unit emit(FleetContext context, InvocationNode node, Fn0<Unit> logAction) {
        if (context.tracing()) {
            context.traceStore().record(node);
        }

        return context.logging()
               ? logAction.apply()
               : Unit.unit();
    }

    private InvocationNode buildNode(FleetContext context,
                                     ObservationCapture capture,
                                     Outcome outcome,
                                     Option<String> errorMessage) {
        var durationNs = System.nanoTime() - capture.startNs();
        var caller = capture.depth() == 0
                     ? ENTRY_CALLER
                     : UNKNOWN_CALLER;

        return invocationNode(capture.requestId(),
                              capture.depth(),
                              Instant.now(),
                              nodeId,
                              caller,
                              context.callee(),
                              durationNs,
                              outcome,
                              errorMessage,
                              LOCAL,
                              0);
    }

    private static Unit logAtDepth(int threshold, InvocationNode node) {
        if (node.depth() <= threshold) {
            traceLog.info(SUCCESS_FORMAT, node.requestId(), node.callee(), node.depth(), node.durationMs());
        } else if (node.depth() <= threshold + 2) {
            traceLog.debug(SUCCESS_FORMAT, node.requestId(), node.callee(), node.depth(), node.durationMs());
        } else {
            traceLog.trace(SUCCESS_FORMAT, node.requestId(), node.callee(), node.depth(), node.durationMs());
        }

        return Unit.unit();
    }

    private static Unit logFailure(String callee,
                                   ObservationCapture capture,
                                   InvocationNode node,
                                   String errorMessage) {
        var slash = callee.indexOf('/');

        traceLog.error(FAILURE_FORMAT,
                       capture.requestId(),
                       callee.substring(0, slash),
                       callee.substring(slash + 1),
                       capture.depth(),
                       node.durationMs(),
                       errorMessage);

        return Unit.unit();
    }

    // Per-cell facet context bound once at swap time: the inner (counting/identity) strategy, the injection-
    // point identity, the depth threshold for the logging ladder, the two facet toggles, and the two fleet
    // collaborators (resolved non-Option here).
    private record FleetContext(InvocationStrategy inner,
                                String callee,
                                int depthThreshold,
                                boolean logging,
                                boolean tracing,
                                AdaptiveSampler sampler,
                                InvocationTraceStore traceStore) {}

    // Per-call synchronous capture of the InvocationContext tracing spine, taken before the async boundary so
    // the on-result recording closures read stable values (ScopedValue bindings do not survive the async gap).
    private record ObservationCapture(String requestId, int depth, long startNs, boolean traced) {}
}

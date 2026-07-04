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


// The absence-default composition for the #277 variant-C posture: an injection point with NO config at
// method, artifact, or global scope resolves to the BASELINE strategy, not identity ("off means baseline,
// not blind"). Since increment 5a this baseline carries the FLEET facets absorbed from the retired
// ObservabilityInterceptor — the strategy-cell system is now the ONE observability engine: ambient (fleet)
// and surgical (per-injection-point) observability are two policies over one mechanism.
//
// `fleet(sampler, traceStore, nodeId, defaultDepth)` layers the ambient behavior around the shared counting
// inner the registry plants per cell: on every observed invocation it ticks the adaptive sampler, branches
// sampled/unsampled exactly as the interceptor did (sampled when the context is already sampled OR the call
// is a depth-0 entry the sampler selects; unsampled records/logs failures only), times the invocation
// (nanoTime), records an InvocationNode into the SAME InvocationTraceStore instance the TRACES_* routes read,
// and logs through the SAME `org.pragmatica.aether.trace` logger with the interceptor's verbatim formats and
// depth-leveled INFO/DEBUG/TRACE ladder (success) / ERROR (failure). The trace record is a pure write-site
// relocation: every InvocationTraceStore consumer sees identical InvocationNode fields.
//
// Depth semantics: the strategy reads requestId/depth/isSampled from InvocationContext at call time (the
// single tracing spine at the dispatch seam — no interceptor depth-param split-brain). The per-injection
// depth threshold for the logging ladder is resolved by the registry from the effective AspectObservabilityConfig
// (else this baseline's `defaultDepth`, which mirrors today's ObservabilityDepthRegistry default =
// ObservabilityConfig.DEFAULT.depthThreshold()) and passed into `decorate`. The `local` flag is the observable
// constant `true`: all four absorbed east-west dispatch sites (InvocationHandler.invokeSliceMethod,
// SliceInvoker.invokeLocalFireAndForget/invokeLocalForFailover/invokeLocal) passed local=true to the interceptor.
//
// `countingOnly()` carries no collaborators and `decorate` returns the shared counting inner untouched, so an
// unconfigured cell counts by default with no fleet emission — the posture a node wired with observability
// disabled (depthThreshold < 0) or any stub uses.
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

    // The fleet baseline: absorbs the retired interceptor's ambient facets (sampling, tracing, depth-leveled
    // logging) around the counting inner.
    public static ObservabilityBaseline fleet(AdaptiveSampler sampler,
                                              InvocationTraceStore traceStore,
                                              String nodeId,
                                              int defaultDepth) {
        return new ObservabilityBaseline(Option.some(sampler), Option.some(traceStore), nodeId, defaultDepth);
    }

    /// Composes the baseline strategy around the shared `counting` inner (the metrics-facet embryo the
    /// registry plants per cell): the fleet facets when the collaborators are present, else the counting
    /// inner untouched. `callee` is the injection point's `artifactBase/methodName` identity (the cell key —
    /// the exact callee string the interceptor formatted); `depthThreshold` is the registry-resolved
    /// per-injection depth for the logging ladder.
    public InvocationStrategy decorate(InvocationStrategy counting, String callee, int depthThreshold) {
        return Option.all(sampler, traceStore)
                     .map((s, ts) -> fleetStrategy(counting, callee, depthThreshold, s, ts))
                     .or(counting);
    }

    private InvocationStrategy fleetStrategy(InvocationStrategy counting,
                                             String callee,
                                             int depthThreshold,
                                             AdaptiveSampler sampler,
                                             InvocationTraceStore traceStore) {
        var context = new FleetContext(counting, callee, depthThreshold, sampler, traceStore);

        return proceed -> observe(context, proceed);
    }

    private Promise<?> observe(FleetContext context, Fn0<Promise<?>> proceed) {
        context.sampler().recordInvocation();
        var capture = capture(context);
        var inner = context.counting().around(proceed);

        return capture.traced()
               ? inner.onSuccess(_ -> recordSuccess(context, capture))
                      .onFailure(cause -> recordFailure(context,
                                                        capture,
                                                        cause.message()))
               : inner.onFailure(cause -> recordFailure(context, capture, cause.message()));
    }

    private static ObservationCapture capture(FleetContext context) {
        var depth = InvocationContext.currentDepth();
        var traced = InvocationContext.isSampled() || (depth == 0 && context.sampler().shouldSample());

        return new ObservationCapture(InvocationContext.getOrGenerateRequestId(), depth, System.nanoTime(), traced);
    }

    private Unit recordSuccess(FleetContext context, ObservationCapture capture) {
        var node = buildNode(context, capture, Outcome.SUCCESS, Option.empty());

        context.traceStore().record(node);

        return logAtDepth(context.depthThreshold(), node);
    }

    private Unit recordFailure(FleetContext context, ObservationCapture capture, String errorMessage) {
        var node = buildNode(context, capture, Outcome.FAILURE, Option.option(errorMessage));

        context.traceStore().record(node);

        return logFailure(context.callee(), capture, node, errorMessage);
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

    // Per-cell facet context bound once at swap time: the counting inner, the injection-point identity, the
    // depth threshold for the logging ladder, and the two fleet collaborators (resolved non-Option here).
    private record FleetContext(InvocationStrategy counting,
                                String callee,
                                int depthThreshold,
                                AdaptiveSampler sampler,
                                InvocationTraceStore traceStore) {}

    // Per-call synchronous capture of the InvocationContext tracing spine, taken before the async boundary so
    // the on-result recording closures read stable values (ScopedValue bindings do not survive the async gap).
    private record ObservationCapture(String requestId, int depth, long startNs, boolean traced) {}
}

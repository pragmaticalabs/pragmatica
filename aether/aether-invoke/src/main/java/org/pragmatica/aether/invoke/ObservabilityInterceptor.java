package org.pragmatica.aether.invoke;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.aether.invoke.InvocationNode.Outcome.FAILURE;
import static org.pragmatica.aether.invoke.InvocationNode.Outcome.SUCCESS;
import static org.pragmatica.aether.invoke.InvocationNode.invocationNode;

/// Intercepts slice method invocations to provide unified observability:
/// adaptive sampling, depth-based logging, and trace recording.
///
/// <p>Provides comprehensive observability:
/// <ul>
///   <li>Always counts invocations for throughput tracking (cheap)</li>
///   <li>Honors propagated sampling decisions from upstream callers</li>
///   <li>At entry points (depth 0), uses adaptive sampling to decide tracing</li>
///   <li>Sampled invocations get full trace recording and depth-mapped logging</li>
///   <li>Failed invocations are always recorded regardless of sampling</li>
/// </ul>
public interface ObservabilityInterceptor {
    /// Intercept a slice method invocation with observability.
    ///
    /// @param slice     Target slice artifact
    /// @param method    Method being invoked
    /// @param requestId Request ID for correlation
    /// @param depth     Invocation depth in call tree (0 = entry point)
    /// @param local     Whether this is a local (in-process) invocation
    /// @param proceed   The actual invocation to execute
    /// @param <R>       Response type
    /// @return Promise resolving to the invocation result
    <R> Promise<R> intercept(Artifact slice,
                             MethodName method,
                             String requestId,
                             int depth,
                             boolean local,
                             Fn0<Promise<R>> proceed);

    /// Create an interceptor with adaptive sampling, trace recording, and depth-based logging.
    ///
    /// @param sampler    Adaptive sampler for throughput-aware trace sampling
    /// @param traceStore Ring buffer store for trace nodes
    /// @param nodeId     Identifier of the current node (for trace topology)
    /// @return a new observability interceptor
    static ObservabilityInterceptor observabilityInterceptor(AdaptiveSampler sampler,
                                                             InvocationTraceStore traceStore,
                                                             String nodeId) {
        return new ObservabilityInterceptorImpl(sampler, traceStore, nodeId);
    }

    /// Create a no-op interceptor that always passes through without any observability logic.
    static ObservabilityInterceptor noOp() {
        return new NoOpObservabilityInterceptor();
    }
}

class ObservabilityInterceptorImpl implements ObservabilityInterceptor {
    private static final Logger traceLog = LoggerFactory.getLogger("org.pragmatica.aether.trace");
    private static final String ENTRY_CALLER = "HTTP";
    private static final String UNKNOWN_CALLER = "unknown";

    private final AdaptiveSampler sampler;
    private final InvocationTraceStore traceStore;
    private final String nodeId;

    ObservabilityInterceptorImpl(AdaptiveSampler sampler, InvocationTraceStore traceStore, String nodeId) {
        this.sampler = sampler;
        this.traceStore = traceStore;
        this.nodeId = nodeId;
    }

    @Override
    public <R> Promise<R> intercept(Artifact slice,
                                    MethodName method,
                                    String requestId,
                                    int depth,
                                    boolean local,
                                    Fn0<Promise<R>> proceed) {
        sampler.recordInvocation();
        if (isAlreadySampled()) {
            return interceptWithTracing(slice, method, requestId, depth, local, proceed);
        }
        return interceptUnsampled(slice, method, requestId, depth, local, proceed);
    }

    private static boolean isAlreadySampled() {
        return InvocationContext.isSampled();
    }

    private boolean shouldSampleAtEntry(int depth) {
        return depth == 0 && sampler.shouldSample();
    }

    private <R> Promise<R> interceptUnsampled(Artifact slice,
                                              MethodName method,
                                              String requestId,
                                              int depth,
                                              boolean local,
                                              Fn0<Promise<R>> proceed) {
        var startNs = System.nanoTime();
        if (!shouldSampleAtEntry(depth)) {
            return proceed.apply()
                          .onFailure(cause -> recordAndLogFailure(slice,
                                                                  method,
                                                                  requestId,
                                                                  depth,
                                                                  local,
                                                                  startNs,
                                                                  cause.message()));
        }
        // Entry point that just got sampled — route to full tracing
        return interceptWithTracing(slice, method, requestId, depth, local, proceed);
    }

    private <R> Promise<R> interceptWithTracing(Artifact slice,
                                                MethodName method,
                                                String requestId,
                                                int depth,
                                                boolean local,
                                                Fn0<Promise<R>> proceed) {
        var startNs = System.nanoTime();
        return proceed.apply()
                      .onSuccess(_ -> recordAndLogSuccess(slice, method, requestId, depth, local, startNs))
                      .onFailure(cause -> recordAndLogFailure(slice,
                                                              method,
                                                              requestId,
                                                              depth,
                                                              local,
                                                              startNs,
                                                              cause.message()));
    }

    @SuppressWarnings("JBCT-RET-01") // Fire-and-forget trace recording + logging
    private void recordAndLogSuccess(Artifact slice,
                                     MethodName method,
                                     String requestId,
                                     int depth,
                                     boolean local,
                                     long startNs) {
        var durationNs = System.nanoTime() - startNs;
        var callee = formatCallee(slice, method);
        var caller = depth == 0
                     ? ENTRY_CALLER
                     : UNKNOWN_CALLER;
        var node = invocationNode(requestId,
                                  depth,
                                  Instant.now(),
                                  nodeId,
                                  caller,
                                  callee,
                                  durationNs,
                                  SUCCESS,
                                  Option.empty(),
                                  local,
                                  0);
        traceStore.record(node);
        logAtDepth(depth, node);
    }

    @SuppressWarnings("JBCT-RET-01") // Fire-and-forget trace recording + logging
    private void recordAndLogFailure(Artifact slice,
                                     MethodName method,
                                     String requestId,
                                     int depth,
                                     boolean local,
                                     long startNs,
                                     String errorMessage) {
        var durationNs = System.nanoTime() - startNs;
        var callee = formatCallee(slice, method);
        var caller = depth == 0
                     ? ENTRY_CALLER
                     : UNKNOWN_CALLER;
        var node = invocationNode(requestId,
                                  depth,
                                  Instant.now(),
                                  nodeId,
                                  caller,
                                  callee,
                                  durationNs,
                                  FAILURE,
                                  Option.option(errorMessage),
                                  local,
                                  0);
        traceStore.record(node);
        traceLog.error("[trace] [requestId={}] FAILURE {}/{} depth={} duration={}ms error={}",
                       requestId,
                       slice.base()
                            .asString(),
                       method.name(),
                       depth,
                       node.durationMs(),
                       errorMessage);
    }

    @SuppressWarnings("JBCT-RET-01") // Fire-and-forget logging
    private static void logAtDepth(int depth, InvocationNode node) {
        if (depth <= 1) {
            traceLog.info("[trace] [requestId={}] {} depth={} duration={}ms",
                          node.requestId(),
                          node.callee(),
                          node.depth(),
                          node.durationMs());
        } else if (depth <= 3) {
            traceLog.debug("[trace] [requestId={}] {} depth={} duration={}ms",
                           node.requestId(),
                           node.callee(),
                           node.depth(),
                           node.durationMs());
        } else {
            traceLog.trace("[trace] [requestId={}] {} depth={} duration={}ms",
                           node.requestId(),
                           node.callee(),
                           node.depth(),
                           node.durationMs());
        }
    }

    private static String formatCallee(Artifact slice, MethodName method) {
        return slice.base()
                    .asString() + "/" + method.name();
    }
}

class NoOpObservabilityInterceptor implements ObservabilityInterceptor {
    @Override
    public <R> Promise<R> intercept(Artifact slice,
                                    MethodName method,
                                    String requestId,
                                    int depth,
                                    boolean local,
                                    Fn0<Promise<R>> proceed) {
        return proceed.apply();
    }
}

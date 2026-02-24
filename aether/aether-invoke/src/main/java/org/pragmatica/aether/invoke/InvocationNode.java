package org.pragmatica.aether.invoke;

import org.pragmatica.lang.Option;

import java.time.Instant;

/// Single trace node representing one invocation in the call tree.
/// Captures timing, topology, and outcome for distributed tracing.
@SuppressWarnings("JBCT-VO-01") // Record with many fields — represents a single trace event, splitting would lose cohesion
public record InvocationNode(String requestId,
                             int depth,
                             Instant timestamp,
                             String nodeId,
                             String caller,
                             String callee,
                             long durationNs,
                             Outcome outcome,
                             Option<String> errorMessage,
                             boolean local,
                             int hops) {
    public enum Outcome {
        SUCCESS,
        FAILURE
    }

    /// Duration in milliseconds.
    public double durationMs() {
        return durationNs / 1_000_000.0;
    }

    /// Factory following JBCT naming.
    public static InvocationNode invocationNode(String requestId,
                                                int depth,
                                                Instant timestamp,
                                                String nodeId,
                                                String caller,
                                                String callee,
                                                long durationNs,
                                                Outcome outcome,
                                                Option<String> errorMessage,
                                                boolean local,
                                                int hops) {
        return new InvocationNode(requestId,
                                  depth,
                                  timestamp,
                                  nodeId,
                                  caller,
                                  callee,
                                  durationNs,
                                  outcome,
                                  errorMessage,
                                  local,
                                  hops);
    }
}

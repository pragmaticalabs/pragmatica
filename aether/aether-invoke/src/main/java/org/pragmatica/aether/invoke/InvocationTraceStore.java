// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterEventValue;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.function.Supplier;


public final class InvocationTraceStore {
    private static final int DEFAULT_CAPACITY = 50_000;

    private static final long DEFAULT_INJECTED_DURATION_MS = 10L;

    private static final String INJECTED_NODE_ID = "@injected";

    private static final String INJECTED_CALLER = "@injected";

    /// Narrow publisher shape so this module (`aether-invoke`) can replicate injected traces
    /// without taking a hard dependency on `aether/node`'s `ClusterEventLogPublisher`. The
    /// production publisher is adapted to this shape from `AetherNode` via
    /// `publisher::publish`.
    @FunctionalInterface public interface EventLogPublisher {
        Promise<Unit> publish(ClusterEventValue.EventType type,
                              ClusterEventValue.Severity severity,
                              String message,
                              Map<String, String> metadata);
    }

    private final InvocationNode[] buffer;
    private final int capacity;

    private final ReentrantLock lock = new ReentrantLock();

    private int head = 0;

    private int size = 0;

    /// Optional publisher for the cluster-scoped replicated event log. Bound post-construction
    /// because `InvocationTraceStore` is created (line ~781 in AetherNode) BEFORE the
    /// publisher (line ~1024). Unbound: inject paths remain node-local, matching prior
    /// behaviour for tests and non-cluster harnesses.
    private volatile Option<EventLogPublisher> eventLogPublisher = Option.none();

    /// Optional cluster-wide read source for cross-node visibility on `/api/traces`. When
    /// bound, `all()` UNIONs the local ring with peer-originated `TRACE_INJECTED` entries.
    /// Dedup by `requestId`.
    private volatile Option<Supplier<List<ClusterTraceEvent>>> clusterEventsSource = Option.none();

    /// View record exposed to bindings — keeps `InvocationTraceStore` decoupled from the
    /// `ClusterEvent` projection type owned by `aether/node`. Producers convert to this
    /// shape when invoking `bindClusterEventsSource`.
    public record ClusterTraceEvent(String requestId,
                                    String operation,
                                    long durationMs,
                                    int depth,
                                    long timestamp,
                                    String nodeId){}

    private InvocationTraceStore(int capacity) {
        this.capacity = capacity;
        this.buffer = new InvocationNode[capacity];
    }

    /// Bind the replicated event-log publisher. Idempotent; replaces any prior binding.
    @Contract
    public void bindEventLogPublisher(EventLogPublisher publisher) {
        this.eventLogPublisher = Option.option(publisher);
    }

    /// Bind the cross-node injected-trace reader. Supplier returns the latest view of the
    /// replicated log filtered/projected by the caller to `TRACE_INJECTED` events only.
    @Contract
    public void bindClusterEventsSource(Supplier<List<ClusterTraceEvent>> source) {
        this.clusterEventsSource = Option.option(source);
    }

    public static InvocationTraceStore invocationTraceStore() {
        return new InvocationTraceStore(DEFAULT_CAPACITY);
    }

    public static InvocationTraceStore invocationTraceStore(int capacity) {
        return new InvocationTraceStore(capacity);
    }

    @SuppressWarnings("JBCT-RET-01") public void record(InvocationNode node) {
        lock.lock();
        try {
            buffer[head] = node;
            head = (head + 1) % capacity;
            if (size <capacity) {size++;}
        } finally {
            lock.unlock();
        }
    }

    public Result<InvocationNode> inject(String operation,
                                         Option<Long> durationMs,
                                         Option<Integer> depth,
                                         Option<String> requestId,
                                         Option<String> traceId) {
        return validateInjectionInput(operation).map(_ -> stampAndStoreInjection(operation,
                                                                                 durationMs,
                                                                                 depth,
                                                                                 requestId,
                                                                                 traceId));
    }

    private static Result<String> validateInjectionInput(String operation) {
        if (operation == null || operation.isBlank()) {return InjectionError.OPERATION_REQUIRED.result();}
        return Result.success(operation);
    }

    private InvocationNode stampAndStoreInjection(String operation,
                                                  Option<Long> durationMs,
                                                  Option<Integer> depth,
                                                  Option<String> requestId,
                                                  Option<String> traceId) {
        var resolvedRequestId = requestId.filter(InvocationTraceStore::nonBlank).orElse(() -> traceId.filter(InvocationTraceStore::nonBlank))
                                                .or(InvocationTraceStore::generateUuid);
        var resolvedDepth = depth.or(0);
        var resolvedDurationMs = durationMs.or(DEFAULT_INJECTED_DURATION_MS);
        var durationNs = Math.max(0L, resolvedDurationMs) * 1_000_000L;
        var node = new InvocationNode(resolvedRequestId,
                                      resolvedDepth,
                                      Instant.now(),
                                      INJECTED_NODE_ID,
                                      INJECTED_CALLER,
                                      operation,
                                      durationNs,
                                      InvocationNode.Outcome.SUCCESS,
                                      Option.none(),
                                      true,
                                      0);
        record(node);
        publishInjectionToClusterLog(operation, resolvedRequestId, resolvedDepth, resolvedDurationMs);
        return node;
    }

    /// Replicate the injected trace via the cluster-scoped event log so peer nodes can return
    /// it on their `/api/traces` reads. Failures are logged-by-publisher and swallowed — the
    /// local ring already holds the entry, so the originating node remains correct even if
    /// Rabia apply is briefly unavailable.
    private void publishInjectionToClusterLog(String operation, String requestId, int depth, long durationMs) {
        eventLogPublisher.onPresent(publisher -> publisher.publish(ClusterEventValue.EventType.TRACE_INJECTED,
                                                                    ClusterEventValue.Severity.INFO,
                                                                    "Injected trace: " + operation,
                                                                    buildTraceInjectMetadata(operation,
                                                                                              requestId,
                                                                                              depth,
                                                                                              durationMs)));
    }

    private static Map<String, String> buildTraceInjectMetadata(String operation,
                                                                 String requestId,
                                                                 int depth,
                                                                 long durationMs) {
        var metadata = new LinkedHashMap<String, String>();
        metadata.put("requestId", requestId);
        metadata.put("operation", operation);
        metadata.put("durationMs", Long.toString(durationMs));
        metadata.put("depth", Integer.toString(depth));
        metadata.put("timestamp", Long.toString(System.currentTimeMillis()));
        return Map.copyOf(metadata);
    }

    private static String generateUuid() {
        return UUID.randomUUID().toString();
    }

    private static boolean nonBlank(String s) {
        return ! s.isBlank();
    }

    private enum InjectionError implements Cause {
        OPERATION_REQUIRED("Injected trace requires a non-blank operation");
        private final String message;
        InjectionError(String message) {
            this.message = message;
        }
        @Override public String message() {
            return message;
        }
    }

    public List<InvocationNode> all() {
        List<InvocationNode> local;
        lock.lock();
        try {
            local = collectNewestFirst(size);
        } finally {
            lock.unlock();
        }
        return unionWithClusterWideInjected(local, _ -> true, Integer.MAX_VALUE);
    }

    public List<InvocationNode> forRequest(String requestId) {
        return query(node -> node.requestId().equals(requestId),
                     capacity);
    }

    public List<InvocationNode> query(Predicate<InvocationNode> predicate, int limit) {
        List<InvocationNode> local;
        lock.lock();
        try {
            local = new ArrayList<>(Math.min(limit, size));
            var count = 0;
            for (int i = 0;i <size && count <limit;i++) {
                var node = nodeAtReverseIndex(i);
                if (node != null && predicate.test(node)) {
                    local.add(node);
                    count++;
                }
            }
        } finally {
            lock.unlock();
        }
        return unionWithClusterWideInjected(local, predicate, limit);
    }

    /// UNION the local-ring result with peer-originated `TRACE_INJECTED` entries from the
    /// replicated log. Dedup by `requestId` (originator's local entry wins). Filter the
    /// cluster-wide projection by the same predicate so query semantics stay consistent.
    private List<InvocationNode> unionWithClusterWideInjected(List<InvocationNode> local,
                                                              Predicate<InvocationNode> predicate,
                                                              int limit) {
        if (clusterEventsSource.isEmpty()) {return local;}
        var seenRequestIds = new java.util.HashSet<String>();
        for (var node : local) {seenRequestIds.add(node.requestId());}
        var combined = new ArrayList<>(local);
        clusterEventsSource.onPresent(source -> {
            for (var event : source.get()) {
                if (combined.size() >= limit) {return;}
                if (event.requestId() == null || !seenRequestIds.add(event.requestId())) {continue;}
                var projected = projectClusterTraceEvent(event);
                if (predicate.test(projected)) {combined.add(projected);}
            }
        });
        return combined;
    }

    private static InvocationNode projectClusterTraceEvent(ClusterTraceEvent event) {
        var durationNs = Math.max(0L, event.durationMs()) * 1_000_000L;
        var resolvedNodeId = event.nodeId() == null || event.nodeId().isEmpty() ? INJECTED_NODE_ID : event.nodeId();
        return new InvocationNode(event.requestId(),
                                   event.depth(),
                                   Instant.ofEpochMilli(event.timestamp()),
                                   resolvedNodeId,
                                   INJECTED_CALLER,
                                   event.operation(),
                                   durationNs,
                                   InvocationNode.Outcome.SUCCESS,
                                   Option.none(),
                                   false,
                                   0);
    }

    public TraceStats stats() {
        lock.lock();
        try {
            return computeStats();
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return size;
        } finally {
            lock.unlock();
        }
    }

    private InvocationNode nodeAtReverseIndex(int reverseIndex) {
        var index = ((head - 1 - reverseIndex) % capacity + capacity) % capacity;
        return buffer[index];
    }

    private List<InvocationNode> collectNewestFirst(int count) {
        var result = new ArrayList<InvocationNode>(count);
        for (int i = 0;i <count;i++) {
            var node = nodeAtReverseIndex(i);
            if (node != null) {result.add(node);}
        }
        return result;
    }

    private TraceStats computeStats() {
        long successCount = 0;
        long failureCount = 0;
        double totalDurationMs = 0;
        for (int i = 0;i <size;i++) {
            var node = nodeAtReverseIndex(i);
            if (node != null) {
                if (node.outcome() == InvocationNode.Outcome.SUCCESS) {successCount++;} else {failureCount++;}
                totalDurationMs += node.durationMs();
            }
        }
        var total = successCount + failureCount;
        var avgDuration = total > 0
                         ? totalDurationMs / total
                         : 0.0;
        return new TraceStats(total, successCount, failureCount, avgDuration, size, capacity);
    }

    public record TraceStats(long totalTraces,
                             long successCount,
                             long failureCount,
                             double avgDurationMs,
                             int bufferSize,
                             int bufferCapacity){}
}

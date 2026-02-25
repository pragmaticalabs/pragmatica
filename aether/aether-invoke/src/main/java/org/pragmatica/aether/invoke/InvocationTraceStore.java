package org.pragmatica.aether.invoke;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

/// Fixed-capacity thread-safe trace store backed by a ring buffer.
/// Stores recent [InvocationNode] records for distributed tracing queries.
public final class InvocationTraceStore {
    private static final int DEFAULT_CAPACITY = 50_000;

    private final InvocationNode[] buffer;
    private final int capacity;
    private final ReentrantLock lock = new ReentrantLock();
    private int head = 0;
    private int size = 0;

    private InvocationTraceStore(int capacity) {
        this.capacity = capacity;
        this.buffer = new InvocationNode[capacity];
    }

    /// Factory with default capacity (50K traces).
    public static InvocationTraceStore invocationTraceStore() {
        return new InvocationTraceStore(DEFAULT_CAPACITY);
    }

    /// Factory with custom capacity.
    public static InvocationTraceStore invocationTraceStore(int capacity) {
        return new InvocationTraceStore(capacity);
    }

    /// Record a trace node (overwrites oldest when full).
    @SuppressWarnings("JBCT-RET-01") // Fire-and-forget mutation
    public void record(InvocationNode node) {
        lock.lock();
        try{
            buffer[head] = node;
            head = (head + 1) % capacity;
            if (size < capacity) {
                size++;
            }
        } finally{
            lock.unlock();
        }
    }

    /// Get all stored traces (newest first).
    public List<InvocationNode> all() {
        lock.lock();
        try{
            return collectNewestFirst(size);
        } finally{
            lock.unlock();
        }
    }

    /// Get traces for a specific request ID.
    public List<InvocationNode> forRequest(String requestId) {
        return query(node -> node.requestId()
                                 .equals(requestId),
                     capacity);
    }

    /// Query traces matching a predicate, up to limit.
    public List<InvocationNode> query(Predicate<InvocationNode> predicate, int limit) {
        lock.lock();
        try{
            var result = new ArrayList<InvocationNode>(Math.min(limit, size));
            var count = 0;
            for (int i = 0; i < size && count < limit; i++) {
                var node = nodeAtReverseIndex(i);
                if (node != null && predicate.test(node)) {
                    result.add(node);
                    count++;
                }
            }
            return result;
        } finally{
            lock.unlock();
        }
    }

    /// Get aggregated statistics.
    public TraceStats stats() {
        lock.lock();
        try{
            return computeStats();
        } finally{
            lock.unlock();
        }
    }

    /// Buffer size.
    public int size() {
        lock.lock();
        try{
            return size;
        } finally{
            lock.unlock();
        }
    }

    private InvocationNode nodeAtReverseIndex(int reverseIndex) {
        var index = ((head - 1 - reverseIndex) % capacity + capacity) % capacity;
        return buffer[index];
    }

    private List<InvocationNode> collectNewestFirst(int count) {
        var result = new ArrayList<InvocationNode>(count);
        for (int i = 0; i < count; i++) {
            var node = nodeAtReverseIndex(i);
            if (node != null) {
                result.add(node);
            }
        }
        return result;
    }

    private TraceStats computeStats() {
        long successCount = 0;
        long failureCount = 0;
        double totalDurationMs = 0;
        for (int i = 0; i < size; i++) {
            var node = nodeAtReverseIndex(i);
            if (node != null) {
                if (node.outcome() == InvocationNode.Outcome.SUCCESS) {
                    successCount++;
                } else {
                    failureCount++;
                }
                totalDurationMs += node.durationMs();
            }
        }
        var total = successCount + failureCount;
        var avgDuration = total > 0
                          ? totalDurationMs / total
                          : 0.0;
        return new TraceStats(total, successCount, failureCount, avgDuration, size, capacity);
    }

    /// Aggregated trace statistics.
    public record TraceStats(long totalTraces,
                             long successCount,
                             long failureCount,
                             double avgDurationMs,
                             int bufferSize,
                             int bufferCapacity) {}
}

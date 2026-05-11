// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;


public final class InvocationTraceStore {
    private static final int DEFAULT_CAPACITY = 50_000;

    private static final long DEFAULT_INJECTED_DURATION_MS = 10L;

    private static final String INJECTED_NODE_ID = "@injected";

    private static final String INJECTED_CALLER = "@injected";

    private final InvocationNode[] buffer;
    private final int capacity;

    private final ReentrantLock lock = new ReentrantLock();

    private int head = 0;

    private int size = 0;

    private InvocationTraceStore(int capacity) {
        this.capacity = capacity;
        this.buffer = new InvocationNode[capacity];
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
        return node;
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
        lock.lock();
        try {
            return collectNewestFirst(size);
        } finally {
            lock.unlock();
        }
    }

    public List<InvocationNode> forRequest(String requestId) {
        return query(node -> node.requestId().equals(requestId),
                     capacity);
    }

    public List<InvocationNode> query(Predicate<InvocationNode> predicate, int limit) {
        lock.lock();
        try {
            var result = new ArrayList<InvocationNode>(Math.min(limit, size));
            var count = 0;
            for (int i = 0;i <size && count <limit;i++) {
                var node = nodeAtReverseIndex(i);
                if (node != null && predicate.test(node)) {
                    result.add(node);
                    count++;
                }
            }
            return result;
        } finally {
            lock.unlock();
        }
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

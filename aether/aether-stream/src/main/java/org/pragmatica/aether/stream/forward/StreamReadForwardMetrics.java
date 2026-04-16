// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.forward;

import org.pragmatica.lang.Contract;

import java.util.concurrent.atomic.AtomicLong;


/// Counters for stream read forwarding observability.
///
/// SPEC: §10.1 — five counters, no stream-name tag, low cardinality.
///  - aether.streams.read.forward.attempts   — every readRemote invocation
///  - aether.streams.read.forward.success    — successful response resolved
///  - aether.streams.read.forward.retry      — second-replica retry fired
///  - aether.streams.read.forward.timeout    — timeout path fired
///  - aether.streams.read.forward.truncated  — server-side cap truncated events
///
/// This thin interface avoids a hard Micrometer dependency in aether-stream.
/// AetherNode (or tests) supplies the implementation. The default uses AtomicLong
/// counters so metric values are observable without a registry.
@Contract public interface StreamReadForwardMetrics {
    void recordAttempt();
    void recordSuccess();
    void recordRetry();
    void recordTimeout();
    void recordTruncated();

    StreamReadForwardMetrics NOOP = new StreamReadForwardMetrics() {
        @Override public void recordAttempt() {}

        @Override public void recordSuccess() {}

        @Override public void recordRetry() {}

        @Override public void recordTimeout() {}

        @Override public void recordTruncated() {}
    };

    static StreamReadForwardMetrics inMemory() {
        return new InMemoryStreamReadForwardMetrics();
    }
}

@Contract final class InMemoryStreamReadForwardMetrics implements StreamReadForwardMetrics {
    private final AtomicLong attempts = new AtomicLong();

    private final AtomicLong success = new AtomicLong();

    private final AtomicLong retry = new AtomicLong();

    private final AtomicLong timeout = new AtomicLong();

    private final AtomicLong truncated = new AtomicLong();

    @Override public void recordAttempt() {
        attempts.incrementAndGet();
    }

    @Override public void recordSuccess() {
        success.incrementAndGet();
    }

    @Override public void recordRetry() {
        retry.incrementAndGet();
    }

    @Override public void recordTimeout() {
        timeout.incrementAndGet();
    }

    @Override public void recordTruncated() {
        truncated.incrementAndGet();
    }

    public long attempts() {
        return attempts.get();
    }

    public long success() {
        return success.get();
    }

    public long retry() {
        return retry.get();
    }

    public long timeout() {
        return timeout.get();
    }

    public long truncated() {
        return truncated.get();
    }
}

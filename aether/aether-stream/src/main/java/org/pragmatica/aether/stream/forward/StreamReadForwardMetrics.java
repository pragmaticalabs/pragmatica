// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.forward;

import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.lang.Contract;


@Contract
public interface StreamReadForwardMetrics {
    void recordAttempt();
    void recordSuccess();
    void recordRetry();
    void recordTimeout();
    void recordTruncated();
    /// A reply this node sent to a forwarding requester was not accepted by the transport (#2583 P2).
    void recordReplyNotSent();

    /// Replies this node sent to a forwarding requester that the transport did not accept, one per refused send (#2583 P2).
    /// The read side of [#recordReplyNotSent], on the instance the node assembly shares with the forward handler and client.
    long replyNotSent();

    StreamReadForwardMetrics NOOP = new StreamReadForwardMetrics() {
        @Override
        public void recordAttempt() {}

        @Override
        public void recordSuccess() {}

        @Override
        public void recordRetry() {}

        @Override
        public void recordTimeout() {}

        @Override
        public void recordTruncated() {}

        @Override
        public void recordReplyNotSent() {}

        @Override
        @Override
    public long replyNotSent() {
            return 0L;
        }
    };

    static StreamReadForwardMetrics inMemory() {
        return new InMemoryStreamReadForwardMetrics();
    }
}

@Contract
final class InMemoryStreamReadForwardMetrics implements StreamReadForwardMetrics {
    private final AtomicLong attempts = new AtomicLong();
    private final AtomicLong success = new AtomicLong();
    private final AtomicLong retry = new AtomicLong();
    private final AtomicLong timeout = new AtomicLong();
    private final AtomicLong truncated = new AtomicLong();
    private final AtomicLong replyNotSent = new AtomicLong();

    @Override
    public void recordAttempt() {
        attempts.incrementAndGet();
    }

    @Override
    public void recordSuccess() {
        success.incrementAndGet();
    }

    @Override
    public void recordRetry() {
        retry.incrementAndGet();
    }

    @Override
    public void recordTimeout() {
        timeout.incrementAndGet();
    }

    @Override
    public void recordTruncated() {
        truncated.incrementAndGet();
    }

    @Override
    public void recordReplyNotSent() {
        replyNotSent.incrementAndGet();
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

    public long replyNotSent() {
        return replyNotSent.get();
    }
}

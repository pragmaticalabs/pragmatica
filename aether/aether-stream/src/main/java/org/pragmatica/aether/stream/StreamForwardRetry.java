// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.aether.stream.forward.StreamForwardError;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Deadline;
import org.pragmatica.lang.utils.SharedScheduler;


/// Shared bounded forward-publish retry for the three owner-forward sites — {@link StreamWriteRouter}
/// (the management/API write path) and {@link DefaultStreamPublisher} / {@link PartitionedStreamAccess}
/// (the app publish paths). Each site previously carried its own verbatim copy of this retry
/// state-machine, so a fix landing on one lagged the others — the exact divergence #485 and #506
/// corrected — and this indirection collapses the three copies into ONE policy so that class cannot recur.
///
/// Semantics (owner-config-lag absorption): re-attempt a forward ONLY when the owner reported the failure
/// as retryable ({@link StreamForwardError.RemotePublishRetryable} — its committed-config view had not yet
/// caught up to the config the sender just committed and forwarded), bounded at {@link #MAX_FORWARD_ATTEMPTS}
/// with a short fixed {@link #FORWARD_RETRY_BACKOFF} scheduled through the shared scheduler (no blocking
/// sleep). Every other failure is permanent and never retried; success and permanent failure both resolve
/// immediately.
///
/// The caller's ambient [Deadline] is captured ONCE at [#withBoundedRetry] and re-bound around every
/// attempt: retries run from resolution and scheduler threads where the ScopedValue binding is gone,
/// and without the re-bind each retry would read unbounded and wait its full configured timeout
/// regardless of the client's budget. An expired budget also stops the retry ladder — re-forwarding
/// for a caller that already gave up is pure zombie work.
sealed interface StreamForwardRetry {
    /// Bounded forward-publish retry budget: 1 initial attempt + up to 2 retries.
    int MAX_FORWARD_ATTEMPTS = 3;
    /// Fixed short backoff between forward-publish retries — long enough for the owner's KV apply of the
    /// just-committed config to land, short enough to stay well within the publish path's forward budget.
    TimeSpan FORWARD_RETRY_BACKOFF = TimeSpan.timeSpan(150).millis();

    /// Drive `sendAttempt` — one owner-forward that resolves to the owner-assigned offset — under the
    /// bounded retry. The thunk is re-invoked per attempt, so every retry issues a fresh forward with the
    /// same target, identical to an inline per-site retry but with no per-call argument threading.
    static Promise<Long> withBoundedRetry(Fn0<Promise<Long>> sendAttempt) {
        return attempt(sendAttempt, 1, Deadline.current());
    }

    private static Promise<Long> attempt(Fn0<Promise<Long>> sendAttempt, int attemptNo, Deadline deadline) {
        return Deadline.runWith(deadline, sendAttempt::apply)
                       .fold(result -> result.fold(cause -> retryOrPropagate(sendAttempt,
                                                                             attemptNo,
                                                                             cause,
                                                                             deadline),
                                                   Promise::success));
    }

    private static Promise<Long> retryOrPropagate(Fn0<Promise<Long>> sendAttempt,
                                                  int attemptNo,
                                                  Cause cause,
                                                  Deadline deadline) {
        return StreamForwardError.isRetryablePublish(cause)
               && attemptNo < MAX_FORWARD_ATTEMPTS
               && !deadline.expired(FORWARD_RETRY_BACKOFF)
               ? scheduleRetry(sendAttempt, attemptNo + 1, deadline)
               : cause.promise();
    }

    /// Schedule the next attempt after the fixed backoff through the shared scheduler (no `Thread.sleep`),
    /// bridging its resolution into the pending promise returned to the caller.
    private static Promise<Long> scheduleRetry(Fn0<Promise<Long>> sendAttempt, int nextAttempt, Deadline deadline) {
        var pending = Promise.<Long> promise();

        SharedScheduler.schedule(() -> sendScheduled(pending, sendAttempt, nextAttempt, deadline),
                                 FORWARD_RETRY_BACKOFF);

        return pending;
    }

    private static void sendScheduled(Promise<Long> pending,
                                      Fn0<Promise<Long>> sendAttempt,
                                      int nextAttempt,
                                      Deadline deadline) {
        attempt(sendAttempt, nextAttempt, deadline).onResult(pending::resolve);
    }

    record unused() implements StreamForwardRetry {}
}

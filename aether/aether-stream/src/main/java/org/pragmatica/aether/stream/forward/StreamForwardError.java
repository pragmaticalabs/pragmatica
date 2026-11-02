// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.forward;

import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;


public sealed interface StreamForwardError extends Cause {
    /// Timeouts, a missing governor and an unavailable forwarder are passing conditions a retry may
    /// outlive (#280); an oversized response is not.
    enum General implements StreamForwardError {
        FORWARD_TIMEOUT("Stream publish forward timed out", true),
        GOVERNOR_UNAVAILABLE("No governor available for STREAMING task group", true),
        READ_FORWARD_TIMEOUT("Stream read forward timed out", true),
        READ_RESPONSE_OVERSIZED("Read forward response exceeded maximum size and could not be returned", false),
        STREAM_FORWARD_UNAVAILABLE("Stream forwarding is not available on this node", true);
        private final String message;
        private final boolean transientCondition;
        General(String message, boolean transientCondition) {
            this.message = message;
            this.transientCondition = transientCondition;
        }
        @Override
        public String message() {
            return message;
        }
        @Override
        public boolean isTransient() {
            return transientCondition;
        }
    }

    record RemotePublishFailed(String detail) implements StreamForwardError {
        @Override
        public String message() {
            return "Remote publish failed: " + detail;
        }
    }

    /// A forwarded publish the OWNER reported as RETRYABLE (write-forward race fix): the owner could not
    /// yet see the stream's committed config or was transiently capacity-deferred, so the forward should
    /// be re-attempted a BOUNDED number of times with short backoff rather than surfaced as permanent. The
    /// forwarder ({@link org.pragmatica.aether.stream.StreamWriteRouter}) discriminates on this exact type
    /// via {@link #isRetryablePublish(org.pragmatica.lang.Cause)}; every other publish failure is permanent
    /// and never retried.
    record RemotePublishRetryable(String detail) implements StreamForwardError, Cause.Transient {
        @Override
        public String message() {
            return "Remote publish retryable: " + detail;
        }
    }

    /// A forward request whose frame the local transport refused before it left this node (#2574): FORWARD-lane
    /// backpressure, no peer state, or an encode failure ([WriteOutcome]). The target never saw it, so a forwarded publish
    /// was not applied and a read was not served; a retry may outlive the condition, so the cause is transient. It fails
    /// the request at once, where it used to wait out the read or publish timeout.
    ///
    /// `retryAfter` is the wait the request would have spent on the answer (its caller's wait, the read or publish timeout
    /// capped by the ambient deadline). A caller that retries keeps at least that long between a refusal and its next send
    /// ([#refusalRetryAfter]), so failing fast never re-sends on the congested lane sooner than the silent wait did.
    record ForwardNotSent(WriteOutcome outcome, TimeSpan retryAfter, String message) implements StreamForwardError, Cause.Transient {
        public static final Fn2<ForwardNotSent, WriteOutcome, TimeSpan> FACTORY = Causes.forTwoValues("Stream forward was not sent, the transport refused it: %s; retry after %s",
                                                                                                      ForwardNotSent::new);
    }

    record ReadForwardFailed(String detail) implements StreamForwardError {
        @Override
        public String message() {
            return "Remote read failed: " + detail;
        }
    }

    /// Typed classifier the forwarder uses to decide whether a forward-publish failure is worth a bounded
    /// retry: true ONLY for {@link RemotePublishRetryable} (the owner's explicit retryable signal), so no
    /// other failure cause — a permanent {@link RemotePublishFailed}, a timeout, an unavailable transport —
    /// is ever retried.
    static boolean isRetryablePublish(Cause cause) {
        return cause instanceof RemotePublishRetryable;
    }

    /// The wait a refused forward asks its caller to keep before sending again (#2574): the `retryAfter` of the
    /// [ForwardNotSent] anywhere in `cause`'s chain, none for any other failure.
    static Option<TimeSpan> refusalRetryAfter(Cause cause) {
        return cause instanceof ForwardNotSent notSent
               ? Option.some(notSent.retryAfter())
               : cause.source()
                      .flatMap(StreamForwardError::refusalRetryAfter);
    }
}

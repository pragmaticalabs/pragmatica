// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.forward;

import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
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

    /// A forward READ whose frame the local transport did not send (#2574): the FORWARD lane refused it under backpressure,
    /// the connection was dead, or there was no peer state ([WriteOutcome.BackpressureRefused], [WriteOutcome.ConnectionDead],
    /// [WriteOutcome.NoPeerState]). The read fails at once, where it used to wait out its read timeout for an answer that
    /// could not come. A retry may outlive the condition, so the cause is transient; every caller that re-reads on its own
    /// trigger backs off on it ([RefusalBackoff]) rather than re-sending on the congested lane at once.
    record ForwardNotSent(WriteOutcome outcome, String message) implements StreamForwardError, Cause.Transient {
        public static final Fn1<ForwardNotSent, WriteOutcome> FACTORY = Causes.forOneValue("Stream forward read was not sent: %s",
                                                                                           ForwardNotSent::new);
    }

    /// A forward READ whose message could not be encoded ([WriteOutcome.EncodeFailed], #2574): nothing was written and no
    /// retry can change that (a missing or broken codec), so it is a hard error the caller does not retry.
    record ForwardNotEncoded(WriteOutcome outcome, String message) implements StreamForwardError, Cause.Terminal {
        public static final Fn1<ForwardNotEncoded, WriteOutcome> FACTORY = Causes.forOneValue("Stream forward read could not be encoded: %s",
                                                                                              ForwardNotEncoded::new);
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

    /// Whether `cause`, or any cause in its chain, is a read the transport did not send ([ForwardNotSent], #2574): the
    /// failure every re-reading caller backs off on.
    static boolean isRefusal(Cause cause) {
        return cause instanceof ForwardNotSent || cause.source()
                                                       .map(StreamForwardError::isRefusal)
                                                       .or(false);
    }
}

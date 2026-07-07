// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.forward;

import org.pragmatica.lang.Cause;


public sealed interface StreamForwardError extends Cause {
    enum General implements StreamForwardError {
        FORWARD_TIMEOUT("Stream publish forward timed out"),
        GOVERNOR_UNAVAILABLE("No governor available for STREAMING task group"),
        READ_FORWARD_TIMEOUT("Stream read forward timed out"),
        READ_RESPONSE_OVERSIZED("Read forward response exceeded maximum size and could not be returned"),
        STREAM_FORWARD_UNAVAILABLE("Stream forwarding is not available on this node");
        private final String message;
        General(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
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
    record RemotePublishRetryable(String detail) implements StreamForwardError {
        @Override
        public String message() {
            return "Remote publish retryable: " + detail;
        }
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
}

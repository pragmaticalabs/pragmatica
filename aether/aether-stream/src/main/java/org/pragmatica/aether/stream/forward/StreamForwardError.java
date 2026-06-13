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

    record ReadForwardFailed(String detail) implements StreamForwardError {
        @Override
        public String message() {
            return "Remote read failed: " + detail;
        }
    }
}

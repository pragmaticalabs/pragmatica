// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.http;

import org.pragmatica.lang.Cause;


public sealed interface HttpClientError extends Cause {
    record SerializationFailed(String message) implements HttpClientError {}

    /// The request could not be built at all — a URI that does not parse, has no scheme, or a header
    /// name/value the JDK refuses. Terminal: the same arguments produce the same refusal (#270 R6).
    record InvalidRequest(String uri, String detail) implements HttpClientError, Cause.Terminal {
        @Override
        public String message() {
            return "Invalid request for " + uri + ": " + detail;
        }
    }

    record DeserializationFailed(String detail, String responseBody) implements HttpClientError {
        @Override
        public String message() {
            return "Deserialization failed: " + detail;
        }
    }

    record RequestFailed(int statusCode, String responseBody) implements HttpClientError {
        @Override
        public String message() {
            return "HTTP " + statusCode + ": " + responseBody;
        }
    }

    record RequestFailedWithBody(int statusCode, Object parsedError, String rawBody) implements HttpClientError {
        @Override
        public String message() {
            return "HTTP " + statusCode + ": " + parsedError;
        }
    }
}

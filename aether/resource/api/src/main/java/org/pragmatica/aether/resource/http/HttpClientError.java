// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.http;

import org.pragmatica.lang.Cause;


/// Typed error causes for HTTP client JSON operations.
/// Separate from HttpError (transport-level) and JsonError (Jackson-level).
/// API module stays Jackson-free.
public sealed interface HttpClientError extends Cause {
    record SerializationFailed(String message) implements HttpClientError{}

    record DeserializationFailed(String detail, String responseBody) implements HttpClientError {
        @Override public String message() {
            return "Deserialization failed: " + detail;
        }
    }

    record RequestFailed(int statusCode, String responseBody) implements HttpClientError {
        @Override public String message() {
            return "HTTP " + statusCode + ": " + responseBody;
        }
    }

    record RequestFailedWithBody(int statusCode, Object parsedError, String rawBody) implements HttpClientError {
        @Override public String message() {
            return "HTTP " + statusCode + ": " + parsedError;
        }
    }
}

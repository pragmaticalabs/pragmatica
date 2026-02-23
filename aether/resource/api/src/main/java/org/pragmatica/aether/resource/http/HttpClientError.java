package org.pragmatica.aether.resource.http;

import org.pragmatica.lang.Cause;

/// Typed error causes for HTTP client JSON operations.
/// Separate from HttpError (transport-level) and JsonError (Jackson-level).
/// API module stays Jackson-free.
public sealed interface HttpClientError extends Cause {
    /// JSON serialization of request body failed.
    record SerializationFailed(String message) implements HttpClientError {}

    /// JSON deserialization of response body failed.
    record DeserializationFailed(String detail, String responseBody) implements HttpClientError {
        @Override
        public String message() {
            return "Deserialization failed: " + detail;
        }
    }

    /// HTTP request returned non-2xx status code.
    record RequestFailed(int statusCode, String responseBody) implements HttpClientError {
        @Override
        public String message() {
            return "HTTP " + statusCode + ": " + responseBody;
        }
    }

    /// HTTP request returned non-2xx status code with a parseable error body.
    record RequestFailedWithBody(int statusCode, Object parsedError, String rawBody) implements HttpClientError {
        @Override
        public String message() {
            return "HTTP " + statusCode + ": " + parsedError;
        }
    }
}

/*
 *  Copyright (c) 2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.pragmatica.http;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;

import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

import static org.pragmatica.http.HttpClientError.ConnectionFailed.connectionFailed;
import static org.pragmatica.http.HttpClientError.Failure.failure;
import static org.pragmatica.http.HttpClientError.Timeout.timeout;

/// Typed error causes for HTTP client operations.
/// Maps common HTTP exceptions to domain-friendly error types.
public sealed interface HttpClientError extends Cause {
    /// Connection to server failed (network unreachable, DNS failure, connection refused).
    record ConnectionFailed(String message, Option<Throwable> cause) implements HttpClientError {
        public static ConnectionFailed connectionFailed(String message) {
            return new ConnectionFailed(message, Option.none());
        }

        public static ConnectionFailed connectionFailed(String message, Throwable cause) {
            return new ConnectionFailed(message, Option.option(cause));
        }

        @Override
        public String message() {
            return "Connection failed: " + message;
        }
    }

    /// Request or connection timeout exceeded.
    record Timeout(String message, Option<Duration> duration) implements HttpClientError {
        public static Timeout timeout(String message) {
            return new Timeout(message, Option.none());
        }

        public static Timeout timeout(String message, Duration duration) {
            return new Timeout(message, Option.option(duration));
        }

        @Override
        public String message() {
            return duration.map(d -> "Timeout after " + d.toMillis() + "ms: " + message)
                           .or("Timeout: " + message);
        }
    }

    /// HTTP request completed but returned an error status code.
    record RequestFailed(int statusCode, String reason) implements HttpClientError {
        @Override
        public String message() {
            return "HTTP " + statusCode + ": " + reason;
        }
    }

    /// Response could not be parsed or is invalid.
    record InvalidResponse(String message, Option<Throwable> cause) implements HttpClientError {
        public static InvalidResponse invalidResponse(String message) {
            return new InvalidResponse(message, Option.none());
        }

        public static InvalidResponse invalidResponse(String message, Throwable cause) {
            return new InvalidResponse(message, Option.option(cause));
        }

        @Override
        public String message() {
            return "Invalid response: " + message;
        }
    }

    /// General HTTP failure (catch-all for unexpected errors).
    record Failure(Throwable cause) implements HttpClientError {
        public static Failure failure(Throwable cause) {
            return new Failure(cause);
        }

        @Override
        public String message() {
            return "HTTP operation failed: " + Option.option(cause.getMessage())
                                                    .or(cause.getClass()
                                                             .getName());
        }
    }

    /// Maps HTTP exceptions to typed HttpClientError causes.
    ///
    /// @param throwable Exception to map
    ///
    /// @return Corresponding HttpClientError
    static HttpClientError fromException(Throwable throwable) {
        return switch (throwable) {
            case HttpConnectTimeoutException _ -> timeout("Connection timeout");
            case HttpTimeoutException e -> timeout(e.getMessage());
            case java.net.ConnectException e -> connectionFailed(e.getMessage(), e);
            case java.net.UnknownHostException e -> connectionFailed("Unknown host: " + e.getMessage(), e);
            case java.io.IOException e -> connectionFailed(e.getMessage(), e);
            case InterruptedException _ -> timeout("Request interrupted");
            default -> failure(throwable);
        };
    }
}

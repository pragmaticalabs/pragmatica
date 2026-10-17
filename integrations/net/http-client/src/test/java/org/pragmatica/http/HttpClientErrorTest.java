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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.pragmatica.http.HttpClientError.ConnectionFailed.connectionFailed;
import static org.pragmatica.http.HttpClientError.Failure.failure;
import static org.pragmatica.http.HttpClientError.InvalidResponse.invalidResponse;
import static org.pragmatica.http.HttpClientError.Timeout.timeout;

class HttpClientErrorTest {

    @Test
    void connectionFailed_containsMessage() {
        var error = connectionFailed("Connection refused");

        assertThat(error.message()).contains("Connection refused");
        assertThat(error.cause().isEmpty()).isTrue();
    }

    @Test
    void connectionFailed_containsCause() {
        var cause = new IOException("Network error");
        var error = connectionFailed("Failed", cause);

        assertThat(error.message()).contains("Failed");
        assertThat(error.cause().isPresent()).isTrue();
    }

    @Test
    void timeout_withoutDuration_displaysMessage() {
        var error = timeout("Request timed out");

        assertThat(error.message()).isEqualTo("Timeout: Request timed out");
    }

    @Test
    void timeout_withDuration_displaysMillis() {
        var error = timeout("Connect", Duration.ofSeconds(5));

        assertThat(error.message()).contains("5000ms");
        assertThat(error.message()).contains("Connect");
    }

    @Test
    void requestFailed_displaysStatusAndReason() {
        var error = new HttpClientError.RequestFailed(404, "Not Found");

        assertThat(error.message()).isEqualTo("HTTP 404: Not Found");
    }

    @Test
    void invalidResponse_containsDetails() {
        var error = invalidResponse("Malformed JSON");

        assertThat(error.message()).contains("Malformed JSON");
    }

    @Test
    void failure_wrapsException() {
        var cause = new RuntimeException("Unexpected");
        var error = failure(cause);

        assertThat(error.message()).contains("Unexpected");
        assertThat(error.cause()).isSameAs(cause);
    }

    @Test
    void fromException_mapsTimeoutException() {
        var ex = new HttpTimeoutException("Timed out");
        var error = HttpClientError.fromException(ex);

        assertInstanceOf(HttpClientError.Timeout.class, error);
    }

    @Test
    void fromException_mapsConnectException() {
        var ex = new ConnectException("Connection refused");
        var error = HttpClientError.fromException(ex);

        assertInstanceOf(HttpClientError.ConnectionFailed.class, error);
    }

    @Test
    void fromException_mapsUnknownHostException() {
        var ex = new UnknownHostException("example.invalid");
        var error = HttpClientError.fromException(ex);

        assertInstanceOf(HttpClientError.ConnectionFailed.class, error);
        assertThat(error.message()).contains("Unknown host");
    }

    @Test
    void fromException_mapsInterruptedException() {
        var ex = new InterruptedException();
        var error = HttpClientError.fromException(ex);

        assertInstanceOf(HttpClientError.Timeout.class, error);
        assertThat(error.message()).contains("interrupted");
    }

    @Test
    void fromException_mapsUnknownToFailure() {
        var ex = new IllegalStateException("Unknown error");
        var error = HttpClientError.fromException(ex);

        assertInstanceOf(HttpClientError.Failure.class, error);
    }

    @Test
    void fromException_unwrapsCompletionException_toTheTransportCause() {
        var error = HttpClientError.fromException(new CompletionException(new ConnectException("Connection refused")));

        assertInstanceOf(HttpClientError.ConnectionFailed.class, error);
    }

    /// The mapping as JdkHttpOperations really invokes it: a dependent stage hands `whenComplete` a WRAPPED
    /// failure. Feeding `fromException` a raw ConnectException (above) cannot see that; a real refused socket can.
    @Test
    void send_toAPortNothingListensOn_failsWithConnectionFailedCarryingTheConnectException() throws IOException {
        int port;

        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }

        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).GET().build();
        var result = JdkHttpOperations.jdkHttpOperations().sendString(request).await();

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> {
            assertInstanceOf(HttpClientError.ConnectionFailed.class, cause, cause.getClass().getName() + ": " + cause.message());
            assertThat(((HttpClientError.ConnectionFailed) cause).cause().map(ConnectException.class::isInstance).or(false)).isTrue();
            assertThat(cause.message()).isEqualTo("Connection failed: Connection refused");
        });
    }

    @Test
    void transientClassification_onlyForRequestsThatNeverReachedAServer() {
        assertThat(HttpClientError.fromException(new CompletionException(new ConnectException("x"))).isTransient()).isTrue();
        assertThat(HttpClientError.fromException(new UnknownHostException("h")).isTransient()).isTrue();
        assertThat(HttpClientError.fromException(new IOException("Connection reset")).isTransient())
            .as("a reset mid-request may follow execution").isFalse();
        assertThat(HttpClientError.fromException(new HttpTimeoutException("slow")).isTransient())
            .as("a timed-out request may have executed").isFalse();
    }
}

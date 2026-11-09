// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;
import org.pragmatica.aether.management.route.ManagementRoute;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.net.http.HttpRequest;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterHttpClientTest {

    @AfterEach
    void resetTimeout() {
        ClusterHttpClient.setRequestTimeout(ClusterHttpClient.DEFAULT_REQUEST_TIMEOUT);
    }

    /// #2944: a 409 carrying the transient-conflict hint is retried a bounded number of times; any other 409 or status is not.
    @Nested
    class TransientConflictTests {
        private static final String HINT_BODY = "{\"status\":409,\"detail\":\"Hierarchy state changed before conditional commit; nothing was written, retry the request\"}";
        private static final String REAL_CONFLICT_BODY = "{\"status\":409,\"detail\":\"slice minAvailable floor\"}";

        private HttpServer server;
        private final AtomicInteger calls = new AtomicInteger();

        @AfterEach
        void stop() {
            if (server != null) {
                server.stop(0);
            }
            ClusterHttpClient.setEndpointOverride(null);
        }

        private void serve(int conflictsBeforeSuccess, int conflictStatus, String conflictBody) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                var conflicting = calls.incrementAndGet() <= conflictsBeforeSuccess;
                var bytes = (conflicting ? conflictBody : "{\"ok\":true}").getBytes(StandardCharsets.UTF_8);

                exchange.sendResponseHeaders(conflicting ? conflictStatus : 200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            ClusterHttpClient.setEndpointOverride("http://127.0.0.1:" + server.getAddress().getPort());
        }

        @Test
        void post_retriesTransientConflict_untilItSettles() throws IOException {
            serve(2, 409, HINT_BODY);

            var result = ClusterHttpClient.post(ManagementRoute.CLUSTER_AUTO_HEAL_DISABLE, "{}");

            assertTrue(result.isSuccess(), "the settled retry is returned as success");
            assertEquals(3, calls.get());
        }

        @Test
        void post_givesUpAfterTheBound_andReturnsTheConflict() throws IOException {
            serve(Integer.MAX_VALUE, 409, HINT_BODY);

            var startedAt = System.nanoTime();
            var result = ClusterHttpClient.post(ManagementRoute.CLUSTER_AUTO_HEAL_DISABLE, "{}");
            var elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

            assertTrue(result.isFailure());
            // The bound is the number the changelog states: 4 requests in all, not the constant compared with itself.
            assertEquals(4, calls.get());
            assertEquals(4, ClusterHttpClient.CONFLICT_RETRY_ATTEMPTS);
            assertEquals(250L, ClusterHttpClient.CONFLICT_RETRY_PAUSE_MS);
            // Pauses of 250 ms x attempt before retries 1..3: 250 + 500 + 750 ms at least (lower bound only, a timing upper bound would flake).
            assertTrue(elapsedMs >= 1500, "pauses grow with the attempt, elapsed ms: " + elapsedMs);
        }

        @Test
        void post_doesNotRetryAConflictWithoutTheHint() throws IOException {
            serve(Integer.MAX_VALUE, 409, REAL_CONFLICT_BODY);

            var result = ClusterHttpClient.post(ManagementRoute.CLUSTER_AUTO_HEAL_DISABLE, "{}");

            assertTrue(result.isFailure());
            assertEquals(1, calls.get());
        }

        @Test
        void post_doesNotRetryAServerError() throws IOException {
            serve(Integer.MAX_VALUE, 500, HINT_BODY);

            var result = ClusterHttpClient.post(ManagementRoute.CLUSTER_AUTO_HEAL_DISABLE, "{}");

            assertTrue(result.isFailure());
            assertEquals(1, calls.get());
        }
    }

    @Nested
    class TimeoutTests {

        @Test
        void setRequestTimeout_appliesToOutgoingRequests_whenPositive() {
            ClusterHttpClient.setRequestTimeout(Duration.ofSeconds(5));
            var builder = HttpRequest.newBuilder().uri(URI.create("http://example.invalid/api"))
                                                  .GET();
            ClusterHttpClient.applyTimeout(builder);
            var request = builder.build();
            assertTrue(request.timeout().isPresent(), "timeout must be applied to request");
            assertEquals(Duration.ofSeconds(5), request.timeout().get());
        }

        @Test
        void setRequestTimeout_zero_disablesTimeout() {
            ClusterHttpClient.setRequestTimeout(Duration.ZERO);
            var builder = HttpRequest.newBuilder().uri(URI.create("http://example.invalid/api"))
                                                  .GET();
            ClusterHttpClient.applyTimeout(builder);
            var request = builder.build();
            assertTrue(request.timeout().isEmpty(), "zero timeout must not be applied");
        }

        @Test
        void setRequestTimeout_negative_disablesTimeout() {
            ClusterHttpClient.setRequestTimeout(Duration.ofSeconds(-1));
            var builder = HttpRequest.newBuilder().uri(URI.create("http://example.invalid/api"))
                                                  .GET();
            ClusterHttpClient.applyTimeout(builder);
            var request = builder.build();
            assertTrue(request.timeout().isEmpty(), "negative timeout must not be applied");
        }

        @Test
        void setRequestTimeout_defaultsTo130Seconds_whenUnset() {
            assertEquals(Duration.ofSeconds(130), ClusterHttpClient.DEFAULT_REQUEST_TIMEOUT);
        }

        @Test
        void setRequestTimeout_overridesPreviousValue() {
            ClusterHttpClient.setRequestTimeout(Duration.ofSeconds(7));
            ClusterHttpClient.setRequestTimeout(Duration.ofSeconds(11));
            var builder = HttpRequest.newBuilder().uri(URI.create("http://example.invalid/api"))
                                                  .GET();
            ClusterHttpClient.applyTimeout(builder);
            var request = builder.build();
            assertEquals(Duration.ofSeconds(11), request.timeout().get());
        }
    }
}

// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterHttpClientTest {

    @AfterEach
    void resetTimeout() {
        ClusterHttpClient.setRequestTimeout(ClusterHttpClient.DEFAULT_REQUEST_TIMEOUT);
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

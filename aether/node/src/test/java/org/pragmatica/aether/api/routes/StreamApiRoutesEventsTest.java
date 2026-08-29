// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.slice.stream.StreamNamespacesService;
import org.pragmatica.aether.stream.consumer.ConsumerGroupCoordinator;
import org.pragmatica.aether.stream.consumer.ConsumerGroupRegistry;
import org.pragmatica.aether.http.handler.security.RoutePermission;
import org.pragmatica.aether.http.handler.security.RoutePermissionRegistry;

import static org.assertj.core.api.Assertions.assertThat;


/// Tests for the polling-based paginated event read endpoint added in Wave 6B —
/// spec event-stream-namespaces §16. Exercises the route registration shape and
/// the role binding (ALL_AUTHENTICATED for GET reads).
class StreamApiRoutesEventsTest {

    @Nested
    class RouteRegistration {

        @Test
        void streamsEvents_routeRegistered_hasExpectedShape() {
            var route = ManagementRoute.STREAMS_EVENTS;
            assertThat(route.method().name()).isEqualTo("GET");
            assertThat(route.prefix()).isEqualTo("/api/v1/streams");
        }

        @Test
        void streamsEvents_assemblesCanonicalPath() {
            var assembled = ManagementRoute.STREAMS_EVENTS
                    .assemble("com.example.app", "orders", "1.0.0");
            assertThat(assembled.isSuccess()).isTrue();
            assembled.onSuccess(path -> assertThat(path)
                    .isEqualTo("/api/v1/streams/com.example.app/orders/1.0.0/events"));
        }

        @Test
        void streamsEvents_routeIsDistinctFromTail() {
            var tail = ManagementRoute.STREAMS_TAIL.assemble("ns", "s", "1.0.0");
            var events = ManagementRoute.STREAMS_EVENTS.assemble("ns", "s", "1.0.0");
            assertThat(tail.isSuccess()).isTrue();
            assertThat(events.isSuccess()).isTrue();
            tail.onSuccess(t -> events.onSuccess(e -> assertThat(t).isNotEqualTo(e)));
        }
    }

    @Nested
    class PermissionResolution {

        @Test
        void streamsEvents_get_resolvesToAllAuthenticated() {
            var perm = RoutePermissionRegistry.resolve(
                    "GET",
                    "/api/v1/streams/events/com.example.app/orders/1.0.0");
            assertThat(perm).isEqualTo(RoutePermission.ALL_AUTHENTICATED);
        }

        @Test
        void streamsTail_get_resolvesToAllAuthenticated() {
            var perm = RoutePermissionRegistry.resolve(
                    "GET",
                    "/api/v1/streams/tail/com.example.app/orders/1.0.0");
            assertThat(perm).isEqualTo(RoutePermission.ALL_AUTHENTICATED);
        }
    }

    @Nested
    class RouteSourceWiring {

        /// `StreamApiRoutes.routes()` enumerates every spec-required HTTP verb. After Wave 6B the
        /// surface count went from 11 (Wave 4A) to 12 with the addition of `/events`. Asserting the
        /// count + the presence of the new route name is a minimal smoke test that the stream is
        /// emitting the expected entries even without a live HTTP server.
        @Test
        void routes_includeStreamsEvents_andCountIsTwelve() {
            var namespaces = StreamNamespacesService.inMemory();
            var routes = StreamApiRoutes.streamApiRoutes(() -> null,
                                                         namespaces,
                                                         ConsumerGroupCoordinator.noOp(),
                                                         ConsumerGroupRegistry.consumerGroupRegistry());
            var routeList = routes.routes().toList();
            assertThat(routeList).hasSize(12);
            assertThat(routeList.stream().anyMatch(r -> r.name().equals(ManagementRoute.STREAMS_EVENTS.name())))
                    .isTrue();
            assertThat(routeList.stream().anyMatch(r -> r.name().equals(ManagementRoute.STREAMS_TAIL.name())))
                    .isTrue();
        }
    }
}

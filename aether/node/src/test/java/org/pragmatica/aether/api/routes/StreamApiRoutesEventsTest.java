// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.Set;
import java.util.stream.Collectors;

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

        /// `StreamApiRoutes.routes()` must emit exactly one entry per spec-required verb: the read
        /// surface (list/versions/latest/metadata/partition/replicas/read/groups-list/tail/events),
        /// the write surface (publish/publish-batch/group-create/group-delete), and the destructive
        /// surface (delete). Asserting the exact NAME SET — rather than a bare size — is a smoke test
        /// that the stream is emitting exactly the expected entries, and it fails on the right
        /// question (which route appeared or vanished) instead of just "a number moved."
        private static final Set<String> EXPECTED_ROUTE_NAMES = Set.of(ManagementRoute.STREAMS_LIST.name(),
                                                                        ManagementRoute.STREAMS_VERSIONS_LIST.name(),
                                                                        ManagementRoute.STREAMS_LATEST.name(),
                                                                        ManagementRoute.STREAMS_METADATA.name(),
                                                                        ManagementRoute.STREAM_GET.name(),
                                                                        ManagementRoute.STREAM_PARTITION.name(),
                                                                        ManagementRoute.STREAM_REPLICAS.name(),
                                                                        ManagementRoute.STREAM_READ.name(),
                                                                        ManagementRoute.STREAMS_GROUPS_LIST.name(),
                                                                        ManagementRoute.STREAMS_TAIL.name(),
                                                                        ManagementRoute.STREAMS_EVENTS.name(),
                                                                        ManagementRoute.STREAMS_PUBLISH.name(),
                                                                        ManagementRoute.STREAMS_PUBLISH_BATCH.name(),
                                                                        ManagementRoute.STREAMS_GROUP_CREATE.name(),
                                                                        ManagementRoute.STREAMS_GROUP_DELETE.name(),
                                                                        ManagementRoute.STREAMS_DELETE.name());

        @Test
        void routes_enumerateEverySpecRequiredVerb_asExactNameSet() {
            var namespaces = StreamNamespacesService.inMemory();
            var routes = StreamApiRoutes.streamApiRoutes(() -> null,
                                                         namespaces,
                                                         ConsumerGroupCoordinator.noOp(),
                                                         ConsumerGroupRegistry.consumerGroupRegistry());
            var actualNames = routes.routes().map(r -> r.name()).collect(Collectors.toSet());

            assertThat(actualNames).hasSameSizeAs(EXPECTED_ROUTE_NAMES)
                                   .containsExactlyInAnyOrderElementsOf(EXPECTED_ROUTE_NAMES);
        }
    }
}

// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.stream.StreamNamespacesService;
import org.pragmatica.aether.slice.stream.StreamRegistryEntry;
import org.pragmatica.aether.slice.stream.StreamRegistry;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.consumer.ConsumerGroupCoordinator;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.routing.Route;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;


/// #1921 (b): an untyped not-found refusal from the stream registry or the stream engine answered 500 on the routes that read
/// them, which told a caller who named an unknown stream that the cluster had broken. Each test drives the real route handler
/// against an EMPTY registry and an engine that holds no streams -- the unknown-stream case -- then the router's own
/// `ProblemResponses.writeProblem`.
class ManagementStreamNotFoundStatusTest {
    private static final List<String> ADDRESS = List.of("ns", "orders", "1.0.0");

    @Test
    void streamsMetadata_answers404_whenTheStreamIsNotRegistered() {
        assertThat(apiStatus(ManagementRoute.STREAMS_METADATA, ADDRESS)).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void streamsEvents_answers404_whenTheStreamIsNotRegistered() {
        assertThat(apiStatus(ManagementRoute.STREAMS_EVENTS, path(ADDRESS, "events"))).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void streamsLatest_answers404_whenNoVersionIsRegistered() {
        assertThat(apiStatus(ManagementRoute.STREAMS_LATEST, List.of("ns", "orders", "latest"))).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void streamNamespacesGet_answers404_whenTheStreamIsNotRegistered() {
        var routes = StreamNamespacesRoutes.streamNamespacesRoutes(StreamNamespacesService.inMemory()).routes();

        assertThat(statusOf(routes, ManagementRoute.STREAM_NAMESPACES_GET, List.of("ns:orders:1.0.0"))).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /// The route's path carries ONE segment, the full `namespace:stream:version` address; the earlier handler registered three and
    /// could not be reached over HTTP at all. A path the real matcher delivers must find a registered stream.
    @Test
    void streamNamespacesGet_returnsTheEntry_whenTheAddressNamesARegisteredStream() {
        var service = StreamNamespacesService.inMemory();
        var address = ResourceAddress.resourceAddress("ns", "orders", "1.0.0").unwrap();

        service.registry()
               .register(StreamRegistryEntry.operator(address, RetentionPolicy.retentionPolicy(10_000, 1024 * 1024, 600_000), Instant.now()))
               .onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()));

        var matched = ManagementRoute.match(HttpMethod.GET, "/api/v1/streams/namespaces/ns:orders:1.0.0").unwrap();

        assertThat(matched.route()).isEqualTo(ManagementRoute.STREAM_NAMESPACES_GET);

        RouteProbe.run(StreamNamespacesRoutes.streamNamespacesRoutes(service).routes(),
                       matched.route(),
                       matched.route().paramNames().stream().map(matched.params()::get).toList(),
                       Map.of())
                  .onFailure(cause -> org.junit.jupiter.api.Assertions.fail("a registered stream must be reachable: " + cause.message()))
                  .onSuccess(value -> assertThat(value).isInstanceOf(StreamNamespacesRoutes.StreamNamespacesEntryResponse.class));
    }

    @Test
    void streamNamespacesGet_answers400_whenTheAddressIsMalformed() {
        var routes = StreamNamespacesRoutes.streamNamespacesRoutes(StreamNamespacesService.inMemory()).routes();

        assertThat(statusOf(routes, ManagementRoute.STREAM_NAMESPACES_GET, List.of("not-an-address"))).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void streamPartition_answers404_whenTheEngineHoldsNoSuchStream() {
        assertThat(apiStatus(ManagementRoute.STREAM_PARTITION, path(ADDRESS, "partitions", "0"))).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void streamConsumers_answers404_whenTheEngineHoldsNoSuchStream() {
        var manager = streamPartitionManager(Long.MAX_VALUE);

        try {
            var routes = StreamRoutes.streamRoutes(() -> nodeWith(manager), ConsumerGroupCoordinator.noOp(), null).routes();

            assertThat(statusOf(routes, ManagementRoute.STREAM_CONSUMERS, path(ADDRESS, "consumers"))).isEqualTo(HttpStatus.NOT_FOUND);
        } finally {
            manager.close();
        }
    }

    /// Control: the mapping is keyed on the three not-found causes, not on "any cause the stream layer produces".
    @Test
    void anyOtherCause_isLeftAlone() {
        Cause untyped = Causes.cause("boom");

        assertThat(mapped(untyped)).isSameAs(untyped);
    }

    @Test
    void alreadyRegistered_isLeftAlone() {
        Cause cause = StreamRegistry.StreamRegistryError.General.ALREADY_REGISTERED;

        assertThat(mapped(cause)).isSameAs(cause);
    }

    @Test
    void theThreeNotFoundCauses_becomeATypedNotFound() {
        for (Cause cause : List.of(StreamRegistry.StreamRegistryError.General.NOT_FOUND,
                                   StreamRegistry.StreamRegistryError.General.NO_VERSIONS_REGISTERED,
                                   new StreamError.StreamNotFound("x"))) {
            var mapped = mapped(cause);

            assertThat(ProblemStatus.of(mapped)).as(cause.message()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(mapped.message()).isEqualTo(cause.message());
        }
    }

    private static Cause mapped(Cause cause) {
        var holder = new java.util.concurrent.atomic.AtomicReference<Cause>();

        RequestParse.asNotFound(cause.<String> result()).onFailure(holder::set);

        return holder.get();
    }

    private static HttpStatus apiStatus(ManagementRoute which, List<String> pathParams) {
        var manager = streamPartitionManager(Long.MAX_VALUE);

        try {
            var routes = StreamApiRoutes.streamApiRoutes(() -> nodeWith(manager),
                                                         StreamNamespacesService.inMemory(),
                                                         ConsumerGroupCoordinator.noOp(),
                                                         null).routes();

            return statusOf(routes, which, pathParams);
        } finally {
            manager.close();
        }
    }

    private static HttpStatus statusOf(Stream<Route<?>> routes, ManagementRoute which, List<String> pathParams) {
        return ProblemStatus.of(RouteProbe.failureOf(routes, which, pathParams, Map.of()));
    }

    private static List<String> path(List<String> address, String... rest) {
        return Stream.concat(address.stream(), Stream.of(rest)).toList();
    }

    private static ManageableNode nodeWith(StreamPartitionManager manager) {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, _) -> {
                                                           if (method.getName().equals("streamPartitionManager")) {
                                                               return manager;
                                                           }
                                                           throw new UnsupportedOperationException("Not stubbed: " + method.getName());
                                                       });
    }

    private static final class ProblemStatus {
        static HttpStatus of(Cause cause) {
            return RouteProbe.problemStatus(cause);
        }
    }
}

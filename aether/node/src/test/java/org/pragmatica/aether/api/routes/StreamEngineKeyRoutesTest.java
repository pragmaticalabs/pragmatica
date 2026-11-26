// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.stream.StreamNamespacesService;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.StreamReadRouter;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.routing.Route;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #1399: a durable-topic stream's engine key (`topic:<ns>:<topic>:<ver>`) has four parts, which the `(namespace, stream, version)`
/// grammar of the catalog routes cannot carry. `/engine-streams/{engineKey}/events` and `/consumers` take the key raw and
/// resolve it through the same catalog lookup as the catalog routes (#1753), so a topic stream is readable like any other.
class StreamEngineKeyRoutesTest {
    private static final String ENGINE_KEY = "topic:com.example.app:orders:1.0.0";
    private static final RetentionPolicy RETENTION = RetentionPolicy.retentionPolicy(10_000, 1024 * 1024, 600_000);
    private static final int EVENTS = 3;

    /// One row per raw-key route.
    private enum Probe {
        EVENTS(ManagementRoute.STREAM_ENGINE_EVENTS, "events"),
        CONSUMERS(ManagementRoute.STREAM_ENGINE_CONSUMERS, "consumers");

        private final ManagementRoute route;
        private final String literal;

        Probe(ManagementRoute route, String literal) {
            this.route = route;
            this.literal = literal;
        }

        List<String> path(String engineKey) {
            return List.of(engineKey, literal);
        }
    }

    private StreamPartitionManager manager;

    @BeforeEach
    void setUp() {
        manager = StreamPartitionManager.streamPartitionManager();
    }

    @AfterEach
    void tearDown() throws Exception {
        manager.close();
    }

    /// The path a real request takes: the matcher must bind the colon-bearing key as ONE parameter and pick the raw-key route,
    /// while the catalog route of the same trailing verb stays what it was.
    @ParameterizedTest
    @EnumSource(Probe.class)
    void matcher_bindsTheFourPartKeyAsOneParameter(Probe probe) {
        var matched = ManagementRoute.match(HttpMethod.GET, "/api/v1/engine-streams/" + ENGINE_KEY + "/" + probe.literal).unwrap();

        assertThat(matched.route()).isEqualTo(probe.route);
        assertThat(matched.params().get("engineKey")).isEqualTo(ENGINE_KEY);
    }

    @Test
    void matcher_leavesTheCatalogRouteOfTheSameVerbAlone() {
        var matched = ManagementRoute.match(HttpMethod.GET, "/api/v1/streams/ns/orders/1.0.0/events").unwrap();

        assertThat(matched.route()).isEqualTo(ManagementRoute.STREAMS_EVENTS);
    }

    /// The #1399 pin: a published topic event is read back by its 4-part name.
    @Test
    void events_readsAPublishedTopicEvent_byItsFourPartEngineKey() {
        holds(2);
        publish(EVENTS);

        var response = (StreamApiRoutes.StreamEventsResponse) answer(Probe.EVENTS, holderRoutes(true));

        assertThat(response.address()).isEqualTo(ENGINE_KEY);
        assertThat(response.events()).hasSize(EVENTS);
        assertThat(response.events().getFirst().payload()).isEqualTo(new String(new byte[]{0}, StandardCharsets.UTF_8));
    }

    /// The query is the one the catalog `/events` route takes: a window of `maxEvents` from `fromOffset`.
    @Test
    void events_honoursTheFromOffsetAndMaxEventsQuery() {
        holds(2);
        publish(EVENTS);

        var response = (StreamApiRoutes.StreamEventsResponse) RouteProbe.run(holderRoutes(true),
                                                                              Probe.EVENTS.route,
                                                                              Probe.EVENTS.path(ENGINE_KEY),
                                                                              Map.of("fromOffset", List.of("1"), "maxEvents", List.of("1")))
                                                                      .onFailure(cause -> fail("a windowed read must answer: " + cause.message()))
                                                                      .unwrap();

        assertThat(response.events()).extracting(StreamApiRoutes.EventEntry::offset).containsExactly(1L);
        assertThat(response.nextOffset()).isEqualTo(2L);
        assertThat(response.hasMore()).isTrue();
    }

    /// Same routing target as the catalog route it mirrors: delegate-routed to a streaming node, never answered from a node's own view.
    @ParameterizedTest
    @EnumSource(Probe.class)
    void route_hasTheSameTargetAsItsCatalogCounterpart(Probe probe) {
        var counterpart = probe == Probe.EVENTS
                          ? ManagementRoute.STREAMS_EVENTS
                          : ManagementRoute.STREAM_CONSUMERS;

        assertThat(probe.route.target()).isEqualTo(counterpart.target());
    }

    @Test
    void consumers_listsThePartitionsAndTheirCounts_byTheFourPartEngineKey() {
        holds(2);
        publish(EVENTS);

        var response = (StreamRoutes.StreamConsumersResponse) answer(Probe.CONSUMERS, holderRoutes(true));

        assertThat(response.name()).isEqualTo(ENGINE_KEY);
        assertThat(response.partitions()).hasSize(2);
        assertThat(response.partitions().getFirst().eventCount()).isEqualTo(EVENTS);
    }

    /// The catalog is what decides unknown: a key the cluster never committed is 404 even when the engine holds a ring for it.
    @ParameterizedTest
    @EnumSource(Probe.class)
    void unknownKey_answers404_evenWhenTheEngineHoldsARing(Probe probe) {
        holds(2);

        assertThat(statusOf(probe, holderRoutes(false))).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /// The catalog knows the key and this node holds nothing it can ask about: retry (503), never "no such stream".
    @ParameterizedTest
    @EnumSource(Probe.class)
    void knownKey_thisNodeCannotAnswer_answers503NotNotFound(Probe probe) {
        assertThat(statusOf(probe, holderRoutes(true))).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    // === helpers ===

    private void holds(int partitions) {
        manager.createStream(config(partitions)).onFailure(cause -> fail(cause.message()));
    }

    private void publish(int events) {
        for (var i = 0; i < events; i++) {
            manager.publishLocal(ENGINE_KEY, 0, new byte[]{(byte) i}, 1_000L + i).onFailure(cause -> fail(cause.message()));
        }
    }

    private static StreamConfig config(int partitions) {
        return StreamConfig.streamConfig(ENGINE_KEY, partitions, RETENTION, "earliest");
    }

    /// A node over `manager` whose catalog holds the topic stream (`catalogKnows`) or nothing.
    private Stream<Route<?>> holderRoutes(boolean catalogKnows) {
        var store = catalogKnows
                    ? StreamRouteFixtures.storeCommitting(ENGINE_KEY, config(2))
                    : StreamRouteFixtures.emptyStore();

        return StreamRouteFixtures.routes(manager, StreamReadRouter.localOnly(manager), store, StreamNamespacesService.inMemory());
    }

    private static Object answer(Probe probe, Stream<Route<?>> routes) {
        return RouteProbe.run(routes, probe.route, probe.path(ENGINE_KEY), Map.of())
                         .onFailure(cause -> fail(probe + " must read the topic stream by its raw key: " + cause.message()))
                         .unwrap();
    }

    private static HttpStatus statusOf(Probe probe, Stream<Route<?>> routes) {
        return RouteProbe.problemStatus(RouteProbe.failureOf(routes, probe.route, probe.path(ENGINE_KEY), Map.of()));
    }
}

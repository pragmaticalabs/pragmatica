// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.ArrayList;
import java.util.List;

import org.pragmatica.aether.api.OperationalEvent;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue.RouteEntry;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1206: two artifacts serving one HTTP route is announced when it begins and when it ends, derived from the committed
/// route table. A route is claimed by an artifact BASE, so two versions of one slice (a rolling redeploy) never collide.
class RouteCollisionAnnouncerTest {
    private static final NodeId N1 = new NodeId("node-1");
    private static final NodeId N2 = new NodeId("node-2");

    private final List<OperationalEvent> events = new ArrayList<>();
    private final RouteCollisionAnnouncer announcer = RouteCollisionAnnouncer.routeCollisionAnnouncer(events::add);

    private static NodeRoutesKey key(NodeId node, String artifact) {
        return NodeRoutesKey.nodeRoutesKey(node, Artifact.artifact(artifact).unwrap());
    }

    private static RouteEntry route(String method, String prefix, int arity, long registeredAt, String state) {
        return new RouteEntry(method, prefix, "handle", state, 100, registeredAt, "PUBLIC", "PUBLIC", arity, List.of());
    }

    private static NodeRoutesValue routes(RouteEntry... entries) {
        return NodeRoutesValue.nodeRoutesValue(List.of(entries), Epoch.ZERO);
    }

    private void put(NodeRoutesKey key, NodeRoutesValue value) {
        announcer.onRoutesPut(new ValuePut<>(new KVCommand.Put<>(key, value), Option.none()));
    }

    private void remove(NodeRoutesKey key) {
        announcer.onRoutesRemove(new ValueRemove<>(new KVCommand.Remove<>(key), Option.none()));
    }

    @Test
    void twoArtifactsClaimingOneRoute_announcesCollisionOnce_naming_bothBases() {
        put(key(N1, "org.a:one:1.0.0"), routes(route("GET", "/api/x/", 0, 10, "ACTIVE")));
        assertThat(events).as("one claimant is no collision").isEmpty();

        put(key(N2, "org.b:two:1.0.0"), routes(route("GET", "/api/x/", 0, 20, "ACTIVE")));

        assertThat(events).singleElement().isInstanceOfSatisfying(OperationalEvent.RoutePrefixCollision.class, event -> {
            assertThat(event.method()).isEqualTo("GET");
            assertThat(event.prefix()).isEqualTo("/api/x/");
            assertThat(event.artifacts()).containsExactly("org.a:one", "org.b:two");
        });

        put(key(N2, "org.b:two:1.0.0"), routes(route("GET", "/api/x/", 0, 20, "ACTIVE")));
        assertThat(events).as("a repeated (replayed) notification announces nothing").hasSize(1);
    }

    @Test
    void rollingRedeploy_twoVersionsOfOneArtifact_isNoCollision() {
        put(key(N1, "org.a:one:1.0.0"), routes(route("GET", "/api/x/", 0, 10, "ACTIVE")));
        put(key(N1, "org.a:one:2.0.0"), routes(route("GET", "/api/x/", 0, 20, "ACTIVE")));
        put(key(N2, "org.a:one:1.0.0"), routes(route("GET", "/api/x/", 0, 30, "ACTIVE")));

        assertThat(events).isEmpty();
    }

    @Test
    void clearing_announcesTheRecovery_andOnlyWhenTheLastExtraClaimantGoes() {
        put(key(N1, "org.a:one:1.0.0"), routes(route("GET", "/api/x/", 0, 10, "ACTIVE")));
        put(key(N2, "org.a:one:1.0.0"), routes(route("GET", "/api/x/", 0, 11, "ACTIVE")));
        put(key(N1, "org.b:two:1.0.0"), routes(route("GET", "/api/x/", 0, 20, "ACTIVE")));
        assertThat(events).hasSize(1);

        remove(key(N2, "org.a:one:1.0.0"));
        assertThat(events).as("org.a still claims it from node-1").hasSize(1);

        remove(key(N1, "org.b:two:1.0.0"));

        assertThat(events).hasSize(2);
        assertThat(events.getLast()).isInstanceOfSatisfying(OperationalEvent.RoutePrefixCollisionCleared.class,
                                                            event -> assertThat(event.artifacts()).containsExactly("org.a:one", "org.b:two"));
    }

    /// A collision that clears and later comes back (a republish stamps new entries) is a different event: its id cannot repeat.
    @Test
    void aCollisionThatComesBack_hasAnIdOfItsOwn_andNoEventIdRepeats() {
        put(key(N1, "org.a:one:1.0.0"), routes(route("GET", "/api/x/", 0, 10, "ACTIVE")));
        put(key(N1, "org.b:two:1.0.0"), routes(route("GET", "/api/x/", 0, 20, "ACTIVE")));
        remove(key(N1, "org.b:two:1.0.0"));
        put(key(N1, "org.b:two:1.0.0"), routes(route("GET", "/api/x/", 0, 30, "ACTIVE")));

        var ids = events.stream().map(event -> switch (event) {
            case OperationalEvent.RoutePrefixCollision c -> c.eventId();
            case OperationalEvent.RoutePrefixCollisionCleared c -> c.eventId();
            default -> "other";
        }).toList();

        assertThat(ids).hasSize(3).doesNotHaveDuplicates();
    }

    /// Different shapes on one prefix, different methods and different prefixes are different routes.
    @Test
    void differentRoutes_areNotACollision() {
        put(key(N1, "org.a:one:1.0.0"), routes(route("GET", "/orders/", 0, 10, "ACTIVE"), route("GET", "/orders/", 1, 10, "ACTIVE")));
        put(key(N1, "org.b:two:1.0.0"),
            routes(route("POST", "/orders/", 0, 20, "ACTIVE"), route("GET", "/orders/export/", 0, 20, "ACTIVE"), route("GET", "/orders/", 2, 20, "ACTIVE")));

        assertThat(events).isEmpty();
    }

    @Test
    void aRouteThatIsNotActive_isNotAClaim() {
        put(key(N1, "org.a:one:1.0.0"), routes(route("GET", "/api/x/", 0, 10, "ACTIVE")));
        put(key(N1, "org.b:two:1.0.0"), routes(route("GET", "/api/x/", 0, 20, "DRAINING")));

        assertThat(events).isEmpty();
    }
}

// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue.RouteEntry;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.GenerationSnapshotSource;
import org.pragmatica.consensus.topology.MembershipView;
import org.pragmatica.lang.Option;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;


/// Hard-reject fence tests: verify that PUTs with stale `observedCoreEpoch` are
/// dropped at the listener and never land in the projection caches.
///
/// See `aether/docs/specs/cluster-generation-spec.md` §12.
class HttpRouteRegistryEpochFenceHardRejectTest {
    private static final NodeId NODE_A = NodeId.nodeId("node-a").unwrap();
    private static final NodeId NODE_B = NodeId.nodeId("node-b").unwrap();
    private static final Artifact TEST_ARTIFACT = Artifact.artifact("com.example:svc:1.0.0").unwrap();

    @Test
    void stalePut_doesNotLandInProjection() {
        var source = new FixedTermSource(20L);
        var registry = HttpRouteRegistry.httpRouteRegistry(source);

        registry.onNodeRoutesPut(putWithEpoch(NODE_A, Epoch.epoch(5L, 0L)));

        assertThat(registry.staleFenceObservationCount()).isEqualTo(1L);
        assertThat(registry.findRoute("GET", "/users/").isPresent())
                .as("stale-epoch route must be rejected at listener")
                .isFalse();
        assertThat(registry.allRoutes()).isEmpty();
    }

    @Test
    void freshPutAfterStalePut_stillLandsCleanly() {
        var source = new FixedTermSource(20L);
        var registry = HttpRouteRegistry.httpRouteRegistry(source);

        registry.onNodeRoutesPut(putWithEpoch(NODE_A, Epoch.epoch(5L, 0L)));
        registry.onNodeRoutesPut(putWithEpoch(NODE_B, Epoch.epoch(20L, 5L)));

        assertThat(registry.staleFenceObservationCount()).isEqualTo(1L);
        assertThat(registry.findRoute("GET", "/users/").isPresent())
                .as("fresh-epoch route from later PUT must be projected")
                .isTrue();
        var info = registry.findRoute("GET", "/users/").unwrap();
        assertThat(info.nodes()).containsExactly(NODE_B);
    }

    @Test
    void multipleStalePuts_allRejected_noProjectionMutations() {
        var source = new FixedTermSource(50L);
        var registry = HttpRouteRegistry.httpRouteRegistry(source);

        registry.onNodeRoutesPut(putWithEpoch(NODE_A, Epoch.epoch(10L, 0L)));
        registry.onNodeRoutesPut(putWithEpoch(NODE_A, Epoch.epoch(15L, 0L)));
        registry.onNodeRoutesPut(putWithEpoch(NODE_B, Epoch.epoch(20L, 0L)));

        assertThat(registry.staleFenceObservationCount()).isEqualTo(3L);
        assertThat(registry.allRoutes()).isEmpty();
    }

    @Test
    void noopSnapshotSource_acceptsEverything() {
        var registry = HttpRouteRegistry.httpRouteRegistry(GenerationSnapshotSource.noop());

        registry.onNodeRoutesPut(putWithEpoch(NODE_A, Epoch.ZERO));

        assertThat(registry.staleFenceObservationCount()).isZero();
        assertThat(registry.findRoute("GET", "/users/").isPresent()).isTrue();
    }

    private static ValuePut<NodeRoutesKey, NodeRoutesValue> putWithEpoch(NodeId nodeId, Epoch epoch) {
        var key = NodeRoutesKey.nodeRoutesKey(nodeId, TEST_ARTIFACT);
        var route = RouteEntry.activeRoute("GET", "/users/", "list");
        var value = NodeRoutesValue.nodeRoutesValue(List.of(route), epoch);
        var command = new KVCommand.Put<>(key, value);
        return new ValuePut<>(command, Option.none());
    }

    private static final class FixedTermSource implements GenerationSnapshotSource {
        private volatile long term;

        FixedTermSource(long initialTerm) {
            this.term = initialTerm;
        }

        @Override public Option<MembershipView> currentMembershipView() {
            return Option.none();
        }

        @Override public long observedRabiaTerm() {
            return term;
        }
    }
}

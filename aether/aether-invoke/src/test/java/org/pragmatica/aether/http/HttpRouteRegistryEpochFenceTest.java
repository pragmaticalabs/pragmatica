// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
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

class HttpRouteRegistryEpochFenceTest {
    private static final NodeId NODE_A = NodeId.nodeId("node-a").unwrap();
    private static final Artifact TEST_ARTIFACT = Artifact.artifact("com.example:svc:1.0.0").unwrap();

    private FixedTermSource snapshotSource;

    @BeforeEach
    void setUp() {
        snapshotSource = new FixedTermSource(0L);
    }

    @Nested
    class StaleFenceDetection {
        @Test
        void putWithFreshEpoch_notFlagged() {
            snapshotSource.setTerm(10L);
            var registry = HttpRouteRegistry.httpRouteRegistry(snapshotSource);

            registry.onNodeRoutesPut(putWithEpoch(Epoch.epoch(10L, 0L)));

            assertThat(registry.staleFenceObservationCount()).isZero();
            assertThat(registry.findRoute("GET", "/users/").isPresent()).isTrue();
        }

        @Test
        void putWithStaleEpoch_flaggedButAccepted() {
            snapshotSource.setTerm(20L);
            var registry = HttpRouteRegistry.httpRouteRegistry(snapshotSource);

            registry.onNodeRoutesPut(putWithEpoch(Epoch.epoch(5L, 0L)));

            assertThat(registry.staleFenceObservationCount()).isEqualTo(1L);
            assertThat(registry.findRoute("GET", "/users/").isPresent())
                    .as("stale update must still be projected (soft fence)")
                    .isTrue();
        }

        @Test
        void putAtThresholdExactly_notFlagged() {
            snapshotSource.setTerm(10L);
            var registry = HttpRouteRegistry.httpRouteRegistry(snapshotSource);

            // Diff of exactly 5 is NOT flagged — only diff > 5 is stale
            registry.onNodeRoutesPut(putWithEpoch(Epoch.epoch(5L, 0L)));

            assertThat(registry.staleFenceObservationCount()).isZero();
        }

        @Test
        void putJustPastThreshold_flagged() {
            snapshotSource.setTerm(10L);
            var registry = HttpRouteRegistry.httpRouteRegistry(snapshotSource);

            // Diff of 6 is flagged
            registry.onNodeRoutesPut(putWithEpoch(Epoch.epoch(4L, 0L)));

            assertThat(registry.staleFenceObservationCount()).isEqualTo(1L);
        }

        @Test
        void multipleStalePuts_incrementCounter() {
            snapshotSource.setTerm(100L);
            var registry = HttpRouteRegistry.httpRouteRegistry(snapshotSource);

            registry.onNodeRoutesPut(putWithEpoch(Epoch.epoch(10L, 0L)));
            registry.onNodeRoutesPut(putWithEpoch(Epoch.epoch(11L, 0L)));
            registry.onNodeRoutesPut(putWithEpoch(Epoch.epoch(12L, 0L)));

            assertThat(registry.staleFenceObservationCount()).isEqualTo(3L);
        }

        @Test
        void valueAheadOfObserved_notFlagged() {
            snapshotSource.setTerm(5L);
            var registry = HttpRouteRegistry.httpRouteRegistry(snapshotSource);

            // Value's term > observed term: cannot be "stale" from snapshot's perspective
            registry.onNodeRoutesPut(putWithEpoch(Epoch.epoch(50L, 0L)));

            assertThat(registry.staleFenceObservationCount()).isZero();
        }

        @Test
        void noopSnapshotSource_neverFlags() {
            var registry = HttpRouteRegistry.httpRouteRegistry(GenerationSnapshotSource.noop());

            registry.onNodeRoutesPut(putWithEpoch(Epoch.epoch(0L, 0L)));
            registry.onNodeRoutesPut(putWithEpoch(Epoch.epoch(100L, 0L)));

            assertThat(registry.staleFenceObservationCount()).isZero();
        }
    }

    @Nested
    class BackwardCompatibility {
        @Test
        void zeroArgFactory_usesNoopSource() {
            var registry = HttpRouteRegistry.httpRouteRegistry();

            registry.onNodeRoutesPut(putWithEpoch(Epoch.ZERO));

            assertThat(registry.staleFenceObservationCount()).isZero();
            assertThat(registry.findRoute("GET", "/users/").isPresent()).isTrue();
        }
    }

    private static ValuePut<NodeRoutesKey, NodeRoutesValue> putWithEpoch(Epoch epoch) {
        var key = NodeRoutesKey.nodeRoutesKey(NODE_A, TEST_ARTIFACT);
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

        void setTerm(long newTerm) {
            this.term = newTerm;
        }

        @Override public Option<MembershipView> currentMembershipView() {
            return Option.none();
        }

        @Override public long observedRabiaTerm() {
            return term;
        }
    }
}

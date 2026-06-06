// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.cluster.metrics;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPing;
import org.pragmatica.consensus.ConsensusCodecs;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.serialization.SliceCodec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.pragmatica.serialization.FrameworkCodecs.frameworkCodecs;

/// `ClusterSyncPing` carries the leader's GLOBAL `drainNodes` set alongside the global
/// `evictionHints` set. The compact constructor defensively copies both (null → empty); the
/// legacy factory defaults `drainNodes` to the empty set.
class ClusterSyncPingTest {
    private static final NodeId SENDER = NodeId.nodeId("sender").unwrap();
    private static final NodeId PEER_A = NodeId.nodeId("peer-a").unwrap();
    private static final NodeId PEER_B = NodeId.nodeId("peer-b").unwrap();

    @Test
    void constructor_nullDrainNodes_defaultsToEmptySet() {
        var ping = new ClusterSyncPing(SENDER, Map.of(), 1L, 1L, 0L, Set.of(), null);

        assertThat(ping.drainNodes()).isEmpty();
    }

    @Test
    void constructor_drainNodes_isPreserved() {
        var ping = new ClusterSyncPing(SENDER, Map.of(), 1L, 1L, 0L, Set.of(), Set.of(PEER_A, PEER_B));

        assertThat(ping.drainNodes()).containsExactlyInAnyOrder(PEER_A, PEER_B);
    }

    @Test
    void constructor_drainNodes_isDefensivelyCopied() {
        var mutable = new HashSet<NodeId>();
        mutable.add(PEER_A);
        var ping = new ClusterSyncPing(SENDER, Map.of(), 1L, 1L, 0L, Set.of(), mutable);

        mutable.add(PEER_B);

        assertThat(ping.drainNodes()).containsExactly(PEER_A);
    }

    @Test
    void constructor_drainNodes_isImmutable() {
        var ping = new ClusterSyncPing(SENDER, Map.of(), 1L, 1L, 0L, Set.of(), Set.of(PEER_A));

        assertThatThrownBy(() -> ping.drainNodes().add(PEER_B))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void legacyFactory_defaultsDrainNodesToEmptySet() {
        var ping = ClusterSyncPing.clusterSyncPing(SENDER, Map.of());

        assertThat(ping.drainNodes()).isEmpty();
        assertThat(ping.evictionHints()).isEmpty();
    }

    @Test
    void constructor_nullDispatchedNodes_defaultsToEmptySet() {
        var ping = new ClusterSyncPing(SENDER, Map.of(), 1L, 1L, 0L, Set.of(), Set.of(), Map.of(), null);

        assertThat(ping.dispatchedNodes()).isEmpty();
    }

    @Test
    void backCompatConstructors_defaultDispatchedNodesToEmptySet() {
        var sevenArg = new ClusterSyncPing(SENDER, Map.of(), 1L, 1L, 0L, Set.of(), Set.of(PEER_A));
        var eightArg = new ClusterSyncPing(SENDER, Map.of(), 1L, 1L, 0L, Set.of(), Set.of(PEER_A), Map.of());

        assertThat(sevenArg.dispatchedNodes()).isEmpty();
        assertThat(eightArg.dispatchedNodes()).isEmpty();
    }

    /// Codec round-trip: a `ClusterSyncPing` carrying a non-empty `dispatchedNodes` set serializes
    /// and deserializes to an equal value through the generated `@Codec` machinery (mirrors how the
    /// existing `drainNodes` / `evictionHints` `Set<NodeId>` fields are encoded). Proves the new
    /// field crosses the wire.
    @Test
    void codecRoundTrip_dispatchedNodes_preservedAcrossSerialization() {
        var codec = pingCodec();
        var original = new ClusterSyncPing(SENDER,
                                           Map.of(),
                                           7L,
                                           7L,
                                           3L,
                                           Set.of(PEER_A),
                                           Set.of(PEER_B),
                                           Map.of(),
                                           Set.of(PEER_A, PEER_B));

        var buffer = Unpooled.buffer();
        codec.write(buffer, original);
        ClusterSyncPing decoded = codec.read(buffer);

        assertThat(decoded.dispatchedNodes()).containsExactlyInAnyOrder(PEER_A, PEER_B);
        assertThat(decoded).isEqualTo(original);
    }

    /// Codec round-trip with an EMPTY dispatched set still deserializes to an equal value (the empty
    /// set is the steady-state on followers and on a leader with nothing in flight).
    @Test
    void codecRoundTrip_emptyDispatchedNodes_preserved() {
        var codec = pingCodec();
        var original = new ClusterSyncPing(SENDER, Map.of(), 2L, 2L, 0L, Set.of(), Set.of(), Map.of(), Set.of());

        var buffer = Unpooled.buffer();
        codec.write(buffer, original);
        ClusterSyncPing decoded = codec.read(buffer);

        assertThat(decoded.dispatchedNodes()).isEmpty();
        assertThat(decoded).isEqualTo(original);
    }

    /// Codec resolved over the framework codecs + the consensus codecs (`NodeId`) + the metrics
    /// module codecs (`ClusterSyncPing`), mirroring how the production wire serializer is assembled.
    private static SliceCodec pingCodec() {
        var codecs = new ArrayList<SliceCodec.TypeCodec<?>>();
        codecs.addAll(ConsensusCodecs.CODECS);
        codecs.addAll(MetricsCodecs.CODECS);
        return SliceCodec.sliceCodec(frameworkCodecs(), codecs);
    }
}

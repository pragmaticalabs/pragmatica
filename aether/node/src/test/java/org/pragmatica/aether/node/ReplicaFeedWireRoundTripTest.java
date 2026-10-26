// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.Map;
import java.util.Set;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.LogLevelKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.metrics.ClusterDirectory;
import org.pragmatica.cluster.metrics.ClusterDirectory.DhtReplication;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPing;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPong;
import org.pragmatica.cluster.metrics.DirectoryVersion;
import org.pragmatica.cluster.metrics.MetricObservation;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaEntries;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaHeartbeat;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaRefused;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaSnapshotChunk;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaSnapshotRequest;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.ReplicaSubscribe;
import org.pragmatica.cluster.replica.ReplicaFeedMessage.SubscribeMode;
import org.pragmatica.cluster.state.kvstore.AnchorPair;
import org.pragmatica.cluster.state.kvstore.CommitRing.RefusalReason;
import org.pragmatica.cluster.state.kvstore.IncarnationTag;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.ReplicaEntry;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.FrameworkCodecs;

import static org.assertj.core.api.Assertions.assertThat;

/// The replica feed's messages and the extended ClusterSync ping and pong survive the node codec: each is encoded and decoded through the
/// aggregated `NodeCodecs`, including the effects a ring entry carries (plain `Put`/`Remove` of real keys and values) and the directory.
/// Mutation: drop the `ReplicaCodecs` line of `NodeCodecs` (every feed message fails to resolve).
class ReplicaFeedWireRoundTripTest {
    private static final NodeId SENDER = new NodeId("core-1");
    private static final IncarnationTag TAG = IncarnationTag.incarnationTag(3L, "01J-incarnation");
    private static final List<AnchorPair> ANCHOR = List.of(new AnchorPair(7L, "batch-7"), new AnchorPair(9L, "batch-9"));

    private static <T> T roundTrip(T message) {
        var codec = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
        var buffer = Unpooled.buffer();

        codec.write(buffer, message);

        return codec.read(buffer);
    }

    private static ReplicaEntry effectEntry() {
        var key = new LogLevelKey("org.example");
        List<KVCommand<?>> effects = List.of(new KVCommand.Put<AetherKey, AetherValue>(key, AetherValue.LogLevelValue.logLevelValue("org.example", "DEBUG")),
                                             new KVCommand.Remove<AetherKey>(new LogLevelKey("org.gone")));

        return new ReplicaEntry(TAG, 7L, 9L, "batch-9", effects);
    }

    @Test
    void subscribe_survives() {
        var message = new ReplicaSubscribe(SENDER, 42L, TAG, 10L, ANCHOR, SubscribeMode.FROM_ANCHOR);

        assertThat(roundTrip(message)).isEqualTo(message);
        assertThat(roundTrip(new ReplicaSubscribe(SENDER, 1L, IncarnationTag.NONE, 0L, List.of(), SubscribeMode.LIVE_ONLY))).as("an unanchored live subscribe").isNotNull();
    }

    @Test
    void entries_surviveWithTheirEffectsAndTheRangeMarkers() {
        var marker = new ReplicaEntry(TAG, 10L, 12L, ReplicaEntry.NONE, List.of());
        var message = new ReplicaEntries(SENDER, 42L, 5L, List.of(effectEntry(), marker), 13L);
        var back = roundTrip(message);

        assertThat(back).isEqualTo(message);
        assertThat(back.entries().get(0).effects()).hasSize(2).first().isInstanceOf(KVCommand.Put.class);
        assertThat(back.entries().get(1).isMarker()).isTrue();
    }

    @Test
    void refusal_survives_withEveryReason() {
        for (var reason : RefusalReason.values()) {
            var message = new ReplicaRefused(SENDER, 42L, reason, 5L, TAG);

            assertThat(roundTrip(message)).isEqualTo(message);
        }
    }

    @Test
    void snapshotRequestAndChunk_survive() {
        var request = new ReplicaSnapshotRequest(SENDER, 42L, TAG, 11L, "abc123", 65536L);
        var chunk = new ReplicaSnapshotChunk(SENDER, 42L, TAG, 11L, ANCHOR, "abc123", 100_000L, 65536L, new byte[] {1, 2, 3, 4});
        var back = roundTrip(chunk);

        assertThat(roundTrip(request)).isEqualTo(request);
        assertThat(back.bytes()).containsExactly(1, 2, 3, 4);
        assertThat(back.anchor()).isEqualTo(ANCHOR);
        assertThat(back.total()).isEqualTo(100_000L);
        assertThat(back.hash()).isEqualTo("abc123");
    }

    @Test
    void heartbeat_survives() {
        var message = new ReplicaHeartbeat(SENDER, 42L, 6L, TAG, 13L, true, 13L);

        assertThat(roundTrip(message)).isEqualTo(message);
    }

    private static ClusterDirectory directory() {
        return new ClusterDirectory(4L,
                                    1L,
                                    5L,
                                    0L,
                                    List.of(NodeInfo.nodeInfo(SENDER, new NodeAddress("localhost", 7000), Map.of(NodeInfo.LABEL_ROLE, "core"))),
                                    new DhtReplication(3, 2, 3, 2, 8L, -1L, 0, 0, true));
    }

    /// C-3. The directory pair and payload are the last two components, and a ping with and without a directory both survive. Mutation:
    /// reorder the two components, or make `directory` a non-Option.
    @Test
    void clusterSyncPing_survives_withAndWithoutADirectory() {
        var without = new ClusterSyncPing(SENDER, Map.of(), 5L, 1L, 5L, 0L, Set.of(), Set.of(), Map.of(), Set.of(), true, true, DirectoryVersion.NONE, Option.none());
        var with = new ClusterSyncPing(SENDER, Map.of(), 5L, 1L, 5L, 0L, Set.of(), Set.of(), Map.of(), Set.of(), true, true, directory().version(), Option.some(directory()));

        assertThat(roundTrip(without)).isEqualTo(without);
        assertThat(roundTrip(with)).isEqualTo(with);
        assertThat(roundTrip(with).directory().unwrap().nodes()).hasSize(1);
    }

    @Test
    void clusterSyncPong_survives_withAndWithoutADirectory() {
        var observation = MetricObservation.metricObservation(1L, 1L, 1_000L, Map.of());
        var held = directory().version();
        var without = new ClusterSyncPong(SENDER, observation, 1L, 5L, 1L, 5L, 0L, "READY", List.of(), List.of(), List.of(), Option.none(), DirectoryVersion.NONE, Option.none());
        var with = new ClusterSyncPong(SENDER, observation, 1L, 5L, 1L, 5L, 0L, "READY", List.of(), List.of(), List.of(), Option.none(), held, Option.some(directory()));

        assertThat(roundTrip(without)).isEqualTo(without);
        assertThat(roundTrip(with)).isEqualTo(with);
        assertThat(roundTrip(with).observedDirectoryGeneration()).isEqualTo(held);
    }
}

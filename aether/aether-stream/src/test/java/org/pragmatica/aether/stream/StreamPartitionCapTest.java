// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.aether.stream.StreamPartitionManager.Exhaustion;
import org.pragmatica.aether.stream.StreamPartitionManager.HydrationSnapshot;
import org.pragmatica.aether.stream.StreamPartitionManager.StreamHydration;
import org.pragmatica.aether.stream.replication.ReplicaSetController.Role;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.lang.Option;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #265 increment 4 — partition caps (spec §7/§10). Verifies (a) the runtime PRE-COMMIT per-stream ceiling
/// (`createStream` of an over-ceiling config is rejected with [StreamError.PartitionCeilingExceeded] before
/// any commit — mirroring the build-time `StreamConfigParser` check), (b) the cluster-wide aggregate guard
/// `100 × nodes × maxDeclaredReplicas` (rejected with [StreamError.PartitionCapExceeded], EXISTING streams
/// counted, and SKIPPED where the cluster size is unknown), (c) the follower over-ceiling event — a
/// committed over-ceiling config does NOT reject the commit but emits a `CONFIG_OVER_CEILING` event through
/// the existing exhaustion sink and sets the snapshot's `overCeiling` / `configOverCeilingStreams` flags —
/// and (d) the derived cap values surfaced on the hydration snapshot. Placement is pinned to NONE so no
/// rings materialize (metadata-only), keeping the cap assertions fast and independent of the budget path;
/// the config still counts toward the aggregate (which reads declared `partitions × replicas`).
class StreamPartitionCapTest {

    /// Small-footprint retention (100 count, 64 KiB, 60s) — the cap checks read declared partition counts,
    /// never a materialized ring, so the retention only bounds any incidental floor.
    private static StreamConfig capConfig(String name, int partitions) {
        var retention = RetentionPolicy.retentionPolicy(100, 64L * 1024, 60_000L);
        return StreamConfig.streamConfig(name, partitions, retention, "latest");
    }

    private static StreamHydration find(HydrationSnapshot snapshot, String name) {
        return snapshot.streams()
                       .stream()
                       .filter(s -> s.name().equals(name))
                       .findFirst()
                       .orElseThrow(() -> new AssertionError("stream not in snapshot: " + name));
    }

    private static ValuePut<StreamConfigKey, StreamConfigValue> streamConfigPut(StreamConfig config) {
        var key = StreamConfigKey.streamConfigKey(config.name());
        var value = StreamConfigValue.streamConfigValue(config);
        var put = new KVCommand.Put<>(key, value);

        return new ValuePut<>(put, Option.none());
    }

    @Nested
    class PerStreamCeiling {

        @Test
        void createStream_overCeiling_rejectedPreCommit() {
            var manager = streamPartitionManager(Long.MAX_VALUE);
            manager.placementRoleSupplier((_, _) -> Role.NONE);
            try {
                manager.createStream(capConfig("huge", 2000))
                       .onSuccess(_ -> fail("Expected PartitionCeilingExceeded"))
                       .onFailure(cause -> assertThat(cause).isEqualTo(new StreamError.PartitionCeilingExceeded("huge",
                                                                                                                2000,
                                                                                                                1024)));

                assertThat(manager.hydrationSnapshot().streams()).isEmpty();
            } finally {
                manager.close();
            }
        }

        @Test
        void createStream_atCeiling_admitted() {
            var manager = streamPartitionManager(Long.MAX_VALUE);
            manager.placementRoleSupplier((_, _) -> Role.NONE);
            try {
                manager.createStream(capConfig("edge", 1024)).onFailure(_ -> fail("Expected success at the ceiling"));

                assertThat(manager.hydrationSnapshot().streams()).hasSize(1);
            } finally {
                manager.close();
            }
        }
    }

    @Nested
    class ClusterAggregateGuard {

        @Test
        void createStream_aggregateBreach_rejected_existingStreamsCounted() {
            var manager = streamPartitionManager(Long.MAX_VALUE);
            manager.placementRoleSupplier((_, _) -> Role.NONE);
            manager.clusterSizeSupplier(() -> 1);   // guard = 100 × 1 node × 1 max-replica = 100
            try {
                manager.createStream(capConfig("alpha", 60)).onFailure(_ -> fail("Expected 60 slots within guard 100"));

                // alpha already holds 60 slots; beta's 60 pushes the projected total to 120 > 100.
                manager.createStream(capConfig("beta", 60))
                       .onSuccess(_ -> fail("Expected PartitionCapExceeded"))
                       .onFailure(cause -> assertThat(cause).isEqualTo(new StreamError.PartitionCapExceeded("beta",
                                                                                                            120L,
                                                                                                            100L,
                                                                                                            1,
                                                                                                            1)));

                var snapshot = manager.hydrationSnapshot();
                assertThat(snapshot.streams()).hasSize(1);
                assertThat(snapshot.currentAggregatePartitionSlots()).isEqualTo(60L);
            } finally {
                manager.close();
            }
        }

        @Test
        void createStream_aggregateSkipped_whenClusterSizeUnknown() {
            var manager = streamPartitionManager(Long.MAX_VALUE);
            manager.placementRoleSupplier((_, _) -> Role.NONE);   // default clusterSizeSupplier == 0 (unknown)
            try {
                // 500 slots would breach a small guard, but with an unknown cluster size the aggregate guard
                // is not enforced (spec §7 — enforce where knowable). Only the ceiling (1024) still applies.
                manager.createStream(capConfig("wide", 500)).onFailure(_ -> fail("Aggregate guard must skip when cluster size unknown"));

                var snapshot = manager.hydrationSnapshot();
                assertThat(snapshot.clusterAggregateGuard()).isEqualTo(-1L);
                assertThat(snapshot.aggregateHeadroom()).isEqualTo(-1L);
                assertThat(snapshot.currentAggregatePartitionSlots()).isEqualTo(500L);
            } finally {
                manager.close();
            }
        }
    }

    @Nested
    class FollowerCeilingEvent {

        @Test
        void onStreamConfigPut_overCeiling_emitsEvent_doesNotReject() {
            var sink = new RecordingSink();
            var manager = streamPartitionManager(64 * 1024 * 1024L);
            manager.exhaustionSink(sink);
            manager.placementRoleSupplier((_, _) -> Role.NONE);   // metadata-only: no rings for 2000 partitions
            try {
                manager.onStreamConfigPut(streamConfigPut(capConfig("committed-huge", 2000)));

                var ceilingEvents = sink.events.stream().filter(e -> e.phase() == Exhaustion.Phase.CONFIG_OVER_CEILING).toList();

                assertThat(ceilingEvents).hasSize(1);
                var event = ceilingEvents.getFirst();
                assertThat(event.partitions()).isEqualTo(2000);
                assertThat(event.summary()).contains("over per-stream partition ceiling");
                assertThat(event.details()).containsEntry("phase", "config-over-ceiling")
                                           .containsEntry("ceiling", "1024")
                                           .containsEntry("declaredPartitions", "2000");

                // The follower does NOT reject — the committed config is present (metadata-only) and flagged.
                var snapshot = manager.hydrationSnapshot();
                assertThat(snapshot.configOverCeilingStreams()).isEqualTo(1);
                var view = find(snapshot, "committed-huge");
                assertThat(view.overCeiling()).isTrue();
                assertThat(view.ringsMaterialized()).isEqualTo(0);
            } finally {
                manager.close();
            }
        }

        @Test
        void onStreamConfigPut_withinCeiling_emitsNoCeilingEvent() {
            var sink = new RecordingSink();
            var manager = streamPartitionManager(64 * 1024 * 1024L);
            manager.exhaustionSink(sink);
            manager.placementRoleSupplier((_, _) -> Role.NONE);
            try {
                manager.onStreamConfigPut(streamConfigPut(capConfig("committed-ok", 4)));

                assertThat(sink.events.stream().anyMatch(e -> e.phase() == Exhaustion.Phase.CONFIG_OVER_CEILING)).isFalse();
                assertThat(manager.hydrationSnapshot().configOverCeilingStreams()).isEqualTo(0);
                assertThat(find(manager.hydrationSnapshot(), "committed-ok").overCeiling()).isFalse();
            } finally {
                manager.close();
            }
        }
    }

    @Nested
    class CapObservability {

        @Test
        void hydrationSnapshot_exposesDerivedCapValues() {
            var manager = streamPartitionManager(Long.MAX_VALUE);
            manager.placementRoleSupplier((_, _) -> Role.NONE);
            manager.clusterSizeSupplier(() -> 5);   // guard = 100 × 5 nodes × 1 max-replica = 500
            try {
                manager.createStream(capConfig("orders", 4)).onFailure(_ -> fail("Expected success"));

                var snapshot = manager.hydrationSnapshot();

                assertThat(snapshot.perStreamCeiling()).isEqualTo(1024);
                assertThat(snapshot.clusterAggregateGuard()).isEqualTo(500L);
                assertThat(snapshot.currentAggregatePartitionSlots()).isEqualTo(4L);
                assertThat(snapshot.aggregateHeadroom()).isEqualTo(496L);
                assertThat(snapshot.configOverCeilingStreams()).isEqualTo(0);
            } finally {
                manager.close();
            }
        }
    }

    private static final class RecordingSink implements Consumer<Exhaustion> {
        private final List<Exhaustion> events = new CopyOnWriteArrayList<>();

        @Override
        public void accept(Exhaustion exhaustion) {
            events.add(exhaustion);
        }
    }
}

// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.fence.OwnershipDomain;
import org.pragmatica.aether.slice.fence.OwnershipEpochHighWater;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import io.netty.buffer.ByteBuf;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

class StreamPartitionManagerTest {

    private StreamPartitionManager manager;

    @BeforeEach
    void setUp() {
        manager = streamPartitionManager(Long.MAX_VALUE);
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    /// #336 owner-epoch fence on the recovery/backfill seam. The A4 recovery seam (`streamPartitionRecovery`)
    /// must stamp appends with the COMMITTED owner epoch a live publish would, not `Epoch.ZERO`. When the
    /// partition high-water has advanced (a real ownership epoch is committed), a ZERO-stamped recovered
    /// append is strictly-older → fenced → every backfill-apply fails and the fresh owner loops in
    /// `escapeOwnerCatchup`. Stamping the committed epoch (equal-or-newer) passes the fence; cold-start ZERO
    /// vs the fence's ZERO default is equal → passes, so fresh-stream recovery is not regressed.
    @Nested
    class RecoveryEpochFence {
        private static final String FENCE_STREAM = "orders";
        private static final int FENCE_PARTITION = 0;

        @Test
        void appendRecovered_zeroEpoch_rejectedWhenHighWaterAdvanced() {
            // Pre-fix behavior: the 4-arg recovery overload stamps Epoch.ZERO. With the committed high-water at
            // 1:3, 0:0 is STRICTLY older → the append is fenced (StaleEpochAppend) — the real-infra symptom.
            var fenced = fencedManager(highWaterAt(Epoch.epoch(1, 3)));
            assertThat(fenced.createStream(StreamConfig.streamConfig(FENCE_STREAM)).isSuccess()).isTrue();

            var rejected = fenced.appendRecovered(FENCE_STREAM, FENCE_PARTITION, "e".getBytes(), 1L);

            assertThat(rejected.isFailure()).isTrue();
            rejected.onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.StaleEpochAppend.class));
            fenced.close();
        }

        @Test
        void appendRecovered_committedEpoch_passesFenceWhenHighWaterAdvanced() {
            // The fix stamps the COMMITTED owner epoch (1:3) — equal to the high-water → passes the fence, so
            // the backfilled event lands and the fresh owner can reach CAUGHT_UP.
            var fenced = fencedManager(highWaterAt(Epoch.epoch(1, 3)));
            assertThat(fenced.createStream(StreamConfig.streamConfig(FENCE_STREAM)).isSuccess()).isTrue();

            var accepted = fenced.appendRecovered(FENCE_STREAM, FENCE_PARTITION, "e".getBytes(), 1L, Epoch.epoch(1, 3));

            assertThat(accepted.isSuccess()).isTrue();
            assertThat(accepted.or(-1L)).isEqualTo(0L); // first offset landed locally
            fenced.close();
        }

        @Test
        void appendRecovered_zeroEpoch_passesOnColdStartNoHighWater() {
            // Cold-start / no committed ownership record: currentOwnerEpoch is Epoch.ZERO and the fence has no
            // mark, so 0:0 is not strictly older than anything → passes. Fresh-stream recovery is not regressed.
            var fenced = fencedManager(freshHighWater());
            assertThat(fenced.createStream(StreamConfig.streamConfig(FENCE_STREAM)).isSuccess()).isTrue();

            var accepted = fenced.appendRecovered(FENCE_STREAM, FENCE_PARTITION, "e".getBytes(), 1L);

            assertThat(accepted.isSuccess()).isTrue();
            assertThat(accepted.or(-1L)).isEqualTo(0L);
            fenced.close();
        }

        private static StreamPartitionManager fencedManager(OwnershipEpochHighWater highWater) {
            return StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE,
                                                                 EvictionListener.NOOP,
                                                                 ReplicationManager.NONE,
                                                                 new StubClusterNode(StubApply.SUCCESS),
                                                                 highWater,
                                                                 StreamOwnerEpochSource.zero(),
                                                                 Option.none(),
                                                                 LastSealedOffsetSource.none());
        }

        private static OwnershipEpochHighWater highWaterAt(Epoch epoch) {
            var highWater = freshHighWater();

            highWater.advance(OwnershipDomain.streamPartition(FENCE_STREAM, FENCE_PARTITION), epoch);

            return highWater;
        }

        private static OwnershipEpochHighWater freshHighWater() {
            return OwnershipEpochHighWater.ownershipEpochHighWater(emptyStore());
        }

        private static KVStore<AetherKey, AetherValue> emptyStore() {
            return new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
        }

        private static Serializer stubSerializer() {
            return new Serializer() {
                @Override
                public <T> void write(ByteBuf byteBuf, T object) {}
            };
        }

        private static Deserializer stubDeserializer() {
            return new Deserializer() {
                @Override
                public <T> T read(ByteBuf byteBuf) {
                    return null;
                }
            };
        }
    }

    @Nested
    class CreateStream {

        @Test
        void createStream_success_defaultConfig() {            var config = StreamConfig.streamConfig("orders");

            manager.createStream(config)
                   .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"));

            var info = manager.streamInfo("orders");
            assertThat(info.isPresent()).isTrue();
            info.onPresent(si -> {
                assertThat(si.name()).isEqualTo("orders");
                assertThat(si.partitions()).isEqualTo(4);
                assertThat(si.totalEvents()).isEqualTo(0L);
            });
        }

        @Test
        void createStream_success_customPartitions() {
            var retention = RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 60_000);
            var config = StreamConfig.streamConfig("events", 8, retention, "earliest");

            manager.createStream(config)
                   .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"));

            manager.streamInfo("events")
                   .onPresent(si -> assertThat(si.partitions()).isEqualTo(8));
        }

        @Test
        void createStream_failure_duplicateName() {
            var config = StreamConfig.streamConfig("orders");
            manager.createStream(config);

            manager.createStream(config)
                   .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"))
                   .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.STREAM_ALREADY_EXISTS));
        }
    }

    @Nested
    class DestroyStream {

        @Test
        void destroyStream_success_existingStream() {
            manager.createStream(StreamConfig.streamConfig("orders"));

            manager.destroyStream("orders")
                   .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"));

            assertThat(manager.streamInfo("orders").isEmpty()).isTrue();
        }

        @Test
        void destroyStream_failure_nonExistentStream() {
            manager.destroyStream("nonexistent")
                   .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"))
                   .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.StreamNotFound.class));
        }

        @Test
        void destroyStream_closesBuffers() {
            var retention = RetentionPolicy.retentionPolicy(100, 4096, 60_000);
            var config = StreamConfig.streamConfig("orders", 2, retention, "latest");
            manager.createStream(config);

            // Publish some data first
            manager.publishLocal("orders", 0, "test".getBytes(), System.currentTimeMillis());

            manager.destroyStream("orders")
                   .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"));

            // Stream should no longer be accessible
            manager.publishLocal("orders", 0, "test".getBytes(), System.currentTimeMillis())
                   .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"))
                   .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.StreamNotFound.class));
        }
    }

    @Nested
    class PublishAndRead {

        @Test
        void publishLocal_success_returnsOffset() {
            var retention = RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 60_000);
            var config = StreamConfig.streamConfig("orders", 4, retention, "latest");
            manager.createStream(config);

            manager.publishLocal("orders", 0, "event-1".getBytes(), 1000L)
                   .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                   .onSuccess(offset -> assertThat(offset).isEqualTo(0L));
        }

        @Test
        void readLocal_success_afterPublish() {
            var retention = RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 60_000);
            var config = StreamConfig.streamConfig("orders", 4, retention, "latest");
            manager.createStream(config);

            manager.publishLocal("orders", 0, "event-1".getBytes(), 1000L);
            manager.publishLocal("orders", 0, "event-2".getBytes(), 2000L);

            manager.readLocal("orders", 0, 0, 10)
                   .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                   .onSuccess(events -> {
                       assertThat(events).hasSize(2);
                       assertThat(events.get(0).data()).isEqualTo("event-1".getBytes());
                       assertThat(events.get(1).data()).isEqualTo("event-2".getBytes());
                   });
        }

        @Test
        void publishLocal_preservesOrdering_acrossEvents() {
            var retention = RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 60_000);
            var config = StreamConfig.streamConfig("orders", 2, retention, "latest");
            manager.createStream(config);

            for (int i = 0; i < 10; i++) {
                manager.publishLocal("orders", 1, ("msg-" + i).getBytes(), 1000L + i);
            }

            manager.readLocal("orders", 1, 0, 10)
                   .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                   .onSuccess(events -> {
                       assertThat(events).hasSize(10);
                       for (int i = 0; i < 10; i++) {
                           assertThat(events.get(i).data()).isEqualTo(("msg-" + i).getBytes());
                           assertThat(events.get(i).offset()).isEqualTo(i);
                       }
                   });
        }

        @Test
        void readLocal_withOffset_returnsSubsequentEvents() {
            var retention = RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 60_000);
            var config = StreamConfig.streamConfig("orders", 2, retention, "latest");
            manager.createStream(config);

            manager.publishLocal("orders", 0, "a".getBytes(), 1000L);
            manager.publishLocal("orders", 0, "b".getBytes(), 2000L);
            manager.publishLocal("orders", 0, "c".getBytes(), 3000L);

            manager.readLocal("orders", 0, 1, 10)
                   .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                   .onSuccess(events -> {
                       assertThat(events).hasSize(2);
                       assertThat(events.get(0).data()).isEqualTo("b".getBytes());
                       assertThat(events.get(1).data()).isEqualTo("c".getBytes());
                   });
        }

        @Test
        void publishLocal_isolatesPartitions() {
            var retention = RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 60_000);
            var config = StreamConfig.streamConfig("orders", 3, retention, "latest");
            manager.createStream(config);

            manager.publishLocal("orders", 0, "p0-event".getBytes(), 1000L);
            manager.publishLocal("orders", 1, "p1-event".getBytes(), 2000L);
            manager.publishLocal("orders", 2, "p2-event".getBytes(), 3000L);

            manager.readLocal("orders", 0, 0, 10)
                   .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                   .onSuccess(events -> {
                       assertThat(events).hasSize(1);
                       assertThat(events.getFirst().data()).isEqualTo("p0-event".getBytes());
                   });

            manager.readLocal("orders", 1, 0, 10)
                   .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                   .onSuccess(events -> {
                       assertThat(events).hasSize(1);
                       assertThat(events.getFirst().data()).isEqualTo("p1-event".getBytes());
                   });
        }
    }

    @Nested
    class ErrorCases {

        @Test
        void publishLocal_failure_nonExistentStream() {
            manager.publishLocal("missing", 0, "data".getBytes(), 1000L)
                   .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"))
                   .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.StreamNotFound.class));
        }

        @Test
        void readLocal_failure_nonExistentStream() {
            manager.readLocal("missing", 0, 0, 10)
                   .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"))
                   .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.StreamNotFound.class));
        }

        @Test
        void publishLocal_failure_partitionOutOfRange() {
            manager.createStream(StreamConfig.streamConfig("orders"));

            manager.publishLocal("orders", 99, "data".getBytes(), 1000L)
                   .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"))
                   .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.PartitionOutOfRange.class));
        }

        @Test
        void readLocal_failure_partitionOutOfRange() {
            manager.createStream(StreamConfig.streamConfig("orders"));

            manager.readLocal("orders", -1, 0, 10)
                   .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"))
                   .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.PartitionOutOfRange.class));
        }

        @Test
        void publishLocal_failure_negativePartition() {
            manager.createStream(StreamConfig.streamConfig("orders"));

            manager.publishLocal("orders", -1, "data".getBytes(), 1000L)
                   .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"))
                   .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.PartitionOutOfRange.class));
        }
    }

    @Nested
    class ListStreams {

        @Test
        void listStreams_empty_noStreams() {
            assertThat(manager.listStreams()).isEmpty();
        }

        @Test
        void listStreams_returnsAll_multipleStreams() {
            manager.createStream(StreamConfig.streamConfig("orders"));
            manager.createStream(StreamConfig.streamConfig("events"));
            manager.createStream(StreamConfig.streamConfig("logs"));

            var streams = manager.listStreams();
            assertThat(streams).hasSize(3);
            assertThat(streams.stream().map(StreamPartitionManager.StreamInfo::name).toList())
                .containsExactlyInAnyOrder("orders", "events", "logs");
        }

        @Test
        void listStreams_reflectsEventCounts() {
            var retention = RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 60_000);
            var config = StreamConfig.streamConfig("orders", 2, retention, "latest");
            manager.createStream(config);

            manager.publishLocal("orders", 0, "a".getBytes(), 1000L);
            manager.publishLocal("orders", 0, "b".getBytes(), 2000L);
            manager.publishLocal("orders", 1, "c".getBytes(), 3000L);

            var streams = manager.listStreams();
            assertThat(streams).hasSize(1);
            assertThat(streams.getFirst().totalEvents()).isEqualTo(3L);
        }
    }

    @Nested
    class MemoryCapTests {

        @Test
        void createStream_failure_exceedsMemoryCap() {
            // Use a very small cap so even a minimal stream exceeds it
            var cappedManager = StreamPartitionManager.streamPartitionManager(1024);
            var config = StreamConfig.streamConfig("big-stream");

            cappedManager.createStream(config)
                         .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"))
                         .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.STREAM_MEMORY_EXCEEDED));

            cappedManager.close();
        }

        @Test
        void createStream_success_withinMemoryCap() {
            // 512MB cap should fit a small stream
            var cappedManager = StreamPartitionManager.streamPartitionManager(512 * 1024 * 1024L);
            var retention = RetentionPolicy.retentionPolicy(100, 4096, 60_000);
            var config = StreamConfig.streamConfig("small", 1, retention, "latest");

            cappedManager.createStream(config)
                         .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"));

            assertThat(cappedManager.totalAllocatedBytes()).isGreaterThan(0L);
            cappedManager.close();
        }

        @Test
        void destroyStream_releasesTrackedMemory() {
            var cappedManager = StreamPartitionManager.streamPartitionManager(512 * 1024 * 1024L);
            var retention = RetentionPolicy.retentionPolicy(100, 4096, 60_000);
            var config = StreamConfig.streamConfig("temp", 1, retention, "latest");

            cappedManager.createStream(config);
            var allocated = cappedManager.totalAllocatedBytes();
            assertThat(allocated).isGreaterThan(0L);

            cappedManager.destroyStream("temp");
            assertThat(cappedManager.totalAllocatedBytes()).isEqualTo(0L);

            cappedManager.close();
        }

        @Test
        void createStream_success_afterDestroyFreesMemory() {
            var retention = RetentionPolicy.retentionPolicy(100, 4096, 60_000);
            var streamBytes = (64 + (24 * 100L) + 4096) * 1;
            // Cap allows exactly one stream
            var cappedManager = StreamPartitionManager.streamPartitionManager(streamBytes + 1);

            var config1 = StreamConfig.streamConfig("first", 1, retention, "latest");
            var config2 = StreamConfig.streamConfig("second", 1, retention, "latest");

            cappedManager.createStream(config1)
                         .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"));

            // Second stream should fail — no room
            cappedManager.createStream(config2)
                         .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"))
                         .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.STREAM_MEMORY_EXCEEDED));

            // Destroy first, then second should succeed
            cappedManager.destroyStream("first");

            cappedManager.createStream(config2)
                         .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success after destroy"));

            cappedManager.close();
        }

        @Test
        void close_resetsTrackedMemory() {
            var cappedManager = StreamPartitionManager.streamPartitionManager(512 * 1024 * 1024L);
            var retention = RetentionPolicy.retentionPolicy(100, 4096, 60_000);
            cappedManager.createStream(StreamConfig.streamConfig("s1", 1, retention, "latest"));

            assertThat(cappedManager.totalAllocatedBytes()).isGreaterThan(0L);

            cappedManager.close();
            assertThat(cappedManager.totalAllocatedBytes()).isEqualTo(0L);
        }
    }

    @Nested
    class ReapIdleStreams {

        @Test
        void reapIdleStreams_updatesLastActivity_onPublish() {
            var retention = RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 60_000);
            var config = StreamConfig.streamConfig("orders", 1, retention, "latest");
            manager.createStream(config);

            manager.publishLocal("orders", 0, "event".getBytes(), 1000L);

            // Stream should still exist — lastActivity was just updated
            assertThat(manager.reapIdleStreams()).isEqualTo(0);
            assertThat(manager.streamInfo("orders").isPresent()).isTrue();
        }

        @Test
        void reapIdleStreams_reapsExpiredEmptyIdleStream() {
            // Use 1ms retention so the stream expires immediately
            var retention = RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 1);
            var config = StreamConfig.streamConfig("ephemeral", 1, retention, "latest");
            manager.createStream(config);

            // Small delay to ensure creation time + lastActivity are in the past
            busyWait(5);

            assertThat(manager.reapIdleStreams()).isEqualTo(1);
            assertThat(manager.streamInfo("ephemeral").isEmpty()).isTrue();
        }

        @Test
        void reapIdleStreams_preventsDestructionWhenPublishOccurred() {
            // Use 1ms retention so the stream expires immediately
            var retention = RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 1);
            var config = StreamConfig.streamConfig("active", 1, retention, "latest");
            manager.createStream(config);

            // Wait for creation time to expire
            busyWait(5);

            // Publish updates lastActivity — prevents reaping even though createdAt is expired
            manager.publishLocal("active", 0, "data".getBytes(), System.currentTimeMillis());

            // Stream has data and recent activity — should not be reaped
            assertThat(manager.reapIdleStreams()).isEqualTo(0);
            assertThat(manager.streamInfo("active").isPresent()).isTrue();
        }

        @Test
        void reapIdleStreams_skipsNonEmptyStreams() {
            var retention = RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 1);
            var config = StreamConfig.streamConfig("with-data", 1, retention, "latest");
            manager.createStream(config);

            manager.publishLocal("with-data", 0, "event".getBytes(), 1000L);

            busyWait(5);

            // Stream has events — should not be reaped regardless of age
            assertThat(manager.reapIdleStreams()).isEqualTo(0);
        }

        private void busyWait(long millis) {
            var deadline = System.currentTimeMillis() + millis;
            while (System.currentTimeMillis() < deadline) {Thread.onSpinWait();}
        }
    }

    @Nested
    class StreamInfoTests {

        @Test
        void streamInfo_none_nonExistentStream() {
            assertThat(manager.streamInfo("missing").isEmpty()).isTrue();
        }

        @Test
        void streamInfo_present_existingStream() {
            manager.createStream(StreamConfig.streamConfig("orders"));

            manager.streamInfo("orders")
                   .onPresent(si -> {
                       assertThat(si.name()).isEqualTo("orders");
                       assertThat(si.partitions()).isEqualTo(4);
                       assertThat(si.totalBytes()).isGreaterThan(0L);
                   });
        }
    }

    /// Fix #2 — publish-path create (`ensureStreamMaterialized`) must NOT block on the leader-pinned
    /// consensus commit, while explicit `createStream` keeps awaiting it. A `ClusterNode` whose `apply`
    /// returns an unresolved/slow Promise stands in for a still-catching-up replacement leader.
    @Nested
    class PublishPathMaterialization {

        @Test
        void ensureStreamMaterialized_returnsPromptly_whenCommitNeverResolves() {
            var node = new StubClusterNode(StubApply.NEVER);
            var withNode = streamPartitionManager(Long.MAX_VALUE, node);

            withNode.ensureStreamMaterialized(StreamConfig.streamConfig("publish-stream"))
                    .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected prompt success despite stalled commit"));

            // Entry is materialized in `streams` even though the async commit has not (and never will) resolve.
            assertThat(withNode.streamInfo("publish-stream").isPresent()).isTrue();

            withNode.close();
        }

        @Test
        void publishLocal_succeeds_afterMaterializeWithStalledCommit() {
            var node = new StubClusterNode(StubApply.NEVER);
            var withNode = streamPartitionManager(Long.MAX_VALUE, node);

            withNode.ensureStreamMaterialized(StreamConfig.streamConfig("publish-stream"))
                    .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected prompt success"));

            withNode.publishLocal("publish-stream", 0, "event".getBytes(), 1000L)
                    .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected publish to local ring to succeed"))
                    .onSuccess(offset -> assertThat(offset).isEqualTo(0L));

            withNode.close();
        }

        @Test
        void ensureStreamMaterialized_firesAtMostOneCommit_whenAlreadyCommitted() {
            var node = new StubClusterNode(StubApply.SUCCESS);
            var withNode = streamPartitionManager(Long.MAX_VALUE, node);

            withNode.ensureStreamMaterialized(StreamConfig.streamConfig("idempotent"))
                    .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"));

            // Second publish to the now-committed stream short-circuits: success with NO further consensus.
            withNode.ensureStreamMaterialized(StreamConfig.streamConfig("idempotent"))
                    .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success on already-committed stream"));

            assertThat(node.applyCount()).isEqualTo(1);

            withNode.close();
        }

        @Test
        void ensureStreamMaterialized_failsLocalMaterialization_strongWithoutAhse() {
            var node = new StubClusterNode(StubApply.SUCCESS);
            // Default NOOP eviction listener => AHSE not present.
            var withNode = streamPartitionManager(Long.MAX_VALUE, node);
            var strong = StreamConfig.streamConfig("strong-stream",
                                                   1,
                                                   RetentionPolicy.retentionPolicy(100, 4096, 60_000),
                                                   "latest",
                                                   4096,
                                                   ConsistencyMode.STRONG);

            withNode.ensureStreamMaterialized(strong)
                    .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected AHSE_REQUIRED_FOR_STRONG"))
                    .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.AHSE_REQUIRED_FOR_STRONG));

            // No consensus commit attempted for a rejected local materialization.
            assertThat(node.applyCount()).isEqualTo(0);

            withNode.close();
        }

        @Test
        void ensureStreamMaterialized_failsLocalMaterialization_floorExhaustion() {
            var node = new StubClusterNode(StubApply.SUCCESS);
            // Tiny cap so even the floor reservation cannot be admitted.
            var capped = StreamPartitionManager.streamPartitionManager(1024, node);

            capped.ensureStreamMaterialized(StreamConfig.streamConfig("too-big"))
                  .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected STREAM_MEMORY_EXCEEDED"))
                  .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.STREAM_MEMORY_EXCEEDED));

            assertThat(node.applyCount()).isEqualTo(0);

            capped.close();
        }
    }

    /// Fix #2 — explicit STREAM_CREATE (`createStream`) keeps its synchronous, awaited-commit contract.
    @Nested
    class ExplicitCreateCommit {

        @Test
        void createStream_marksCommitted_whenCommitSucceeds() {
            var node = new StubClusterNode(StubApply.SUCCESS);
            var withNode = streamPartitionManager(Long.MAX_VALUE, node);

            withNode.createStream(StreamConfig.streamConfig("explicit"))
                    .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"));

            // Committed => a duplicate explicit create short-circuits to STREAM_ALREADY_EXISTS, no re-commit.
            withNode.createStream(StreamConfig.streamConfig("explicit"))
                    .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected STREAM_ALREADY_EXISTS"))
                    .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.STREAM_ALREADY_EXISTS));

            assertThat(node.applyCount()).isEqualTo(1);

            withNode.close();
        }

        @Test
        void createStream_fails_whenCommitFails() {
            var node = new StubClusterNode(StubApply.FAILURE);
            var withNode = streamPartitionManager(Long.MAX_VALUE, node);

            withNode.createStream(StreamConfig.streamConfig("explicit-fail"))
                    .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected STREAM_CONFIG_COMMIT_FAILED"))
                    .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.STREAM_CONFIG_COMMIT_FAILED));

            withNode.close();
        }
    }

    /// Behaviour of the stubbed `ClusterNode.apply`: never-resolving (stalled leader), resolving
    /// successfully, or resolving with a failure.
    enum StubApply {
        NEVER,
        SUCCESS,
        FAILURE
    }

    /// Minimal `ClusterNode` stub for the consensus-commit decoupling tests. Only `apply` is exercised;
    /// the other methods are unused by `StreamPartitionManager`'s publish path. Counts `apply` calls so
    /// tests can assert how many consensus commits were attempted.
    static final class StubClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private final StubApply behavior;
        private final AtomicInteger applyCount = new AtomicInteger(0);

        StubClusterNode(StubApply behavior) {
            this.behavior = behavior;
        }

        int applyCount() {
            return applyCount.get();
        }

        @Override
        public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            applyCount.incrementAndGet();

            return switch (behavior) {
                case NEVER -> Promise.promise();
                case SUCCESS -> Promise.success(List.of());
                case FAILURE -> new StubCommitError().promise();
            };
        }

        @Override
        public NodeId self() {
            return NodeId.randomNodeId("stub");
        }

        @Override
        public TopologyManager topologyManager() {
            return org.junit.jupiter.api.Assertions.fail("topologyManager not used by publish path");
        }

        @Override
        public Promise<Unit> start() {
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.success(Unit.unit());
        }
    }

    record StubCommitError() implements Cause {
        @Override
        public String message() {
            return "stub commit failure";
        }
    }
}

// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Promise;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.replication.PartitionBackfill.partitionBackfill;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.CatchupResponse.catchupResponse;

class PartitionBackfillTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId SELF = NodeId.randomNodeId();
    private static final NodeId SOURCE = NodeId.randomNodeId();

    private ReplicaRegistry registry;
    private StreamPartitionManager manager;
    private StreamPartitionRecovery recovery;

    @BeforeEach
    void setUp() {
        registry = replicaRegistry();
        manager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);
        manager.createStream(StreamConfig.streamConfig("orders"));
        recovery = manager::appendRecovered;
    }

    @Nested
    class HappyPath {
        @Test
        void backfill_emptyReplica_appliesAllEventsOffsetPreserving_andFlipsCaughtUp() {
            var m = 5;
            // Source is a CAUGHT_UP peer at watermark m-1; self is freshly registered (SYNCING, -1).
            registry.registerReplica(STREAM, PARTITION, SOURCE);
            registry.updateWatermark(STREAM, PARTITION, SOURCE, m - 1);
            registry.registerReplica(STREAM, PARTITION, SELF);

            var transport = fixedSource(eventsFrom(0, m));
            var backfill = partitionBackfill(registry, recovery, transport, SELF);

            var applied = await(backfill.backfill(STREAM, PARTITION));
            assertThat(applied).isEqualTo((long) m);

            // Local partition now holds all M events, offsets 0..m-1 preserved.
            var local = manager.readLocal(STREAM, PARTITION, 0, 100)
                               .or(List.of());
            assertThat(local).hasSize(m);
            for (var i = 0; i < m; i++) {
                assertThat(local.get(i).offset()).isEqualTo((long) i);
                assertThat(new String(local.get(i).data())).isEqualTo("event-" + i);
            }

            // Self descriptor flipped to CAUGHT_UP at the source watermark.
            var self = descriptorFor(SELF);
            assertThat(self.state()).isEqualTo(ReplicationState.CAUGHT_UP);
            assertThat(self.confirmedOffset()).isEqualTo((long) (m - 1));
        }
    }

    @Nested
    class SourceSelection {
        @Test
        void backfill_noSource_failsAndStaysSyncing() {
            // Only self registered: no caught-up peer to pull from.
            registry.registerReplica(STREAM, PARTITION, SELF);
            var transport = fixedSource(eventsFrom(0, 3));
            var backfill = partitionBackfill(registry, recovery, transport, SELF);

            var result = backfill.backfill(STREAM, PARTITION)
                                 .await();

            assertThat(result.isFailure()).isTrue();
            assertThat(descriptorFor(SELF).state()).isEqualTo(ReplicationState.SYNCING);
            // Nothing applied locally.
            assertThat(manager.readLocal(STREAM, PARTITION, 0, 100).or(List.of())).isEmpty();
        }

        @Test
        void backfill_onlySyncingPeers_failsAndStaysSyncing() {
            // A peer exists but is still SYNCING (never updated to CAUGHT_UP) — not a valid source.
            registry.registerReplica(STREAM, PARTITION, SOURCE);
            registry.registerReplica(STREAM, PARTITION, SELF);
            var backfill = partitionBackfill(registry, recovery, fixedSource(eventsFrom(0, 3)), SELF);

            assertThat(backfill.backfill(STREAM, PARTITION).await().isFailure()).isTrue();
            assertThat(descriptorFor(SELF).state()).isEqualTo(ReplicationState.SYNCING);
        }
    }

    @Nested
    class FailureSafety {
        @Test
        void backfill_sourceUnreachable_failsWithoutCorruption_staysSyncing() {
            registry.registerReplica(STREAM, PARTITION, SOURCE);
            registry.updateWatermark(STREAM, PARTITION, SOURCE, 4L);
            registry.registerReplica(STREAM, PARTITION, SELF);

            CatchupTransport unreachable = (_, _) ->
                    ReplicationError.General.REPLICATION_TIMEOUT.promise();
            var backfill = partitionBackfill(registry, recovery, unreachable, SELF);

            var result = backfill.backfill(STREAM, PARTITION).await();

            assertThat(result.isFailure()).isTrue();
            // Local partition untouched and self remains SYNCING (not falsely CAUGHT_UP).
            assertThat(manager.readLocal(STREAM, PARTITION, 0, 100).or(List.of())).isEmpty();
            assertThat(descriptorFor(SELF).state()).isEqualTo(ReplicationState.SYNCING);
        }
    }

    @Nested
    class ReadPathExclusion {
        @Test
        void syncingReplica_isExcludedBySelectionPredicate_caughtUpIsIncluded() {
            registry.registerReplica(STREAM, PARTITION, SELF);       // SYNCING by registration
            registry.registerReplica(STREAM, PARTITION, SOURCE);
            registry.updateWatermark(STREAM, PARTITION, SOURCE, 9L); // SOURCE -> CAUGHT_UP

            var caughtUp = registry.replicasFor(STREAM, PARTITION).stream()
                                   .filter(d -> d.state() == ReplicationState.CAUGHT_UP)
                                   .map(ReplicaDescriptor::nodeId)
                                   .toList();

            assertThat(caughtUp).containsExactly(SOURCE);
            assertThat(caughtUp).doesNotContain(SELF);
        }
    }

    @Nested
    class DurabilityB2 {
        @Test
        void backfill_truncatedResponse_payloadTimestampMismatch_failsAndStaysSyncing() {
            // Source is CAUGHT_UP at watermark 4; self fresh.
            registry.registerReplica(STREAM, PARTITION, SOURCE);
            registry.updateWatermark(STREAM, PARTITION, SOURCE, 4L);
            registry.registerReplica(STREAM, PARTITION, SELF);

            // Malformed/truncated response: 3 payloads but only 2 timestamps. toOffset still claims the
            // source watermark (4) — the false-ready trap. Must be treated as a parse failure.
            CatchupTransport truncated = (target, request) -> {
                var payloads = new ArrayList<byte[]>(List.of("a".getBytes(), "b".getBytes(), "c".getBytes()));
                var timestamps = new ArrayList<Long>(List.of(1000L, 1001L));
                return Promise.success(catchupResponse(target,
                                                       request.streamName(),
                                                       request.partition(),
                                                       request.fromOffset(),
                                                       4L,
                                                       payloads,
                                                       timestamps));
            };
            var backfill = partitionBackfill(registry, recovery, truncated, SELF);

            var result = backfill.backfill(STREAM, PARTITION).await();

            assertThat(result.isFailure()).isTrue();
            // No promotion: self stays SYNCING, nothing partially applied.
            assertThat(descriptorFor(SELF).state()).isEqualTo(ReplicationState.SYNCING);
            assertThat(manager.readLocal(STREAM, PARTITION, 0, 100).or(List.of())).isEmpty();
        }

        @Test
        void backfill_appliedBelowWatermark_failsAndStaysSyncing_noFalseReady() {
            // Source watermark is 9 but the response only carries events 0..2 (a short/holey page that
            // does not reach the watermark). Applying them must NOT promote to CAUGHT_UP@9.
            registry.registerReplica(STREAM, PARTITION, SOURCE);
            registry.updateWatermark(STREAM, PARTITION, SOURCE, 9L);
            registry.registerReplica(STREAM, PARTITION, SELF);

            // Well-formed response (3 payloads, 3 timestamps) but toOffset=2 while source watermark=9.
            var transport = fixedSource(eventsFrom(0, 3));
            var backfill = partitionBackfill(registry, recovery, transport, SELF);

            var result = backfill.backfill(STREAM, PARTITION).await();

            assertThat(result.isFailure()).isTrue();
            // Highest applied offset (2) < watermark (9) => no promotion; stays SYNCING for a re-run.
            assertThat(descriptorFor(SELF).state()).isEqualTo(ReplicationState.SYNCING);
            assertThat(descriptorFor(SELF).confirmedOffset()).isEqualTo(-1L);
        }
    }

    private ReplicaDescriptor descriptorFor(NodeId nodeId) {
        return registry.replicasFor(STREAM, PARTITION).stream()
                       .filter(d -> d.nodeId().equals(nodeId))
                       .findFirst()
                       .orElseThrow();
    }

    private CatchupTransport fixedSource(List<EventData> events) {
        return (target, request) -> {
            var payloads = new ArrayList<byte[]>();
            var timestamps = new ArrayList<Long>();
            events.forEach(event -> {
                payloads.add(event.data());
                timestamps.add(event.timestamp());
            });
            var toOffset = events.isEmpty() ? request.fromOffset() - 1 : events.getLast().offset();
            return Promise.success(catchupResponse(target,
                                                   request.streamName(),
                                                   request.partition(),
                                                   request.fromOffset(),
                                                   toOffset,
                                                   payloads,
                                                   timestamps));
        };
    }

    private static long await(Promise<Long> promise) {
        return promise.await().or(-1L);
    }

    private static List<EventData> eventsFrom(long startOffset, int count) {
        var events = new ArrayList<EventData>(count);
        for (var i = 0; i < count; i++) {
            var offset = startOffset + i;
            events.add(new EventData(offset, 1000L + offset, ("event-" + offset).getBytes()));
        }
        return events;
    }

    private record EventData(long offset, long timestamp, byte[] data) {}
}

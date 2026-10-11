// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.OffHeapRingBuffer;
import org.pragmatica.aether.stream.OwnerActivation;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.replication.PartitionBackfill.partitionBackfill;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;

/// #2084 F2 (ruling: two mutually unreachable in-sync candidates MAY both promote under the contest escape, on condition that a
/// replica promoted this way never serves a divergent unacknowledged tail).
///
/// Two replicas, AA and BB, were both owners' replicas in epoch E1 and hold DIFFERENT unacknowledged tails above offset 4 (AA 5..9, BB
/// 5..7). The committed epoch E2 began at offset 5, so those tails belong to a replaced lineage. They cannot reach each other, the third
/// replica CC answers, both are named in the committed in-sync set: both promote via the escape. Neither may serve a record at or above 5
/// until it has been compared with E2's owner; the prefix below 5, which both share and which was acknowledged, is served.
///
/// What enforces it is not the promotion: `StreamPartitionManager#servedIfVerified` reads the committed epoch and this copy's own trust,
/// and the contest's promotion marks nothing verified. Mutation: `servedIfVerified` returning the events unfiltered turns this red.
class EscapePromotedReplicaServesNoUnackedTailTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final Epoch E1 = Epoch.epoch(1L, 1L, 1L);
    private static final Epoch E2 = Epoch.epoch(1L, 1L, 2L);
    private static final NodeId NODE_AA = NodeId.nodeId("node-aa").unwrap();
    private static final NodeId NODE_BB = NodeId.nodeId("node-bb").unwrap();
    private static final NodeId NODE_CC = NodeId.nodeId("node-cc").unwrap();
    private static final TimeSpan BOUND = TimeSpan.timeSpan(10).seconds();

    private final List<OwnerActivation.PromotionEscape> escapes = new CopyOnWriteArrayList<>();

    private record Replica(StreamPartitionManager manager, ReplicaRegistry registry, NodeId self) {}

    private Replica promotedReplica(NodeId self, NodeId silentPeer, String label, int tailLength) {
        var manager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);

        manager.ownerEpochSource((_, _) -> E2);
        manager.createStream(StreamConfig.streamConfig(STREAM, 1, RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 600_000), "earliest"));
        manager.placementRoleSupplier((_, _) -> ReplicaSetController.Role.REPLICA);
        manager.epochStarts((_, _, epoch) -> epoch.equals(E2) ? Option.some(5L) : Option.none());
        for (var i = 0; i < tailLength; i++) {
            var text = i < 5 ? "shared-" + i : label + "-" + i;

            manager.appendRecovered(STREAM, PARTITION, i, text.getBytes(UTF_8), 1000L + i, E1).unwrap();
        }
        manager.syncReplicated(STREAM, PARTITION).await();

        var registry = replicaRegistry();

        registry.registerReplica(STREAM, PARTITION, NODE_AA);
        registry.registerReplica(STREAM, PARTITION, NODE_BB);
        registry.registerReplica(STREAM, PARTITION, NODE_CC);

        var clock = new AtomicLong(0L);
        ReplicaWatermarkProbe probe = (target, _, _) -> target.equals(silentPeer)
                                                        ? ReplicationError.General.REPLICATION_TIMEOUT.promise()
                                                        : Promise.success(3L);
        var backfill = partitionBackfill(registry, manager.alignedRecovery(), CatchupTransport.NOOP, probe, (_, _) -> tailLength - 1L, self, BOUND, clock::get);

        backfill.committedIsr((_, _, node) -> true);
        backfill.promotionEscapeAfter(BOUND);
        backfill.blockAlarm(new OwnerActivation.BlockAlarm() {
            @Override
            public Unit raise(OwnerActivation.ActivationBlock block) {
                return Unit.unit();
            }

            @Override
            public Unit escaped(OwnerActivation.PromotionEscape escape) {
                escapes.add(escape);

                return Unit.unit();
            }
        });
        backfill.backfill(STREAM, PARTITION).await();
        clock.set(BOUND.millis() + 1);
        backfill.backfill(STREAM, PARTITION).await();
        clock.addAndGet(BOUND.millis());
        backfill.backfill(STREAM, PARTITION).await();

        return new Replica(manager, registry, self);
    }

    private static ReplicationState stateOf(Replica replica) {
        return replica.registry()
                      .replicasFor(STREAM, PARTITION)
                      .stream()
                      .filter(descriptor -> descriptor.nodeId().equals(replica.self()))
                      .findFirst()
                      .orElseThrow()
                      .state();
    }

    private static List<String> texts(List<OffHeapRingBuffer.RawEvent> events) {
        return events.stream().map(event -> new String(event.data(), UTF_8)).toList();
    }

    @Test
    void twoMutuallySilentIsrCandidates_bothPromote_butNeitherServesItsDivergentUnackedTail() {
        var aa = promotedReplica(NODE_AA, NODE_BB, "aa", 10);
        var bb = promotedReplica(NODE_BB, NODE_AA, "bb", 8);

        assertThat(stateOf(aa)).as("AA promoted past silent BB").isEqualTo(ReplicationState.CAUGHT_UP);
        assertThat(stateOf(bb)).as("BB promoted past silent AA (accepted, F2)").isEqualTo(ReplicationState.CAUGHT_UP);
        assertThat(escapes).hasSize(2);
        for (var replica : List.of(aa, bb)) {
            var refused = replica.manager().readServing(STREAM, PARTITION, 5L, 10);

            assertThat(refused.isFailure()).as("%s must not serve from offset 5", replica.self()).isTrue();
            refused.onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.ReplicaNotVerified.class));
            assertThat(texts(replica.manager().readServing(STREAM, PARTITION, 0L, 10).unwrap()))
                .as("%s serves only the shared acknowledged prefix", replica.self())
                .containsExactly("shared-0", "shared-1", "shared-2", "shared-3", "shared-4");
        }

        aa.manager().markVerifiedForEpoch(STREAM, PARTITION, E2);

        assertThat(aa.manager().readServing(STREAM, PARTITION, 5L, 10).isSuccess()).as("control: the gate is the epoch verification").isTrue();
        aa.manager().close();
        bb.manager().close();
    }
}

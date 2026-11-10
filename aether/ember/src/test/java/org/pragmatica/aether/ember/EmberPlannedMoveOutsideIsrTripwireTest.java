// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.OffHeapRingBuffer;
import org.pragmatica.aether.stream.StreamWriteRouter;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.StreamTripwireSupport.PARTITION;
import static org.pragmatica.aether.ember.StreamTripwireSupport.awaitUntil;
import static org.pragmatica.aether.ember.StreamTripwireSupport.engineKey;
import static org.pragmatica.aether.ember.StreamTripwireSupport.sleepQuietly;

/// #2981 (TLA+ N2), multi-node: a planned ownership move goes only to a member of the live ISR.
///
/// **Why the decisive test is disabled.** The loss needs two conditions this harness cannot force without new test-only seams:
/// (1) the leader's writer must see the committed owner live while its desired owner differs, which on the stream path needs
/// the reconcile snapshot to change between the writer's two reads of it (desired owner, live members), and on the entity
/// path an entity keyspace hosted on a node outside the partition's ISR; (2) the deposed owner and the third node must apply
/// the move record after the new owner has applied it and activated, so that the deposed owner still acknowledges on its old
/// ISR. Neither an apply-delay seam on chosen nodes nor a snapshot-skew seam exists; `EmberCluster.relaunchNode` is refused
/// under terminal removal, so the model's restart of the old owner is not available either. The decisive test is written
/// below, `@Disabled`. The two seams are test infrastructure for the runtime proof of the election rule (#2981 direction 1);
/// they fence nothing. The mechanism that closes the N2 loss regardless of the election rule is direction B, the ack-time
/// ownership check on the deposed owner, a separate later change that this test does not cover.
///
/// **The enabled tripwire** pins the current behaviour that makes it unreachable: after an owner's loss the failover elects
/// a member of the old ISR, and with the new owner live and no membership change, no planned move follows. If it goes red,
/// the harness reached a move with the owner live: read the committed history it prints, then enable the decisive test.
@PortBudget
class EmberPlannedMoveOutsideIsrTripwireTest {
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(180).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(90).seconds();
    private static final long ISR_BUDGET_MS = 90_000L;
    private static final long FAILOVER_BUDGET_MS = 120_000L;
    private static final long QUIET_MS = 15_000L;
    private static final long MOVE_BUDGET_MS = 60_000L;
    private static final int ACKED = 5;
    private static final String STREAM = "n2move";
    private static final String ENGINE_KEY = engineKey(STREAM);

    private EmberCluster cluster;
    private Path dataDir;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            assertThat(cluster.stop().await(STOP_BOUND).fold(Cause::message, _ -> "stopped")).isEqualTo("stopped");
        }
        StreamTripwireSupport.deleteTree(dataDir);
    }

    @Test
    @Timeout(600)
    void ownerLost_failoverElectsAnIsrMember_andNoPlannedMoveFollowsWithTheNewOwnerLive() {
        var failedOver = failedOverCluster();
        var history = new ArrayList<StreamPartitionOwnershipValue>(List.of(failedOver.before(), failedOver.after()));
        var deadline = System.currentTimeMillis() + QUIET_MS;

        while (System.currentTimeMillis() < deadline) {
            committed().filter(record -> !record.equals(history.getLast())).onPresent(history::add);
            sleepQuietly(100L);
        }

        assertThat(failedOver.before().isr()).as("failover elects a member of the old ISR: %s", history)
                                             .contains(failedOver.after().owner());
        assertThat(history.stream().map(StreamPartitionOwnershipValue::owner).distinct().toList())
            .as("TRIPWIRE #2981: no planned move with the new owner live and no membership change; if this goes red the "
                + "harness reached a planned move, so enable plannedMoveOutsideTheDeposedOwnersIsr_losesNoAcknowledgedRecord "
                + "(#2981 direction 1 runtime proof). Committed history: %s", history)
            .containsExactly(failedOver.before().owner(), failedOver.after().owner());
    }

    /// The decisive test (TLA+ N2 trace). Needs (1) a snapshot-skew seam that makes the leader's writer commit a planned move
    /// to a live node outside the ISR (refused since #2981, so with the seam this asserts the move does not happen or, if the
    /// guard is removed, that the record is lost), and (2) an apply-delay seam that holds the move record back on the deposed
    /// owner and the third node while the new owner activates. Every acknowledged record must be held by the final owner.
    @Test
    @Timeout(600)
    @Disabled("#2981 direction 1 runtime proof: needs two test-only seams (test infrastructure, not fencing): a snapshot-skew seam "
              + "on the leader's reconcile and an apply-delay seam for the ownership record on chosen nodes; neither exists. The "
              + "enabled tripwire above pins why it is unreachable today. Fencing the deposed owner is direction B (ack-time "
              + "ownership check), a separate follow-up.")
    void plannedMoveOutsideTheDeposedOwnersIsr_losesNoAcknowledgedRecord() {
        var failedOver = failedOverCluster();
        var owner = node(failedOver.after().owner().id());
        var acked = new ArrayList<Long>();

        for (var i = 0; i < ACKED; i++) {
            StreamTripwireSupport.publishOnceActivated(StreamTripwireSupport.component(owner, "streamWriteRouter", StreamWriteRouter.class),
                                                       ENGINE_KEY,
                                                       ("acked-" + i).getBytes(StandardCharsets.UTF_8))
                                 .onSuccess(acked::add);
        }

        assertThat(acked).as("control: the failover owner acknowledges").hasSize(ACKED);
        awaitUntil(() -> committed().filter(record -> !record.owner().equals(failedOver.after().owner())).isPresent(),
                   MOVE_BUDGET_MS,
                   "the seams force a planned move away from the live owner");

        var finalOwner = node(committed().unwrap().owner().id());

        assertThat(heldOffsets(finalOwner)).as("every acknowledged record is held by the new owner").containsAll(acked);
    }

    private record FailedOver(StreamPartitionOwnershipValue before, StreamPartitionOwnershipValue after) {}

    /// A three-node cluster at the default replication (RF 3, CF 2) whose first owner was stopped and replaced by failover.
    private FailedOver failedOverCluster() {
        dataDir = StreamTripwireSupport.newDataDir("n2move");
        cluster = EmberTestPorts.startedCluster(StreamTripwireSupport.PORTS,
                                                basePort -> StreamTripwireSupport.threeNodes(basePort, "n2m", dataDir, 1),
                                                START_BOUND);
        StreamTripwireSupport.awaitLeader(cluster);
        StreamTripwireSupport.createStream(cluster.allNodes().getFirst(),
                                           StreamTripwireSupport.smallRingStream(STREAM, 1, 10_000L, 3_600_000L));

        var before = StreamTripwireSupport.awaitFullIsr(cluster, ENGINE_KEY, ISR_BUDGET_MS);

        assertThat(cluster.killNode(before.owner().id()).await(STOP_BOUND).fold(Cause::message, _ -> "stopped"))
            .isEqualTo("stopped");
        awaitUntil(() -> committed().filter(record -> !record.owner().equals(before.owner())).isPresent(),
                   FAILOVER_BUDGET_MS,
                   "a failover away from the stopped owner " + before.owner().id());

        return new FailedOver(before, committed().unwrap());
    }

    private Option<StreamPartitionOwnershipValue> committed() {
        return StreamTripwireSupport.committedRecord(cluster, ENGINE_KEY, PARTITION);
    }

    private AetherNode node(String id) {
        return cluster.getNode(id).unwrap();
    }

    private static List<Long> heldOffsets(AetherNode node) {
        return node.streamPartitionManager()
                   .readLocal(ENGINE_KEY, PARTITION, 0L, 1000)
                   .map(events -> events.stream().map(OffHeapRingBuffer.RawEvent::offset).toList())
                   .or(List.of());
    }
}

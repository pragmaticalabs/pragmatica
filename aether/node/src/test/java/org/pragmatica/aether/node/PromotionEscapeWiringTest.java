// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.CommittedStreamOwnerSource;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.replication.CatchupTransport;
import org.pragmatica.aether.stream.replication.PartitionBackfill;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicationError;
import org.pragmatica.aether.stream.replication.ReplicationState;
import org.pragmatica.aether.stream.replication.ReplicationTransport;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// #2080 (T1d, wiring): the node binds the replica promotion contest's escape through `AetherNode#bindPromotionAlarm` -- the code the
/// production assembly calls. Driven end to end over a real backfill and registry: with the committed in-sync set naming this node the
/// contest escapes a silent co-replica and the OPERATOR EVENT reaches the sink; with the record naming another node, or no record at
/// all, it does not. Deleting either binding (the alarm or the in-sync reader) turns the first test red.
class PromotionEscapeWiringTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId NODE_AA = NodeId.nodeId("node-aa").unwrap();
    private static final NodeId NODE_BB = NodeId.nodeId("node-bb").unwrap();
    private static final NodeId NODE_CC = NodeId.nodeId("node-cc").unwrap();
    private static final TimeSpan BOUND = TimeSpan.timeSpan(150).millis();

    private final List<OperatorWarning> published = new CopyOnWriteArrayList<>();
    private final OperatorWarningSink sink = OperatorWarningSink.handingOffTo(published::add);

    private static StreamPartitionOwnershipValue recordWithIsr(List<NodeId> isr, long isrVersion) {
        return StreamPartitionOwnershipValue.streamPartitionOwnershipValue(NODE_BB, Epoch.epoch(0L, 3L, 0), 3L, HlcTimestamp.ZERO, isr, isrVersion);
    }

    /// Runs the cold-start contest of NODE_AA (holds 8; NODE_BB answers 5; NODE_CC never answers) past the bound and the silence.
    private ReplicationState contestWith(Option<StreamPartitionOwnershipValue> record) {
        var registry = ReplicaRegistry.replicaRegistry();
        var manager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);

        manager.createStream(StreamConfig.streamConfig(STREAM));
        registry.registerReplica(STREAM, PARTITION, NODE_BB);
        registry.registerReplica(STREAM, PARTITION, NODE_CC);
        registry.registerReplica(STREAM, PARTITION, NODE_AA);

        var backfill = PartitionBackfill.partitionBackfill(registry,
                                                           manager.alignedRecovery(),
                                                           CatchupTransport.NOOP,
                                                           ReplicationTransport.NOOP,
                                                           (target, _, _) -> target.equals(NODE_BB)
                                                                             ? Promise.success(5L)
                                                                             : ReplicationError.General.REPLICATION_TIMEOUT.promise(),
                                                           (_, _) -> 8L,
                                                           NODE_AA,
                                                           BOUND,
                                                           List::of,
                                                           CommittedStreamOwnerSource.none());

        AetherNode.bindPromotionAlarm(backfill, sink, (_, _) -> record, BOUND);
        backfill.backfill(STREAM, PARTITION).await();      // arms the source wait
        pause();
        backfill.backfill(STREAM, PARTITION).await();      // the contest starts and sees the silent peer
        pause();
        backfill.backfill(STREAM, PARTITION).await();      // silent for longer than the bound

        return registry.replicasFor(STREAM, PARTITION)
                       .stream()
                       .filter(descriptor -> descriptor.nodeId().equals(NODE_AA))
                       .findFirst()
                       .orElseThrow()
                       .state();
    }

    private static void pause() {
        try {
            Thread.sleep(BOUND.millis() + 100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void contestEscape_whenTheCommittedIsrNamesThisNode_promotes_andTheOperatorEventIsPublished() {
        var state = contestWith(Option.some(recordWithIsr(List.of(NODE_BB, NODE_AA), 4L)));

        assertThat(state).isEqualTo(ReplicationState.CAUGHT_UP);
        await().atMost(java.time.Duration.ofSeconds(5)).until(() -> !published.isEmpty());
        assertThat(published).singleElement().satisfies(warning -> {
            assertThat(warning.code()).isEqualTo(OperatorWarningCode.STREAM_PROMOTION_PAST_UNREACHABLE_PEERS);
            assertThat(warning.message()).contains("orders[0]").contains(NODE_AA.toString()).contains(NODE_CC.toString());
        });
    }

    @Test
    void contestEscape_whenTheCommittedIsrDoesNotNameThisNode_orThereIsNoIsr_staysBlocked_andPublishesNothing() throws InterruptedException {
        assertThat(contestWith(Option.some(recordWithIsr(List.of(NODE_BB, NODE_CC), 4L)))).as("ISR names others").isEqualTo(ReplicationState.SYNCING);
        assertThat(contestWith(Option.some(recordWithIsr(List.of(NODE_BB, NODE_AA), 0L)))).as("ISR never committed").isEqualTo(ReplicationState.SYNCING);
        assertThat(contestWith(Option.none())).as("no record").isEqualTo(ReplicationState.SYNCING);
        Thread.sleep(300);

        assertThat(published).isEmpty();
    }
}

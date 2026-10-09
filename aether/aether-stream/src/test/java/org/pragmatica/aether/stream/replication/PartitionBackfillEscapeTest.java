// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.OwnerActivation;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.replication.PartitionBackfill.partitionBackfill;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;

/// #2080: the bounded escape of the replica promotion contest (`PartitionBackfill#decidePromotionUnquarantined`). A co-replica that
/// stays unreachable for `sourceWaitBound` no longer blocks a candidate that the partition's committed in-sync set names; a candidate
/// the set does not name, and a record with no committed set, keep waiting. Fixture: three registered replicas, none a caught-up
/// source, no committed owner, so the cold-start contest decides; self is the lowest id, holds 8, one peer answers at 5, one never
/// answers.
class PartitionBackfillEscapeTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId NODE_AA = NodeId.nodeId("node-aa").unwrap();
    private static final NodeId NODE_BB = NodeId.nodeId("node-bb").unwrap();
    private static final NodeId NODE_CC = NodeId.nodeId("node-cc").unwrap();
    private static final TimeSpan BOUND = TimeSpan.timeSpan(10).seconds();

    private ReplicaRegistry registry;
    private AlignedRecovery recovery;
    private final AtomicLong clock = new AtomicLong(0L);
    private final List<OwnerActivation.PromotionEscape> escapes = new CopyOnWriteArrayList<>();
    private final AtomicLong peerWatermark = new AtomicLong(5L);
    private final AtomicBoolean silentPeerAnswers = new AtomicBoolean(false);

    @BeforeEach
    void setUp() {
        registry = replicaRegistry();
        var manager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);

        manager.createStream(StreamConfig.streamConfig(STREAM));
        recovery = manager.alignedRecovery();
        registry.registerReplica(STREAM, PARTITION, NODE_BB);
        registry.registerReplica(STREAM, PARTITION, NODE_CC);
        registry.registerReplica(STREAM, PARTITION, NODE_AA);
    }

    private PartitionBackfill backfill(PartitionBackfill.CommittedIsr isr) {
        return backfillAs(NODE_AA, 8L, isr);
    }

    /// `self` holds `selfWatermark`; NODE_BB answers `peerWatermark` (or NODE_CC when self is NODE_BB); the third node never answers.
    private PartitionBackfill backfillAs(NodeId self, long selfWatermark, PartitionBackfill.CommittedIsr isr) {
        var answering = self.equals(NODE_BB) ? NODE_CC : NODE_BB;
        ReplicaWatermarkProbe probe = (target, _, _) -> target.equals(answering)
                                                        ? Promise.success(peerWatermark.get())
                                                        : silentPeerAnswers.get()
                                                          ? Promise.success(1L)
                                                          : ReplicationError.General.REPLICATION_TIMEOUT.promise();
        var backfill = partitionBackfill(registry, recovery, CatchupTransport.NOOP, probe, (_, _) -> selfWatermark, self, BOUND, clock::get);

        backfill.committedIsr(isr);
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

        return backfill;
    }

    /// Arms the source wait (call 1), lets the contest start and observe the silent peer (call 2, at bound + 1), and returns the
    /// result of the contest `afterSilence` later (call 3).
    private boolean contestAfter(PartitionBackfill backfill, long afterSilenceMs) {
        backfill.backfill(STREAM, PARTITION).await();
        clock.set(BOUND.millis() + 1);
        backfill.backfill(STREAM, PARTITION).await();
        clock.addAndGet(afterSilenceMs);

        return backfill.backfill(STREAM, PARTITION).await().isSuccess();
    }

    private ReplicationState selfState() {
        return selfState(NODE_AA);
    }

    private ReplicationState selfState(NodeId self) {
        return registry.replicasFor(STREAM, PARTITION)
                       .stream()
                       .filter(descriptor -> descriptor.nodeId().equals(self))
                       .findFirst()
                       .orElseThrow()
                       .state();
    }

    /// T1c. Red on rc4: the contest answers UNREACHABLE_REPLICA_BLOCKS_PROMOTION for as long as the peer stays silent, self stays
    /// SYNCING and nothing is reported.
    @Test
    void backfill_isrCandidate_peerSilentPastTheBound_promotesAndReportsTheEscape() {
        var backfill = backfill((_, _, node) -> node.equals(NODE_AA));

        assertThat(contestAfter(backfill, BOUND.millis())).isTrue();
        assertThat(selfState()).isEqualTo(ReplicationState.CAUGHT_UP);
        assertThat(escapes).singleElement().satisfies(escape -> {
            assertThat(escape.streamName()).isEqualTo(STREAM);
            assertThat(escape.partition()).isEqualTo(PARTITION);
            assertThat(escape.gate()).isEqualTo(OwnerActivation.EscapeGate.REPLICA_CONTEST);
            assertThat(escape.candidate()).isEqualTo(NODE_AA);
            assertThat(escape.skipped()).containsExactly(NODE_CC);
        });
    }

    /// T1c control: the escape is bounded -- the silent peer has been observed for less than the bound.
    @Test
    void backfill_isrCandidate_peerSilentWithinTheBound_staysSyncing() {
        var backfill = backfill((_, _, node) -> node.equals(NODE_AA));

        assertThat(contestAfter(backfill, BOUND.millis() - 1)).isFalse();
        assertThat(selfState()).isEqualTo(ReplicationState.SYNCING);
        assertThat(escapes).isEmpty();
    }

    /// T1b twin. A candidate the committed set does not name (or a record with no committed set: the reader answers false for it
    /// the same way) stays blocked past the bound and reports nothing. Mutation: deleting the ISR conjunct turns this red.
    @Test
    void backfill_candidateNotNamedInTheCommittedIsr_peerSilentPastTheBound_staysSyncing() {
        var backfill = backfill((_, _, node) -> node.equals(NODE_BB));

        assertThat(contestAfter(backfill, 10 * BOUND.millis())).isFalse();
        assertThat(selfState()).isEqualTo(ReplicationState.SYNCING);
        assertThat(escapes).isEmpty();
    }

    /// The escape lifts only the wait for the silent peer. A peer that DID answer with a higher watermark still wins the contest, and
    /// nothing is reported because self did not proceed.
    @Test
    void backfill_isrCandidate_reachablePeerAhead_stillDeclines_andReportsNoEscape() {
        peerWatermark.set(9L);
        var backfill = backfill((_, _, node) -> node.equals(NODE_AA));

        assertThat(contestAfter(backfill, BOUND.millis())).isFalse();
        assertThat(selfState()).isEqualTo(ReplicationState.SYNCING);
        assertThat(escapes).isEmpty();
    }

    /// The silent peer takes no part in the contest at all. Self (NODE_BB) is empty and so is the one peer that answered (NODE_CC, a
    /// HIGHER id): self wins that tie. NODE_AA is silent and LOWER; counted at -1 it would tie with self and beat it. Mutation: handing
    /// the unfiltered peers and results to the contest turns this red.
    @Test
    void backfill_isrCandidate_silentLowerPeerDoesNotWinTheTieBreak() {
        peerWatermark.set(-1L);
        var backfill = backfillAs(NODE_BB, -1L, (_, _, node) -> node.equals(NODE_BB));

        assertThat(contestAfter(backfill, BOUND.millis())).isTrue();
        assertThat(selfState(NODE_BB)).isEqualTo(ReplicationState.CAUGHT_UP);
        assertThat(escapes).singleElement().satisfies(escape -> assertThat(escape.skipped()).containsExactly(NODE_AA));
    }

    /// A promoted partition starts over: the run of unreachability that justified the escape does not carry into the next time the
    /// partition is in the contest. Mutation: not forgetting the run on promotion lets the second contest escape at once.
    @Test
    void backfill_isrCandidate_afterTheEscapePromoted_theNextContestNeedsANewBound() {
        var backfill = backfill((_, _, node) -> node.equals(NODE_AA));

        assertThat(contestAfter(backfill, BOUND.millis())).isTrue();
        assertThat(escapes).hasSize(1);
        registry.registerReplica(STREAM, PARTITION, NODE_AA);                   // back to SYNCING: a new episode
        clock.addAndGet(1L);
        backfill.backfill(STREAM, PARTITION).await();                          // arms the source wait again
        clock.addAndGet(BOUND.millis() + 1);

        assertThat(backfill.backfill(STREAM, PARTITION).await().isSuccess()).as("silent for 0 ms in this episode").isFalse();
        assertThat(escapes).hasSize(1);
    }

    /// The run of unreachability must be CONTINUOUS: a round in which every peer answers restarts it. The answering round here
    /// DECLINES (a peer is ahead), so nothing else forgets the partition's wait, and the only thing that can make the last round
    /// fail is the restarted run. Mutation: not ending the run on an all-answer round turns the last assertion red.
    @Test
    void backfill_isrCandidate_peerAnsweredInBetween_restartsTheBound() {
        var backfill = backfill((_, _, node) -> node.equals(NODE_AA));

        backfill.backfill(STREAM, PARTITION).await();                                  // arms the source wait
        clock.set(BOUND.millis() + 1);
        backfill.backfill(STREAM, PARTITION).await();                                  // the silence starts here
        clock.addAndGet(BOUND.millis() - 1);
        silentPeerAnswers.set(true);
        peerWatermark.set(9L);
        assertThat(backfill.backfill(STREAM, PARTITION).await().isSuccess()).as("everyone answers, a peer is ahead").isFalse();
        silentPeerAnswers.set(false);
        peerWatermark.set(5L);
        clock.addAndGet(2L);

        assertThat(backfill.backfill(STREAM, PARTITION).await().isSuccess()).as("silent again, but for 2 ms: the run restarted").isFalse();
        assertThat(escapes).isEmpty();
    }
}

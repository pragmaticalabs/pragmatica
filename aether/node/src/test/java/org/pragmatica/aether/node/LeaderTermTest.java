// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.node.stream.ConsumerAssignmentWriter;
import org.pragmatica.aether.node.stream.StreamConsumerManager.PartitionAssignment;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConsumerAssignmentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerAssignmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.replication.StreamPartitionOwnershipWriter;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.NullReturn;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;


/// S28: the leader term every leader-authored `Epoch(rabiaTerm, counter)` is minted from must order a
/// new leader strictly after EVERY prior leader, cluster-wide — not merely after this node's own earlier
/// tenures. The scenario is the one a per-process "count my own leader gains" counter gets wrong: node A
/// leads twice (with B in between), then A dies and C — which has never led — takes over. C's writes go
/// through the REAL [KVStore] applier, whose `EpochBearing` fence refuses a strictly-older epoch; the
/// replicated store is modelled as one shared instance because every replica applies the same batches.
class LeaderTermTest {
    private static final NodeId NODE_A = NodeId.nodeId("node-a").unwrap();
    private static final NodeId NODE_B = NodeId.nodeId("node-b").unwrap();
    private static final NodeId NODE_C = NodeId.nodeId("node-c").unwrap();
    private static final String STREAM = "orders";
    private static final String GROUP = "orders-onOrderEvent";
    private static final int PARTITION = 0;

    private KVStore<AetherKey, AetherValue> kvStore;
    private AtomicLong viewSequence;
    private AtomicReference<NodeId> leader;
    private Map<NodeId, LeaderTerm> terms;

    @BeforeEach
    void setUp() {
        kvStore = new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
        viewSequence = new AtomicLong(0L);
        leader = new AtomicReference<>(NODE_A);
        terms = Map.of(NODE_A, termOf(NODE_A), NODE_B, termOf(NODE_B), NODE_C, termOf(NODE_C));
    }

    @Test
    void onLeaderGained_ordersANeverBeforeLeaderAfterEveryPriorLeader_whenThePriorLeaderLedMoreOften() {
        var termA = electThenLead(NODE_A, NODE_B, NODE_A);
        var termC = elect(NODE_C);

        assertThat(termC).as("C (first tenure) must out-rank A (second tenure, term %d)", termA).isGreaterThan(termA);
    }

    @Test
    void onLeaderGained_ordersARestartedNodeAfterEveryPriorLeader_whenItsProcessStartsFresh() {
        var priorTerm = electThenLead(NODE_A, NODE_B, NODE_A, NODE_B);
        var restarted = termOf(NODE_A);

        commitLeader(NODE_A);
        assertThat(restarted.onLeaderGained()).isGreaterThan(priorTerm);
    }

    @Test
    void onLeaderGained_keepsTheHeldTerm_whenTheCommittedLeaderIsAnotherNode() {
        var termA = elect(NODE_A);

        commitLeader(NODE_B);
        assertThat(terms.get(NODE_A).onLeaderGained()).isEqualTo(termA);
    }

    /// Max-merge: a leader-gain edge that reads a LOWER committed sequence than the term already held (a
    /// replayed or out-of-order observation) keeps the higher term. The real applier never commits a lower
    /// `LeaderKey` sequence, so the committed record is supplied directly here.
    @Test
    void onLeaderGained_keepsTheHigherHeldTerm_whenTheCommittedSequenceIsLower() {
        var committed = new AtomicReference<>(some(LeaderValue.leaderValue(NODE_A, 5L)));
        var term = LeaderTerm.leaderTerm(NODE_A, committed::get);

        term.onLeaderGained();
        committed.set(some(LeaderValue.leaderValue(NODE_A, 3L)));
        assertThat(term.onLeaderGained()).isEqualTo(5L);
        assertThat(term.current()).isEqualTo(5L);
    }

    /// `committedTerm` max-merges exactly as `onLeaderGained` does. A committed record naming this node with
    /// a sequence BELOW the held term (a replayed or out-of-order `LeaderKey` read, as above) must not report
    /// the lower sequence as the term about to be adopted: `onLeaderGained` would keep the held term, so the
    /// pure read has to agree with the edge it anticipates.
    @Test
    void committedTerm_keepsTheHigherHeldTerm_whenTheCommittedSequenceIsLower() {
        var committed = new AtomicReference<>(some(LeaderValue.leaderValue(NODE_A, 5L)));
        var term = LeaderTerm.leaderTerm(NODE_A, committed::get);

        term.onLeaderGained();
        committed.set(some(LeaderValue.leaderValue(NODE_A, 3L)));

        assertThat(term.committedTerm()).isEqualTo(5L);
        assertThat(term.committedTerm()).as("the pure read agrees with the edge it anticipates")
                                        .isEqualTo(term.onLeaderGained());
    }

    /// #1559 — the re-election pre-latch input, read where `LeaderReconciler.activate()` reads it: after
    /// the node's own `LeaderKey` commit, BEFORE its leader-gain edge. A failover to a node that has never
    /// led reads a term above 1 (pre-latch); the first leader of a fresh cluster reads 1 (no pre-latch).
    @Test
    void committedTerm_atReconcilerActivation_preLatchesAFailoverToANeverLedNode() {
        elect(NODE_A);
        commitLeader(NODE_C);
        leader.set(NODE_C);

        assertThat(terms.get(NODE_C).committedTerm()).as("a never-led successor reads the committed term, above 1")
                                                     .isGreaterThan(1L);
        assertThat(terms.get(NODE_C).current()).as("the read never advances the minting term").isZero();
    }

    @Test
    void committedTerm_atReconcilerActivation_doesNotPreLatchTheFirstLeaderOfAFreshCluster() {
        commitLeader(NODE_A);

        assertThat(terms.get(NODE_A).committedTerm()).isEqualTo(1L);
    }

    /// A node the committed record does not name reads its held term, never another node's sequence.
    @Test
    void committedTerm_readsTheHeldTerm_whenTheCommittedLeaderIsAnotherNode() {
        var termA = elect(NODE_A);

        commitLeader(NODE_B);

        assertThat(terms.get(NODE_A).committedTerm()).isEqualTo(termA);
    }

    /// The consumer-group consequence: A assigned the partition to itself; after A dies, C must move the
    /// assignment. A refused rewrite leaves the record pinned to the dead node, and every cursor
    /// checkpoint from the live consumer is then refused by the assignment guard.
    @Test
    void consumerAssignment_movesToTheNewLeadersAssignee_afterFailoverToANodeThatLedLessOften() {
        electThenLead(NODE_A, NODE_B, NODE_A);
        commit(assignmentWriter(NODE_A).writeAssignmentChanges(STREAM, GROUP, List.of(assigned(NODE_A))));
        elect(NODE_C);
        commit(assignmentWriter(NODE_C).writeAssignmentChanges(STREAM, GROUP, List.of(assigned(NODE_C))));
        assertThat(committedAssignment().map(ConsumerAssignmentValue::assignee)).isEqualTo(some(NODE_C));
    }

    /// The stream-ownership consequence, through the same applier fence.
    @Test
    void streamOwnership_movesToTheNewLeadersOwner_afterFailoverToANodeThatLedLessOften() {
        electThenLead(NODE_A, NODE_B, NODE_A);
        commit(ownershipWriter(NODE_A, NODE_A).writeOwnershipChange(STREAM, PARTITION).stream().toList());
        elect(NODE_C);
        commit(ownershipWriter(NODE_C, NODE_C).writeOwnershipChange(STREAM, PARTITION).stream().toList());
        assertThat(committedOwnership().map(StreamPartitionOwnershipValue::owner)).isEqualTo(some(NODE_C));
    }

    /// Control inside the scenario: the writers DO emit, and the store DOES apply, when leadership never
    /// changes hands — so a refused rewrite above is the term, not a writer or applier that never acts.
    @Test
    void consumerAssignment_moves_whenTheSameLeaderReassigns() {
        elect(NODE_A);
        commit(assignmentWriter(NODE_A).writeAssignmentChanges(STREAM, GROUP, List.of(assigned(NODE_A))));
        commit(assignmentWriter(NODE_A).writeAssignmentChanges(STREAM, GROUP, List.of(assigned(NODE_C))));
        assertThat(committedAssignment().map(ConsumerAssignmentValue::assignee)).isEqualTo(some(NODE_C));
    }

    private long electThenLead(NodeId... history) {
        var term = 0L;

        for (var node : history) {
            term = elect(node);
        }

        return term;
    }

    /// Commits `node` as leader (the `LeaderKey` write the election proposes, fenced by viewSequence), then
    /// delivers the local leader-gain edge — the order in which a node observes its own election.
    private long elect(NodeId node) {
        commitLeader(node);
        leader.set(node);

        return terms.get(node)
                    .onLeaderGained();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void commitLeader(NodeId node) {
        kvStore.process(kvStore.createBatch((List) List.of(new KVCommand.Put<>(LeaderKey.INSTANCE,
                                                                               LeaderValue.leaderValue(node,
                                                                                                       viewSequence.incrementAndGet())))));
    }

    private LeaderTerm termOf(NodeId node) {
        return LeaderTerm.leaderTerm(node, () -> kvStore.getTyped(LeaderKey.INSTANCE, LeaderValue.class));
    }

    private void commit(List<KVCommand<AetherKey>> commands) {
        assertThat(commands).as("the leader's writer emitted nothing to apply").isNotEmpty();
        kvStore.process(kvStore.createBatch(commands));
    }

    private ConsumerAssignmentWriter assignmentWriter(NodeId node) {
        return ConsumerAssignmentWriter.consumerAssignmentWriter(() -> node.equals(leader.get()),
                                                                 () -> Epoch.epoch(0L, terms.get(node).current(), 0L),
                                                                 HlcClock.hlcClock(node),
                                                                 (stream, partition, group) -> kvStore.getTyped(ConsumerAssignmentKey.consumerAssignmentKey(stream,
                                                                                                                                                            partition,
                                                                                                                                                            group),
                                                                                                                ConsumerAssignmentValue.class));
    }

    private StreamPartitionOwnershipWriter ownershipWriter(NodeId node, NodeId owner) {
        return StreamPartitionOwnershipWriter.streamPartitionOwnershipWriter(() -> node.equals(leader.get()),
                                                                             () -> Epoch.epoch(0L, terms.get(node).current(), 0L),
                                                                             HlcClock.hlcClock(node),
                                                                             (stream, partition) -> kvStore.getTyped(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(stream,
                                                                                                                                                                             partition),
                                                                                                                     StreamPartitionOwnershipValue.class),
                                                                             (_, _) -> some(owner));
    }

    private Option<ConsumerAssignmentValue> committedAssignment() {
        return kvStore.getTyped(ConsumerAssignmentKey.consumerAssignmentKey(STREAM, PARTITION, GROUP),
                                ConsumerAssignmentValue.class);
    }

    private Option<StreamPartitionOwnershipValue> committedOwnership() {
        return kvStore.getTyped(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(STREAM, PARTITION),
                                StreamPartitionOwnershipValue.class);
    }

    private static PartitionAssignment assigned(NodeId consumer) {
        return new PartitionAssignment(PARTITION, some(consumer), some(consumer));
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
            @NullReturn
            public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }
}

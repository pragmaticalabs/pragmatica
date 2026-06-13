/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */

package org.pragmatica.consensus.leader.fsm;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.leader.LeaderManager.LeaderProposalHandler;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.ConsensusReady;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.KvSyncGraceTimeout;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.LeaderCommitted;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.FsmTestHarness;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/// R6 verification: rank-staircase first-tick scheduling on entry to `Electing` AND `ReElecting`.
/// Lowest-rank node proposes first; higher ranks delay one `perRankDelay` per position so they
/// can observe the lowest-rank proposal commit before their own tick fires. Defeats the
/// thundering-herd election storm structurally — in a 5-node cold boot only the rank-0 node
/// ever submits a proposal under normal conditions.
class RankStaircaseTest {
    private static final NodeId NODE_1 = new NodeId("node-1");
    private static final NodeId NODE_2 = new NodeId("node-2");
    private static final NodeId NODE_3 = new NodeId("node-3");
    private static final NodeId NODE_4 = new NodeId("node-4");
    private static final NodeId NODE_5 = new NodeId("node-5");
    private static final List<NodeId> CLUSTER = List.of(NODE_1, NODE_2, NODE_3, NODE_4, NODE_5);

    /// Tight base + per-rank delays so the test runs fast but still demonstrates ordering.
    private static final TimeSpan BASE_ELECTION_DELAY = TimeSpan.timeSpan(50).millis();
    private static final TimeSpan PER_RANK_DELAY = TimeSpan.timeSpan(200).millis();
    private static final TimeSpan PROPOSAL_RETRY_DELAY = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan PROPOSAL_TIMEOUT = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan KV_SYNC_GRACE_DELAY = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan LONG_OBSERVATION_INTERVAL = TimeSpan.timeSpan(60).seconds();

    private FsmTestHarness<LeaderElectionState, ClusterFsmEvent> buildHarness(
            NodeId self,
            Option<LeaderProposalHandler> proposalHandler,
            AtomicReference<Option<NodeId>> kvLeader) {
        return FsmTestHarness.<LeaderElectionState, ClusterFsmEvent>harness(
                "rank-staircase-test-" + self.id(),
                fsm -> {
                    var ctx = new LeaderElectionContext(fsm,
                                                        self,
                                                        proposalHandler,
                                                        CLUSTER,
                                                        MessageRouter.mutable(),
                                                        PROPOSAL_RETRY_DELAY,
                                                        BASE_ELECTION_DELAY,
                                                        PER_RANK_DELAY,
                                                        PROPOSAL_TIMEOUT,
                                                        LeaderElectionContext.DEFAULT_STUCK_ELECTION_THRESHOLD,
                                                        LeaderElectionContext.DEFAULT_JITTER_SOURCE,
                                                        LeaderElectionContext.DEFAULT_RABIA_TERM_SUPPLIER,
                                                        () -> true,
                                                        kvLeader::get,
                                                        KV_SYNC_GRACE_DELAY,
                                                        LONG_OBSERVATION_INTERVAL);
                    return ctx.dormant();
                });
    }

    /// rank=0 (NODE_1) proposes first because its first-tick delay is `baseElectionDelay`
    /// (the smallest possible). Validates the staircase formula at the lowest rank.
    @Test
    void leaderElection_rankStaircase_lowestRankProposesFirst() throws InterruptedException {
        var proposalCount = new AtomicInteger(0);
        var lastCandidate = new AtomicReference<NodeId>();
        LeaderProposalHandler trackingHandler = (candidate, viewSeq) -> {
            proposalCount.incrementAndGet();
            lastCandidate.set(candidate);
            return Promise.success(Unit.unit());
        };
        var kvLeader = new AtomicReference<>(Option.<NodeId>none());
        var h = buildHarness(NODE_1, Option.some(trackingHandler), kvLeader);

        h.dispatch(new ClusterFsmEvent.QuorumEstablished());
        h.dispatch(new ClusterFsmEvent.NodeAdded(NODE_1, CLUSTER));
        h.dispatch(new ConsensusReady());
        h.dispatch(new KvSyncGraceTimeout());
        assertThat(h.state())
                .as("FSM in Electing after AwaitingKvSync grace fall-through")
                .isInstanceOf(LeaderElectionState.Electing.class);

        // First-tick delay for rank=0 is BASE_ELECTION_DELAY (50ms). Wait 500ms — the tick MUST
        // have fired and the proposal handler MUST have been called by now.
        Thread.sleep(500);

        assertThat(proposalCount.get())
                .as("rank-0 (NODE_1) submitted exactly one proposal within first-tick window "
                    + "(baseElectionDelay=50ms)")
                .isEqualTo(1);
        assertThat(lastCandidate.get())
                .as("proposal candidate is the rank-0 node (lowest in sorted cluster)")
                .isEqualTo(NODE_1);

        h.dispatch(new ClusterFsmEvent.Shutdown());
    }

    /// rank=4 (NODE_5) does NOT propose for `baseElectionDelay + 4*perRankDelay = 50ms + 800ms
    /// = 850ms`. If during that window a peer-committed leader appears in KV, the higher-rank
    /// node observes it via the synchronous `adoptLeaderFromKvIfPresent` path or the periodic
    /// observation timer and abandons its own pending tick — never proposing.
    @Test
    void leaderElection_higherRanksAbandonOnObservedCommit() throws InterruptedException {
        var proposalCount = new AtomicInteger(0);
        LeaderProposalHandler trackingHandler = (candidate, viewSeq) -> {
            proposalCount.incrementAndGet();
            return Promise.success(Unit.unit());
        };
        var kvLeader = new AtomicReference<>(Option.<NodeId>none());
        var h = buildHarness(NODE_5, Option.some(trackingHandler), kvLeader);

        h.dispatch(new ClusterFsmEvent.QuorumEstablished());
        h.dispatch(new ClusterFsmEvent.NodeAdded(NODE_5, CLUSTER));
        h.dispatch(new ConsensusReady());
        h.dispatch(new KvSyncGraceTimeout());
        assertThat(h.state()).isInstanceOf(LeaderElectionState.Electing.class);

        // First tick on rank=4 is scheduled at 850ms. A peer commits leader BEFORE then by
        // dispatching a synthetic LeaderCommitted (simulating push-side notification path).
        // The peer's commit POSTDATES this node's election decision (entryBaseline=0), so its
        // observed viewSequence (1) exceeds the baseline — model that (in production the LeaderKey
        // ValuePut fires observeViewSequence before the FSM sees LeaderCommitted). The
        // sequence-gated Electing arm then adopts and cancels the pending tick in Electing.onExit.
        Thread.sleep(100);
        ((LeaderElectionState) h.state()).ctx().observeViewSequence(1L);
        h.dispatch(new LeaderCommitted(NODE_1));

        assertThat(h.state()).isInstanceOf(LeaderElectionState.Led.class);
        assertThat(((LeaderElectionState.Led) h.state()).leader()).isEqualTo(NODE_1);

        // Wait past the rank-4 first-tick deadline (850ms) to confirm the cancelled tick truly
        // didn't fire. Add slack for CI scheduler jitter.
        Thread.sleep(1500);

        assertThat(proposalCount.get())
                .as("rank-4 abandoned its pending tick when peer-committed leader observed; "
                    + "no proposal was ever submitted")
                .isZero();

        h.dispatch(new ClusterFsmEvent.Shutdown());
    }

    /// Symmetric coverage for `ReElecting`: re-election after a leader is lost ALSO uses the
    /// rank-staircase formula. Without R6 the prior path scheduled first tick at
    /// `proposalRetryDelay` (~500ms) on every node — classic thundering-herd on leader loss.
    /// With R6 the lowest-rank survivor proposes first; higher ranks observe its commit and
    /// abandon their pending tick.
    @Test
    void leaderElection_reElectingAlsoUsesRankStaircase() throws InterruptedException {
        var proposalCount = new AtomicInteger(0);
        LeaderProposalHandler trackingHandler = (candidate, viewSeq) -> {
            proposalCount.incrementAndGet();
            return Promise.success(Unit.unit());
        };
        var kvLeader = new AtomicReference<>(Option.<NodeId>none());
        var h = buildHarness(NODE_5, Option.some(trackingHandler), kvLeader);

        // Drive into Led first so hasEverHadLeader=true (puts the AwaitingKvSync fall-through
        // on the ReElecting path, not Electing).
        h.dispatch(new ClusterFsmEvent.QuorumEstablished());
        h.dispatch(new ClusterFsmEvent.NodeAdded(NODE_5, CLUSTER));
        h.dispatch(new LeaderCommitted(NODE_1));
        assertThat(h.state()).isInstanceOf(LeaderElectionState.Led.class);

        // Drop the leader: a NodeGone for the current leader transitions Led → ReElecting.
        var topologyAfterLoss = List.of(NODE_2, NODE_3, NODE_4, NODE_5);
        h.dispatch(new ClusterFsmEvent.NodeGone(NODE_1, topologyAfterLoss));
        assertThat(h.state())
                .as("Led → ReElecting on loss of the current leader")
                .isInstanceOf(LeaderElectionState.ReElecting.class);

        // rank of NODE_5 in the surviving topology is 3 (NODE_2 < NODE_3 < NODE_4 < NODE_5),
        // first-tick delay = 50ms + 3*200ms = 650ms. A peer commits a leader well before that.
        // The re-election commit POSTDATES the ReElecting decision (entryBaseline=0), so model the
        // observed post-death sequence (1) > baseline — the sequence-gated ReElecting arm adopts.
        Thread.sleep(100);
        ((LeaderElectionState) h.state()).ctx().observeViewSequence(1L);
        h.dispatch(new LeaderCommitted(NODE_2));

        assertThat(h.state()).isInstanceOf(LeaderElectionState.Led.class);
        assertThat(((LeaderElectionState.Led) h.state()).leader()).isEqualTo(NODE_2);

        // Wait past the rank-3 first-tick deadline (650ms) to confirm the cancelled tick truly
        // didn't fire.
        Thread.sleep(1200);

        assertThat(proposalCount.get())
                .as("ReElecting respects rank-staircase: higher-rank node abandoned its pending "
                    + "tick when peer-committed leader observed")
                .isZero();

        h.dispatch(new ClusterFsmEvent.Shutdown());
    }

    /// Edge case: rank-0 dead. rank-1 (NODE_2) proposes after `baseElectionDelay + 1*perRankDelay
    /// = 50ms + 200ms = 250ms`. Without rank-0 in the picture and no peer commit, NODE_2's tick
    /// fires and submits its proposal as candidate.
    @Test
    void leaderElection_rankZeroDead_rankOneEventuallyProposes() throws InterruptedException {
        var proposalCount = new AtomicInteger(0);
        var lastCandidate = new AtomicReference<NodeId>();
        LeaderProposalHandler trackingHandler = (candidate, viewSeq) -> {
            proposalCount.incrementAndGet();
            lastCandidate.set(candidate);
            return Promise.success(Unit.unit());
        };
        var kvLeader = new AtomicReference<>(Option.<NodeId>none());
        // Cluster reflects the surviving 4 nodes — NODE_1 (rank 0) is absent.
        var survivingCluster = List.of(NODE_2, NODE_3, NODE_4, NODE_5);
        var h = FsmTestHarness.<LeaderElectionState, ClusterFsmEvent>harness(
                "rank-zero-dead-test",
                fsm -> {
                    var ctx = new LeaderElectionContext(fsm,
                                                        NODE_2,
                                                        Option.some(trackingHandler),
                                                        survivingCluster,
                                                        MessageRouter.mutable(),
                                                        PROPOSAL_RETRY_DELAY,
                                                        BASE_ELECTION_DELAY,
                                                        PER_RANK_DELAY,
                                                        PROPOSAL_TIMEOUT,
                                                        LeaderElectionContext.DEFAULT_STUCK_ELECTION_THRESHOLD,
                                                        LeaderElectionContext.DEFAULT_JITTER_SOURCE,
                                                        LeaderElectionContext.DEFAULT_RABIA_TERM_SUPPLIER,
                                                        () -> true,
                                                        kvLeader::get,
                                                        KV_SYNC_GRACE_DELAY,
                                                        LONG_OBSERVATION_INTERVAL);
                    return ctx.dormant();
                });

        h.dispatch(new ClusterFsmEvent.QuorumEstablished());
        h.dispatch(new ClusterFsmEvent.NodeAdded(NODE_2, survivingCluster));
        h.dispatch(new ConsensusReady());
        h.dispatch(new KvSyncGraceTimeout());
        assertThat(h.state()).isInstanceOf(LeaderElectionState.Electing.class);

        // NODE_2 is rank-0 in the surviving cluster (NODE_1 absent). first-tick = 50ms.
        // Wait 500ms — the tick fires and the proposal is submitted.
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline && proposalCount.get() == 0) {
            Thread.sleep(20);
        }

        assertThat(proposalCount.get())
                .as("rank-1 (now rank-0 in surviving cluster) eventually proposed when no peer "
                    + "leader appeared in KV")
                .isEqualTo(1);
        assertThat(lastCandidate.get()).isEqualTo(NODE_2);

        h.dispatch(new ClusterFsmEvent.Shutdown());
    }
}

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
import org.pragmatica.consensus.leader.LeaderManager;
import org.pragmatica.consensus.leader.LeaderManager.LeaderProposalHandler;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.KvSyncGraceTimeout;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.LeaderCommitted;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.PassiveDirectory;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.VoterReadmitted;
import org.pragmatica.consensus.rabia.VoterConfiguration;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.FsmTestHarness;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/// #1803 (b): a node that has just JOINED a live cluster (an observer admitted as a voter) must not
/// start an election while a committed leader is live. The incident: a replacement sorting first by
/// id fell through the 3 s `AwaitingKvSync` grace and DEPOSED the healthy leader, because the leader
/// it had already observed while passive was never consulted by the grace gate (the KV pull skips a
/// leader that equals the one already known), and nothing tied the wait to sync progress.
class JoinerElectionTest {
    private static final NodeId SELF = new NodeId("aether-joiner");
    private static final NodeId LEADER = new NodeId("hetzner-core-0");
    private static final NodeId PEER = new NodeId("hetzner-core-2");
    private static final List<NodeId> ELECTORATE = List.of(SELF, LEADER, PEER);

    private static final TimeSpan LONG = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan SHORT_GRACE = TimeSpan.timeSpan(100).millis();

    private final AtomicInteger proposals = new AtomicInteger();
    private final AtomicReference<LeaderElectionContext> context = new AtomicReference<>();

    private FsmTestHarness<LeaderElectionState, ClusterFsmEvent> harness(Option<NodeId> kvLeader, TimeSpan grace) {
        return harness(() -> kvLeader, grace);
    }

    private FsmTestHarness<LeaderElectionState, ClusterFsmEvent> harness(Supplier<Option<NodeId>> kvLeader, TimeSpan grace) {
        LeaderProposalHandler handler = (candidate, viewSequence) -> countProposal();

        return FsmTestHarness.<LeaderElectionState, ClusterFsmEvent>harness("joiner-election-test",
                                                                            fsm -> initialState(fsm, handler, kvLeader, grace));
    }

    private Promise<Unit> countProposal() {
        proposals.incrementAndGet();

        return Promise.success(Unit.unit());
    }

    private LeaderElectionState initialState(org.pragmatica.statemachine.Fsm<LeaderElectionState, ClusterFsmEvent> fsm,
                                             LeaderProposalHandler handler,
                                             Supplier<Option<NodeId>> kvLeader,
                                             TimeSpan grace) {
        var ctx = new LeaderElectionContext(fsm,
                                            SELF,
                                            Option.some(handler),
                                            ELECTORATE,
                                            MessageRouter.mutable(),
                                            LONG,
                                            LONG,
                                            LONG,
                                            LONG,
                                            LeaderElectionContext.DEFAULT_STUCK_ELECTION_THRESHOLD,
                                            LeaderElectionContext.DEFAULT_JITTER_SOURCE,
                                            LeaderElectionContext.DEFAULT_RABIA_TERM_SUPPLIER,
                                            () -> true,
                                            kvLeader,
                                            grace);

        context.set(ctx);

        return ctx.dormant();
    }

    /// The path a replacement core takes: passive observer of the formed electorate (it sees the committed
    /// leader), then admitted as a voter, then consensus ready.
    private void joinAsObserverThenVoter(FsmTestHarness<LeaderElectionState, ClusterFsmEvent> h) {
        h.dispatch(new PassiveDirectory(List.of(LEADER, PEER)));
        h.dispatch(new LeaderCommitted(LEADER, 1L));
        context.get().installVoters(ELECTORATE);
        h.dispatch(new ClusterFsmEvent.NodeAdded(LEADER, ELECTORATE));
        h.dispatch(new VoterReadmitted());
    }

    @Test
    void awaitingKvSync_leaderObservedWhilePassive_adoptsWithoutProposing() throws InterruptedException {
        var h = harness(Option.some(LEADER), SHORT_GRACE);

        joinAsObserverThenVoter(h);
        // Past several grace windows: a wall-clock fall-through would have started an election by now.
        TimeUnit.MILLISECONDS.sleep(600);

        assertThat(h.state()).as("a joiner that observed the committed leader follows it").isInstanceOf(LeaderElectionState.Led.class);
        assertThat(((LeaderElectionState.Led) h.state()).leader()).isEqualTo(LEADER);
        assertThat(proposals.get()).as("a joiner with a live committed leader never proposes").isZero();

        h.dispatch(new ClusterFsmEvent.Shutdown());
    }

    @Test
    void awaitingKvSync_leaderObservedWhilePassive_followsAtOnceWithoutPayingTheGrace() {
        var h = harness(Option.none(), LONG);

        joinAsObserverThenVoter(h);

        assertThat(h.state()).as("the observed leader is followed on entry, not after the grace").isInstanceOf(LeaderElectionState.Led.class);
        assertThat(((LeaderElectionState.Led) h.state()).leader()).isEqualTo(LEADER);

        h.dispatch(new ClusterFsmEvent.Shutdown());
    }

    @Test
    void awaitingKvSync_leaderReachesKvWithoutAPush_isFollowedAtTheTimeout() {
        var kvLeader = new AtomicReference<Option<NodeId>>(Option.none());
        var h = harness(kvLeader::get, LONG);

        h.dispatch(new ClusterFsmEvent.QuorumEstablished());
        h.dispatch(new ClusterFsmEvent.NodeAdded(SELF, ELECTORATE));
        h.dispatch(new LeaderElectionEvents.ConsensusReady());
        assertThat(h.state()).as("precondition: waiting, nothing visible").isInstanceOf(LeaderElectionState.AwaitingKvSync.class);

        kvLeader.set(Option.some(LEADER));
        h.dispatch(new KvSyncGraceTimeout());

        assertThat(h.state()).as("the timeout reads KV again and follows the leader instead of electing").isInstanceOf(LeaderElectionState.Led.class);
        assertThat(proposals.get()).isZero();

        h.dispatch(new ClusterFsmEvent.Shutdown());
    }

    @Test
    void awaitingKvSync_graceTimeoutWithObservedLeader_neverElects() {
        var h = harness(Option.some(LEADER), LONG);

        joinAsObserverThenVoter(h);
        h.dispatch(new KvSyncGraceTimeout());

        assertThat(h.state()).as("the grace timeout must not depose a live committed leader").isInstanceOf(LeaderElectionState.Led.class);
        assertThat(proposals.get()).isZero();

        h.dispatch(new ClusterFsmEvent.Shutdown());
    }

    /// A leader observed while passive but voted OUT of the configuration that readmits this node is not an eligible
    /// leader: entry adoption must not follow it (eligibility is installed before the readmission).
    @Test
    void awaitingKvSync_observedLeaderVotedOutOfTheReadmittingConfiguration_isNotFollowed() {
        var h = harness(Option.none(), LONG);

        h.dispatch(new PassiveDirectory(List.of(LEADER, PEER)));
        h.dispatch(new LeaderCommitted(LEADER, 1L));
        assertThat(context.get().currentLeader()).as("precondition: observed while passive").isEqualTo(Option.some(LEADER));

        var ctx = context.get();

        new LeaderManager.FsmBackedLeaderManager(ctx.fsm(), ctx, false).installVoterConfiguration(
                VoterConfiguration.voterConfiguration(1L, List.of(SELF, PEER)).unwrap());

        assertThat(h.state()).as("readmitted under a configuration without the old leader").isNotInstanceOf(LeaderElectionState.Passive.class);
        if (h.state() instanceof LeaderElectionState.Led led) {
            assertThat(led.leader()).as("must not follow a leader excluded from the installed electorate").isNotEqualTo(LEADER);
        }

        h.dispatch(new ClusterFsmEvent.Shutdown());
    }

    @Test
    void awaitingKvSync_leaderlessJoiner_stillElectsAfterGrace() throws InterruptedException {
        var h = harness(Option.none(), SHORT_GRACE);

        h.dispatch(new ClusterFsmEvent.QuorumEstablished());
        h.dispatch(new ClusterFsmEvent.NodeAdded(SELF, ELECTORATE));
        h.dispatch(new LeaderElectionEvents.ConsensusReady());
        awaitNotAwaiting(h);

        assertThat(h.state()).as("a genuinely leaderless cluster still lets the joiner elect")
                             .isInstanceOfAny(LeaderElectionState.Electing.class, LeaderElectionState.ReElecting.class);

        h.dispatch(new ClusterFsmEvent.Shutdown());
    }

    @Test
    void awaitingKvSync_syncStillCatchingUp_defersElectionPastTheGrace() throws InterruptedException {
        var catchingUp = new AtomicBoolean(true);
        var h = harness(Option.none(), SHORT_GRACE);

        installThroughTheManager(catchingUp::get);
        h.dispatch(new ClusterFsmEvent.QuorumEstablished());
        h.dispatch(new ClusterFsmEvent.NodeAdded(SELF, ELECTORATE));
        h.dispatch(new LeaderElectionEvents.ConsensusReady());
        TimeUnit.MILLISECONDS.sleep(450);

        assertThat(h.state()).as("sync progress still pending: wait, do not elect on the wall clock")
                             .isInstanceOf(LeaderElectionState.AwaitingKvSync.class);

        catchingUp.set(false);
        awaitNotAwaiting(h);

        assertThat(h.state()).as("sync caught up and no leader appeared: the cluster is leaderless, elect")
                             .isInstanceOfAny(LeaderElectionState.Electing.class, LeaderElectionState.ReElecting.class);

        h.dispatch(new ClusterFsmEvent.Shutdown());
    }

    @Test
    void awaitingKvSync_syncNeverCompletes_electionIsBoundedAtTenGraceWindows() throws InterruptedException {
        var h = harness(Option.none(), SHORT_GRACE);

        context.get().installKvSyncPending(() -> true);
        h.dispatch(new ClusterFsmEvent.QuorumEstablished());
        h.dispatch(new ClusterFsmEvent.NodeAdded(SELF, ELECTORATE));
        h.dispatch(new LeaderElectionEvents.ConsensusReady());
        awaitNotAwaiting(h);

        assertThat(h.state()).as("a wedged sync signal can delay an election, never forbid it")
                             .isInstanceOfAny(LeaderElectionState.Electing.class, LeaderElectionState.ReElecting.class);

        h.dispatch(new ClusterFsmEvent.Shutdown());
    }

    /// The production route: the consensus wiring hands the signal to the `LeaderManager`, which owns the context.
    private void installThroughTheManager(Supplier<Boolean> pending) {
        var ctx = context.get();

        new LeaderManager.FsmBackedLeaderManager(ctx.fsm(), ctx, false).installKvSyncProgress(pending);
    }

    private static void awaitNotAwaiting(FsmTestHarness<LeaderElectionState, ClusterFsmEvent> h) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (System.nanoTime() < deadline && h.state() instanceof LeaderElectionState.AwaitingKvSync) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
    }
}

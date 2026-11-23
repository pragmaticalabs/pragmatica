/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package org.pragmatica.consensus.leader.fsm;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.leader.LeaderManager.FsmBackedLeaderManager;
import org.pragmatica.consensus.leader.LeaderManager.LeaderProposalHandler;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.ElectionTick;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.KvSyncGraceTimeout;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.LeaderCommitted;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.LeaderSilent;
import org.pragmatica.consensus.rabia.VoterConfiguration;
import org.pragmatica.consensus.topology.TransportObservation.ObservationSource;
import org.pragmatica.consensus.topology.TransportObservation.PeerDisconnected;
import org.pragmatica.consensus.topology.TransportObservation.PeerJoined;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmTestHarness;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.LeaderCommitted.leaderCommitted;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;


/// #3588 finding A (v3588r2): `adoptedViewSequence` across a voter readmission. A passive core pulls the committed
/// `LeaderKey` record and adopts its own sequence (#3581), which can exceed `committedViewSequence` while the push that
/// observes that sequence is still queued behind the pull on the serial executor. `VoterReadmitted` then moves the
/// node `Passive -> QuorumWaiting -> AwaitingKvSync`, which re-adopts the held leader as a sequence-less commit, and (at
/// rc6) `recordAdoptedSequence` recorded `max(NO_SEQUENCE, committedViewSequence)`: the adopted sequence goes DOWN. The
/// `Led` swap fence (FIX 2, `seq > adopted`) then admits a queued OLDER commit for another leader.
///
/// The ordering driven here is the one a node's serial executor can run: a view task and the voter-configuration task
/// queued before the two leader pushes, drained after the KV-Store applied both commits. Every call below enters
/// through `FsmBackedLeaderManager`, as production does, and returns after its task ran on the drainer.
///
/// The not-ready pair pins the same order when consensus is not yet active at readmission, so `QuorumWaiting` (not
/// `Led`) receives the stale push: there the stale commit must not displace the newer leader either, and the newest
/// commit must still win (a fix that only raises the recorded sequence would fence the newer leader out of `Led`).
/// The election test pins the same for `Electing`; the sequence-less swap test pins the `Led` swap's recording. The
/// rule all of them pin: a commit older than the adopted leader is never adopted, and the adopted sequence never drops.
class ReadmittedVoterAdoptedSequenceTest {
    private static final NodeId SELF = new NodeId("node-3");
    private static final NodeId OLD_LEADER = new NodeId("node-1");
    private static final NodeId NEW_LEADER = new NodeId("node-2");
    private static final List<NodeId> CORES = List.of(OLD_LEADER, NEW_LEADER);
    private static final List<NodeId> CORES_WITH_SELF = List.of(SELF, OLD_LEADER, NEW_LEADER);
    private static final long OLD_SEQUENCE = 2;
    private static final long NEW_SEQUENCE = 3;
    private static final TimeSpan LONG = TimeSpan.timeSpan(60).seconds();
    private static final Duration READINESS_POLL_WITHIN = Duration.ofSeconds(10);

    private final AtomicReference<Option<LeaderCommitted>> kvLeader = new AtomicReference<>(Option.none());
    private final AtomicBoolean consensusReady = new AtomicBoolean(false);
    private FsmTestHarness<LeaderElectionState, ClusterFsmEvent> harness;
    private FsmBackedLeaderManager manager;

    @AfterEach
    void stopManager() {
        manager.stop();
    }

    @Test
    void readmission_passivePulledNewerLeader_adoptedSequenceNotLowered() {
        passiveCoreThatPulledTheNewerLeader();
        consensusReady.set(true);
        readmitAsVoter();
        assertThat(heldLeader()).as("instrument: AwaitingKvSync re-adopted the leader held while passive")
                  .isEqualTo(Option.some(NEW_LEADER));
        assertThat(ctx().adoptedViewSequence()).as("adoptedViewSequence after Passive -> VoterReadmitted -> AwaitingKvSync")
                  .isGreaterThanOrEqualTo(NEW_SEQUENCE);
    }

    @Test
    void readmission_staleCommitForAnotherLeaderInLed_newerLeaderKept() {
        passiveCoreThatPulledTheNewerLeader();
        consensusReady.set(true);
        readmitAsVoter();
        assertThat(heldLeader()).as("instrument: Led(new leader) before the queued pushes run")
                  .isEqualTo(Option.some(NEW_LEADER));
        manager.onLeaderCommitted(OLD_LEADER, OLD_SEQUENCE);
        assertThat(heldLeader()).as("Led after the queued older commit for another leader")
                  .isEqualTo(Option.some(NEW_LEADER));
    }

    @Test
    void readmissionBeforeConsensusReady_staleCommitInQuorumWaiting_newerLeaderNotDisplaced() {
        passiveCoreThatPulledTheNewerLeader();
        readmitAsVoter();
        assertThat(harness.state()).as("instrument: consensus not ready, the readmitted voter waits")
                  .isInstanceOf(LeaderElectionState.QuorumWaiting.class);
        manager.onLeaderCommitted(OLD_LEADER, OLD_SEQUENCE);
        OnDrainer.drained(ctx());
        assertThat(manager.leader()).as("leader after the queued older commit for another leader reached QuorumWaiting")
                  .isNotEqualTo(Option.some(OLD_LEADER));
    }

    @Test
    void readmissionBeforeConsensusReady_staleThenNewestCommit_newestLeaderWins() {
        passiveCoreThatPulledTheNewerLeader();
        readmitAsVoter();
        assertThat(harness.state()).as("instrument: consensus not ready, the readmitted voter waits")
                  .isInstanceOf(LeaderElectionState.QuorumWaiting.class);
        manager.onLeaderCommitted(OLD_LEADER, OLD_SEQUENCE);
        manager.onLeaderCommitted(NEW_LEADER, NEW_SEQUENCE);
        OnDrainer.drained(ctx());
        assertThat(heldLeader()).as("Led after the queued older commit, then the newest one")
                  .isEqualTo(Option.some(NEW_LEADER));
    }

    /// The `Led` swap on a sequence-less commit (a KV-pull synthesis, which bypasses the FIX-2 fence) records the
    /// adopted sequence by the same rule as every adoption: it keeps the fence, it does not reset it to `NO_SEQUENCE`.
    @Test
    void ledSwapOnSequencelessCommit_afterReadmission_adoptedSequenceNotLowered() {
        passiveCoreThatPulledTheNewerLeader();
        consensusReady.set(true);
        readmitAsVoter();
        OnDrainer.dispatch(ctx(), new LeaderCommitted(OLD_LEADER));
        assertThat(heldLeader()).as("instrument: the sequence-less commit swapped Led")
                  .isEqualTo(Option.some(OLD_LEADER));
        assertThat(ctx().adoptedViewSequence()).as("adoptedViewSequence after a sequence-less Led swap")
                  .isGreaterThanOrEqualTo(NEW_SEQUENCE);
    }

    /// A readmitted voter that elects (no leader held, none visible at the grace timeout) applies the same staleness
    /// rule inside the election: the queued older commit is skipped, the newest one is adopted. The precondition (the
    /// pulled leader dropped from the view and the record not visible at the timeout) is constructed at the FSM level.
    @Test
    void electingAfterReadmission_staleThenNewestCommit_newestLeaderWins() {
        passiveCoreThatPulledTheNewerLeader();
        manager.peerDisconnected(new PeerDisconnected(NEW_LEADER, List.of(SELF, OLD_LEADER), ObservationSource.QUIC, 2));
        assertThat(manager.leader()).as("instrument: the passive core dropped the leader that left its view")
                  .isEqualTo(Option.none());
        kvLeader.set(Option.none());
        consensusReady.set(true);
        readmitAsVoter();
        assertThat(harness.state()).as("instrument: nothing to adopt, the readmitted voter waits for KV sync")
                  .isInstanceOf(LeaderElectionState.AwaitingKvSync.class);
        OnDrainer.dispatch(ctx(), new KvSyncGraceTimeout());
        assertThat(harness.state()).as("instrument: the grace timeout started an election")
                  .isInstanceOf(LeaderElectionState.Electing.class);
        manager.onLeaderCommitted(OLD_LEADER, OLD_SEQUENCE);
        manager.onLeaderCommitted(NEW_LEADER, NEW_SEQUENCE);
        assertThat(heldLeader()).as("Led after the queued older commit, then the newest one, inside the election")
                  .isEqualTo(Option.some(NEW_LEADER));
    }

    /// #3588 F1 (vb3588): a readmitted voter that pulled nothing while passive adopts the KV record at the
    /// `QuorumWaiting` entry with the record's own sequence, so the `Led` swap fence refuses a queued older commit for
    /// another leader. At 2124f0cf01 the pull dropped the sequence, the fence stayed at 0, and the older commit won.
    @Test
    void readmissionBeforeConsensusReady_kvPullAdoptsTheRecordsSequence_staleCommitForAnotherLeaderRefused() {
        passiveCoreThatPulledNothing();
        kvLeader.set(Option.some(leaderCommitted(NEW_LEADER, NEW_SEQUENCE)));
        readmitAsVoter();
        assertThat(heldLeader()).as("instrument: the QuorumWaiting entry adopted the KV record's leader")
                  .isEqualTo(Option.some(NEW_LEADER));
        assertThat(ctx().adoptedViewSequence()).as("adoptedViewSequence after the KV-pull adoption")
                  .isEqualTo(NEW_SEQUENCE);
        manager.onLeaderCommitted(OLD_LEADER, OLD_SEQUENCE);
        OnDrainer.drained(ctx());
        assertThat(heldLeader()).as("Led after the queued older commit for another leader")
                  .isEqualTo(Option.some(NEW_LEADER));
    }

    /// The liveness arm of the test above: carrying the record's sequence never fences out the newest commit.
    @Test
    void readmissionBeforeConsensusReady_kvPullAdoption_staleThenNewestCommit_newestLeaderWins() {
        passiveCoreThatPulledNothing();
        kvLeader.set(Option.some(leaderCommitted(NEW_LEADER, NEW_SEQUENCE)));
        readmitAsVoter();
        manager.onLeaderCommitted(OLD_LEADER, OLD_SEQUENCE);
        manager.onLeaderCommitted(NEW_LEADER, NEW_SEQUENCE);
        OnDrainer.drained(ctx());
        assertThat(heldLeader()).as("Led after the stale then the newest commit")
                  .isEqualTo(Option.some(NEW_LEADER));
    }

    /// The `AwaitingKvSync` entry's KV pull carries the record's sequence too (#3588 F1): a voter that regains quorum
    /// re-enters `AwaitingKvSync`, adopts the record that appeared meanwhile, and the fence refuses a queued older commit.
    @Test
    void quorumRegainedInAwaitingKvSync_kvPullAdoptsTheRecordsSequence_staleCommitForAnotherLeaderRefused() {
        passiveCoreThatPulledNothing();
        consensusReady.set(true);
        readmitAsVoter();
        assertThat(harness.state()).as("instrument: nothing adoptable, the readmitted voter waits for KV sync")
                  .isInstanceOf(LeaderElectionState.AwaitingKvSync.class);
        OnDrainer.dispatch(ctx(), new ClusterFsmEvent.QuorumDisappeared());
        kvLeader.set(Option.some(leaderCommitted(NEW_LEADER, NEW_SEQUENCE)));
        OnDrainer.dispatch(ctx(), new ClusterFsmEvent.QuorumEstablished());
        assertThat(heldLeader()).as("instrument: the AwaitingKvSync entry adopted the KV record's leader")
                  .isEqualTo(Option.some(NEW_LEADER));
        manager.onLeaderCommitted(OLD_LEADER, OLD_SEQUENCE);
        OnDrainer.drained(ctx());
        assertThat(heldLeader()).as("Led after the queued older commit for another leader")
                  .isEqualTo(Option.some(NEW_LEADER));
    }

    /// The grace timeout adopts the visible KV record with its own sequence (#3588 F1), so the fence refuses a queued
    /// older commit for another leader.
    @Test
    void graceTimeout_adoptsTheVisibleRecordWithItsSequence_staleCommitForAnotherLeaderRefused() {
        passiveCoreThatPulledNothing();
        consensusReady.set(true);
        readmitAsVoter();
        assertThat(harness.state()).as("instrument: nothing adoptable, the readmitted voter waits for KV sync")
                  .isInstanceOf(LeaderElectionState.AwaitingKvSync.class);
        kvLeader.set(Option.some(leaderCommitted(NEW_LEADER, NEW_SEQUENCE)));
        OnDrainer.dispatch(ctx(), new KvSyncGraceTimeout());
        assertThat(heldLeader()).as("instrument: the grace timeout followed the visible leader")
                  .isEqualTo(Option.some(NEW_LEADER));
        manager.onLeaderCommitted(OLD_LEADER, OLD_SEQUENCE);
        OnDrainer.drained(ctx());
        assertThat(heldLeader()).as("Led after the queued older commit for another leader")
                  .isEqualTo(Option.some(NEW_LEADER));
    }

    /// The election tick's KV pull in `ReElecting` carries the record's sequence (#3588 F1). The node observed a commit
    /// at 2 (past its entry baseline 1) and the KV record is at 4, so the adoption records 4, not the observed 2, and a
    /// queued commit at 3 for another leader is refused by the fence.
    @Test
    void reElectingTick_kvPullAdoptsTheRecordsSequence_staleCommitForAnotherLeaderRefused() {
        passiveCoreThatPulledNothing();
        consensusReady.set(true);
        readmitAsVoter();
        manager.onLeaderCommitted(OLD_LEADER, 1);
        OnDrainer.drained(ctx());
        assertThat(heldLeader()).as("instrument: Led(old leader) at sequence 1")
                  .isEqualTo(Option.some(OLD_LEADER));
        OnDrainer.dispatch(ctx(), new LeaderSilent(OLD_LEADER));
        assertThat(harness.state()).as("instrument: the lease trip re-elects off baseline 1")
                  .isInstanceOf(LeaderElectionState.ReElecting.class);
        ctx().observeViewSequence(2);
        kvLeader.set(Option.some(leaderCommitted(NEW_LEADER, 4)));
        OnDrainer.dispatch(ctx(), new ElectionTick());
        assertThat(heldLeader()).as("instrument: the tick's KV pull adopted the record's leader")
                  .isEqualTo(Option.some(NEW_LEADER));
        manager.onLeaderCommitted(OLD_LEADER, 3);
        OnDrainer.drained(ctx());
        assertThat(heldLeader()).as("Led after the queued older commit for another leader")
                  .isEqualTo(Option.some(NEW_LEADER));
    }

    /// A KV record older than the adopted leader is not a follow-up of the `QuorumWaiting` entry: the adoption would
    /// refuse it, and the entry would then arm no readiness poll, so the node would wait forever. The entry polls
    /// instead and advances once consensus is ready. The stale record is constructed at the FSM level.
    @Test
    void readmissionBeforeConsensusReady_kvRecordOlderThanAdopted_quorumWaitingStillAdvancesOnReadiness() {
        passiveCoreThatDroppedTheNewerLeader();
        kvLeader.set(Option.some(leaderCommitted(OLD_LEADER, OLD_SEQUENCE)));
        readmitAsVoter();
        assertThat(harness.state()).as("instrument: consensus not ready, the readmitted voter waits")
                  .isInstanceOf(LeaderElectionState.QuorumWaiting.class);
        consensusReady.set(true);
        await().atMost(READINESS_POLL_WITHIN)
               .until(() -> harness.state() instanceof LeaderElectionState.AwaitingKvSync);
        assertThat(ctx().adoptedViewSequence()).as("adoptedViewSequence after the stale record was passed over")
                  .isEqualTo(NEW_SEQUENCE);
    }

    /// The grace timeout's verdict takes the same rule: a KV record older than the adopted leader is not a visible
    /// leader, so the wait elects instead of attempting an adoption that is refused and re-arms nothing.
    @Test
    void graceTimeout_kvRecordOlderThanAdopted_electsInsteadOfWaitingForever() {
        passiveCoreThatDroppedTheNewerLeader();
        kvLeader.set(Option.some(leaderCommitted(OLD_LEADER, OLD_SEQUENCE)));
        consensusReady.set(true);
        readmitAsVoter();
        assertThat(harness.state()).as("instrument: nothing adoptable, the readmitted voter waits for KV sync")
                  .isInstanceOf(LeaderElectionState.AwaitingKvSync.class);
        OnDrainer.dispatch(ctx(), new KvSyncGraceTimeout());
        assertThat(harness.state()).as("state after the grace timeout with only a stale record visible")
                  .isInstanceOf(LeaderElectionState.Electing.class);
    }

    /// #3588 residual (vb3588 F2): a node enters `ReElecting` with its adopted sequence AHEAD of its committed one (the
    /// passive pull adopted the KV record before the push that observes it ran). The entry pull of that record is a
    /// pre-election commit (committed 0 <= entry baseline 0) and is skipped; the queued push then lands, observes the
    /// record's sequence, and the node converges on the KV's leader instead of wedging below its own adopted sequence.
    @Test
    void reElectingWithAdoptedAheadOfCommitted_queuedPushForTheKvLeader_convergesOnIt() {
        passiveCoreThatPulledTheNewerLeader();
        consensusReady.set(true);
        readmitAsVoter();
        assertThat(heldLeader()).as("instrument: Led(new leader) before the queued push runs")
                  .isEqualTo(Option.some(NEW_LEADER));
        OnDrainer.dispatch(ctx(), new LeaderSilent(NEW_LEADER));
        assertThat(harness.state()).as("instrument: the lease trip re-elects")
                  .isInstanceOf(LeaderElectionState.ReElecting.class);
        assertThat(ctx().adoptedViewSequence()).as("instrument: adopted sequence ahead of the committed one")
                  .isGreaterThan(ctx().committedViewSequence());
        manager.onLeaderCommitted(NEW_LEADER, NEW_SEQUENCE);
        OnDrainer.drained(ctx());
        assertThat(heldLeader()).as("Led after the queued push for the KV's leader reached ReElecting")
                  .isEqualTo(Option.some(NEW_LEADER));
    }

    /// A core outside the voter set, `Passive`, that pulled nothing: the KV-Store held no leader record while it was
    /// passive, so it holds no leader and its adopted sequence is still 0.
    private void passiveCoreThatPulledNothing() {
        harness = FsmTestHarness.<LeaderElectionState, ClusterFsmEvent> harness("readmitted-voter-adopted-sequence",
                                                                                fsm -> context(fsm).dormant());
        manager = new FsmBackedLeaderManager(ctx().fsm(), ctx(), false);
        manager.installVoterConfiguration(voters(1, CORES));
        assertThat(harness.state()).as("instrument: the core outside the voter set is Passive")
                  .isInstanceOf(LeaderElectionState.Passive.class);
        manager.peerJoined(new PeerJoined(NEW_LEADER, CORES_WITH_SELF, ObservationSource.QUIC, 1));
        assertThat(manager.leader()).as("instrument: nothing pulled while passive").isEqualTo(Option.none());
    }

    /// [#passiveCoreThatPulledTheNewerLeader], then `NEW_LEADER` leaves the view: the node holds no leader, and its
    /// adopted sequence stays at `NEW_SEQUENCE` (it never moves down).
    private void passiveCoreThatDroppedTheNewerLeader() {
        passiveCoreThatPulledTheNewerLeader();
        manager.peerDisconnected(new PeerDisconnected(NEW_LEADER, List.of(SELF, OLD_LEADER), ObservationSource.QUIC, 2));
        assertThat(manager.leader()).as("instrument: the passive core dropped the leader that left its view")
                  .isEqualTo(Option.none());
    }

    /// A core outside the voter set, `Passive`, whose sequenced view write pulled `NEW_LEADER` at `NEW_SEQUENCE` from
    /// the KV record before any leader push ran: the node's adopted sequence is above its observed committed one.
    private void passiveCoreThatPulledTheNewerLeader() {
        harness = FsmTestHarness.<LeaderElectionState, ClusterFsmEvent> harness("readmitted-voter-adopted-sequence",
                                                                                fsm -> context(fsm).dormant());
        manager = new FsmBackedLeaderManager(ctx().fsm(), ctx(), false);
        manager.installVoterConfiguration(voters(1, CORES));
        assertThat(harness.state()).as("instrument: the core outside the voter set is Passive")
                  .isInstanceOf(LeaderElectionState.Passive.class);
        kvLeader.set(Option.some(leaderCommitted(NEW_LEADER, NEW_SEQUENCE)));
        manager.peerJoined(new PeerJoined(NEW_LEADER, CORES_WITH_SELF, ObservationSource.QUIC, 1));
        assertThat(manager.leader()).as("instrument: the passive pull adopted the KV leader")
                  .isEqualTo(Option.some(NEW_LEADER));
        assertThat(ctx().adoptedViewSequence()).as("instrument: the pull adopted the record's own sequence")
                  .isEqualTo(NEW_SEQUENCE);
        assertThat(ctx().committedViewSequence()).as("instrument: no push has observed a committed sequence yet")
                  .isZero();
    }

    private void readmitAsVoter() {
        manager.installVoterConfiguration(voters(2, CORES_WITH_SELF));
    }

    private Option<NodeId> heldLeader() {
        return harness.state() instanceof LeaderElectionState.Led led
               ? Option.some(led.leader())
               : Option.none();
    }

    private LeaderElectionContext ctx() {
        return harness.state()
                      .ctx();
    }

    private static VoterConfiguration voters(long epoch, List<NodeId> members) {
        return VoterConfiguration.voterConfiguration(epoch, members).unwrap();
    }

    private static void ignoreChange(LeaderChange change) {}

    private LeaderElectionContext context(Fsm<LeaderElectionState, ClusterFsmEvent> fsm) {
        LeaderProposalHandler neverSettling = (_, _) -> Promise.promise();
        var router = MessageRouter.mutable();

        router.addRoute(LeaderChange.class, ReadmittedVoterAdoptedSequenceTest::ignoreChange);

        return new LeaderElectionContext(fsm,
                                         SELF,
                                         Option.some(neverSettling),
                                         CORES_WITH_SELF,
                                         router,
                                         LONG,
                                         LONG,
                                         LONG,
                                         LONG,
                                         LeaderElectionContext.DEFAULT_STUCK_ELECTION_THRESHOLD,
                                         LeaderElectionContext.DEFAULT_JITTER_SOURCE,
                                         LeaderElectionContext.DEFAULT_RABIA_TERM_SUPPLIER,
                                         consensusReady::get,
                                         kvLeader::get,
                                         LONG,
                                         LONG);
    }
}

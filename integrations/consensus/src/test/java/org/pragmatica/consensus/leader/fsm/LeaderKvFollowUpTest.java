/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package org.pragmatica.consensus.leader.fsm;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.leader.LeaderManager.LeaderProposalHandler;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.ConsensusReady;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.KvSyncGraceTimeout;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.LeaderCommitted;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.LeaderSilent;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.PassiveDirectory;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.VoterReadmitted;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmObserver;
import org.pragmatica.statemachine.FsmTags;

import org.junit.jupiter.api.Test;

import static org.pragmatica.consensus.leader.fsm.LeaderCommits.commitLeader;
import static org.assertj.core.api.Assertions.assertThat;

/// #3605: the KV pull's decision ([LeaderElectionState] `kvLeaderToAdopt`) and the entry pulls of `Electing` and
/// `ReElecting` (`followUpKvLeader`). Every event is submitted from the test thread with no other drainer, so each
/// `submit` runs to completion before it returns: no timer is involved (every delay is 60 s).
///
/// Cluster mode cannot observe the two entry pulls: an election adopts a commit only when the committed sequence has
/// advanced past the baseline its entry snapshotted, and that sequence advances only inside a serialized task, never
/// between a transition and its follow-up. Local mode adopts any commit in the stored view, so the entry pull is the
/// one that adopts at once.
class LeaderKvFollowUpTest {
    private static final NodeId SELF = new NodeId("core-0");
    private static final NodeId PEER = new NodeId("core-1");
    private static final NodeId OTHER = new NodeId("core-2");
    private static final List<NodeId> CORES = List.of(SELF, PEER, OTHER);
    private static final TimeSpan LONG = TimeSpan.timeSpan(60).seconds();

    private final List<Object> observed = new CopyOnWriteArrayList<>();
    private final Queue<Option<LeaderCommitted>> scriptedKv = new ConcurrentLinkedQueue<>();
    private final AtomicReference<Option<LeaderCommitted>> kvLeader = new AtomicReference<>(Option.none());
    private final AtomicBoolean consensusReady = new AtomicBoolean();

    /// d8: a joiner that observed the committed leader while passive holds it on readmission; the KV record names the
    /// same leader. The pull skips a held leader, so the node waits for consensus readiness and adopts the held leader
    /// through `AwaitingKvSync` (#1803), never straight from `QuorumWaiting`'s entry.
    @Test
    void quorumWaitingEntry_kvNamesTheHeldLeader_waitsForConsensusThenAdoptsIt() {
        kvLeader.set(Option.some(new LeaderCommitted(PEER, 1L)));
        var fsm = fsm(Option.some(neverSettling()), LeaderElectionContext::dormant);
        var ctx = fsm.current()
                     .ctx();

        ctx.submit(new ClusterFsmEvent.NodeAdded(PEER, CORES, 1));
        ctx.submit(new PassiveDirectory(CORES));
        assertThat(ctx.currentLeader()).as("precondition: the passive node holds the committed leader")
                                       .isEqualTo(Option.some(PEER));
        observed.clear();

        ctx.submit(new VoterReadmitted());

        assertThat(fsm.current()).as("the entry's KV pull re-adopted the leader the node already holds, before consensus "
                                     + "is ready")
                                 .isInstanceOf(LeaderElectionState.QuorumWaiting.class);
        assertThat(observed).as("no LeaderCommitted for a held leader")
                            .noneMatch(LeaderCommitted.class::isInstance);

        consensusReady.set(true);
        ctx.submit(new ConsensusReady());

        assertThat(fsm.current()).as("once consensus is ready the held leader is followed")
                                 .isInstanceOf(LeaderElectionState.Led.class);
        assertThat(heldLeader(fsm)).isEqualTo(Option.some(PEER));
        ctx.submit(new ClusterFsmEvent.Shutdown());
    }

    /// d9: the KV applier writes the leader key on its own thread, so the record can land between the grace
    /// fall-through's read (which found none and chose to elect) and the `Electing` entry's pull. In local mode the
    /// entry pull adopts it at once, without waiting for a tick.
    @Test
    void electingEntry_kvRecordLandsAfterTheGraceRead_localModeAdoptsItAtOnce() {
        var fsm = fsm(Option.none(), LeaderElectionContext::awaitingKvSync);
        var ctx = fsm.current()
                     .ctx();

        ctx.submit(new ClusterFsmEvent.NodeAdded(PEER, CORES, 1));
        scriptedKv.add(Option.none());
        kvLeader.set(Option.some(new LeaderCommitted(PEER)));
        observed.clear();

        ctx.submit(new KvSyncGraceTimeout());

        assertThat(observed).as("precondition: the grace fall-through chose to elect")
                            .anyMatch(LeaderElectionState.Electing.class::isInstance);
        assertThat(fsm.current()).as("the Electing entry's KV pull adopts the leader the KV now names")
                                 .isInstanceOf(LeaderElectionState.Led.class);
        assertThat(heldLeader(fsm)).isEqualTo(Option.some(PEER));
        ctx.submit(new ClusterFsmEvent.Shutdown());
    }

    /// d10: a follower whose leader's lease expired (no pre-vote) re-elects; the KV already names another leader of the
    /// stored view. In local mode the `ReElecting` entry pull adopts it at once, without waiting for a tick.
    @Test
    void reElectingEntry_kvNamesAnotherLeader_localModeAdoptsItAtOnce() {
        var fsm = fsm(Option.none(), ctx -> LeaderElectionState.Led.fresh(ctx, PEER));
        var ctx = fsm.current()
                     .ctx();

        ctx.submit(new ClusterFsmEvent.NodeAdded(PEER, CORES, 1));
        kvLeader.set(Option.some(new LeaderCommitted(OTHER)));
        observed.clear();

        ctx.submit(new LeaderSilent(PEER));

        assertThat(observed).as("precondition: the lease expiry re-elects")
                            .anyMatch(LeaderElectionState.ReElecting.class::isInstance);
        assertThat(fsm.current()).as("the ReElecting entry's KV pull adopts the leader the KV names")
                                 .isInstanceOf(LeaderElectionState.Led.class);
        assertThat(heldLeader(fsm)).isEqualTo(Option.some(OTHER));
        ctx.submit(new ClusterFsmEvent.Shutdown());
    }

    /// #3588 F1: the `Electing` entry pull hands the adoption the record as the KV-Store holds it, with its own
    /// sequence. The leader is adopted in local mode either way, so the adopted sequence is what tells a carried
    /// sequence from a stripped one: it records the record's 4, not the 0 a sequence-less commit would leave.
    @Test
    void electingEntry_kvRecordCarriesItsSequence_adoptedSequenceIsTheRecords() {
        var fsm = fsm(Option.none(), LeaderElectionContext::awaitingKvSync);
        var ctx = fsm.current()
                     .ctx();

        ctx.submit(new ClusterFsmEvent.NodeAdded(PEER, CORES, 1));
        scriptedKv.add(Option.none());
        kvLeader.set(Option.some(new LeaderCommitted(PEER, 4L)));
        observed.clear();

        ctx.submit(new KvSyncGraceTimeout());

        assertThat(heldLeader(fsm)).as("instrument: the Electing entry's pull adopted the record's leader")
                                   .isEqualTo(Option.some(PEER));
        assertThat(ctx.adoptedViewSequence()).as("adoptedViewSequence after the Electing entry pull")
                                             .isEqualTo(4L);
        ctx.submit(new ClusterFsmEvent.Shutdown());
    }

    /// #3588 F1: the `ReElecting` entry pull carries the record's own sequence, as the `Electing` one does.
    @Test
    void reElectingEntry_kvRecordCarriesItsSequence_adoptedSequenceIsTheRecords() {
        var fsm = fsm(Option.none(), ctx -> LeaderElectionState.Led.fresh(ctx, PEER));
        var ctx = fsm.current()
                     .ctx();

        ctx.submit(new ClusterFsmEvent.NodeAdded(PEER, CORES, 1));
        kvLeader.set(Option.some(new LeaderCommitted(OTHER, 4L)));
        observed.clear();

        ctx.submit(new LeaderSilent(PEER));

        assertThat(heldLeader(fsm)).as("instrument: the ReElecting entry's pull adopted the record's leader")
                                   .isEqualTo(Option.some(OTHER));
        assertThat(ctx.adoptedViewSequence()).as("adoptedViewSequence after the ReElecting entry pull")
                                             .isEqualTo(4L);
        ctx.submit(new ClusterFsmEvent.Shutdown());
    }

    /// #3588 F1 (the case the model leaves out: local mode has no record in `LeaderAdmission`): a local-mode pull of a
    /// stale record with a sequence above 0 names a leader the stored view no longer holds. The admission refuses it, so
    /// the node settles in `ReElecting` and the adopted sequence does not take the record's 7.
    @Test
    void reElectingEntry_staleKvRecordWithASequence_localModeAdoptsNeitherLeaderNorSequence() {
        var fsm = fsm(Option.none(), ctx -> LeaderElectionState.Led.fresh(ctx, PEER));
        var ctx = fsm.current()
                     .ctx();

        ctx.submit(new ClusterFsmEvent.NodeAdded(PEER, List.of(SELF, PEER), 1));
        kvLeader.set(Option.some(new LeaderCommitted(OTHER, 7L)));
        observed.clear();

        ctx.submit(new LeaderSilent(PEER));

        assertThat(observed).as("instrument: the lease expiry re-elected, so the entry pull ran")
                            .anyMatch(LeaderElectionState.ReElecting.class::isInstance);
        assertThat(fsm.current()).as("the evicted leader's record is not adopted")
                                 .isInstanceOf(LeaderElectionState.ReElecting.class);
        assertThat(ctx.adoptedViewSequence()).as("adoptedViewSequence did not take the stale record's sequence")
                                             .isEqualTo(0L);
        ctx.submit(new ClusterFsmEvent.Shutdown());
    }

    /// #3588 F1: after a local-mode pull adopted sequence 4, a pulled record of the stored view that is older (2) is
    /// not adopted, and a sequence-less local commit (a swap) does not move the adopted sequence back: it only rises.
    @Test
    void localMode_afterAPullAdoptedSequence4_olderRecordIsSkippedAndSequenceLessSwapKeepsIt() {
        var fsm = fsm(Option.none(), LeaderElectionContext::awaitingKvSync);
        var ctx = fsm.current()
                     .ctx();

        ctx.submit(new ClusterFsmEvent.NodeAdded(PEER, CORES, 1));
        scriptedKv.add(Option.none());
        kvLeader.set(Option.some(new LeaderCommitted(PEER, 4L)));
        ctx.submit(new KvSyncGraceTimeout());
        assertThat(ctx.adoptedViewSequence()).as("instrument: the pull adopted the record's sequence")
                                             .isEqualTo(4L);
        kvLeader.set(Option.some(new LeaderCommitted(OTHER, 2L)));
        observed.clear();

        ctx.submit(new LeaderSilent(PEER));

        assertThat(observed).as("instrument: the lease expiry re-elected, so the entry pull ran")
                            .anyMatch(LeaderElectionState.ReElecting.class::isInstance);
        assertThat(fsm.current()).as("the older record is passed over")
                                 .isInstanceOf(LeaderElectionState.ReElecting.class);
        assertThat(ctx.adoptedViewSequence()).as("adoptedViewSequence after the older record was passed over")
                                             .isEqualTo(4L);

        commitLeader(ctx, ctx::submit, OTHER);

        assertThat(heldLeader(fsm)).as("instrument: the sequence-less local commit was adopted")
                                   .isEqualTo(Option.some(OTHER));
        assertThat(ctx.adoptedViewSequence()).as("adoptedViewSequence after the sequence-less local commit")
                                             .isEqualTo(4L);
        ctx.submit(new ClusterFsmEvent.Shutdown());
    }

    private static Option<NodeId> heldLeader(Fsm<LeaderElectionState, ClusterFsmEvent> fsm) {
        return fsm.current() instanceof LeaderElectionState.Led led
               ? Option.some(led.leader())
               : Option.none();
    }

    /// The scripted values first (a record landing between two reads), then the current one.
    private Option<LeaderCommitted> kv() {
        var scripted = scriptedKv.poll();

        return scripted != null
               ? scripted
               : kvLeader.get();
    }

    private static LeaderProposalHandler neverSettling() {
        return (_, _) -> Promise.promise();
    }

    private Fsm<LeaderElectionState, ClusterFsmEvent> fsm(Option<LeaderProposalHandler> proposalHandler,
                                                         Function<LeaderElectionContext, LeaderElectionState> initial) {
        var router = MessageRouter.mutable();

        router.addRoute(LeaderChange.class, LeaderKvFollowUpTest::ignoreChange);

        return Fsm.<LeaderElectionState, ClusterFsmEvent> fsm("leader-kv-follow-up",
                                                              f -> initial.apply(context(f, router, proposalHandler)),
                                                              recorder());
    }

    private FsmObserver<LeaderElectionState, ClusterFsmEvent> recorder() {
        return new FsmObserver<>() {
            @Override
            public void onTransition(FsmTags tags, LeaderElectionState from, LeaderElectionState to) {
                observed.add(to);
            }

            @Override
            public void onCasLost(FsmTags tags, LeaderElectionState expected, LeaderElectionState actual) {}

            @Override
            public void onEventIgnored(FsmTags tags, LeaderElectionState state, ClusterFsmEvent event) {
                observed.add(event);
            }

            @Override
            public void onHandled(FsmTags tags, LeaderElectionState state, ClusterFsmEvent event) {
                observed.add(event);
            }
        };
    }

    private static void ignoreChange(LeaderChange change) {}

    private LeaderElectionContext context(Fsm<LeaderElectionState, ClusterFsmEvent> fsm,
                                          MessageRouter router,
                                          Option<LeaderProposalHandler> proposalHandler) {
        return new LeaderElectionContext(fsm,
                                         SELF,
                                         proposalHandler,
                                         CORES,
                                         router,
                                         LONG,
                                         LONG,
                                         LONG,
                                         LONG,
                                         LeaderElectionContext.DEFAULT_STUCK_ELECTION_THRESHOLD,
                                         LeaderElectionContext.DEFAULT_JITTER_SOURCE,
                                         LeaderElectionContext.DEFAULT_RABIA_TERM_SUPPLIER,
                                         consensusReady::get,
                                         this::kv,
                                         LONG,
                                         LONG,
                                         LONG,
                                         _ -> true,
                                         LONG);
    }
}

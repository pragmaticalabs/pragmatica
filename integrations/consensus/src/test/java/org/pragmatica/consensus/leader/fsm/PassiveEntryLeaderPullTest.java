/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */

package org.pragmatica.consensus.leader.fsm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.leader.LeaderManager.FsmBackedLeaderManager;
import org.pragmatica.consensus.leader.LeaderManager.LeaderProposalHandler;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.LeaderCommitted;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.PassiveDirectory;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.PeerRemovedFromView;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmTestHarness;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.LeaderCommitted.leaderCommitted;

/// #3414: a worker whose leader state machine is still `Dormant` when the committed leader arrives drops that
/// notification (`Dormant` has no `LeaderCommitted` arm). `Passive` can therefore be entered after the
/// notification passed, so its entry reads the committed leader key, as the other entry states do.
///
/// #3581: the pull adopts exactly the committed record, the leader AND its own `viewSequence`, through the one
/// admission the push takes (`observeCommittedLeader`: eligibility, the sequence gate, I2's in-view admission).
/// `Passive` re-enters about once a second on every directory refresh, so a repeated entry adopts nothing new,
/// never resurrects a leader the I2 site dropped, and admits the committed leader once the view holds it.
class PassiveEntryLeaderPullTest {
    private static final NodeId SELF = new NodeId("worker-1");
    private static final NodeId LEADER = new NodeId("core-1");
    private static final NodeId SUCCESSOR = new NodeId("core-2");
    private static final NodeId JOINED = new NodeId("core-3");
    private static final List<NodeId> CORES = List.of(LEADER, SUCCESSOR);
    /// The worker's transport links: the configured cores and the joined one (filtered by eligibility on read).
    private static final List<NodeId> LINKED = List.of(SELF, LEADER, SUCCESSOR, JOINED);

    private static final TimeSpan LONG = TimeSpan.timeSpan(60).seconds();

    private final AtomicReference<Option<LeaderCommitted>> kvLeader = new AtomicReference<>(Option.none());
    private final List<Option<NodeId>> changes = new CopyOnWriteArrayList<>();
    private final AtomicLong viewSequence = new AtomicLong();
    private FsmTestHarness<LeaderElectionState, ClusterFsmEvent> harness;
    private LeaderElectionContext ctx;
    private FsmBackedLeaderManager manager;

    @AfterEach
    void tearDown() {
        manager.stop();
    }

    @Test
    void passiveEntry_committedLeaderDroppedWhileDormant_viewNamesCommittedLeader() {
        startLinked();
        commitDroppedWhileDormant(LEADER, 5);

        harness.dispatch(new PassiveDirectory(CORES));

        assertThat(harness.state()).isInstanceOf(LeaderElectionState.Passive.class);
        assertThat(manager.leader()).as("Passive entry pulls the committed leader key").isEqualTo(Option.some(LEADER));
        assertThat(ctx.adoptedViewSequence()).as("adopted at the committed sequence").isEqualTo(5L);
        assertThat(changes).as("one leader change announced").containsExactly(Option.some(LEADER));
    }

    @Test
    void passiveReentry_sameCommittedLeader_adoptsNothingNew() {
        startLinked();
        commitDroppedWhileDormant(LEADER, 5);
        harness.dispatch(new PassiveDirectory(CORES));

        harness.dispatch(new PassiveDirectory(CORES));
        harness.dispatch(new PassiveDirectory(CORES));

        assertThat(manager.leader()).isEqualTo(Option.some(LEADER));
        assertThat(ctx.adoptedViewSequence()).isEqualTo(5L);
        assertThat(changes).as("a repeated entry announces nothing").containsExactly(Option.some(LEADER));

        // A record naming another leader at the adopted sequence: the equal-sequence gate refuses it.
        kvLeader.set(Option.some(leaderCommitted(SUCCESSOR, 5)));
        harness.dispatch(new PassiveDirectory(CORES));

        assertThat(manager.leader()).as("no adoption without a newer committed sequence")
                                    .isEqualTo(Option.some(LEADER));
        assertThat(changes).containsExactly(Option.some(LEADER));
    }

    @Test
    void passiveReentry_sameLeaderHigherObservedSequence_announcesNothing() {
        startLinked();
        commitDroppedWhileDormant(LEADER, 5);
        harness.dispatch(new PassiveDirectory(CORES));
        ctx.observeViewSequence(6L);

        harness.dispatch(new PassiveDirectory(CORES));

        assertThat(manager.leader()).as("same leader already in view: nothing adopted")
                                    .isEqualTo(Option.some(LEADER));
        assertThat(ctx.adoptedViewSequence()).as("the record's own sequence, not the observed one").isEqualTo(5L);
        assertThat(changes).as("same leader already in view: nothing announced").containsExactly(Option.some(LEADER));
    }

    @Test
    void passiveEntry_committedSequenceOlderThanAdopted_notAdopted() {
        startLinked();
        commitDroppedWhileDormant(LEADER, 3);
        ctx.setAdoptedViewSequence(7L);

        harness.dispatch(new PassiveDirectory(CORES));

        assertThat(harness.state()).isInstanceOf(LeaderElectionState.Passive.class);
        assertThat(manager.leader()).as("a committed sequence older than the adopted one is not adopted")
                                    .isEqualTo(Option.none());
        assertThat(ctx.adoptedViewSequence()).isEqualTo(7L);
        assertThat(changes).isEmpty();
    }

    /// #3581 A1 (P1): the real push of a committed leader outside the configured cluster (a joined core) arrives while
    /// `Dormant`; `onLeaderCommitted` returns on eligibility before it observes the sequence. The pull carries the
    /// record's own sequence, so the entry adopts it once the entered members make it eligible.
    @Test
    void passiveEntry_committedLeaderOnlyInEnteredMembers_pullRunsAfterVotersInstalled() {
        startLinked();
        commitDroppedWhileDormant(JOINED, 5);
        assertThat(ctx.committedViewSequence()).as("instrument: the push of an ineligible leader observed no sequence")
                                               .isEqualTo(0L);

        harness.dispatch(new PassiveDirectory(List.of(LEADER, JOINED)));

        assertThat(manager.leader()).as("eligibility comes from the members installed by this entry, not the configured cluster")
                                    .isEqualTo(Option.some(JOINED));
        assertThat(ctx.adoptedViewSequence()).isEqualTo(5L);
    }

    /// #3581 A2 (P2): a worker holding LEADER@5; a joined core is committed @6 before the worker's directory lists it,
    /// so the push is dropped on eligibility and seq 6 is never observed. The next refresh adopts the record @6.
    @Test
    void passiveRefresh_newLeaderCommittedBeforeDirectoryListsIt_refreshAdoptsIt() {
        startPassiveHolding(LEADER, 5);
        kvLeader.set(Option.some(leaderCommitted(JOINED, 6)));
        manager.onLeaderCommitted(JOINED, 6);
        assertThat(ctx.committedViewSequence()).as("instrument: the push of a not-yet-listed leader observed no sequence")
                                               .isEqualTo(5L);

        harness.dispatch(new PassiveDirectory(List.of(LEADER, SUCCESSOR, JOINED)));

        assertThat(manager.leader()).as("the refresh reads the committed record naming the new leader")
                                    .isEqualTo(Option.some(JOINED));
        assertThat(ctx.adoptedViewSequence()).isEqualTo(6L);
        assertThat(changes).containsExactly(Option.some(LEADER), Option.some(JOINED));
    }

    /// #3581 A3 (P3): the I2 site drops the observed leader when the transport view loses it (the leader crashed; it
    /// stays a voter, so the directory still lists it, and the key still names it until re-election commits). The next
    /// directory refresh must not resurrect it: the pull takes the push's in-view admission.
    @Test
    void passiveRefresh_leaderDroppedOutOfView_refreshDoesNotResurrectIt() {
        startPassiveHolding(LEADER, 5);
        dropLeaderOutOfView();

        harness.dispatch(new PassiveDirectory(CORES));

        assertThat(manager.leader()).as("a refresh with no newer commit keeps the out-of-view leader dropped")
                                    .isEqualTo(Option.none());
        assertThat(changes).as("no re-announcement of the dropped leader").containsExactly(Option.some(LEADER), Option.none());
    }

    /// The other half of P3's invariant: `Passive` holds the committed leader iff the view holds it, so the view write
    /// that regains it (the link came back, no re-election) re-runs the pull and re-admits the same record.
    @Test
    void passiveViewWrite_droppedLeaderBackInView_readmitsTheRecord() {
        startPassiveHolding(LEADER, 5);
        dropLeaderOutOfView();

        link(LEADER, List.of(SELF, LEADER, SUCCESSOR));

        assertThat(manager.leader()).as("the view write re-ran the pull").isEqualTo(Option.some(LEADER));
        assertThat(ctx.adoptedViewSequence()).isEqualTo(5L);
        assertThat(changes).containsExactly(Option.some(LEADER), Option.none(), Option.some(LEADER));
    }

    /// #3581 item 3: a `Dormant` -> `Passive` entry can find the stored view empty (it starts empty, and a worker links a
    /// core the directory names only after the directory is installed). The entry refuses the commit, and the view
    /// write that lands the link re-runs the pull and admits it, at the record's own sequence: no special case.
    @Test
    void passiveEntry_emptyStoredView_viewWriteOfTheLinkAdoptsTheRecord() {
        startDormant();
        commitDroppedWhileDormant(LEADER, 5);

        harness.dispatch(new PassiveDirectory(CORES));
        assertThat(manager.leader()).as("the stored view is empty at the entry: not admitted").isEqualTo(Option.none());

        link(LEADER, List.of(SELF, LEADER));

        assertThat(manager.leader()).as("the view write of the link admits the record").isEqualTo(Option.some(LEADER));
        assertThat(ctx.adoptedViewSequence()).isEqualTo(5L);
        assertThat(changes).containsExactly(Option.some(LEADER));
    }

    /// The push takes the same admission: a commit whose leader the stored view lacks is not adopted.
    @Test
    void passivePush_leaderAbsentFromStoredView_notAdopted() {
        startDormant();
        harness.dispatch(new PassiveDirectory(CORES));
        kvLeader.set(Option.some(leaderCommitted(LEADER, 5)));

        manager.onLeaderCommitted(LEADER, 5);

        assertThat(manager.leader()).isEqualTo(Option.none());
        assertThat(changes).isEmpty();
    }

    /// #3581 (a+): a push that lags the view (refused while the view lacked its leader) heals on the next view write,
    /// which re-runs the pull of the committed record; no directory refresh is needed.
    @Test
    void passivePush_refusedOutOfView_nextViewWriteAdoptsTheRecord() {
        startDormant();
        harness.dispatch(new PassiveDirectory(CORES));
        kvLeader.set(Option.some(leaderCommitted(LEADER, 5)));
        manager.onLeaderCommitted(LEADER, 5);
        assertThat(manager.leader()).as("instrument: the push was refused out of view").isEqualTo(Option.none());

        link(LEADER, List.of(SELF, LEADER));

        assertThat(manager.leader()).isEqualTo(Option.some(LEADER));
        assertThat(ctx.adoptedViewSequence()).isEqualTo(5L);
        assertThat(changes).containsExactly(Option.some(LEADER));
    }

    /// I2's second half on the re-pull: a view-free removal that names the held leader failed drops it even where the
    /// storage keeps it (the last node left), and the pull after that removal must not re-admit it.
    @Test
    void passiveRemoval_heldLeaderNamedFailedButKept_pullDoesNotReadmitIt() {
        startDormant();
        link(LEADER, List.of(LEADER));
        kvLeader.set(Option.some(leaderCommitted(LEADER, 5)));
        harness.dispatch(new PassiveDirectory(CORES));
        assertThat(manager.leader()).as("instrument: adopted in view").isEqualTo(Option.some(LEADER));

        harness.dispatch(new PeerRemovedFromView(LEADER));

        assertThat(ctx.currentTopology()).as("instrument: the storage kept the last node").containsExactly(LEADER);
        assertThat(manager.leader()).as("a leader named failed is not re-admitted by the pull").isEqualTo(Option.none());
        assertThat(changes).containsExactly(Option.some(LEADER), Option.none());
    }

    /// #3581 B1: the pull runs AFTER the entry clears a previous leader the entered members no longer list. Run before
    /// it, the pull would adopt the committed leader and the clear would then drop it.
    @Test
    void passiveEntry_previousLeaderDeparted_pullAdoptsCommittedLeaderAfterTheClear() {
        startPassiveHolding(LEADER, 5);
        kvLeader.set(Option.some(leaderCommitted(JOINED, 6)));
        manager.onLeaderCommitted(JOINED, 6);

        harness.dispatch(new PassiveDirectory(List.of(SUCCESSOR, JOINED)));

        assertThat(manager.leader()).as("the departed leader is cleared, then the committed one adopted")
                                    .isEqualTo(Option.some(JOINED));
        assertThat(changes).containsExactly(Option.some(LEADER), Option.none(), Option.some(JOINED));
    }

    private void startPassiveHolding(NodeId leader, long sequence) {
        startLinked();
        kvLeader.set(Option.some(leaderCommitted(leader, sequence)));
        harness.dispatch(new PassiveDirectory(CORES));
        manager.onLeaderCommitted(leader, sequence);
        assertThat(manager.leader()).as("instrument: setup adopted").isEqualTo(Option.some(leader));
        assertThat(ctx.adoptedViewSequence()).as("instrument: setup sequence").isEqualTo(sequence);
    }

    private void dropLeaderOutOfView() {
        link(SUCCESSOR, List.of(SELF, SUCCESSOR, JOINED));
        assertThat(manager.leader()).as("instrument: the I2 site dropped the out-of-view leader").isEqualTo(Option.none());
        assertThat(changes).as("instrument").containsExactly(Option.some(LEADER), Option.none());
    }

    private void commitDroppedWhileDormant(NodeId leader, long sequence) {
        kvLeader.set(Option.some(leaderCommitted(leader, sequence)));
        manager.onLeaderCommitted(leader, sequence);
        assertThat(harness.state()).as("instrument: the commit arrived while Dormant")
                                   .isInstanceOf(LeaderElectionState.Dormant.class);
        assertThat(manager.leader()).as("instrument: Dormant dropped the commit").isEqualTo(Option.none());
    }

    private void startLinked() {
        startDormant();
        link(LEADER, LINKED);
    }

    private void link(NodeId node, List<NodeId> topology) {
        harness.dispatch(new ClusterFsmEvent.NodeAdded(node, topology, viewSequence.incrementAndGet()));
    }

    private void startDormant() {
        var router = MessageRouter.mutable();
        router.addRoute(LeaderChange.class, this::record);
        harness = FsmTestHarness.<LeaderElectionState, ClusterFsmEvent>harness("passive-entry-leader-pull",
                                                                               fsm -> context(fsm, router).dormant());
        ctx = ((LeaderElectionState) harness.state()).ctx();
        manager = new FsmBackedLeaderManager(ctx.fsm(), ctx, false);
    }

    private void record(LeaderChange change) {
        changes.add(change.leaderId());
    }

    private LeaderElectionContext context(Fsm<LeaderElectionState, ClusterFsmEvent> fsm, MessageRouter router) {
        LeaderProposalHandler neverSettling = (_, _) -> Promise.promise();

        return new LeaderElectionContext(fsm, SELF, Option.some(neverSettling), CORES, router, LONG, LONG, LONG, LONG,
                                         LeaderElectionContext.DEFAULT_STUCK_ELECTION_THRESHOLD,
                                         LeaderElectionContext.DEFAULT_JITTER_SOURCE,
                                         LeaderElectionContext.DEFAULT_RABIA_TERM_SUPPLIER,
                                         () -> true, kvLeader::get, LONG, LONG);
    }
}

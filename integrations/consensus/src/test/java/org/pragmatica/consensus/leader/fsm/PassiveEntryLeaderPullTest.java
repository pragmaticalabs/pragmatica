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
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.PassiveDirectory;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmTestHarness;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/// #3414: a worker whose leader state machine is still `Dormant` when the committed leader arrives drops that
/// notification (`Dormant` has no `LeaderCommitted` arm). `Passive` can therefore be entered after the
/// notification passed, so its entry reads the committed leader key, as the other entry states do. The pull goes
/// through the same sequence gate as the push (`observeCommittedLeader`), and `Passive` re-enters about once a
/// second on every directory refresh, so a repeated entry must adopt nothing new.
class PassiveEntryLeaderPullTest {
    private static final NodeId SELF = new NodeId("worker-1");
    private static final NodeId LEADER = new NodeId("core-1");
    private static final NodeId SUCCESSOR = new NodeId("core-2");
    private static final List<NodeId> CORES = List.of(LEADER, SUCCESSOR);

    private static final TimeSpan LONG = TimeSpan.timeSpan(60).seconds();

    private final AtomicReference<Option<NodeId>> kvLeader = new AtomicReference<>(Option.some(LEADER));
    private final List<Option<NodeId>> changes = new CopyOnWriteArrayList<>();
    private FsmTestHarness<LeaderElectionState, ClusterFsmEvent> harness;
    private LeaderElectionContext ctx;
    private FsmBackedLeaderManager manager;

    @AfterEach
    void tearDown() {
        manager.stop();
    }

    @Test
    void passiveEntry_committedLeaderDroppedWhileDormant_viewNamesCommittedLeader() {
        startDormant();

        manager.onLeaderCommitted(LEADER, 5);
        assertThat(harness.state()).as("instrument: the commit arrived while Dormant")
                                   .isInstanceOf(LeaderElectionState.Dormant.class);
        assertThat(manager.leader()).as("instrument: Dormant dropped the commit").isEqualTo(Option.none());

        harness.dispatch(new PassiveDirectory(CORES));

        assertThat(harness.state()).isInstanceOf(LeaderElectionState.Passive.class);
        assertThat(manager.leader()).as("Passive entry pulls the committed leader key").isEqualTo(Option.some(LEADER));
        assertThat(ctx.adoptedViewSequence()).as("adopted at the committed sequence").isEqualTo(5L);
        assertThat(changes).as("one leader change announced").containsExactly(Option.some(LEADER));
    }

    @Test
    void passiveReentry_sameCommittedLeader_adoptsNothingNew() {
        startDormant();
        manager.onLeaderCommitted(LEADER, 5);
        harness.dispatch(new PassiveDirectory(CORES));

        harness.dispatch(new PassiveDirectory(CORES));
        harness.dispatch(new PassiveDirectory(CORES));

        assertThat(manager.leader()).isEqualTo(Option.some(LEADER));
        assertThat(ctx.adoptedViewSequence()).isEqualTo(5L);
        assertThat(changes).as("a repeated entry announces nothing").containsExactly(Option.some(LEADER));

        // The key names another leader, but no newer commit has been observed: the equal-sequence gate refuses it.
        kvLeader.set(Option.some(SUCCESSOR));
        harness.dispatch(new PassiveDirectory(CORES));

        assertThat(manager.leader()).as("no adoption without a newer committed sequence")
                                    .isEqualTo(Option.some(LEADER));
        assertThat(changes).containsExactly(Option.some(LEADER));
    }

    @Test
    void passiveEntry_committedSequenceOlderThanAdopted_notAdopted() {
        startDormant();
        manager.onLeaderCommitted(LEADER, 3);
        ctx.setAdoptedViewSequence(7L);

        harness.dispatch(new PassiveDirectory(CORES));

        assertThat(harness.state()).isInstanceOf(LeaderElectionState.Passive.class);
        assertThat(manager.leader()).as("a committed sequence older than the adopted one is not adopted")
                                    .isEqualTo(Option.none());
        assertThat(ctx.adoptedViewSequence()).isEqualTo(7L);
        assertThat(changes).isEmpty();
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

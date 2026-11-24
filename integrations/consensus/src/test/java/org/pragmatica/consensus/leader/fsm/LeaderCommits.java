/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package org.pragmatica.consensus.leader.fsm;

import java.util.function.Consumer;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.LeaderCommitted;
import org.pragmatica.statemachine.FsmTestHarness;

/// The one test entry for a leader commit, in the order production performs it (rework rule 4, #3588): a commit carrying a
/// real sequence is first OBSERVED (`LeaderElectionContext#observeViewSequence`, the `LeaderKey` `ValuePut` observation) and
/// then dispatched, both as one serial task (`LeaderManager.commitLeader(leader, viewSequence)`). A test that wrote the
/// event pair by hand could build a state production never reaches (a sequenced commit the node never observed, or an
/// observation with a sequence-less event); the order lives here, once.
///
/// Production drops a commit for a leader the context deems ineligible (`LeaderElectionContext#isEligible`, the installed
/// voter set or the expected cluster) before observing or dispatching anything; the sequenced entries here make the same
/// decision, with the same predicate, so a test cannot observe a sequence for a leader production would drop.
///
/// The sequence-less overloads are production's `onLeaderCommitted(leader)` and local-election path: no observation
/// precedes them, so none is made. The observation is a max-merge, so a straggling commit below the observed sequence
/// observes nothing new and dispatches as production does.
///
/// Entry kinds: `commitLeader` enters from the test thread through the harness; `commitLeaderOnDrainer` enters the way
/// [OnDrainer] does, as a single task on the node's serial drainer.
public final class LeaderCommits {
    private LeaderCommits() {}

    public static void commitLeader(FsmTestHarness<LeaderElectionState, ClusterFsmEvent> harness, NodeId leader, long viewSequence) {
        observeThenCommit(contextOf(harness), harness::dispatch, leader, viewSequence);
    }

    public static void commitLeader(LeaderElectionContext ctx, Consumer<ClusterFsmEvent> entry, NodeId leader, long viewSequence) {
        observeThenCommit(ctx, entry, leader, viewSequence);
    }

    public static void commitLeader(FsmTestHarness<LeaderElectionState, ClusterFsmEvent> harness, NodeId leader) {
        harness.dispatch(LeaderCommitted.leaderCommitted(leader, LeaderCommitted.NO_SEQUENCE));
    }

    public static void commitLeader(Consumer<ClusterFsmEvent> entry, NodeId leader) {
        entry.accept(LeaderCommitted.leaderCommitted(leader, LeaderCommitted.NO_SEQUENCE));
    }

    public static void commitLeaderOnDrainer(LeaderElectionContext ctx, NodeId leader, long viewSequence) {
        OnDrainer.run(ctx, () -> observeThenCommit(ctx, ctx::submit, leader, viewSequence));
    }

    public static void commitLeaderOnDrainer(FsmTestHarness<LeaderElectionState, ClusterFsmEvent> harness, NodeId leader, long viewSequence) {
        commitLeaderOnDrainer(contextOf(harness), leader, viewSequence);
    }

    public static void commitLeaderOnDrainer(LeaderElectionContext ctx, NodeId leader) {
        OnDrainer.dispatch(ctx, LeaderCommitted.leaderCommitted(leader, LeaderCommitted.NO_SEQUENCE));
    }

    public static void commitLeaderOnDrainer(FsmTestHarness<LeaderElectionState, ClusterFsmEvent> harness, NodeId leader) {
        commitLeaderOnDrainer(contextOf(harness), leader);
    }

    private static void observeThenCommit(LeaderElectionContext ctx,
                                          Consumer<ClusterFsmEvent> entry,
                                          NodeId leader,
                                          long viewSequence) {
        if (ctx.isEligible(leader)) {
            ctx.observeViewSequence(viewSequence);
            entry.accept(LeaderCommitted.leaderCommitted(leader, viewSequence));
        }
    }

    private static LeaderElectionContext contextOf(FsmTestHarness<LeaderElectionState, ClusterFsmEvent> harness) {
        return harness.state()
                      .ctx();
    }
}

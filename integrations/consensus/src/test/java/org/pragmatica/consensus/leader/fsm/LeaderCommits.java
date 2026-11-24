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
/// voter set or the expected cluster) before observing or dispatching anything. Every entry here evaluates the same
/// predicate and FAILS LOUDLY (`AssertionError` naming the leader and the electorate) instead of dropping silently, so a
/// test cannot build a state production never reaches and no call site can pass on a commit that was never made. A test
/// that means to show the drop calls production's own path (`FsmBackedLeaderManager#onLeaderCommitted`), not this helper.
///
/// The sequence-less overloads are production's `onLeaderCommitted(leader)` and local-election path: no observation
/// precedes them, so none is made, but the same eligibility rule applies (`LeaderManager.commitLeader(leader)`).
/// The observation is a max-merge, so a straggling commit below the observed sequence observes nothing new and dispatches as production does.
///
/// Entry kinds: `commitLeader` enters from the test thread through the harness; `commitLeaderOnDrainer` enters the way
/// [OnDrainer] does, as a single task on the node's serial drainer.
public final class LeaderCommits {
    private LeaderCommits() {}

    public static void commitLeader(FsmTestHarness<LeaderElectionState, ClusterFsmEvent> harness, NodeId leader, long viewSequence) {
        commitLeader(contextOf(harness), harness::dispatch, leader, viewSequence);
    }

    public static void commitLeader(LeaderElectionContext ctx, Consumer<ClusterFsmEvent> entry, NodeId leader, long viewSequence) {
        requireEligible(ctx, leader);
        ctx.observeViewSequence(viewSequence);
        entry.accept(LeaderCommitted.leaderCommitted(leader, viewSequence));
    }

    public static void commitLeader(FsmTestHarness<LeaderElectionState, ClusterFsmEvent> harness, NodeId leader) {
        commitLeader(contextOf(harness), harness::dispatch, leader);
    }

    public static void commitLeader(LeaderElectionContext ctx, Consumer<ClusterFsmEvent> entry, NodeId leader) {
        requireEligible(ctx, leader);
        entry.accept(LeaderCommitted.leaderCommitted(leader, LeaderCommitted.NO_SEQUENCE));
    }

    public static void commitLeaderOnDrainer(LeaderElectionContext ctx, NodeId leader, long viewSequence) {
        requireEligible(ctx, leader);
        OnDrainer.run(ctx, () -> observeThenSubmit(ctx, leader, viewSequence));
    }

    public static void commitLeaderOnDrainer(FsmTestHarness<LeaderElectionState, ClusterFsmEvent> harness, NodeId leader, long viewSequence) {
        commitLeaderOnDrainer(contextOf(harness), leader, viewSequence);
    }

    public static void commitLeaderOnDrainer(LeaderElectionContext ctx, NodeId leader) {
        requireEligible(ctx, leader);
        OnDrainer.dispatch(ctx, LeaderCommitted.leaderCommitted(leader, LeaderCommitted.NO_SEQUENCE));
    }

    public static void commitLeaderOnDrainer(FsmTestHarness<LeaderElectionState, ClusterFsmEvent> harness, NodeId leader) {
        commitLeaderOnDrainer(contextOf(harness), leader);
    }

    private static void observeThenSubmit(LeaderElectionContext ctx, NodeId leader, long viewSequence) {
        ctx.observeViewSequence(viewSequence);
        ctx.submit(LeaderCommitted.leaderCommitted(leader, viewSequence));
    }

    /// Checked on the calling thread, before any drainer task: an exception inside a drainer task is swallowed by the
    /// executor, so the test would pass with the commit never made.
    private static void requireEligible(LeaderElectionContext ctx, NodeId leader) {
        if (!ctx.isEligible(leader)) {
            throw new AssertionError("leader " + leader + " is not eligible (electorate " + ctx.expectedCluster()
                                     + "): production drops this commit, the helper does not model a drop");
        }
    }

    private static LeaderElectionContext contextOf(FsmTestHarness<LeaderElectionState, ClusterFsmEvent> harness) {
        return harness.state()
                      .ctx();
    }
}

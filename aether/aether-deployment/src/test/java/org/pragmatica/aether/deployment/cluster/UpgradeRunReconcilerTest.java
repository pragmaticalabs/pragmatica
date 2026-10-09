// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.cluster.UpgradeRunPlanner.Member;
import org.pragmatica.aether.deployment.cluster.UpgradeRunPlanner.Observation;
import org.pragmatica.aether.deployment.cluster.UpgradeRunReconciler.BeginResult;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.UpgradeRunState;
import org.pragmatica.aether.slice.kvstore.AetherValue.UpgradeRunValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.UpgradeStop;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;

/// #1543 part F — the driver around the planner: the one effect (start a replacement) and the compare-and-set commit, on a model of the
/// cluster. The planner's rules are pinned in [UpgradeRunPlannerTest]; here: what is done, in what order, and what happens when the
/// effect or the commit does not go through.
class UpgradeRunReconcilerTest {
    private static final NodeId C1 = new NodeId("core-1");
    private static final NodeId C2 = new NodeId("core-2");

    private final AtomicReference<UpgradeRunValue> committed = new AtomicReference<>(run());
    private final List<String> calls = new ArrayList<>();
    private boolean leader = true;
    private boolean accept = true;
    private BeginResult beginResult = new BeginResult.Started();

    private static UpgradeRunValue run() {
        return new UpgradeRunValue("1.1.0", List.of(C1, C2), 0, "", UpgradeRunState.RUNNING, UpgradeStop.NONE, "", 0L, 0L, 0L);
    }

    private final UpgradeRunReconciler.Environment environment = new UpgradeRunReconciler.Environment() {
        @Override
        public boolean isLeader() {
            return leader;
        }

        @Override
        public Option<UpgradeRunValue> run() {
            return Option.option(committed.get());
        }

        @Override
        public Promise<Boolean> commit(UpgradeRunValue expected, UpgradeRunValue next) {
            calls.add("commit:" + next.state() + ":" + next.inFlight());

            if (accept && committed.get().equals(expected)) {
                committed.set(next);

                return Promise.success(true);
            }

            return Promise.success(false);
        }

        @Override
        public Observation observe() {
            return new Observation(Map.of(C1, new Member("core", "1.0.0"), C2, new Member("core", "1.0.0")), Map.<NodeId, NodeReplacementValue> of());
        }

        @Override
        public Promise<BeginResult> begin(NodeId node, String targetVersion) {
            calls.add("begin:" + node.id() + ":" + targetVersion);

            return Promise.success(beginResult);
        }

        @Override
        public long now() {
            return 5L;
        }
    };

    private final UpgradeRunReconciler reconciler = UpgradeRunReconciler.upgradeRunReconciler(environment);

    @Test
    void startsTheFirstReplacement_thenCommitsTheRunWithItInFlight_inThatOrder() {
        reconciler.reconcile().await();

        assertThat(calls).containsExactly("begin:core-1:1.1.0", "commit:RUNNING:core-1");
        assertThat(committed.get().inFlight()).isEqualTo("core-1");
    }

    @Test
    void aNodeThatIsNotTheLeader_doesNothing() {
        leader = false;

        reconciler.reconcile().await();

        assertThat(calls).isEmpty();
    }

    @Test
    void aRunThatIsNotRunning_isLeftAlone() {
        committed.set(run().with(0, "", UpgradeRunState.PAUSED, UpgradeStop.NONE, "paused", 1L));

        reconciler.reconcile().await();

        assertThat(calls).isEmpty();
    }

    @Test
    void aDeferredReplacement_commitsNothing_andIsTriedAgainOnTheNextTick() {
        beginResult = new BeginResult.Deferred("another replacement holds the slot");

        reconciler.reconcile().await();
        beginResult = new BeginResult.Started();
        reconciler.reconcile().await();

        assertThat(calls).containsExactly("begin:core-1:1.1.0", "begin:core-1:1.1.0", "commit:RUNNING:core-1");
    }

    @Test
    void aRefusedReplacement_pausesTheRun_namingTheNodeAndTheReason() {
        beginResult = new BeginResult.Refused("no capacity");

        reconciler.reconcile().await();

        assertThat(committed.get().state()).isEqualTo(UpgradeRunState.PAUSED);
        assertThat(committed.get().reason()).contains("core-1").contains("no capacity");
    }

    /// The replacement started but the run commit lost the race (a concurrent pause, a leader change): the next tick must ADOPT the live
    /// replacement rather than start it again, which the merged replacement service would refuse anyway.
    @Test
    void aLostCommit_leavesTheRunAsItWas_andNeverThrows() {
        accept = false;

        reconciler.reconcile().await();

        assertThat(committed.get().inFlight()).isEmpty();
        assertThat(calls).containsExactly("begin:core-1:1.1.0", "commit:RUNNING:core-1");
    }

    @Test
    void aTickIsNeverReentered() {
        var gate = new AtomicReference<Promise<BeginResult>>(Promise.promise());

        var slow = UpgradeRunReconciler.upgradeRunReconciler(new UpgradeRunReconciler.Environment() {
            @Override
            public boolean isLeader() {
                return true;
            }

            @Override
            public Option<UpgradeRunValue> run() {
                return Option.option(committed.get());
            }

            @Override
            public Promise<Boolean> commit(UpgradeRunValue expected, UpgradeRunValue next) {
                return Promise.success(true);
            }

            @Override
            public Observation observe() {
                return environment.observe();
            }

            @Override
            public Promise<BeginResult> begin(NodeId node, String targetVersion) {
                calls.add("begin:" + node.id());

                return gate.get();
            }

            @Override
            public long now() {
                return 5L;
            }
        });

        slow.reconcile();
        slow.reconcile();
        gate.get().succeed(new BeginResult.Started());

        assertThat(calls).as("a second tick while the first waits would start the same replacement twice").containsExactly("begin:core-1");
    }
}

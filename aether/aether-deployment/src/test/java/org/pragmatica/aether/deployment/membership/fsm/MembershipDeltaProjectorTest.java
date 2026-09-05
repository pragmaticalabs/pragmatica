// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.ntt.NttTimerScheduler;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.io.TimeSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.membership.fsm.MembershipDeltaProjector.membershipDeltaProjector;

/// Verifies the Wave-4 [`MembershipDeltaProjector`] — the SOLE emitter of `MembershipDecision`,
/// projecting the [`MembershipFsm`] JOINED/REMOVED delta edge (cluster-topology-overhaul spec,
/// Wave 4 / #245). Covers the moved `TopologyObserver.MembershipDecisionEmission` contract:
/// (logIndex, stampedAt) stamping, removal emission + prune, minority-side suppression, worker
/// core-scoping AND the separate non-core worker channel (#728), and exactly-once idempotence —
/// PLUS the follower-starvation fix (live-gate
/// CRITICAL): drainage must not depend on the one-shot `ClusterStateNotification` ACTIVE echo.
/// The FIFO queue is the pending store, the DRAINER is the only place that consults
/// `inQuorum()`, and a deduped one-shot flush retry re-pokes the drainer while suppressed work
/// remains — so a follower whose boot edges all precede its local quorum flip (and whose
/// steady-state FSM emits no further edges) still drains, in order, with NO echo delivered.
/// The drain executor is synchronous (`Runnable::run`) and the retry scheduler manual, so
/// every assertion is deterministic.
class MembershipDeltaProjectorTest {
    private static final NodeId A = new NodeId("node-a");
    private static final NodeId B = new NodeId("node-b");
    private static final NodeId W = new NodeId("node-w");
    private static final HlcTimestamp HLC = new HlcTimestamp(HlcTimestamp.pack(12_345L, 0), new NodeId("node-self"));

    private final List<MembershipDecision> decisions = new ArrayList<>();
    private final List<WorkerJoinDecision> workerJoins = new ArrayList<>();
    private final List<WorkerLeaveDecision> workerLeaves = new ArrayList<>();
    private final List<NodeId> pruned = new ArrayList<>();
    private final AtomicBoolean quorate = new AtomicBoolean(true);
    private final AtomicLong logIndex = new AtomicLong(42L);
    private ManualRetryScheduler retryScheduler;
    private MembershipDeltaProjector projector;

    @BeforeEach
    void setUp() {
        decisions.clear();
        workerJoins.clear();
        workerLeaves.clear();
        pruned.clear();
        quorate.set(true);
        logIndex.set(42L);
        retryScheduler = new ManualRetryScheduler();
        projector = membershipDeltaProjector(quorate::get,
                                             logIndex::get,
                                             () -> HLC,
                                             decisions::add,
                                             workerJoins::add,
                                             workerLeaves::add,
                                             pruned::add,
                                             Runnable::run,
                                             retryScheduler);
    }

    private static MembershipDeltaEdge joined(NodeId node, String role) {
        return new MembershipDeltaEdge(node, MembershipDeltaEdge.Kind.JOINED, 1L, role);
    }

    private static MembershipDeltaEdge removed(NodeId node, String role) {
        return new MembershipDeltaEdge(node, MembershipDeltaEdge.Kind.REMOVED, 2L, role);
    }

    @Nested
    class HappyPath {
        @Test
        void joinedThenRemoved_emitsNodeJoinedThenNodeRemoved_withStampsAndPrune() {
            projector.onDelta(joined(A, "core"));

            assertThat(decisions).hasSize(1);
            assertThat(decisions.getFirst()).isInstanceOf(MembershipDecision.NodeJoined.class);
            assertThat(decisions.getFirst().nodeId()).isEqualTo(A);
            assertThat(decisions.getFirst().topology()).containsExactly(A);
            assertThat(decisions.getFirst().logIndex()).isEqualTo(42L);
            assertThat(decisions.getFirst().stampedAt()).isEqualTo(HLC);
            assertThat(projector.announcedCoreMembers()).containsExactly(A);

            logIndex.set(43L);
            projector.onDelta(removed(A, "core"));

            assertThat(decisions).hasSize(2);
            assertThat(decisions.getLast()).isInstanceOf(MembershipDecision.NodeRemoved.class);
            assertThat(decisions.getLast().nodeId()).isEqualTo(A);
            assertThat(decisions.getLast().topology()).isEmpty();
            assertThat(decisions.getLast().logIndex()).isEqualTo(43L);
            assertThat(decisions.getLast().stampedAt()).isEqualTo(HLC);
            assertThat(pruned).containsExactly(A);
            assertThat(projector.announcedCoreMembers()).isEmpty();
            assertThat(workerLeaves)
                .as("#731: a CORE removal must never travel on the worker-leave channel")
                .isEmpty();
        }

        @Test
        void blankRole_countsAsCore_isAnnounced() {
            projector.onDelta(joined(A, ""));

            assertThat(decisions).hasSize(1);
            assertThat(decisions.getFirst()).isInstanceOf(MembershipDecision.NodeJoined.class);
        }
    }

    @Nested
    class NeverJoined {
        /// The projector-side mirror of the FSM's `everJoined` rule (the spec's tangential
        /// consideration): a REMOVED edge for a never-announced member emits nothing.
        @Test
        void removedWithoutPriorJoin_emitsNothing() {
            projector.onDelta(removed(A, "core"));

            assertThat(decisions).isEmpty();
            assertThat(pruned).isEmpty();
        }
    }

    @Nested
    class WorkerScoping {
        @Test
        void workerJoin_emitsOnWorkerChannelOnly_coreDeltaUnperturbed() {
            projector.onDelta(joined(W, "worker"));

            assertThat(decisions)
                .as("a worker must never travel on the core MembershipDecision stream")
                .isEmpty();
            assertThat(workerJoins)
                .as("#728: the worker join must still be emitted — on its own channel")
                .hasSize(1);
            assertThat(workerJoins.getFirst().nodeId()).isEqualTo(W);
            assertThat(workerJoins.getFirst().role()).isEqualTo("worker");
            assertThat(workerJoins.getFirst().stampedAt()).isEqualTo(HLC);
            assertThat(projector.announcedCoreMembers())
                .as("the core baseline must stay worker-free")
                .isEmpty();

            projector.onDelta(joined(A, "core"));

            assertThat(decisions).hasSize(1);
            assertThat(decisions.getFirst().topology())
                .as("a worker join must not perturb the core topology payload")
                .containsExactly(A);
        }

        /// #728 regression: the defect was that this emitted NOTHING ANYWHERE, so a labelled
        /// worker was never assigned a role, never minted a community, and never activated. The
        /// core-channel emptiness above is the Wave-2 invariant; this is the bug.
        @Test
        void workerJoin_reachesTheRoleAssignmentChannel_ratherThanBeingDropped() {
            projector.onDelta(joined(W, "worker"));

            assertThat(workerJoins).extracting(WorkerJoinDecision::nodeId).containsExactly(W);
        }

        @Test
        void workerJoin_repeated_emitsExactlyOnce() {
            projector.onDelta(joined(W, "worker"));
            projector.onDelta(joined(W, "worker"));
            projector.onDelta(joined(W, "worker"));

            assertThat(workerJoins)
                .as("the worker baseline mirrors the core arm's exactly-once guard")
                .hasSize(1);
        }

        /// The worker baseline MUST be pruned on removal — a departed worker's leave is now
        /// observable (#731), but the once-only guard prune below is what makes rejoin possible
        /// regardless of whether anything is emitted. Without the prune, a worker that departs
        /// stays in the once-only guard forever and its rejoin is silently swallowed — never
        /// re-assigned a role. That is the same silent non-participation #728 was, reintroduced by
        /// the fix's own dedup set.
        @Test
        void workerRejoinAfterRemoval_emitsAgain_becauseTheWorkerBaselineIsPruned() {
            projector.onDelta(joined(W, "worker"));
            projector.onDelta(removed(W, "worker"));
            projector.onDelta(joined(W, "worker"));

            assertThat(workerJoins)
                .as("a departed worker must be re-assignable on rejoin")
                .hasSize(2);
            assertThat(workerLeaves)
                .as("#731: the intervening departure must still be observed exactly once")
                .hasSize(1);
            assertThat(workerLeaves.getFirst().nodeId()).isEqualTo(W);
        }

        /// #731 regression: the defect was that a departed worker's REMOVED edge emitted NOTHING
        /// ANYWHERE, so `handleNodeRemoval` was unreachable for it and its allocation-pool slot
        /// and KV footprint (`workerNodes`/`SliceNodeKey`/`NodeArtifactKey`/`NodeRoutesKey`)
        /// lingered forever. The core-channel emptiness below is the Wave-2 invariant, unaffected
        /// by this fix; the worker-leave emission is the bug.
        @Test
        void workerRemoval_emitsOnWorkerLeaveChannelOnly_coreArmUntouched() {
            projector.onDelta(joined(W, "worker"));
            projector.onDelta(removed(W, "worker"));

            assertThat(decisions)
                .as("a worker departure must never travel on the core MembershipDecision stream")
                .isEmpty();
            assertThat(pruned)
                .as("core-only prune (TopologyObserver::pruneDeparted) must not run for a worker")
                .isEmpty();
            assertThat(workerLeaves)
                .as("#731: the worker leave must still be emitted — on its own channel")
                .hasSize(1);
            assertThat(workerLeaves.getFirst().nodeId()).isEqualTo(W);
            assertThat(workerLeaves.getFirst().stampedAt()).isEqualTo(HLC);
        }

        /// Symmetric to [`#workerJoin_repeated_emitsExactlyOnce`]: the once-only guard is
        /// `announcedWorkers.remove`, consumed by the FIRST REMOVED edge, so a structurally
        /// impossible (per the FSM's `everJoined`) duplicate REMOVED edge for the same worker
        /// still can't double-emit.
        @Test
        void workerRemoval_duplicateEdge_emitsLeaveOnce() {
            projector.onDelta(joined(W, "worker"));
            projector.onDelta(removed(W, "worker"));
            projector.onDelta(removed(W, "worker"));

            assertThat(workerLeaves)
                .as("the worker-leave channel mirrors the core arm's exactly-once guard")
                .hasSize(1);
        }

        /// The worker channel is emitted from the drain path, so it inherits the quorum gate.
        /// This matters because the consumer answers a worker join by submitting KV commands into
        /// consensus — emitting while non-quorate would produce writes that cannot commit.
        @Test
        void workerJoin_whileNonQuorate_isSuppressedThenDrainedInOrder() {
            quorate.set(false);
            projector.onDelta(joined(W, "worker"));

            assertThat(workerJoins)
                .as("a non-quorate node must not emit worker joins either")
                .isEmpty();

            quorate.set(true);
            projector.onDelta(joined(A, "core"));

            assertThat(workerJoins)
                .as("the suppressed worker join drains once quorum returns")
                .hasSize(1);
            assertThat(decisions).hasSize(1);
        }
    }

    @Nested
    class QuorumGate {
        @Test
        void nonQuorate_joinSuppressed_thenFlushedOnActiveNotification() {
            quorate.set(false);
            projector.onDelta(joined(A, "core"));

            assertThat(decisions)
                .as("a non-quorate node must not advertise membership decisions (A7)")
                .isEmpty();

            projector.onClusterStateNotification(ClusterStateNotification.active());

            assertThat(decisions)
                .as("ACTIVE echo while still non-quorate must not drain (drainer-gated)")
                .isEmpty();

            quorate.set(true);
            projector.onClusterStateNotification(ClusterStateNotification.active());

            assertThat(decisions).hasSize(1);
            assertThat(decisions.getFirst()).isInstanceOf(MembershipDecision.NodeJoined.class);
            assertThat(decisions.getFirst().nodeId()).isEqualTo(A);

            retryScheduler.runAllPending();

            assertThat(decisions)
                .as("a stale armed retry firing after the drain must be a no-op (empty queue)")
                .hasSize(1);
        }

        @Test
        void nonQuorate_removalOfAnnouncedMember_deferredThenFlushed() {
            projector.onDelta(joined(A, "core"));
            quorate.set(false);
            projector.onDelta(removed(A, "core"));

            assertThat(decisions).hasSize(1);
            assertThat(pruned).isEmpty();

            quorate.set(true);
            projector.onClusterStateNotification(ClusterStateNotification.active());

            assertThat(decisions).hasSize(2);
            assertThat(decisions.getLast()).isInstanceOf(MembershipDecision.NodeRemoved.class);
            assertThat(pruned).containsExactly(A);
        }

        /// Queue-as-pending-store replay semantics: a member that joins AND dies entirely
        /// inside a non-quorate window is REPLAYED in order on drain (`NodeJoined` then
        /// `NodeRemoved`) — the old frozen-baseline diff collapsed it to nothing; the new
        /// drainer preserves the raw episode, with an identical final announced state.
        @Test
        void joinAndDeathWithinNonQuorateWindow_replaysOrderedPair() {
            quorate.set(false);
            projector.onDelta(joined(B, "core"));
            projector.onDelta(removed(B, "core"));
            quorate.set(true);

            projector.onClusterStateNotification(ClusterStateNotification.active());

            assertThat(decisions).hasSize(2);
            assertThat(decisions.get(0)).isInstanceOf(MembershipDecision.NodeJoined.class);
            assertThat(decisions.get(1)).isInstanceOf(MembershipDecision.NodeRemoved.class);
            assertThat(pruned).containsExactly(B);
            assertThat(projector.announcedCoreMembers()).isEmpty();
        }

        /// Drainage path 1: pendings drain FIRST (FIFO), then the fresh edge — no echo needed.
        @Test
        void pendingJoin_flushedByNextQuorateEdge_pendingEmitsFirst() {
            quorate.set(false);
            projector.onDelta(joined(A, "core"));
            quorate.set(true);

            projector.onDelta(joined(B, "core"));

            assertThat(decisions).hasSize(2);
            assertThat(decisions.get(0).nodeId()).isEqualTo(A);
            assertThat(decisions.get(1).nodeId()).isEqualTo(B);
            assertThat(decisions.get(1).topology()).containsExactly(A, B);
        }

        @Test
        void passiveNotification_isIgnored() {
            quorate.set(false);
            projector.onDelta(joined(A, "core"));

            projector.onClusterStateNotification(ClusterStateNotification.passive());

            assertThat(decisions).isEmpty();
        }
    }

    @Nested
    class Idempotence {
        @Test
        void duplicateJoined_emitsOnce() {
            projector.onDelta(joined(A, "core"));
            projector.onDelta(joined(A, "core"));

            assertThat(decisions).hasSize(1);
        }

        @Test
        void duplicateRemoved_emitsOnce() {
            projector.onDelta(joined(A, "core"));
            projector.onDelta(removed(A, "core"));
            projector.onDelta(removed(A, "core"));

            assertThat(decisions).hasSize(2);
            assertThat(pruned).containsExactly(A);
        }

        /// Replay semantics for a suppressed removal followed by a rejoin: the drainer replays
        /// the raw sequence (`NodeRemoved` then a fresh `NodeJoined`) — final state identical
        /// to the old net-zero collapse, the episode itself now visible and ordered.
        @Test
        void suppressedRemovalThenRejoin_replaysRemovalThenRejoin() {
            projector.onDelta(joined(A, "core"));
            quorate.set(false);
            projector.onDelta(removed(A, "core"));
            projector.onDelta(joined(A, "core"));
            quorate.set(true);

            projector.onClusterStateNotification(ClusterStateNotification.active());

            assertThat(decisions).hasSize(3);
            assertThat(decisions.get(1)).isInstanceOf(MembershipDecision.NodeRemoved.class);
            assertThat(decisions.get(2)).isInstanceOf(MembershipDecision.NodeJoined.class);
            assertThat(pruned).containsExactly(A);
            assertThat(projector.announcedCoreMembers()).containsExactly(A);
        }
    }

    /// The live-gate CRITICAL scenario: follower boot. All delta edges arrive while the local
    /// `inQuorum()` is still false; the quorum bit later flips true WITHOUT any
    /// `ClusterStateNotification` ACTIVE echo reaching the projector; and the steady-state FSM
    /// emits no further edges. The deduped flush retry alone must drain everything, in order.
    @Nested
    class FollowerBoot {
        @Test
        void edgesPreQuorum_quorumFlipsWithNoEcho_retryDrainsEverythingInOrder() {
            quorate.set(false);
            projector.onDelta(joined(A, "core"));
            projector.onDelta(joined(B, "core"));

            assertThat(decisions).isEmpty();
            assertThat(retryScheduler.pendingCount())
                .as("suppressed undrained work must arm exactly one flush retry")
                .isEqualTo(1);

            quorate.set(true);
            retryScheduler.runAllPending();

            assertThat(decisions).hasSize(2);
            assertThat(decisions.get(0).nodeId()).isEqualTo(A);
            assertThat(decisions.get(1).nodeId()).isEqualTo(B);
            assertThat(decisions.get(1).topology()).containsExactly(A, B);
            assertThat(retryScheduler.pendingCount())
                .as("a drained queue must not re-arm the retry (bounded convergence loop)")
                .isZero();
        }

        @Test
        void retryWhileStillSuppressed_rearmsExactlyOnce_thenDrainsAfterFlip() {
            quorate.set(false);
            projector.onDelta(joined(A, "core"));
            assertThat(retryScheduler.pendingCount()).isEqualTo(1);

            retryScheduler.runAllPending();

            assertThat(decisions).isEmpty();
            assertThat(retryScheduler.pendingCount())
                .as("a still-suppressed retry re-arms exactly one successor")
                .isEqualTo(1);

            projector.onDelta(joined(B, "core"));

            assertThat(retryScheduler.pendingCount())
                .as("further suppressed edges dedupe against the pending retry")
                .isEqualTo(1);

            quorate.set(true);
            retryScheduler.runAllPending();

            assertThat(decisions).hasSize(2);
            assertThat(decisions.get(0).nodeId()).isEqualTo(A);
            assertThat(decisions.get(1).nodeId()).isEqualTo(B);
            assertThat(retryScheduler.pendingCount()).isZero();
        }

        @Test
        void quorumAlreadyTrueBeforeEdges_drainsImmediately_noRetryArmed() {
            projector.onDelta(joined(A, "core"));

            assertThat(decisions).hasSize(1);
            assertThat(decisions.getFirst().nodeId()).isEqualTo(A);
            assertThat(retryScheduler.scheduledCount())
                .as("an immediately-drained edge must never touch the retry scheduler")
                .isZero();
        }
    }

    /// Manual retry scheduler — captures `(Runnable, delay)` pairs without background
    /// execution; tests drive fire explicitly (mirrors `LeaderReconcilerTest.ManualScheduler`).
    private static final class ManualRetryScheduler implements NttTimerScheduler {
        private final List<ManualRetryTask> tasks = new ArrayList<>();

        @Override
        public synchronized ScheduledFuture<?> schedule(Runnable runnable, TimeSpan delay) {
            var task = new ManualRetryTask(runnable);
            tasks.add(task);
            return task;
        }

        synchronized long pendingCount() {
            return tasks.stream().filter(ManualRetryTask::pending).count();
        }

        synchronized int scheduledCount() {
            return tasks.size();
        }

        /// Fire every currently-pending task exactly once (a task armed BY a firing task
        /// stays pending for the next call — letting tests observe each re-arm step).
        @Contract
        synchronized void runAllPending() {
            List.copyOf(tasks).stream().filter(ManualRetryTask::pending).forEach(ManualRetryTask::runIfLive);
        }
    }

    private static final class ManualRetryTask implements ScheduledFuture<Object> {
        private final Runnable runnable;
        private volatile boolean cancelled;
        private volatile boolean done;

        ManualRetryTask(Runnable runnable) {
            this.runnable = runnable;
        }

        boolean pending() {
            return !cancelled && !done;
        }

        @Contract
        void runIfLive() {
            if (cancelled || done) {
                return;
            }
            done = true;
            runnable.run();
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return 0L;
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS));
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (done) {
                return false;
            }
            cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return cancelled || done;
        }

        @Override
        public Object get() {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            return null;
        }
    }
}

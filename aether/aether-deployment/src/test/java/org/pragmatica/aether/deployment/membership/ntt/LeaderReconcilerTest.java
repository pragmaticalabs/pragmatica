// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.ntt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.aether.deployment.cluster.DrainReason;
import org.pragmatica.aether.deployment.cluster.NodeReconcilerState;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.consensus.topology.TransportObservation;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.TimeSource;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.swim.HealthSnapshot;
import org.pragmatica.swim.SwimHealth;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.membership.MembershipConfig.membershipConfig;
import static org.pragmatica.aether.deployment.membership.ntt.LeaderReconciler.leaderReconciler;
import static org.pragmatica.aether.deployment.membership.ntt.NodeTopologyTracker.nodeTopologyTracker;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Unit tests for [`LeaderReconciler`] (E2 Phase 1.6) — state-derived
/// reconciliation sourcing membership from NTT. No periodic tick; the
/// leader-activation reconcile is a single delayed one-shot at
/// `nttDepartureTimeout × 1.5`.
class LeaderReconcilerTest {
    private static final NodeId SELF = NodeId.randomNodeId();
    private static final NodeId PEER_A = NodeId.randomNodeId();
    private static final NodeId PEER_B = NodeId.randomNodeId();
    private static final NodeId PEER_C = NodeId.randomNodeId();
    private static final NodeId PEER_D = NodeId.randomNodeId();
    private static final TimeSpan EXPECTED_ACTIVATION_DELAY =
        timeSpan(membershipConfig().nttDepartureTimeout().nanos() * 3 / 2).nanos();
    private static final TimeSpan EXPECTED_INFLIGHT_EXPIRY =
        timeSpan(membershipConfig().nttDepartureTimeout().nanos() * 3).nanos();
    private static final TimeSpan EXPECTED_GRACE_WINDOW =
        timeSpan(membershipConfig().nttDepartureTimeout().nanos() * 3 / 2).nanos();
    private static final TimeSpan EXPECTED_DEBOUNCE_WINDOW = membershipConfig().nttDepartureTimeout();
    private static final TimeSpan DEBOUNCE_DELAY = timeSpan(100L).millis();

    private TestTimeSource timeSource;
    private ManualScheduler scheduler;
    private RecordingListener listener;
    private MutableIntSupplier configuredCoreCount;
    private MutableLongSupplier leaderTerm;
    private RecordingCtm ctm;
    private MutableHealthSource health;
    private NodeTopologyTracker ntt;
    private LeaderReconciler reconciler;

    @BeforeEach
    void setUp() {
        timeSource = new TestTimeSource();
        scheduler = new ManualScheduler();
        listener = new RecordingListener();
        configuredCoreCount = new MutableIntSupplier(0);
        leaderTerm = new MutableLongSupplier(1L);
        ctm = new RecordingCtm();
        health = new MutableHealthSource();
        // upHysteresis = downHysteresis = 1 so a single sample() converges the member set
        // deterministically — the reconciler reads ntt.currentMembers() synchronously.
        ntt = nodeTopologyTracker(SELF,
                                  health,
                                  timeSpan(1).seconds(),
                                  1,
                                  1,
                                  timeSource::nanoTime);
        reconciler = leaderReconciler(membershipConfig(),
                                      ntt,
                                      configuredCoreCount,
                                      leaderTerm,
                                      ctm,
                                      () -> "test-cluster",
                                      timeSource,
                                      scheduler);
        reconciler.setReconcileListener(listener);
    }

    /// Feed N healthy peers into the NTT health snapshot, then sample so the stable member
    /// set (which always includes `SELF`) absorbs them. The reconciler's
    /// `clusterMembershipCount` reads via `ntt.currentMembers().size()`.
    @Contract
    private void seedClusterWithPeers(NodeId... peers) {
        for (var peer : peers) {
            health.markHealthy(peer);
        }
        ntt.sample();
    }

    /// Mark peers absent in the NTT health snapshot (simulates a departure) and sample so the
    /// next reconcile observes a deficit.
    @Contract
    private void removePeers(NodeId... peers) {
        for (var peer : peers) {
            health.markAbsent(peer);
        }
        ntt.sample();
    }

    /// Drive the post-activation reconcile path: fire the queued debounced reconcile that
    /// the given trigger scheduled.
    @Contract
    private void fireDebouncedReconcile() {
        scheduler.tasksByDelay(DEBOUNCE_DELAY).getLast().runIfLive();
    }

    /// Advance the test clock past BOTH provisioning gates (cold-start grace = nttDepartureTimeout
    /// × 1.5, then deficit debounce = nttDepartureTimeout) so a sustained, armed, quorum-safe
    /// deficit is allowed to provision. Used by tests that assert auto-heal FIRES — they create a
    /// deficit, run one (suppressed) reconcile pass that anchors the deficit run, advance past the
    /// gates, then run a second pass that provisions.
    @Contract
    private void advancePastProvisioningGates() {
        timeSource.advanceTimeMillis(EXPECTED_GRACE_WINDOW.millis() + EXPECTED_DEBOUNCE_WINDOW.millis() + 1);
    }

    /// Convenience: trigger an NTT-fire reconcile and fire its debounced pass. The standard
    /// "observe current membership and reconcile" step used between time advances.
    @Contract
    private void triggerAndFireReconcile() {
        reconciler.onTopologyUnhealthy();
        fireDebouncedReconcile();
    }

    @Nested
    class SeedInFlightFromRetainedDispatched {
        /// Provisioning-stickiness fix — a retained dispatched id that is NOT a current member is
        /// seeded into in-flight on `activate()`, so the next reconcile counts it toward `effective`
        /// and does NOT re-dispatch a replacement the prior leader already provisioned.
        @Test
        void activate_seedsRetainedDispatchedIdNotYetAMember_intoInFlight() {
            configuredCoreCount.set(5);
            seedClusterWithPeers(PEER_A, PEER_B);
            var dispatchedNotYetMember = NodeId.randomNodeId();
            reconciler.setRetainedDispatchedSupplier(() -> Set.of(dispatchedNotYetMember));

            reconciler.activate();

            assertThat(reconciler.inFlightProvisioningSnapshot()).containsKey(dispatchedNotYetMember);
            assertThat(reconciler.inFlightProvisioningKeys()).containsExactly(dispatchedNotYetMember);
        }

        /// A retained id that is ALREADY a current member is NOT seeded — the provision is fulfilled,
        /// so tracking it in-flight would double-count it in `effective`.
        @Test
        void activate_doesNotSeedRetainedIdThatIsAlreadyAMember() {
            configuredCoreCount.set(5);
            seedClusterWithPeers(PEER_A, PEER_B);
            // PEER_A is a current member; the prior leader's retained set still lists it (race window).
            reconciler.setRetainedDispatchedSupplier(() -> Set.of(PEER_A));

            reconciler.activate();

            assertThat(reconciler.inFlightProvisioningSnapshot()).doesNotContainKey(PEER_A);
            assertThat(reconciler.inFlightProvisioningCount()).isZero();
        }

        /// Mixed retained set: the non-member id is seeded, the already-member id is skipped.
        @Test
        void activate_seedsOnlyNonMemberRetainedIds() {
            configuredCoreCount.set(5);
            seedClusterWithPeers(PEER_A, PEER_B);
            var dispatchedNotYetMember = NodeId.randomNodeId();
            reconciler.setRetainedDispatchedSupplier(() -> Set.of(PEER_A, dispatchedNotYetMember));

            reconciler.activate();

            assertThat(reconciler.inFlightProvisioningKeys()).containsExactly(dispatchedNotYetMember);
        }

        /// Default (no supplier wired) and an empty retained set both seed nothing — inert until wired.
        @Test
        void activate_emptyOrUnwiredRetainedSet_seedsNothing() {
            configuredCoreCount.set(5);
            seedClusterWithPeers(PEER_A, PEER_B);

            reconciler.activate();

            assertThat(reconciler.inFlightProvisioningCount()).isZero();
        }

        /// The seed feeds `effectiveCapacity`: a seeded in-flight id closes the deficit so the next
        /// reconcile does NOT dispatch a (duplicate) provision for it. This is the over-provisioning
        /// fix end-to-end at the reconciler level — a re-elected leader inherits, not re-dispatches.
        @Test
        void seededInFlightId_suppressesReDispatchOnReconcile() {
            configuredCoreCount.set(5);
            // Re-election onto an already-formed, now-deficient cluster (4/5): SELF + A + B + C = 4.
            leaderTerm.set(2L);
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C);
            // The prior leader had already dispatched ONE replacement for the missing 5th node.
            var priorDispatch = NodeId.randomNodeId();
            reconciler.setRetainedDispatchedSupplier(() -> Set.of(priorDispatch));

            reconciler.activate();
            // Re-election pre-latches reachedFullMembership and the seed makes effective = 4 + 1 = 5.
            assertThat(reconciler.inFlightProvisioningKeys()).containsExactly(priorDispatch);

            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();
            advancePastProvisioningGates();
            triggerAndFireReconcile();

            // effective (4 members + 1 seeded in-flight) == configured 5 → NO new provision dispatched.
            assertThat(ctm.provisionReplacementCalls()).isEmpty();
        }
    }

    @Nested
    class DefaultState {
        @Test
        void freshReconciler_isNotLeader_andSchedulesNoActivation() {
            assertThat(reconciler.isLeader()).isFalse();
            assertThat(reconciler.inFlightProvisioningCount()).isZero();
            assertThat(reconciler.leaderActivationDelay()).isEqualTo(EXPECTED_ACTIVATION_DELAY);
            assertThat(reconciler.inFlightProvisioningSnapshot()).isEmpty();
            assertThat(scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY)).isEmpty();
        }
    }

    @Nested
    class LeaderActivation {
        @Test
        void activate_doesNotEmitImmediateIntent_schedulesOneShotDelayedReconcile() {
            configuredCoreCount.set(5);
            seedClusterWithPeers(PEER_A, PEER_B);

            reconciler.activate();

            assertThat(reconciler.isLeader()).isTrue();
            // No immediate reconcile — the delay lets SWIM/QUIC quiesce.
            assertThat(listener.events()).isEmpty();
            assertThat(ctm.provisionReplacementCalls()).isEmpty();
            assertThat(ctm.drainNodeCalls()).isEmpty();
            // Exactly one one-shot scheduled at the activation delay.
            assertThat(scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY)).hasSize(1);
        }

        @Test
        void activationDelayFires_emitsLeaderActivationIntent_andDispatchesProvisions() {
            // Bug C: provisioning is gated behind the arm-after-first-full-membership latch.
            // Reach full configured membership (5) so activation arms; then drop two peers
            // so the post-arm deficit dispatches two provisions.
            configuredCoreCount.set(5);
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C, PEER_D);
            reconciler.activate();

            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();
            assertThat(reconciler.isArmedForProvisioning()).isTrue();
            assertThat(listener.events().getFirst().provisionCount()).isZero();

            listener.clear();
            removePeers(PEER_C, PEER_D);
            // First pass anchors the deficit run (suppressed by grace + debounce); advance past
            // both gates so the sustained deficit provisions on the second pass.
            triggerAndFireReconcile();
            assertThat(ctm.provisionReplacementCalls()).isEmpty();
            listener.clear();
            advancePastProvisioningGates();
            triggerAndFireReconcile();

            assertThat(listener.events()).hasSize(1);
            var emitted = listener.events().getFirst();
            assertThat(emitted.trigger()).isEqualTo(ReconcileTrigger.NTT_FIRE);
            assertThat(emitted.clusterMembershipCount()).isEqualTo(3);
            assertThat(emitted.configuredCoreCount()).isEqualTo(5);
            assertThat(emitted.provisionCount()).isEqualTo(2);
            assertThat(emitted.drainCount()).isZero();
            assertThat(ctm.provisionReplacementCalls()).hasSize(2);
        }

        @Test
        void activate_isIdempotent_secondCallNoOp() {
            configuredCoreCount.set(5);
            reconciler.activate();
            var firstCount = scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).size();

            reconciler.activate();

            assertThat(scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY)).hasSize(firstCount);
        }
    }

    @Nested
    class LeaderDeactivation {
        @Test
        void deactivate_cancelsPendingActivation_clearsLeaderFlag() {
            configuredCoreCount.set(5);
            reconciler.activate();
            var activationTask = scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst();

            reconciler.deactivate();

            assertThat(reconciler.isLeader()).isFalse();
            assertThat(activationTask.cancelled()).isTrue();
            assertThat(reconciler.inFlightProvisioningCount()).isZero();
            assertThat(listener.events()).isEmpty();
        }

        @Test
        void deactivate_isIdempotent_secondCallNoOp() {
            reconciler.activate();
            reconciler.deactivate();

            reconciler.deactivate();

            assertThat(reconciler.isLeader()).isFalse();
        }
    }

    @Nested
    class TopologyUnhealthyIngress {
        @Test
        void onTopologyUnhealthy_whileLeader_emitsNttFireIntent_throughDebounce() {
            configuredCoreCount.set(5);
            seedClusterWithPeers(PEER_A, PEER_B, NodeId.randomNodeId());
            reconciler.activate();
            listener.clear();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().cancel(false);

            reconciler.onTopologyUnhealthy();
            scheduler.tasksByDelay(DEBOUNCE_DELAY).getFirst().runIfLive();

            assertThat(listener.events()).hasSize(1);
            var emitted = listener.events().getFirst();
            assertThat(emitted.trigger()).isEqualTo(ReconcileTrigger.NTT_FIRE);
            assertThat(emitted.clusterMembershipCount()).isEqualTo(4);
            assertThat(emitted.configuredCoreCount()).isEqualTo(5);
        }

        @Test
        void onTopologyUnhealthy_whileNotLeader_emitsNothing() {
            reconciler.onTopologyUnhealthy();

            assertThat(scheduler.tasksByDelay(DEBOUNCE_DELAY)).isEmpty();
            assertThat(listener.events()).isEmpty();
        }
    }

    @Nested
    class QuorumLossIngress {
        @Test
        void onQuorumLossIntent_emitsQuorumLossReconcileIntent_evenIfNotLeader() {
            reconciler.onQuorumLossIntent(QuorumLossIntent.quorumLossIntent(timeSource.nanoTime(), 2, 3));
            scheduler.tasksByDelay(DEBOUNCE_DELAY).getFirst().runIfLive();

            assertThat(listener.events()).hasSize(1);
            assertThat(listener.events().getFirst().trigger()).isEqualTo(ReconcileTrigger.QUORUM_LOSS);
        }
    }

    @Nested
    class MemberAppearedIngress {
        @Test
        void onSwimMemberHealthy_whileLeader_emitsMemberAppearedIntent() {
            configuredCoreCount.set(3);
            seedClusterWithPeers(PEER_A, PEER_B, NodeId.randomNodeId(), NodeId.randomNodeId());
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().cancel(false);
            listener.clear();

            reconciler.onSwimMemberHealthy(PEER_A, 1L);
            scheduler.tasksByDelay(DEBOUNCE_DELAY).getFirst().runIfLive();

            assertThat(listener.events()).hasSize(1);
            var emitted = listener.events().getFirst();
            assertThat(emitted.trigger()).isEqualTo(ReconcileTrigger.MEMBER_APPEARED);
            assertThat(emitted.drainCount()).isEqualTo(2);
            assertThat(emitted.provisionCount()).isZero();
        }

        @Test
        void onSwimMemberHealthy_whileNotLeader_emitsNothing() {
            reconciler.onSwimMemberHealthy(PEER_A, 1L);

            assertThat(scheduler.tasksByDelay(DEBOUNCE_DELAY)).isEmpty();
            assertThat(listener.events()).isEmpty();
        }
    }

    @Nested
    class ConfigChangeIngress {
        @Test
        void onConfigChange_whileLeader_emitsConfigChangeIntent() {
            configuredCoreCount.set(5);
            seedClusterWithPeers(PEER_A, PEER_B);
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().cancel(false);
            listener.clear();

            reconciler.onConfigChange();
            scheduler.tasksByDelay(DEBOUNCE_DELAY).getFirst().runIfLive();

            assertThat(listener.events()).hasSize(1);
            assertThat(listener.events().getFirst().trigger()).isEqualTo(ReconcileTrigger.CONFIG_CHANGE);
        }

        @Test
        void onConfigChange_whileNotLeader_emitsNothing() {
            reconciler.onConfigChange();

            assertThat(scheduler.tasksByDelay(DEBOUNCE_DELAY)).isEmpty();
            assertThat(listener.events()).isEmpty();
        }
    }

    @Nested
    class CasDebounce {
        @Test
        void rapidBurstOfTriggers_collapsesIntoAtMostTwoReconcilePasses() {
            configuredCoreCount.set(5);
            seedClusterWithPeers(PEER_A, PEER_B, NodeId.randomNodeId());
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().cancel(false);
            listener.clear();

            // 5 rapid events: the first sets in-flight + schedules; the 4 others set
            // rescheduleRequested. After the first reconcile completes and clears the
            // in-flight flag, exactly one follow-up is scheduled.
            reconciler.onTopologyUnhealthy();
            reconciler.onTopologyUnhealthy();
            reconciler.onTopologyUnhealthy();
            reconciler.onTopologyUnhealthy();
            reconciler.onTopologyUnhealthy();

            var firstDebounced = scheduler.tasksByDelay(DEBOUNCE_DELAY);
            assertThat(firstDebounced).hasSize(1);
            firstDebounced.getFirst().runIfLive();

            // First reconcile fired; the rescheduleRequested follow-up is now scheduled.
            var followUps = scheduler.tasksByDelay(DEBOUNCE_DELAY);
            assertThat(followUps).hasSize(2);
            followUps.get(1).runIfLive();

            assertThat(listener.events()).hasSize(2);

            // After the follow-up runs, no new reschedule was set, so no further task.
            assertThat(scheduler.tasksByDelay(DEBOUNCE_DELAY)).hasSize(2);
        }
    }

    @Nested
    class ReconcileSnapshot {
        @Test
        void underprovisionedSnapshot_intentReflectsObservedAndConfiguredCounts() {
            // Arm at full membership (5), then drop two peers to create the post-arm deficit.
            configuredCoreCount.set(5);
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C, PEER_D);
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();
            listener.clear();

            removePeers(PEER_C, PEER_D);
            triggerAndFireReconcile();
            listener.clear();
            advancePastProvisioningGates();
            triggerAndFireReconcile();

            var intent = listener.events().getFirst();
            assertThat(intent.clusterMembershipCount()).isEqualTo(3);
            assertThat(intent.configuredCoreCount()).isEqualTo(5);
            assertThat(intent.inFlightProvisioningCount()).isEqualTo(2);
            assertThat(intent.provisionCount()).isEqualTo(2);
            assertThat(intent.drainCount()).isZero();
        }

        @Test
        void overprovisionedSnapshot_intentReflectsObservedAndConfiguredCounts() {
            configuredCoreCount.set(3);
            seedClusterWithPeers(PEER_A, PEER_B, NodeId.randomNodeId(), NodeId.randomNodeId());

            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();

            var intent = listener.events().getFirst();
            assertThat(intent.clusterMembershipCount()).isEqualTo(5);
            assertThat(intent.configuredCoreCount()).isEqualTo(3);
            assertThat(intent.drainCount()).isEqualTo(2);
            assertThat(intent.provisionCount()).isZero();
            assertThat(ctm.drainNodeCalls()).hasSize(2);
        }

        /// Quorum-safety guard (spec §7.2, §I5; sub-quorum-must-dissolve). With
        /// `configured=5` the quorum threshold is `5/2+1 = 3`; a membership of 2 (SELF +
        /// PEER_A) is below quorum, so the reconciler MUST NOT provision replacements — a
        /// partitioned minority that provisioned would spawn a phantom split-brain cluster.
        /// The observability intent is still emitted (with `provisionCount==0`) and no
        /// `provisionReplacement` actuation reaches the CTM.
        @Test
        void runReconcile_belowQuorum_suppressesProvisioning() {
            configuredCoreCount.set(5);
            seedClusterWithPeers(PEER_A);

            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();

            var intent = listener.events().getFirst();
            assertThat(intent.clusterMembershipCount()).isEqualTo(2);
            assertThat(intent.configuredCoreCount()).isEqualTo(5);
            assertThat(intent.provisionCount()).isZero();
            assertThat(intent.drainCount()).isZero();
            assertThat(ctm.provisionReplacementCalls()).isEmpty();
        }
    }

    @Nested
    class InFlightExpiry {
        @Test
        void staleInFlightEntryPastExpiryWindow_isEvictedOnNextReconcile() {
            // Arm at full membership (5), drop two peers to provision two (in-flight=2),
            // then advance PAST the (×3) expiry window and reconcile — the stale entries evict.
            configuredCoreCount.set(5);
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C, PEER_D);
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();
            removePeers(PEER_C, PEER_D);
            triggerAndFireReconcile();
            advancePastProvisioningGates();
            triggerAndFireReconcile();
            assertThat(reconciler.inFlightProvisioningCount()).isEqualTo(2);
            listener.clear();
            seedClusterWithPeers(NodeId.randomNodeId(), NodeId.randomNodeId());
            timeSource.advanceTimeMillis(EXPECTED_INFLIGHT_EXPIRY.millis() + 1);

            reconciler.onTopologyUnhealthy();
            fireDebouncedReconcile();

            assertThat(listener.events()).hasSize(1);
            assertThat(listener.events().getFirst().inFlightProvisioningCount()).isZero();
            assertThat(reconciler.inFlightProvisioningCount()).isZero();
        }

        @Test
        void freshInFlightEntryWithinExpiryWindow_isNotEvicted() {
            // Arm at full membership (5), drop two peers to provision two (in-flight=2),
            // then advance LESS than the (×3) expiry window and reconcile — entries survive.
            configuredCoreCount.set(5);
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C, PEER_D);
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();
            removePeers(PEER_C, PEER_D);
            triggerAndFireReconcile();
            advancePastProvisioningGates();
            triggerAndFireReconcile();
            assertThat(reconciler.inFlightProvisioningCount()).isEqualTo(2);
            listener.clear();
            timeSource.advanceTimeMillis(EXPECTED_INFLIGHT_EXPIRY.millis() - 1);

            reconciler.onTopologyUnhealthy();
            fireDebouncedReconcile();

            assertThat(reconciler.inFlightProvisioningCount()).isEqualTo(2);
        }
    }

    @Nested
    class ProvisioningArmLatch {
        /// Bug C: a sub-quorum membership from the very start (initial formation / slow join).
        /// configured=5 → quorum threshold 3; SELF+PEER_A = 2 is below quorum, so the latch never
        /// arms and the reconciler must NOT provision phantom replacements for peers still joining.
        @Test
        void deficitFromStart_belowQuorum_provisionsNothing_andStaysUnarmed() {
            configuredCoreCount.set(5);
            seedClusterWithPeers(PEER_A);
            reconciler.activate();

            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();

            assertThat(reconciler.isArmedForProvisioning()).isFalse();
            var intent = listener.events().getFirst();
            assertThat(intent.clusterMembershipCount()).isEqualTo(2);
            assertThat(intent.configuredCoreCount()).isEqualTo(5);
            assertThat(intent.provisionCount()).isZero();
            assertThat(ctm.provisionReplacementCalls()).isEmpty();
        }

        /// Reaching full configured membership arms the latch; a subsequent genuine
        /// departure (member that WAS present) triggers auto-heal provisioning.
        @Test
        void reachFullMembershipThenDrop_arms_andProvisionsOnDeficit() {
            configuredCoreCount.set(5);
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C, PEER_D);
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();
            assertThat(reconciler.isArmedForProvisioning()).isTrue();
            assertThat(listener.events().getFirst().provisionCount()).isZero();
            listener.clear();

            removePeers(PEER_D);
            triggerAndFireReconcile();
            advancePastProvisioningGates();
            listener.clear();
            triggerAndFireReconcile();

            assertThat(reconciler.isArmedForProvisioning()).isTrue();
            var intent = listener.events().getFirst();
            assertThat(intent.clusterMembershipCount()).isEqualTo(4);
            assertThat(intent.provisionCount()).isEqualTo(1);
            assertThat(ctm.provisionReplacementCalls()).hasSize(1);
        }

        /// The latch is set-only: arm, drop (provision), recover to full, drop again — each
        /// post-arm deficit provisions, the latch never resets.
        @Test
        void latchPersists_provisionsOnEachPostArmDeficit() {
            configuredCoreCount.set(5);
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C, PEER_D);
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();
            assertThat(reconciler.isArmedForProvisioning()).isTrue();

            // First post-arm deficit: drop PEER_D. One suppressed pass anchors the deficit, then
            // advance past the gates so the sustained deficit provisions.
            removePeers(PEER_D);
            triggerAndFireReconcile();
            advancePastProvisioningGates();
            triggerAndFireReconcile();
            assertThat(reconciler.inFlightProvisioningCount()).isEqualTo(1);
            assertThat(ctm.provisionReplacementCalls()).hasSize(1);

            // Recover to full membership and let the in-flight entry expire (past ×3 window). The
            // recovery pass clears the deficit anchor (effective >= configured).
            seedClusterWithPeers(PEER_D);
            timeSource.advanceTimeMillis(EXPECTED_INFLIGHT_EXPIRY.millis() + 1);
            reconciler.onSwimMemberHealthy(PEER_D, 1L);
            fireDebouncedReconcile();
            assertThat(reconciler.inFlightProvisioningCount()).isZero();

            // Second post-arm deficit: grace is one-time (already elapsed), but the debounce
            // re-anchors for this new deficit run, so it still needs a suppressed pass + advance.
            removePeers(PEER_C);
            triggerAndFireReconcile();
            timeSource.advanceTimeMillis(EXPECTED_DEBOUNCE_WINDOW.millis() + 1);
            triggerAndFireReconcile();

            assertThat(reconciler.isArmedForProvisioning()).isTrue();
            assertThat(ctm.provisionReplacementCalls()).hasSize(2);
            assertThat(reconciler.inFlightProvisioningCount()).isEqualTo(1);
        }
    }

    /// Cold-start grace + deficit-debounce gates on the PROVISIONING decision (the convergence
    /// phantom-provisioning fix). Both gates AND into provisioning (in addition to the arm latch
    /// and quorum-safety): (a) suppress until `nttDepartureTimeout × 1.5` past the arm time
    /// (covers the post-election QUIC reconnect churn); (b) suppress until the deficit has held
    /// past `nttDepartureTimeout` (a transient dip resolves within it; a real departure persists).
    /// The drain path is NOT gated by either — verified in [`DrainSafetyFloor`].
    @Nested
    class ColdStartGraceAndDebounce {
        /// Scenario 1 — the exact cold-start bug. The leader arms at quorum during formation, then
        /// `ntt.currentMembers()` transiently shows fewer than configured while QUIC peers are
        /// still reconnecting (NOT a departure). Reconciling DURING the grace window must provision
        /// nothing — no phantom replacements for mid-reconnect peers.
        @Test
        void transientSubCountDuringGraceWindow_provisionsNothing() {
            configuredCoreCount.set(5);
            // Arm at full membership (5) so armedAtNanos anchors at t0.
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C, PEER_D);
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();
            assertThat(reconciler.isArmedForProvisioning()).isTrue();
            listener.clear();

            // Transient deficit (two peers mid-reconnect) WITHIN the grace window. Both reconcile
            // passes stay inside the grace window (total advance < grace), so grace binds even
            // though the deficit has technically persisted long enough to satisfy debounce alone.
            removePeers(PEER_C, PEER_D);
            timeSource.advanceTimeMillis(EXPECTED_GRACE_WINDOW.millis() / 4);
            triggerAndFireReconcile();
            timeSource.advanceTimeMillis(EXPECTED_GRACE_WINDOW.millis() / 4);
            triggerAndFireReconcile();

            var intent = listener.events().getLast();
            assertThat(intent.provisionCount()).isZero();
            assertThat(ctm.provisionReplacementCalls()).isEmpty();
        }

        /// Scenario 2 — past the grace window, but the deficit resolves within the debounce window
        /// (the reconnecting peers came back). No provision: a single transient sub-count pass that
        /// recovers before the debounce elapses must not auto-heal.
        @Test
        void transientSubCountAfterGraceButResolvesWithinDebounce_provisionsNothing() {
            configuredCoreCount.set(5);
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C, PEER_D);
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();
            assertThat(reconciler.isArmedForProvisioning()).isTrue();
            // Clear the grace window so only the debounce gate is under test.
            timeSource.advanceTimeMillis(EXPECTED_GRACE_WINDOW.millis() + 1);
            listener.clear();

            // Deficit appears (anchors the run), then resolves before the debounce window elapses.
            removePeers(PEER_D);
            triggerAndFireReconcile();
            assertThat(listener.events().getLast().provisionCount()).isZero();
            timeSource.advanceTimeMillis(EXPECTED_DEBOUNCE_WINDOW.millis() / 2);
            seedClusterWithPeers(PEER_D);
            reconciler.onSwimMemberHealthy(PEER_D, 1L);
            fireDebouncedReconcile();

            assertThat(ctm.provisionReplacementCalls()).isEmpty();
            assertThat(listener.events().getLast().provisionCount()).isZero();
        }

        /// Scenario 3 — past the grace window and the deficit PERSISTS beyond the debounce window:
        /// auto-heal fires. This is the genuine-departure path that must NOT be permanently
        /// suppressed by the gates.
        @Test
        void persistentDeficitAfterGraceHeldBeyondDebounce_provisions() {
            configuredCoreCount.set(5);
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C, PEER_D);
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();
            assertThat(reconciler.isArmedForProvisioning()).isTrue();
            timeSource.advanceTimeMillis(EXPECTED_GRACE_WINDOW.millis() + 1);
            listener.clear();

            // A genuine departure: PEER_D leaves and never returns. First pass anchors the deficit
            // (suppressed); after the debounce window elapses the sustained deficit provisions.
            removePeers(PEER_D);
            triggerAndFireReconcile();
            assertThat(listener.events().getLast().provisionCount()).isZero();
            assertThat(ctm.provisionReplacementCalls()).isEmpty();
            timeSource.advanceTimeMillis(EXPECTED_DEBOUNCE_WINDOW.millis() + 1);
            triggerAndFireReconcile();

            var intent = listener.events().getLast();
            assertThat(intent.clusterMembershipCount()).isEqualTo(4);
            assertThat(intent.provisionCount()).isEqualTo(1);
            assertThat(ctm.provisionReplacementCalls()).hasSize(1);
        }

        /// Scenario 4 — genuine multi-kill: several peers die at once leaving only the quorum-
        /// holding survivors below full count, deficit persists. The gates must NOT permanently
        /// suppress this — after grace + debounce the survivors-only auto-heal provisions the gap
        /// (here configured=5, survivors SELF+PEER_A+PEER_B = 3 == quorum, gap = 2).
        @Test
        void genuineMultiKillSurvivorsBelowFull_provisionsAfterDebounce() {
            configuredCoreCount.set(5);
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C, PEER_D);
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();
            assertThat(reconciler.isArmedForProvisioning()).isTrue();
            timeSource.advanceTimeMillis(EXPECTED_GRACE_WINDOW.millis() + 1);
            listener.clear();

            // Multi-kill: PEER_C and PEER_D die together; survivors = SELF + PEER_A + PEER_B = 3
            // (== quorum threshold), a persistent deficit of 2.
            removePeers(PEER_C, PEER_D);
            triggerAndFireReconcile();
            assertThat(ctm.provisionReplacementCalls()).isEmpty();
            timeSource.advanceTimeMillis(EXPECTED_DEBOUNCE_WINDOW.millis() + 1);
            triggerAndFireReconcile();

            var intent = listener.events().getLast();
            assertThat(intent.clusterMembershipCount()).isEqualTo(3);
            assertThat(intent.provisionCount()).isEqualTo(2);
            assertThat(ctm.provisionReplacementCalls()).hasSize(2);
        }

        /// Observability — the two gate windows are derived from `nttDepartureTimeout`
        /// (× 1.5 for grace, × 1 for debounce) and exposed for operators/metrics.
        @Test
        void gateWindows_derivedFromDepartureTimeout() {
            assertThat(reconciler.provisioningGraceWindow()).isEqualTo(EXPECTED_GRACE_WINDOW);
            assertThat(reconciler.deficitDebounceWindow()).isEqualTo(EXPECTED_DEBOUNCE_WINDOW);
        }
    }

    /// Reached-full-membership latch decision table (the auto-heal fix). The buggy timer-anchored
    /// cold-start grace is replaced by a FACT latch: provisioning is suppressed
    /// (`COLD_START_NOT_FULL`) while the cluster has never been observed at full configured size;
    /// once full is seen (or this leader was re-elected onto an already-formed cluster) a deficit is
    /// a departure, gated only by deficit-debounce + quorum-safety + the arm latch. A pass suppressed
    /// purely by the debounce schedules a single re-evaluation follow-up so a stable-in-deficit
    /// cluster is healed without any further NTT event.
    @Nested
    class ReachedFullMembershipLatch {
        /// Row 1 — cold-start climbing: count 1..4 below configured, never reached full, deficit.
        /// Provisioning is suppressed (`COLD_START_NOT_FULL` — Bug-C guard preserved): a deficit
        /// during formation may be a slow-joining configured peer, never a phantom replacement.
        @Test
        void coldStartClimbingBelowFull_provisionsNothing_andStaysUnlatched() {
            configuredCoreCount.set(5);
            // SELF + PEER_A..PEER_C = 4 (>= quorum 3, but < full 5). Never reaches full.
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C);
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();
            // A second pass after a long advance — still below full, still suppressed by cold-start.
            advancePastProvisioningGates();
            triggerAndFireReconcile();

            assertThat(reconciler.isReachedFullMembership()).isFalse();
            var intent = listener.events().getLast();
            assertThat(intent.clusterMembershipCount()).isEqualTo(4);
            assertThat(intent.configuredCoreCount()).isEqualTo(5);
            assertThat(intent.provisionCount()).isZero();
            assertThat(ctm.provisionReplacementCalls()).isEmpty();
        }

        /// Row 2 — reaching full configured membership latches `reachedFullMembership` true.
        @Test
        void countReachesConfigured_latchesReachedFullMembership() {
            configuredCoreCount.set(5);
            assertThat(reconciler.isReachedFullMembership()).isFalse();

            seedClusterWithPeers(PEER_A, PEER_B, PEER_C, PEER_D);
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();

            assertThat(reconciler.isReachedFullMembership()).isTrue();
        }

        /// Row 3 — after the latch, a departure that holds past the debounce while quorum-safe and
        /// armed PERMITS provisioning (peersToProvision non-empty, dispatch logged). This is the
        /// auto-heal path the buggy grace used to wedge forever.
        @Test
        void afterLatch_departurePastDebounceQuorumSafeArmed_provisions() {
            configuredCoreCount.set(5);
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C, PEER_D);
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();
            assertThat(reconciler.isReachedFullMembership()).isTrue();
            listener.clear();

            // Genuine departure: PEER_D leaves. First pass anchors the deficit (suppressed by
            // debounce); advance past the debounce window, then provision on the second pass.
            removePeers(PEER_D);
            triggerAndFireReconcile();
            assertThat(ctm.provisionReplacementCalls()).isEmpty();
            timeSource.advanceTimeMillis(EXPECTED_DEBOUNCE_WINDOW.millis() + 1);
            listener.clear();
            triggerAndFireReconcile();

            var intent = listener.events().getLast();
            assertThat(intent.clusterMembershipCount()).isEqualTo(4);
            assertThat(intent.provisionCount()).isEqualTo(1);
            assertThat(ctm.provisionReplacementCalls()).hasSize(1);
        }

        /// Row 4 — after the latch, a departure whose debounce has NOT elapsed is suppressed
        /// (`WITHIN_DEBOUNCE`) AND a single re-evaluation follow-up reconcile is scheduled so the
        /// deficit is acted on when the gate clears, without any further NTT event.
        @Test
        void afterLatch_departureWithinDebounce_suppressedAndSchedulesReEval() {
            configuredCoreCount.set(5);
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C, PEER_D);
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();
            assertThat(reconciler.isReachedFullMembership()).isTrue();
            listener.clear();

            // Departure observed; debounce not yet elapsed → suppressed this pass.
            removePeers(PEER_D);
            triggerAndFireReconcile();
            assertThat(listener.events().getLast().provisionCount()).isZero();
            assertThat(ctm.provisionReplacementCalls()).isEmpty();

            // A re-evaluation follow-up was scheduled on the debounce-delay band: remaining debounce
            // (full window, deficit age 0) + the short margin.
            var reEvalDelay = timeSpan(EXPECTED_DEBOUNCE_WINDOW.nanos() + DEBOUNCE_DELAY.nanos()).nanos();
            assertThat(scheduler.tasksByDelay(reEvalDelay)).hasSize(1);

            // Fire the follow-up after the debounce elapses: it re-triggers a reconcile that
            // provisions the now-aged deficit — no external membership event was needed.
            timeSource.advanceTimeMillis(EXPECTED_DEBOUNCE_WINDOW.millis() + 1);
            scheduler.tasksByDelay(reEvalDelay).getFirst().runIfLive();
            fireDebouncedReconcile();

            assertThat(ctm.provisionReplacementCalls()).hasSize(1);
        }

        /// Row 5 — below quorum: `NOT_QUORUM_SAFE` suppresses BOTH provisioning and draining
        /// regardless of the latch (a sub-quorum minority must not spawn a phantom split-brain).
        @Test
        void belowQuorum_neitherProvisionsNorDrains_regardlessOfLatch() {
            configuredCoreCount.set(5);
            // SELF + PEER_A = 2, below quorum threshold 3.
            seedClusterWithPeers(PEER_A);
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();

            var intent = listener.events().getLast();
            assertThat(intent.clusterMembershipCount()).isEqualTo(2);
            assertThat(intent.configuredCoreCount()).isEqualTo(5);
            assertThat(intent.provisionCount()).isZero();
            assertThat(intent.drainCount()).isZero();
            assertThat(ctm.provisionReplacementCalls()).isEmpty();
            assertThat(ctm.drainNodeCalls()).isEmpty();
        }

        /// Row 6 — re-election (term > 1) onto an already-formed, now-deficient cluster: this leader
        /// never observes full membership (the dead peers never return), so the latch must be
        /// pre-set on activation. After the debounce, provisioning is PERMITTED even though the
        /// count was always below configured for this leader instance.
        @Test
        void reElectionActivationBelowFull_preLatches_andProvisionsAfterDebounce() {
            configuredCoreCount.set(5);
            // Re-election: a prior leader existed (term advanced to 2) before this leader gains it.
            leaderTerm.set(2L);
            // Cluster is already formed but deficient: SELF + PEER_A..PEER_C = 4 (>= quorum 3, < 5).
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C);
            reconciler.activate();

            // Activation pre-latches reachedFullMembership before the first reconcile runs.
            assertThat(reconciler.isReachedFullMembership()).isTrue();

            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();
            // First pass anchors the deficit (suppressed by debounce only — NOT cold-start).
            assertThat(ctm.provisionReplacementCalls()).isEmpty();
            assertThat(listener.events().getLast().provisionCount()).isZero();
            timeSource.advanceTimeMillis(EXPECTED_DEBOUNCE_WINDOW.millis() + 1);
            listener.clear();
            triggerAndFireReconcile();

            var intent = listener.events().getLast();
            assertThat(intent.clusterMembershipCount()).isEqualTo(4);
            assertThat(intent.provisionCount()).isEqualTo(1);
            assertThat(ctm.provisionReplacementCalls()).hasSize(1);
        }

        /// Re-election suppression reason: a term-1 (initial) leader below full is suppressed by the
        /// cold-start latch, NOT debounce — confirming term=1 does NOT pre-latch.
        @Test
        void initialTermBelowFull_staysColdStartUnlatched() {
            configuredCoreCount.set(5);
            assertThat(leaderTerm.get()).isEqualTo(1L);
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C);
            reconciler.activate();

            assertThat(reconciler.isReachedFullMembership()).isFalse();
        }

        /// THE auto-heal fix, reproducing the live Docker trace exactly. NTT reaches full size 5 at
        /// formation (peakMembershipCount→5), then a priming kill drops membership to 4 BEFORE the
        /// reconciler runs a single pass — so the reconciler is departure-triggered and its FIRST
        /// pass observes clusterMembershipCount=4, NEVER 5. With the latch sourced from NTT's
        /// peakMembershipCount() (=5) instead of the per-pass count, that first pass latches
        /// reachedFullMembership=true even at count=4 → COLD_START_NOT_FULL no longer applies → after
        /// the debounce the deficit provisions. (term=1: NOT a re-election; the peak alone latches.)
        @Test
        void peakReachedFullButFirstPassAtDeficit_latchesViaPeak_andProvisionsAfterDebounce() {
            configuredCoreCount.set(5);
            assertThat(leaderTerm.get()).isEqualTo(1L);
            // Formation: NTT reaches full size 5 (peak→5).
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C, PEER_D);
            assertThat(ntt.peakMembershipCount()).isEqualTo(5);
            // Priming kill BEFORE any reconcile pass: membership 5 -> 4. peak stays 5.
            removePeers(PEER_D);
            assertThat(ntt.currentMembers()).hasSize(4);
            assertThat(ntt.peakMembershipCount()).isEqualTo(5);

            // Activate AFTER the kill, then run the first (departure-triggered) pass at count=4.
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();

            // First pass at count=4 latches via peak=5 — the bug was reachedFullMembership=FALSE here.
            assertThat(reconciler.isReachedFullMembership()).isTrue();
            var firstPass = listener.events().getLast();
            assertThat(firstPass.clusterMembershipCount()).isEqualTo(4);
            assertThat(firstPass.provisionCount()).isZero();
            assertThat(ctm.provisionReplacementCalls()).isEmpty();

            // After the debounce window, the sustained deficit provisions.
            timeSource.advanceTimeMillis(EXPECTED_DEBOUNCE_WINDOW.millis() + 1);
            listener.clear();
            triggerAndFireReconcile();

            var intent = listener.events().getLast();
            assertThat(intent.clusterMembershipCount()).isEqualTo(4);
            assertThat(intent.provisionCount()).isEqualTo(1);
            assertThat(ctm.provisionReplacementCalls()).hasSize(1);
        }

        /// Bug-C guard preserved: a cluster that NEVER reached full configured size (peak stays 4 < 5
        /// — a still-forming cluster / slow-joining configured peer) is suppressed by
        /// COLD_START_NOT_FULL and provisions nothing, even after the debounce window elapses.
        @Test
        void peakNeverReachedFull_staysColdStart_provisionsNothing() {
            configuredCoreCount.set(5);
            // Cluster only ever climbs to 4 (SELF + 3 peers) — never reaches full 5.
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C);
            assertThat(ntt.peakMembershipCount()).isEqualTo(4);

            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();
            // Advance well past the debounce — cold-start (not debounce) is the suppressor.
            timeSource.advanceTimeMillis(EXPECTED_DEBOUNCE_WINDOW.millis() + 1);
            triggerAndFireReconcile();

            assertThat(reconciler.isReachedFullMembership()).isFalse();
            var intent = listener.events().getLast();
            assertThat(intent.clusterMembershipCount()).isEqualTo(4);
            assertThat(intent.provisionCount()).isZero();
            assertThat(ctm.provisionReplacementCalls()).isEmpty();
        }
    }

    @Nested
    class InFlightDeathSweep {
        /// Boot-then-die replacement: a provisioned node never reaches READY and its SWIM/QUIC
        /// churn stops, so the event-triggered reconcile is never re-entered. The self-rescheduling
        /// sweep (armed at provision-dispatch) must purge the expired placeholder and re-evaluate
        /// the still-present deficit — re-provisioning a fresh replacement — without any external
        /// event. This is the bug fix: previously the deficit was never re-seen and the cluster
        /// stuck at 4/5 forever.
        @Test
        void deadReplacement_sweepPurgesPlaceholder_andReProvisions() {
            configuredCoreCount.set(5);
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C, PEER_D);
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();
            assertThat(reconciler.isArmedForProvisioning()).isTrue();

            // Drop one peer → one suppressed pass anchors the deficit; advance past the gates so
            // the sustained deficit provisions and arms a sweep future.
            removePeers(PEER_D);
            triggerAndFireReconcile();
            advancePastProvisioningGates();
            triggerAndFireReconcile();
            assertThat(reconciler.inFlightProvisioningCount()).isEqualTo(1);
            assertThat(ctm.provisionReplacementCalls()).hasSize(1);
            assertThat(scheduler.tasksByDelay(EXPECTED_INFLIGHT_EXPIRY)).hasSize(1);
            listener.clear();

            // The replacement boots then dies (never re-joins NTT). No further events arrive.
            // Advance past the expiry window and fire the armed sweep runnable directly. The purge
            // drops effective to 4 (< 5), re-opening the deficit — but the new deficit run must
            // re-age past the debounce window before the re-provision fires.
            timeSource.advanceTimeMillis(EXPECTED_INFLIGHT_EXPIRY.millis() + 1);
            scheduler.tasksByDelay(EXPECTED_INFLIGHT_EXPIRY).getFirst().runIfLive();
            // Sweep re-triggered reconcile (NTT_FIRE) on the debounce delay — fire it (anchors the
            // re-opened deficit, still suppressed), then advance past debounce and reconcile again.
            fireDebouncedReconcile();
            assertThat(reconciler.inFlightProvisioningCount()).isZero();
            timeSource.advanceTimeMillis(EXPECTED_DEBOUNCE_WINDOW.millis() + 1);
            triggerAndFireReconcile();

            // Placeholder purged and a SECOND provision dispatched (deficit re-evaluated).
            assertThat(reconciler.inFlightProvisioningCount()).isEqualTo(1);
            assertThat(ctm.provisionReplacementCalls()).hasSize(2);
        }

        /// No-double-provision: if the real node joins before the sweep fires, the sweep purges the
        /// stale placeholder but the over-count guard suppresses a second provision (effective ==
        /// configured). Confirms the re-trigger cannot double-provision a node that joined meanwhile.
        @Test
        void replacementJoinedBeforeSweep_purgesPlaceholder_noSecondProvision() {
            configuredCoreCount.set(5);
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C, PEER_D);
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();

            removePeers(PEER_D);
            triggerAndFireReconcile();
            advancePastProvisioningGates();
            triggerAndFireReconcile();
            assertThat(reconciler.inFlightProvisioningCount()).isEqualTo(1);
            assertThat(ctm.provisionReplacementCalls()).hasSize(1);
            listener.clear();

            // The real node joins (back to confirmed=5) before the sweep fires.
            seedClusterWithPeers(NodeId.randomNodeId());
            timeSource.advanceTimeMillis(EXPECTED_INFLIGHT_EXPIRY.millis() + 1);
            scheduler.tasksByDelay(EXPECTED_INFLIGHT_EXPIRY).getFirst().runIfLive();
            fireDebouncedReconcile();

            // Placeholder purged; no second provision — effective == configured.
            assertThat(reconciler.inFlightProvisioningCount()).isZero();
            assertThat(ctm.provisionReplacementCalls()).hasSize(1);
        }

        /// Self-cancel: once the in-flight map drains, the sweep does not re-arm. A subsequent
        /// sweep fire while empty arms no replacement and schedules no further sweep.
        @Test
        void sweepSelfCancels_whenInFlightMapEmpties() {
            configuredCoreCount.set(5);
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C, PEER_D);
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();

            removePeers(PEER_D);
            triggerAndFireReconcile();
            advancePastProvisioningGates();
            triggerAndFireReconcile();
            assertThat(reconciler.inFlightProvisioningCount()).isEqualTo(1);
            var armedSweeps = scheduler.tasksByDelay(EXPECTED_INFLIGHT_EXPIRY).size();

            // The real node joins so the deficit is gone; purge the placeholder via the sweep.
            seedClusterWithPeers(NodeId.randomNodeId());
            timeSource.advanceTimeMillis(EXPECTED_INFLIGHT_EXPIRY.millis() + 1);
            scheduler.tasksByDelay(EXPECTED_INFLIGHT_EXPIRY).getFirst().runIfLive();
            fireDebouncedReconcile();
            assertThat(reconciler.inFlightProvisioningCount()).isZero();

            // Map empty → no new sweep future scheduled beyond the ones already created.
            assertThat(scheduler.tasksByDelay(EXPECTED_INFLIGHT_EXPIRY)).hasSize(armedSweeps);
        }

        /// Deactivation cancels the armed sweep so a deposed leader's timer does not fire.
        @Test
        void deactivate_cancelsArmedSweep() {
            configuredCoreCount.set(5);
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C, PEER_D);
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();

            removePeers(PEER_D);
            triggerAndFireReconcile();
            advancePastProvisioningGates();
            triggerAndFireReconcile();
            var sweep = scheduler.tasksByDelay(EXPECTED_INFLIGHT_EXPIRY).getFirst();

            reconciler.deactivate();

            assertThat(sweep.cancelled()).isTrue();
            assertThat(reconciler.inFlightProvisioningCount()).isZero();
        }
    }

    @Nested
    class DrainSafetyFloor {
        /// Defect 1 (double-count): a freshly provisioned replacement appears in BOTH the
        /// membership view AND the in-flight provisioning map within the expiry window.
        /// `effective` must count each node ONCE (union, not sum) so a phantom surplus is
        /// not conjured. Here confirmed=5 (== configured), one in-flight placeholder lingers
        /// from a prior provision. Sum would give effective=6 → drain 1 healthy core. Union
        /// gives effective=6 too (distinct ids), but the HARD FLOOR (Defect 2) forbids
        /// draining below configured=5, so drainCount==0. No core node is drained.
        @Test
        void phantomInflightAfterRejoin_neverDrainsHealthyCore() {
            configuredCoreCount.set(5);
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C, PEER_D);
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();
            assertThat(reconciler.isArmedForProvisioning()).isTrue();

            // Drop one peer → provisions one in-flight placeholder (after the gates elapse).
            removePeers(PEER_D);
            triggerAndFireReconcile();
            advancePastProvisioningGates();
            triggerAndFireReconcile();
            assertThat(reconciler.inFlightProvisioningCount()).isEqualTo(1);
            ctm.clear();
            listener.clear();

            // The replacement joins (back to confirmed=5) while the in-flight placeholder is
            // STILL tracked (within the ×3 expiry window). effective = 5 confirmed + 1 stale
            // placeholder. A naive sum would think there is a surplus of 1 and drain a core.
            seedClusterWithPeers(PEER_D);
            reconciler.onSwimMemberHealthy(PEER_D, 1L);
            fireDebouncedReconcile();

            var intent = listener.events().getFirst();
            assertThat(intent.clusterMembershipCount()).isEqualTo(5);
            assertThat(intent.configuredCoreCount()).isEqualTo(5);
            assertThat(intent.drainCount()).isZero();
            assertThat(ctm.drainNodeCalls()).isEmpty();
        }

        /// The hard floor binds independently of WHY effective is inflated. Here a
        /// replacement that was provisioned NEVER joins (DNS failure / slow boot / crash):
        /// confirmed stays at 5 (== configured) while the in-flight placeholder lingers,
        /// inflating effective to 6. The reconciler must drain NOTHING — draining any of the
        /// 5 confirmed core members would drop the cluster below the configured floor and
        /// dissolve it.
        @Test
        void inflatedEffectiveByStuckProvision_neverDrainsBelowConfiguredFloor() {
            configuredCoreCount.set(5);
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C, PEER_D);
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();

            // Drop one → provision one placeholder that will never join (after the gates elapse).
            removePeers(PEER_D);
            triggerAndFireReconcile();
            advancePastProvisioningGates();
            triggerAndFireReconcile();
            assertThat(reconciler.inFlightProvisioningCount()).isEqualTo(1);
            ctm.clear();
            listener.clear();

            // Membership recovers to full (a DIFFERENT node joins) but the original stuck
            // placeholder is still tracked. confirmed=5, in-flight=1, effective=6.
            seedClusterWithPeers(PEER_D);
            reconciler.onSwimMemberHealthy(PEER_D, 1L);
            fireDebouncedReconcile();

            var intent = listener.events().getFirst();
            assertThat(intent.clusterMembershipCount()).isEqualTo(5);
            assertThat(intent.inFlightProvisioningCount()).isEqualTo(1);
            // effective inflated to 6, but floor forbids dropping confirmed below 5.
            assertThat(intent.drainCount()).isZero();
            assertThat(ctm.drainNodeCalls()).isEmpty();
        }

        /// Regression guard: a GENUINE surplus of CONFIRMED members above the configured core
        /// count still drains correctly down to (and never below) the floor. confirmed=6,
        /// configured=3, no in-flight → drain exactly 3 (6 - 3), leaving 3 confirmed at the
        /// floor.
        @Test
        void genuineConfirmedSurplus_drainsDownToFloorExactly() {
            configuredCoreCount.set(3);
            // SELF + 5 peers = 6 confirmed, no in-flight.
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C, PEER_D, NodeId.randomNodeId());

            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();

            var intent = listener.events().getFirst();
            assertThat(intent.clusterMembershipCount()).isEqualTo(6);
            assertThat(intent.configuredCoreCount()).isEqualTo(3);
            assertThat(intent.inFlightProvisioningCount()).isZero();
            // 6 - 3 = 3 surplus, floor headroom = 6 - 3 = 3 → drain exactly 3, leaving 3.
            assertThat(intent.drainCount()).isEqualTo(3);
            assertThat(ctm.drainNodeCalls()).hasSize(3);
        }

        /// Regression guard: a confirmed surplus that EXCEEDS what the floor allows is capped
        /// at the floor. (Constructed via in-flight inflation: confirmed=5, configured=3,
        /// in-flight=0 → drain 2; this asserts the min(nominalExcess, floorHeadroom) path
        /// when nominalExcess and floorHeadroom coincide for pure-confirmed surplus.)
        @Test
        void confirmedSurplusEqualsFloorHeadroom_drainsAllExcess() {
            configuredCoreCount.set(3);
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C, PEER_D);

            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();

            var intent = listener.events().getFirst();
            assertThat(intent.clusterMembershipCount()).isEqualTo(5);
            assertThat(intent.configuredCoreCount()).isEqualTo(3);
            assertThat(intent.drainCount()).isEqualTo(2);
            assertThat(ctm.drainNodeCalls()).hasSize(2);
        }
    }

    /// Regression: "boot under the leader-supplied id" contract. The minted in-flight id IS the
    /// id passed to `provisionReplacement` AND the id the replacement boots under, so membership
    /// presence of that exact id is the authoritative fulfillment signal — it clears the in-flight
    /// entry, counts exactly once in `effective`, and never triggers a spurious drain.
    @Nested
    class BootUnderMintedIdentity {
        /// (a) The dispatch passes the SAME minted id it tracks in-flight to `provisionReplacement`
        /// — the id stored in the in-flight map equals the id handed to the CTM.
        @Test
        void provisionDispatch_passesMintedInFlightId_toProvisionReplacement() {
            configuredCoreCount.set(5);
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C, PEER_D);
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();
            listener.clear();

            removePeers(PEER_D);
            triggerAndFireReconcile();
            advancePastProvisioningGates();
            triggerAndFireReconcile();

            assertThat(ctm.provisionReplacementCalls()).hasSize(1);
            var mintedId = ctm.provisionReplacementCalls().getFirst();
            assertThat(reconciler.inFlightProvisioningSnapshot()).containsKey(mintedId);
            assertThat(reconciler.inFlightProvisioningCount()).isEqualTo(1);
        }

        /// (a2) The minted replacement id carries the cluster-scoped `aether-<cluster>-node-`
        /// prefix (NodeId == container name), so a replacement is shape-identical to its
        /// compose-seeded siblings. The setup supplier returns `test-cluster`.
        @Test
        void computePeersToProvision_clusterNameKnown_idCarriesClusterPrefix() {
            configuredCoreCount.set(5);
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C, PEER_D);
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();

            removePeers(PEER_D);
            triggerAndFireReconcile();
            advancePastProvisioningGates();
            triggerAndFireReconcile();

            assertThat(ctm.provisionReplacementCalls()).hasSize(1);
            var mintedId = ctm.provisionReplacementCalls().getFirst();
            assertThat(mintedId.id()).startsWith("aether-test-cluster-node-");
        }

        /// (b) Once the exact minted id appears in NTT `currentMembers`, the next reconcile clears
        /// it from in-flight and `effective` counts it exactly once (no double count): membership
        /// returns to configured, so the snapshot shows no in-flight and no further provision.
        @Test
        void mintedIdJoinsMembership_nextReconcileClearsInFlight_andCountsOnce() {
            configuredCoreCount.set(5);
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C, PEER_D);
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();

            removePeers(PEER_D);
            triggerAndFireReconcile();
            advancePastProvisioningGates();
            triggerAndFireReconcile();
            assertThat(ctm.provisionReplacementCalls()).hasSize(1);
            var mintedId = ctm.provisionReplacementCalls().getFirst();
            assertThat(reconciler.inFlightProvisioningCount()).isEqualTo(1);
            listener.clear();

            // The replacement boots under its minted id and joins membership (back to 5).
            seedClusterWithPeers(mintedId);
            reconciler.onSwimMemberHealthy(mintedId, 1L);
            fireDebouncedReconcile();

            assertThat(reconciler.inFlightProvisioningSnapshot()).doesNotContainKey(mintedId);
            assertThat(reconciler.inFlightProvisioningCount()).isZero();
            var intent = listener.events().getFirst();
            // effective counts the joined replacement exactly once: 5 confirmed, 0 in-flight.
            assertThat(intent.clusterMembershipCount()).isEqualTo(5);
            assertThat(intent.inFlightProvisioningCount()).isZero();
            assertThat(intent.provisionCount()).isZero();
            assertThat(ctm.provisionReplacementCalls()).hasSize(1);
        }

        /// (c) No spurious drain when a replacement joins under its minted id. Members are back at
        /// target (5 == configured), the in-flight entry is cleared by identity match, so the
        /// drain set is empty — no healthy core is drained.
        @Test
        void replacementJoinsUnderMintedId_membersAtTarget_drainCountZero() {
            configuredCoreCount.set(5);
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C, PEER_D);
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();

            removePeers(PEER_D);
            triggerAndFireReconcile();
            advancePastProvisioningGates();
            triggerAndFireReconcile();
            var mintedId = ctm.provisionReplacementCalls().getFirst();
            ctm.clear();
            listener.clear();

            seedClusterWithPeers(mintedId);
            reconciler.onSwimMemberHealthy(mintedId, 1L);
            fireDebouncedReconcile();

            var intent = listener.events().getFirst();
            assertThat(intent.clusterMembershipCount()).isEqualTo(5);
            assertThat(intent.configuredCoreCount()).isEqualTo(5);
            assertThat(intent.drainCount()).isZero();
            assertThat(ctm.drainNodeCalls()).isEmpty();
            assertThat(reconciler.inFlightProvisioningCount()).isZero();
        }
    }

    /// Mutable SWIM health snapshot source backing the [`NodeTopologyTracker`]. `markHealthy`
    /// adds a peer as `HEALTHY`; `markAbsent` drops it (so the next NTT sample sees it gone).
    /// `SELF` is supplied by the tracker's self-seed and is never listed here. The tracker is
    /// constructed with hysteresis 1, so a single `sample()` after a mutation converges the
    /// stable member set the reconciler reads via `currentMembers()`.
    private static final class MutableHealthSource implements Supplier<HealthSnapshot> {
        private final Map<NodeId, SwimHealth> peerHealth = new LinkedHashMap<>();

        @Contract
        synchronized void markHealthy(NodeId nodeId) {
            peerHealth.put(nodeId, SwimHealth.HEALTHY);
        }

        @Contract
        synchronized void markAbsent(NodeId nodeId) {
            peerHealth.remove(nodeId);
        }

        @Override
        public synchronized HealthSnapshot get() {
            return HealthSnapshot.healthSnapshot(Map.copyOf(peerHealth));
        }
    }

    private static final class RecordingListener implements Consumer<ReconcileIntent> {
        private final List<ReconcileIntent> events = new CopyOnWriteArrayList<>();

        @Override
        public void accept(ReconcileIntent intent) {
            events.add(intent);
        }

        List<ReconcileIntent> events() {
            return List.copyOf(events);
        }

        @Contract
        void clear() {
            events.clear();
        }
    }

    private static final class MutableIntSupplier implements IntSupplier {
        private final AtomicInteger value;

        MutableIntSupplier(int initial) {
            this.value = new AtomicInteger(initial);
        }

        @Override
        public int getAsInt() {
            return value.get();
        }

        @Contract
        void set(int newValue) {
            value.set(newValue);
        }
    }

    /// Mutable leader-term supplier. Term `1` = initial leadership (cold-start applies); term `> 1`
    /// = re-election (the reconciler pre-latches reachedFullMembership on activation).
    private static final class MutableLongSupplier implements Supplier<Long> {
        private final AtomicLong value;

        MutableLongSupplier(long initial) {
            this.value = new AtomicLong(initial);
        }

        @Override
        public Long get() {
            return value.get();
        }

        @Contract
        void set(long newValue) {
            value.set(newValue);
        }
    }

    /// Recording `ClusterTopologyManager` stub. Phase 1.5 verification surface for
    /// `provisionReplacement` / `drainNode` / `reconcile` v2 calls.
    private static final class RecordingCtm implements ClusterTopologyManager {
        private final List<NodeId> drainNodeCalls = new CopyOnWriteArrayList<>();
        private final List<NodeId> provisionReplacementCalls = new CopyOnWriteArrayList<>();
        private final List<DrainReason> drainReasons = new CopyOnWriteArrayList<>();
        private final AtomicInteger reconcileCount = new AtomicInteger(0);

        List<NodeId> drainNodeCalls() {
            return List.copyOf(drainNodeCalls);
        }

        @Contract
        void clear() {
            drainNodeCalls.clear();
            provisionReplacementCalls.clear();
            drainReasons.clear();
        }

        List<NodeId> provisionReplacementCalls() {
            return List.copyOf(provisionReplacementCalls);
        }

        List<DrainReason> drainReasons() {
            return List.copyOf(drainReasons);
        }

        int reconcileCount() {
            return reconcileCount.get();
        }

        @Override
        public Promise<Unit> provisionReplacement(NodeId newNodeId, Option<NodeId> failedPeer, Set<NodeId> clusterMembers) {
            provisionReplacementCalls.add(newNodeId);
            return Promise.success(unit());
        }

        @Override
        public Promise<Unit> drainNode(NodeId targetNodeId, DrainReason reason) {
            drainNodeCalls.add(targetNodeId);
            drainReasons.add(reason);
            return Promise.success(unit());
        }

        @Override
        public Promise<Unit> reconcile() {
            reconcileCount.incrementAndGet();
            return Promise.success(unit());
        }

        @Override
        public NodeReconcilerState reconcilerState() {
            return new NodeReconcilerState.Inactive("stub");
        }

        @Override
        public Promise<Unit> setDesiredSize(int size) {
            return Promise.success(unit());
        }

        @Override
        public int desiredSize() {
            return 0;
        }

        @Override
        public int configuredSize() {
            return 0;
        }

        @Override
        @Contract
        public void onNodeReady(NodeId nodeId) {}

        @Override
        @Contract
        public void onMembershipDecision(MembershipDecision decision) {}

        @Override
        @Contract
        public void onSelfShutdown(TransportObservation.SelfShutdown selfShutdown) {}

        @Override
        @Contract
        public void onClusterConfigChanged() {}

        @Override
        @Contract
        public void onClusterPhaseChanged(ClusterPhase newPhase) {}

        @Override
        @Contract
        public void activate() {}

        @Override
        @Contract
        public void deactivate() {}

        @Override
        @Contract
        public TopologyObserver observer() {
            return null;
        }

        @Override
        public CircuitBreakerState circuitBreakerState() {
            return new CircuitBreakerState(0, 0, 0L, false);
        }

        @Override
        public int resetCircuitBreaker(String reason) {
            return 0;
        }

        @Override
        public boolean isAutoHealEnabled() {
            return true;
        }

        @Override
        public boolean setAutoHealEnabled(boolean enabled, String reason) {
            return true;
        }

        @Override
        @Contract
        public NodeInfo self() {
            return null;
        }

        @Override
        public Option<NodeInfo> get(NodeId id) {
            return Option.none();
        }

        @Override
        public int clusterSize() {
            return 0;
        }

        @Override
        public Option<NodeId> reverseLookup(SocketAddress socketAddress) {
            return Option.none();
        }

        @Override
        public Promise<Unit> start() {
            return Promise.success(unit());
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.success(unit());
        }

        @Override
        public TimeSpan pingInterval() {
            return timeSpan(1).seconds();
        }

        @Override
        public TimeSpan helloTimeout() {
            return timeSpan(1).seconds();
        }

        @Override
        public Option<TlsConfig> tls() {
            return Option.none();
        }

        @Override
        public Option<NodeState> getState(NodeId id) {
            return Option.none();
        }

        @Override
        public List<NodeId> topology() {
            return List.of();
        }
    }

    /// Controllable time source — advances only on explicit method calls.
    private static final class TestTimeSource implements TimeSource {
        private volatile long nanos = 0L;

        @Override
        public long nanoTime() {
            return nanos;
        }

        @Contract
        void advanceTimeMillis(long millis) {
            nanos += TimeUnit.MILLISECONDS.toNanos(millis);
        }
    }

    /// Manual scheduler — captures `(Runnable, delay)` pairs without ever invoking them
    /// on a background thread. Tests drive fire/cancel explicitly.
    private static final class ManualScheduler implements NttTimerScheduler {
        private final List<ManualTask> tasks = new ArrayList<>();

        @Override
        public synchronized ScheduledFuture<?> schedule(Runnable runnable, TimeSpan delay) {
            var task = new ManualTask(runnable, delay);

            tasks.add(task);

            return task;
        }

        synchronized List<ManualTask> tasksByDelay(TimeSpan delay) {
            return tasks.stream().filter(task -> task.delay().nanos() == delay.nanos()).toList();
        }
    }

    private static final class ManualTask implements ScheduledFuture<Object> {
        private final Runnable runnable;
        private final TimeSpan delay;
        private volatile boolean cancelled;
        private volatile boolean done;

        ManualTask(Runnable runnable, TimeSpan delay) {
            this.runnable = runnable;
            this.delay = delay;
        }

        TimeSpan delay() {
            return delay;
        }

        boolean cancelled() {
            return cancelled;
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
            return unit.convert(delay.nanos(), TimeUnit.NANOSECONDS);
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

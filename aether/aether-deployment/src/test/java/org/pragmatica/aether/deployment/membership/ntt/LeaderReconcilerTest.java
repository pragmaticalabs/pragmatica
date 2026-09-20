// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.ntt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.aether.deployment.cluster.DrainReason;
import org.pragmatica.aether.deployment.cluster.NodeReconcilerState;
import org.pragmatica.aether.deployment.cluster.ProvisionDisposition;
import org.pragmatica.aether.deployment.cluster.ReplacementInstanceState;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.environment.ProvisionContext;
import org.pragmatica.aether.environment.SourceName;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.consensus.topology.TransportObservation;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.TimeSource;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.statemachine.FsmObserver;
import org.pragmatica.swim.HealthSnapshot;
import org.pragmatica.swim.SwimHealth;
import org.pragmatica.utility.ULID;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import static org.pragmatica.aether.environment.ClusterName.maybeClusterName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.deployment.membership.MembershipConfig.membershipConfig;
import static org.pragmatica.aether.deployment.membership.fsm.MembershipFsm.membershipFsm;
import static org.pragmatica.aether.deployment.membership.ntt.LeaderReconciler.leaderReconciler;
import static org.pragmatica.aether.deployment.membership.ntt.PresenceSampler.presenceSampler;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Unit tests for [`LeaderReconciler`] (E2 Phase 1.6) — state-derived
/// reconciliation sourcing membership from presence sampler. No periodic tick; the
/// leader-activation reconcile is a single delayed one-shot at
/// `nttDepartureTimeout × 1.5`.
class LeaderReconcilerTest {
    private static final NodeId SELF = NodeId.randomNodeId();
    private static final NodeId PEER_A = NodeId.randomNodeId();
    private static final NodeId PEER_B = NodeId.randomNodeId();
    private static final NodeId PEER_C = NodeId.randomNodeId();
    private static final NodeId PEER_D = NodeId.randomNodeId();
    private static final TimeSpan EXPECTED_ACTIVATION_DELAY =
        timeSpan(membershipConfig().splitTimeout().nanos() * 3 / 2).nanos();
    /// #1049 — the in-flight sweep's provider-status poll cadence (= nttDepartureTimeout), and the
    /// production ten-minute default per-source replacement ceiling the fake CTM answers with unless a
    /// test sets another.
    private static final TimeSpan EXPECTED_POLL_INTERVAL = membershipConfig().splitTimeout();
    private static final TimeSpan DEFAULT_REPLACEMENT_CEILING = timeSpan(10).minutes();
    /// #1049 round 3 — a never-listed replacement counts as deleted only after twelve consecutive successful
    /// listings omit it AND three minutes have passed since it became pollable (its create call resolved).
    private static final TimeSpan EXPECTED_FIRST_LISTING_FLOOR = timeSpan(3).minutes();
    private static final int EXPECTED_ABSENT_LISTINGS = 12;
    private static final TimeSpan EXPECTED_GRACE_WINDOW =
        timeSpan(membershipConfig().splitTimeout().nanos() * 3 / 2).nanos();
    private static final TimeSpan EXPECTED_DEBOUNCE_WINDOW = membershipConfig().splitTimeout();
    /// Drain-safety grace window (Wave 2 defense in depth) = nttDepartureTimeout × 2 — a
    /// surplus-drain victim younger than this is never selected.
    private static final TimeSpan EXPECTED_DRAIN_GRACE =
        timeSpan(membershipConfig().splitTimeout().nanos() * 2).nanos();
    private static final TimeSpan DEBOUNCE_DELAY = timeSpan(100L).millis();
    /// Short terminal-eviction backstop (#131 Model C) for the fixture FSM: a co-confirmed kill
    /// holds the member in SUSPECT (still counted — the churn cure) for this REAL-TIME window,
    /// then the backstop terminalizes it to DEAD and the count drops. Short so [`#removePeers`]
    /// can await the post-backstop terminal state promptly (the same SharedScheduler-plus-await
    /// pattern as `MembershipFsmTest`); the mid-window SUSPECT hold itself is asserted with a
    /// LONG window by [`ModelCKillSequence`].
    private static final TimeSpan TEST_EVICTION_BACKSTOP = timeSpan(150).millis();

    private TestTimeSource timeSource;
    private ManualScheduler scheduler;
    private RecordingListener listener;
    private MutableIntSupplier configuredCoreCount;
    private MutableLongSupplier leaderTerm;
    private RecordingCtm ctm;
    private MutableHealthSource health;
    private PresenceSampler sampler;
    private MembershipFsm membershipFsm;
    /// Controllable wall clock (ms) injected into the FSM — drives the drain-safety grace member
    /// ages deterministically. The seeding helpers advance it past the grace window after every
    /// seed so helper-seeded members are MATURE (drainable), preserving every pre-grace drain
    /// expectation; the [`DrainSafetyGrace`] tests seed young members without the advance.
    private AtomicLong fsmWallClockMs;
    /// Monotonic SWIM incarnation for FSM drives. Every promote/kill uses a strictly increasing
    /// incarnation so a re-seed after death always clears the DEAD incarnation fence (rejoin) and a
    /// kill always carries a fresh incarnation.
    private final AtomicLong fsmIncarnation = new AtomicLong(1L);
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
        // deterministically — the reconciler reads sampler.currentMembers() synchronously.
        sampler = presenceSampler(SELF,
                                  health,
                                  timeSpan(1).seconds(),
                                  1,
                                  1,
                                  timeSource::nanoTime);
        // FSM-count cutover: the reconciler now counts membershipFsm.countedMembers() (MEMBER +
        // SUSPECT), NOT sampler.currentMembers(). The FSM is always-on (armed from construction, no leader
        // gate) and is driven in lockstep with the presence sampler helpers so countedMembers() mirrors the
        // presence sampler-intended membership at every step. SELF is promoted to MEMBER here to match presence sampler's self-seed
        // (sampler.currentMembers() always includes SELF), so the FSM count and the presence sampler-era count agree.
        // The FSM wall clock is injected (controllable) so the drain-safety grace member ages are
        // deterministic — see the [`#fsmWallClockMs`] field doc.
        fsmWallClockMs = new AtomicLong(0L);
        // Explicit SHORT eviction backstop (#131 Model C): removePeers drives kills to the
        // POST-BACKSTOP terminal DEAD state (awaited), so reconciler scenarios observe the
        // settled count drop instead of asserting mid-window.
        membershipFsm = membershipFsm(FsmObserver.noop(), fsmWallClockMs::get, Long.MAX_VALUE, TEST_EVICTION_BACKSTOP);
        observeCoreHealthy(SELF);
        reconciler = leaderReconciler(membershipConfig(),
                                      sampler,
                                      membershipFsm,
                                      configuredCoreCount,
                                      leaderTerm,
                                      ctm,
                                      () -> maybeClusterName("test-cluster"),
                                      timeSource,
                                      scheduler);
        reconciler.setReconcileListener(listener);
    }

    @Test
    void healthyUnknownIdentityDoesNotContributeToCoreCapacity() {
        seedClusterWithPeers(PEER_A);
        health.markHealthy(PEER_B);
        membershipFsm.onSwimHealthy(PEER_B, fsmIncarnation.getAndIncrement());
        sampler.sample();
        assertThat(membershipFsm.countedMembers()).contains(PEER_B);
        assertThat(membershipFsm.coreCountedMembers()).containsExactlyInAnyOrder(SELF, PEER_A);
    }

    @Test
    void targetIncreaseBeforeSamplerCatchesUp_preservesVerifiedFormationAndProvisioningGates() {
        configuredCoreCount.set(3);
        observeCoreHealthy(PEER_A);
        observeCoreHealthy(PEER_B);
        assertThat(sampler.peakMembershipCount()).isEqualTo(1);
        assertThat(membershipFsm.coreCountedMembers()).hasSize(3);
        reconciler.activate();
        scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();
        assertThat(reconciler.isReachedFullMembership()).isTrue();
        assertThat(ctm.provisionReplacementCalls()).isEmpty();

        configuredCoreCount.set(5);
        reconciler.onConfigChange();
        fireDebouncedReconcile();
        assertThat(ctm.provisionReplacementCalls()).as("deficit still requires existing grace/debounce").isEmpty();
        advancePastProvisioningGates();
        triggerAndFireReconcile();
        assertThat(sampler.peakMembershipCount()).isEqualTo(1);
        assertThat(listener.events().getLast().provisionCount()).isEqualTo(2);
        assertThat(ctm.provisionReplacementCalls()).hasSize(2);
    }

    @Test
    void targetRaisedBeforeFirstPass_usesVerifiedInstalledElectorateAsFormationEvidence() {
        configuredCoreCount.set(3);
        observeCoreHealthy(PEER_A);
        observeCoreHealthy(PEER_B);
        reconciler.setInstalledVotersSupplier(() -> Set.of(SELF, PEER_A, PEER_B));
        reconciler.activate();
        configuredCoreCount.set(5); // No reconcile or sampler pass ever observed the old target.
        scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();
        assertThat(sampler.peakMembershipCount()).isEqualTo(1);
        assertThat(reconciler.isReachedFullMembership()).isTrue();
        assertThat(ctm.provisionReplacementCalls()).isEmpty();
        advancePastProvisioningGates();
        triggerAndFireReconcile();
        assertThat(ctm.provisionReplacementCalls()).hasSize(2);
    }

    @Test
    void partiallyObservedInstalledElectorate_doesNotProveFormation() {
        configuredCoreCount.set(5);
        observeCoreHealthy(PEER_A);
        observeCoreHealthy(PEER_B);
        reconciler.setInstalledVotersSupplier(() -> Set.of(SELF, PEER_A, PEER_B, PEER_C, PEER_D));
        reconciler.activate();
        scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();
        advancePastProvisioningGates();
        triggerAndFireReconcile();
        assertThat(reconciler.isReachedFullMembership()).isFalse();
        assertThat(ctm.provisionReplacementCalls()).isEmpty();
    }

    @Test
    void desiredSevenUsesInstalledThreeQuorumAndProvisionsFourAfterDelay() {
        configuredCoreCount.set(7);
        observeCoreHealthy(PEER_A);
        observeCoreHealthy(PEER_B);
        reconciler.setInstalledVotersSupplier(() -> Set.of(SELF, PEER_A, PEER_B));
        reconciler.activate();
        scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();
        assertThat(reconciler.isArmedForProvisioning()).isTrue();
        assertThat(reconciler.currentProvisioningSnapshot().quorumSafe()).isTrue();
        assertThat(ctm.provisionReplacementCalls()).isEmpty();
        advancePastProvisioningGates();
        triggerAndFireReconcile();
        assertThat(ctm.provisionReplacementCalls()).hasSize(4);
    }

    @Test
    void nonvotingCoreCandidatesCannotSupplyInstalledMajority() {
        configuredCoreCount.set(7);
        seedClusterWithPeers(PEER_A, PEER_B, PEER_C, PEER_D);
        reconciler.setInstalledVotersSupplier(() -> Set.of(SELF, PEER_A,
            new NodeId("absent-voter-1"), new NodeId("absent-voter-2"), new NodeId("absent-voter-3")));
        reconciler.activate();
        scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();
        advancePastProvisioningGates();
        triggerAndFireReconcile();
        assertThat(reconciler.isArmedForProvisioning()).isFalse();
        assertThat(reconciler.currentProvisioningSnapshot().quorumSafe()).isFalse();
        assertThat(ctm.provisionReplacementCalls()).isEmpty();
    }

    @Test
    void wiredEmptyInstalledElectorateFailsClosedDespiteHealthyCoreCapacity() {
        configuredCoreCount.set(3);
        seedClusterWithPeers(PEER_A, PEER_B);
        reconciler.setInstalledVotersSupplier(Set::of);
        reconciler.activate();
        scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();
        configuredCoreCount.set(5);
        advancePastProvisioningGates();
        triggerAndFireReconcile();
        assertThat(reconciler.isArmedForProvisioning()).isFalse();
        assertThat(reconciler.currentProvisioningSnapshot().quorumSafe()).isFalse();
        assertThat(ctm.provisionReplacementCalls()).isEmpty();
    }

    /// Feed N healthy peers into the presence sampler health snapshot, then sample so the stable member
    /// set (which always includes `SELF`) absorbs them. Drive the FSM in lockstep: each peer is
    /// promoted to MEMBER (a single SWIM HealthyObserved edge, up-hysteresis = 1) so the
    /// reconciler's base set [`MembershipFsm#countedMembers`] grows to match. A peer that was
    /// previously driven DEAD rejoins because the monotonic incarnation clears the DEAD fence.
    @Contract
    private void seedClusterWithPeers(NodeId... peers) {
        for (var peer : peers) {
            health.markHealthy(peer);
            observeCoreHealthy(peer);
        }
        sampler.sample();
        // Mature every tracked member past the drain-safety grace, so helper-seeded members are
        // immediately drainable — preserving every pre-grace drain expectation. Young-member
        // behaviour is exercised explicitly by the DrainSafetyGrace tests.
        agePastDrainSafetyGrace();
    }

    /// Mark peers absent in the presence sampler health snapshot (simulates a departure) and sample so the
    /// next reconcile observes a deficit. Drive the FSM in lockstep through the FULL #131 Model C
    /// kill sequence to its POST-BACKSTOP terminal state: each peer is co-confirmed dead
    /// (SWIM-FAULTY ∧ liveness-gone), which holds it in SUSPECT (still counted) until the
    /// fixture's short [`#TEST_EVICTION_BACKSTOP`] fires, then the helper AWAITS the DEAD edge —
    /// so every caller asserts against the SETTLED count drop, matching the ratified Model C
    /// observable sequence (the mid-window SUSPECT hold is asserted by [`ModelCKillSequence`]).
    @Contract
    private void removePeers(NodeId... peers) {
        for (var peer : peers) {
            health.markAbsent(peer);
            membershipFsm.onSwimFaulty(peer, fsmIncarnation.getAndIncrement());
            membershipFsm.onLivenessGone(peer);
        }
        for (var peer : peers) {
            awaitDead(peer);
        }
        sampler.sample();
    }

    /// Poll until `id` reaches terminal DEAD in the fixture FSM (the Model C backstop fired).
    /// Real SharedScheduler time drives the backstop; the 2s ceiling is comfortably above the
    /// 150ms [`#TEST_EVICTION_BACKSTOP`] — widen the ceiling (not the backstop) if real-time
    /// scheduling ever makes it flaky.
    @Contract
    private void awaitDead(NodeId id) {
        await().atMost(2, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(membershipFsm.memberStates()).containsEntry(id, "Dead"));
    }

    /// Wave 2 (cluster-topology-overhaul spec): feed N WORKER-labeled peers — healthy in the
    /// sampler, promoted to MEMBER in the FSM, descriptor-labeled `role=worker` so the
    /// role-scoped [`MembershipFsm#coreCountedMembers`] projection excludes them while the
    /// role-blind `countedMembers()` still counts them.
    @Contract
    private void seedWorkers(NodeId... workers) {
        for (var worker : workers) {
            health.markHealthy(worker);
            membershipFsm.onMemberDescriptor(workerInfo(worker));
            membershipFsm.onSwimHealthy(worker, fsmIncarnation.getAndIncrement());
        }
        sampler.sample();
        agePastDrainSafetyGrace();
    }

    /// Advance the FSM wall clock past the drain-safety grace window so every CURRENTLY-tracked
    /// member is mature (eligible for surplus-drain victim selection). Members tracked AFTER this
    /// call stay young until the next advance.
    @Contract
    private void agePastDrainSafetyGrace() {
        fsmWallClockMs.addAndGet(EXPECTED_DRAIN_GRACE.millis() + 1);
    }

    /// Fixtures model an explicitly admitted core descriptor before its positive health edge.
    private void observeCoreHealthy(NodeId node) {
        membershipFsm.onMemberDescriptor(NodeInfo.nodeInfo(node, NodeAddress.nodeAddress("core-host", 6000).unwrap(),
            Map.of(NodeInfo.LABEL_ROLE, "core")));
        membershipFsm.onSwimHealthy(node, fsmIncarnation.getAndIncrement());
    }

    /// A NodeInfo carrying the explicit `role=worker` label. The transport ACTIVE/PASSIVE
    /// `NodeRole` was retired in the cluster-topology-overhaul Wave 9; the worker classification
    /// now lives solely in the `role` label (the config CORE/WORKER/SPOT vocabulary).
    private static NodeInfo workerInfo(NodeId id) {
        var address = NodeAddress.nodeAddress("worker-host", 6000).unwrap();

        return NodeInfo.nodeInfo(id, address, Map.of(NodeInfo.LABEL_ROLE, "worker"));
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

    /// Convenience: trigger a presence sampler-fire reconcile and fire its debounced pass. The standard
    /// "observe current membership and reconcile" step used between time advances.
    @Contract
    private void triggerAndFireReconcile() {
        reconciler.onTopologyUnhealthy();
        fireDebouncedReconcile();
    }

    /// #1049 — advance the clock one in-flight poll interval, fire every live in-flight sweep (which
    /// evicts entries past their ceiling and polls the provider through the CTM), then run one observing
    /// reconcile pass — the real path the production sweep drives, one tick at a time.
    @Contract
    private void advanceOnePollInterval() {
        timeSource.advanceTimeMillis(EXPECTED_POLL_INTERVAL.millis());
        scheduler.tasksByDelay(EXPECTED_POLL_INTERVAL).forEach(ManualTask::runIfLive);
        triggerAndFireReconcile();
    }

    @Contract
    private void advancePollIntervals(int count) {
        for (var i = 0; i < count; i++) {
            advanceOnePollInterval();
        }
    }

    /// #1049 round 3 — as [`#advanceOnePollInterval`], for any advance: the manual scheduler runs the live
    /// sweep whenever the test says, so a test can place listings off the 15s grid.
    @Contract
    private void advanceAndTick(long millis) {
        advanceAndRunSweepOnly(millis);
        triggerAndFireReconcile();
    }

    /// #1049 round 3 — advance the clock and fire the live in-flight sweep, with no reconcile pass after it.
    @Contract
    private void advanceAndRunSweepOnly(long millis) {
        timeSource.advanceTimeMillis(millis);
        scheduler.tasksByDelay(EXPECTED_POLL_INTERVAL).forEach(ManualTask::runIfLive);
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

    /// Join-grace-reap actuation: the leader-gated ingress that terminates a never-healthy
    /// CTM-replacement's lingering container/JVM after the `MembershipFsm` reaped it OBSERVED→DEAD.
    /// A DIRECT actuation (not a debounced reconcile trigger): it calls `ctm.drainNode(reaped,
    /// JOIN_GRACE_REAP)` synchronously so the existing grace-terminate backstop reaps the
    /// container/instance. Leader-gated identically to the other ingress points.
    @Nested
    class JoinGraceReapActuation {
        @Test
        void onJoinGraceReap_whileLeader_drainsReapedNodeWithJoinGraceReapReason() {
            reconciler.activate();

            reconciler.onJoinGraceReap(PEER_A);

            assertThat(ctm.drainNodeCalls()).containsExactly(PEER_A);
            assertThat(ctm.drainReasons()).containsExactly(DrainReason.JOIN_GRACE_REAP);
            assertThat(ctm.provisionReplacementCalls()).isEmpty();
        }

        @Test
        void onJoinGraceReap_whileNotLeader_doesNotDrain() {
            reconciler.onJoinGraceReap(PEER_A);

            assertThat(ctm.drainNodeCalls()).isEmpty();
            assertThat(ctm.drainReasons()).isEmpty();
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

    /// Drain-safety grace (Wave 2 defense in depth at the drain authority) — narrowed by the
    /// Approach-3 victim-selection rewrite to apply to CONFIGURED SEEDS only. A just-joined
    /// configured seed whose role labels may still be propagating is never picked as a surplus
    /// victim while inside `nttDepartureTimeout × 2`; an EPHEMERAL (CTM-provisioned, ULID-suffix)
    /// node that owns no slices is drainable regardless of age (it was added for scale-up and owns
    /// nothing — resolving the maturity-grace tension that previously forced seed-draining). These
    /// tests therefore use CONFIGURED-SHAPED young members (`aether-test-cluster-node-<ordinal>`)
    /// where they assert the grace protects a young node, and ephemeral-shaped (`randomNodeId`)
    /// members where they assert young-ephemeral nodes drain. An all-young CONFIGURED candidate
    /// pool DEFERS the drain (WARN + exactly one armed follow-up reconcile) instead of silently
    /// dropping the surplus.
    @Nested
    class DrainSafetyGrace {
        /// Configured-shaped young seeds (numeric ordinal suffix → NOT a ULID → the grace applies).
        private final NodeId youngSeed1 = new NodeId("aether-test-cluster-node-3");
        private final NodeId youngSeed2 = new NodeId("aether-test-cluster-node-4");
        /// The follow-up delay armed when deferral happens at age 0 for every young candidate:
        /// full grace remaining + the debounce margin (mirrors `drainGraceReEvalDelay`).
        private static final TimeSpan FULL_GRACE_FOLLOW_UP_DELAY =
            timeSpan(EXPECTED_DRAIN_GRACE.millis() + DEBOUNCE_DELAY.millis()).millis();

        /// Seed peers WITHOUT the post-seed maturity advance: the members are tracked at the
        /// current FSM wall clock and stay YOUNG (inside the drain-safety grace) until the
        /// clock advances.
        @Contract
        private void seedYoungPeers(NodeId... peers) {
            for (var peer : peers) {
                health.markHealthy(peer);
                observeCoreHealthy(peer);
            }
            sampler.sample();
        }

        @Test
        void surplusDrain_youngConfiguredVictimSkipped_matureVictimSelected() {
            configuredCoreCount.set(3);
            // SELF + two mature peers (the seeding helper matures every tracked member)...
            seedClusterWithPeers(PEER_A, PEER_B);
            // ...plus one just-joined CONFIGURED seed (numeric-ordinal suffix). Under the grace it
            // must never be the victim even though it is a fresh member.
            seedYoungPeers(youngSeed1);

            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();

            // Surplus = 4 - 3 = 1: one MATURE victim is drained; the young seed is never selected.
            assertThat(ctm.drainNodeCalls())
                .as("the surplus drain must skip the young configured seed and select a mature victim")
                .hasSize(1)
                .doesNotContain(youngSeed1);
        }

        @Test
        void surplusDrain_youngEphemeralNode_isDrainable_graceDoesNotBlock() {
            configuredCoreCount.set(3);
            // SELF + two mature peers, plus one just-joined EPHEMERAL (CTM-shaped, ULID-suffix)
            // node. The grace must NOT protect it — it owns no slices and was added for scale-up,
            // so it is the PREFERRED victim despite its youth (the maturity-grace resolution).
            seedClusterWithPeers(PEER_A, PEER_B);
            var youngEphemeral = NodeId.randomNodeId();
            seedYoungPeers(youngEphemeral);

            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();

            // Surplus = 4 - 3 = 1: the young ephemeral node is eligible and drained.
            assertThat(ctm.drainNodeCalls())
                .as("a young ephemeral non-slice-owner must be drainable — the grace does not block it")
                .hasSize(1);
        }

        @Test
        void surplusDrain_allConfiguredCandidatesYoung_defersWithFollowUp_noDrainDispatched() {
            configuredCoreCount.set(2);
            // SELF (ephemeral, young) is drainable; the only non-SELF candidates are young
            // CONFIGURED seeds. Surplus = 3 - 2 = 1, floor headroom = 3 - 2 = 1. The single
            // eligible (ephemeral SELF) covers it... so to force an all-young-CONFIGURED deferral
            // we exclude SELF as a slice owner, leaving only the two young seeds in the pool.
            reconciler.setOwnsActiveSlices(id -> id.equals(SELF));
            seedYoungPeers(youngSeed1, youngSeed2);

            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();

            var intent = listener.events().getFirst();
            assertThat(intent.drainCount())
                .as("an all-young configured candidate pool must defer the surplus drain entirely")
                .isZero();
            assertThat(ctm.drainNodeCalls()).isEmpty();
            // The deferral armed EXACTLY ONE follow-up reconcile at (remaining grace + margin).
            assertThat(scheduler.tasksByDelay(FULL_GRACE_FOLLOW_UP_DELAY))
                .as("a deferred drain must arm one re-evaluation follow-up — never silently dropped")
                .hasSize(1);
        }

        @Test
        void deferredConfiguredSurplusDrain_firesAfterGraceElapses_viaArmedFollowUp() {
            configuredCoreCount.set(2);
            // Same shielded-SELF setup so the only candidates are the two young configured seeds.
            reconciler.setOwnsActiveSlices(id -> id.equals(SELF));
            seedYoungPeers(youngSeed1, youngSeed2);
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();
            assertThat(ctm.drainNodeCalls()).isEmpty();

            // The young seeds mature past the grace window...
            agePastDrainSafetyGrace();
            // ...and the armed follow-up re-enters the reconcile path (follow-up → debounced pass).
            scheduler.tasksByDelay(FULL_GRACE_FOLLOW_UP_DELAY).getFirst().runIfLive();
            fireDebouncedReconcile();

            // Surplus = 3 - 2 = 1: the deferred drain now fires against a matured seed.
            assertThat(ctm.drainNodeCalls())
                .as("the deferred configured-surplus drain must fire once the seeds mature")
                .hasSize(1);
        }

        @Test
        void surplusDrain_partialMaturePool_drainsMatureAndDefersShortfall() {
            configuredCoreCount.set(1);
            // SELF (tracked in setUp at clock 0) matures and is ephemeral → an eligible victim;
            // the two CONFIGURED seeds seeded afterwards stay young → deferred. Surplus = 2, only
            // the one mature (SELF) is drainable now.
            agePastDrainSafetyGrace();
            seedYoungPeers(youngSeed1, youngSeed2);

            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();

            // Surplus = 2; eligible pool = {SELF} → one mature victim drained, the shortfall deferred.
            assertThat(ctm.drainNodeCalls())
                .as("the mature part of the surplus is drained immediately")
                .containsExactly(SELF);
            assertThat(scheduler.tasksByDelay(FULL_GRACE_FOLLOW_UP_DELAY))
                .as("the young configured shortfall arms one re-evaluation follow-up")
                .hasSize(1);
        }

        @Test
        void surplusDrain_matureBlankRoleMembers_stillDrained_roleAgnostic() {
            configuredCoreCount.set(1);
            // All members carry BLANK roles (no descriptor labels) — the grace is age-based, so
            // a mature all-core cluster drains its genuine surplus exactly as before.
            seedClusterWithPeers(PEER_A, PEER_B);

            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();

            var intent = listener.events().getFirst();
            assertThat(intent.drainCount()).isEqualTo(2);
            assertThat(ctm.drainNodeCalls()).hasSize(2);
        }
    }

    /// Approach-3 drain-victim selection (the 7→5-scale-down-under-load fix). Two guards:
    /// (1) a node OWNING active slices is never a victim; (2) EPHEMERAL (CTM-provisioned,
    /// ULID-suffix) nodes are preferred over CONFIGURED compose seeds (`<prefix>-<ordinal>`).
    /// Slice ownership is consulted through the injected [`LeaderReconciler#setOwnsActiveSlices`]
    /// predicate; ephemeral detection rides the minted-id ULID-suffix shape. The legacy bug:
    /// descending-NodeId order sorted seeds (`...-3`,`-4`,`-5`) ahead of ULID-named replacements
    /// (`'0' < '5'`), so a scale-down drained the stable seed owning live slices.
    @Nested
    class DrainVictimSelection {
        /// Configured compose seeds — numeric-ordinal suffix, NOT a ULID → preserved by preference.
        private final NodeId seed1 = new NodeId("aether-test-cluster-node-1");
        private final NodeId seed2 = new NodeId("aether-test-cluster-node-2");
        private final NodeId seed3 = new NodeId("aether-test-cluster-node-3");
        private final NodeId seed4 = new NodeId("aether-test-cluster-node-4");
        private final NodeId seed5 = new NodeId("aether-test-cluster-node-5");
        /// Ephemeral CTM-provisioned replacements — ULID suffix → preferred as victims.
        private final NodeId ctm1 = NodeId.randomNodeId(ProvisionContext.coreNodeNamePrefix(maybeClusterName("test-cluster")));
        private final NodeId ctm2 = NodeId.randomNodeId(ProvisionContext.coreNodeNamePrefix(maybeClusterName("test-cluster")));

        /// A slice owner is removed from the victim pool entirely: with configured=1 and a 2-node
        /// surplus, the ONLY ephemeral candidate that would otherwise be drained is shielded as a
        /// slice owner, so the drain falls back to the next eligible candidate and never touches it.
        @Test
        void surplusDrain_sliceOwnerExcluded_evenWhenOtherwiseSelected() {
            configuredCoreCount.set(1);
            // Members = SELF(ephemeral) + ctm1 + ctm2 = 3. ctm1 owns active slices → shielded.
            seedClusterWithPeers(ctm1, ctm2);
            reconciler.setOwnsActiveSlices(id -> id.equals(ctm1));

            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();

            // Surplus = 3 - 1 = 2, but only SELF + ctm2 are eligible (ctm1 shielded). Drains exactly
            // those two — never the slice owner.
            assertThat(ctm.drainNodeCalls())
                .as("a slice owner must never be a drain victim")
                .doesNotContain(ctm1)
                .containsExactlyInAnyOrder(SELF, ctm2);
        }

        /// Ephemeral preference: a mix of configured seeds and ephemeral CTM nodes drains the
        /// EPHEMERAL ones, leaving the originally-configured seeds intact.
        @Test
        void surplusDrain_ephemeralPreferredOverConfiguredSeeds() {
            configuredCoreCount.set(4);
            // SELF is shielded (slice owner) so the candidate pool is exactly {seed1, seed2, ctm1, ctm2}.
            // Members = SELF + 4 = 5; surplus = 5 - 4 = 1; floor headroom = 5 - 4 = 1 → drain 1.
            seedClusterWithPeers(seed1, seed2, ctm1, ctm2);
            reconciler.setOwnsActiveSlices(id -> id.equals(SELF));

            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();

            // The single victim must be one of the ephemeral nodes, never a configured seed.
            assertThat(ctm.drainNodeCalls())
                .as("ephemeral CTM nodes are drained before configured seeds")
                .hasSize(1);
            assertThat(ctm.drainNodeCalls().getFirst())
                .as("the victim is an ephemeral CTM node, not a seed")
                .isIn(ctm1, ctm2);
            assertThat(ctm.drainNodeCalls())
                .doesNotContain(seed1, seed2);
        }

        /// The headline scenario: 7 members (5 configured seeds + 2 ephemeral CTM), configured=5,
        /// scale-down drains exactly the 2 CTM nodes — the legacy reversed-id order would have
        /// drained two seeds (one of them the live-slice owner). SELF is shielded as a slice owner
        /// so the literal "5 seeds + 2 CTM" pool is exercised cleanly.
        @Test
        void scaleDown_sevenToFive_drainsTwoCtmNodes_preservesAllSeeds() {
            configuredCoreCount.set(5);
            // Members = SELF + 5 seeds + 2 ctm = 8; shield SELF (slice owner) → candidate pool is the
            // literal {5 seeds, 2 ctm}. Surplus = 8 - 5 = 3, floor headroom = 8 - 5 = 3 → drain 3:
            // both ephemeral CTM nodes first, then one mature seed (shortfall) — NEVER more seeds
            // than forced, and never a CTM left behind. We assert both CTM nodes are drained and the
            // seed survivors are preserved beyond the single forced one.
            seedClusterWithPeers(seed1, seed2, seed3, seed4, seed5, ctm1, ctm2);
            reconciler.setOwnsActiveSlices(id -> id.equals(SELF));

            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();

            assertThat(ctm.drainNodeCalls())
                .as("both ephemeral CTM nodes are drained as the preferred victims")
                .contains(ctm1, ctm2);
        }

        /// Exact 7→5 with SELF counted as one of the keep-set: 5 seeds + 2 CTM where SELF is NOT a
        /// seed (ephemeral) — to drain exactly 2 we shield SELF AND keep configured at 5 over a
        /// 7-node non-SELF pool. Drains EXACTLY the two CTM nodes, every seed preserved.
        @Test
        void scaleDown_drainsExactlyTheTwoCtmNodes_whenSurplusIsTwo() {
            configuredCoreCount.set(6);
            // Members = SELF + 5 seeds + 2 ctm = 8; shield SELF → candidate pool {5 seeds, 2 ctm}.
            // Surplus = 8 - 6 = 2, floor headroom = 8 - 6 = 2 → drain exactly 2: the 2 ephemeral CTM.
            seedClusterWithPeers(seed1, seed2, seed3, seed4, seed5, ctm1, ctm2);
            reconciler.setOwnsActiveSlices(id -> id.equals(SELF));

            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();

            assertThat(ctm.drainNodeCalls())
                .as("a 2-node scale-down drains exactly the two CTM nodes, preserving all seeds")
                .containsExactlyInAnyOrder(ctm1, ctm2);
            assertThat(ctm.drainNodeCalls())
                .doesNotContain(seed1, seed2, seed3, seed4, seed5);
        }

        /// Maturity-grace resolution: a YOUNG ephemeral node that owns no slices IS drainable —
        /// the grace (which now governs configured seeds only) does not block it.
        @Test
        void youngEphemeralNonOwner_isDrainable_graceDoesNotBlock() {
            configuredCoreCount.set(2);
            // SELF + seed1 (mature) + ctm1 (YOUNG ephemeral). Shield the mature ones so ctm1 is the
            // only eligible victim — proving the young ephemeral is selected despite its age.
            seedClusterWithPeers(seed1);
            health.markHealthy(ctm1);
            observeCoreHealthy(ctm1);
            sampler.sample();
            reconciler.setOwnsActiveSlices(id -> id.equals(SELF) || id.equals(seed1));

            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();

            // Members = 3, configured = 2, surplus = 1, only ctm1 eligible (others shielded) → drained
            // even though it is young.
            assertThat(ctm.drainNodeCalls())
                .as("a young ephemeral non-slice-owner is drainable — the grace does not block it")
                .containsExactly(ctm1);
        }

        /// Combined guards: every ephemeral candidate is a slice owner, so the drain falls back to a
        /// mature configured seed (slice-owner exclusion takes precedence over ephemeral preference).
        @Test
        void allEphemeralOwnSlices_fallsBackToMatureConfiguredSeed() {
            configuredCoreCount.set(3);
            // SELF + seed1 + seed2 + ctm1 = 4 (all mature). ctm1 AND SELF own slices → shielded.
            // Surplus = 4 - 3 = 1; ephemeral pool {ctm1} is fully shielded → fall back to a seed.
            seedClusterWithPeers(seed1, seed2, ctm1);
            reconciler.setOwnsActiveSlices(id -> id.equals(SELF) || id.equals(ctm1));

            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();

            assertThat(ctm.drainNodeCalls())
                .as("with all ephemeral candidates shielded, a mature seed is the fallback victim")
                .hasSize(1);
            assertThat(ctm.drainNodeCalls().getFirst())
                .isIn(seed1, seed2);
        }
    }

    /// #1049 — an in-flight replacement is tracked by what the compute provider reports about its instance,
    /// not by a timer. Every scenario drives the real reconcile path: the dispatching pass, the armed
    /// in-flight sweep (which polls the provider through the CTM), and the reconcile passes it triggers.
    /// The `splitTimeout × 3` (45s) expiry these scenarios run past is the one #1049 deleted: cloud
    /// replacements take 50–63s to join, so it expired first and the leader minted a duplicate.
    ///
    /// These replace `InFlightExpiry`, whose two tests specified the deleted timer (evict past × 3,
    /// keep below it) — the owner ruling on #1049 withdrew that behaviour, so the specification changed,
    /// not the code under an unchanged one.
    @Nested
    class InFlightInstanceState {
        /// Arm at full membership (5), drop PEER_D, run one anchoring pass, advance past the gates and run
        /// the dispatching pass — exactly one replacement in flight. Returns its minted id.
        private NodeId dispatchOneReplacement() {
            configuredCoreCount.set(5);
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C, PEER_D);
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();
            removePeers(PEER_D);
            triggerAndFireReconcile();
            advancePastProvisioningGates();
            triggerAndFireReconcile();
            assertThat(ctm.provisionReplacementCalls()).hasSize(1);
            assertThat(reconciler.inFlightProvisioningCount()).isEqualTo(1);

            return ctm.provisionReplacementCalls().getFirst();
        }

        /// A re-elected leader (term 2) on a 4/5 cluster inheriting one replacement the prior leader
        /// dispatched, with the provider reporting `state` for it. Returns the inherited id.
        private NodeId inheritOneReplacement(ReplacementInstanceState state) {
            return inheritReplacement(NodeId.randomNodeId(), state);
        }

        /// As [`#inheritOneReplacement`], for a given prior-leader id — the activation pass runs at once.
        private NodeId inheritReplacement(NodeId priorDispatch, ReplacementInstanceState state) {
            configuredCoreCount.set(5);
            leaderTerm.set(2L);
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C);
            reconciler.setRetainedDispatchedSupplier(() -> Set.of(priorDispatch));
            ctm.reportInstanceState(priorDispatch, state);
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();

            return priorDispatch;
        }

        /// A replacement id shaped exactly like a CTM mint (`<prefix>-<ULID>`) whose ULID timestamp is
        /// `epochMs` — what a prior leader's dispatch carries.
        private static NodeId mintedAt(long epochMs) {
            var bytes = new byte[ULID.BYTE_LENGTH];

            for (var i = 0; i < ULID.TIMESTAMP_LENGTH; i++) {
                bytes[i] = (byte) (epochMs >>> (8 * (ULID.TIMESTAMP_LENGTH - 1 - i)));
            }

            return NodeId.nodeId("aether-test-cluster-node-" + ULID.fromBytes(bytes).unwrap().encoded()).unwrap();
        }

        /// One more poll tick, returning how many provider status queries have been issued so far — the
        /// probe for the two scenarios whose answer arrives on a promise resolved after the tick.
        private int tickAndCountQueries() {
            advanceOnePollInterval();

            return ctm.instanceStateQueries().size();
        }

        /// Fire the live sweep without moving the clock, returning the provider status queries issued so far —
        /// waits for an asynchronously resolved create to make its entry pollable, at a fixed instant.
        private int tickInPlaceAndCountQueries() {
            advanceAndTick(0);

            return ctm.instanceStateQueries().size();
        }

        @Test
        void inFlightEntry_providerReportsBooting_isNotReDispatched_pastSplitTimeoutTimesThree() {
            var minted = dispatchOneReplacement();

            ctm.reportInstanceState(minted, ReplacementInstanceState.PRESENT);
            advancePollIntervals(12);

            assertThat(ctm.provisionReplacementCalls())
                .as("three minutes in (4x the old 45s expiry) a replacement the provider reports booting is not minted twice")
                .hasSize(1);
            assertThat(reconciler.inFlightProvisioningKeys()).containsExactly(minted);
            assertThat(ctm.instanceStateQueries())
                .as("the leader asked the provider rather than ageing the entry out")
                .contains(minted);
        }

        @Test
        void inFlightEntry_providerReportsDeletionOfSeenInstance_isDroppedAndReDispatchedAfterDebounce() {
            var minted = dispatchOneReplacement();

            ctm.reportInstanceState(minted, ReplacementInstanceState.PRESENT);
            advanceOnePollInterval();
            ctm.reportInstanceState(minted, ReplacementInstanceState.ABSENT);
            advanceOnePollInterval();

            assertThat(reconciler.inFlightProvisioningKeys())
                .as("an instance the provider listed and no longer lists was deleted")
                .isEmpty();
            assertThat(EXPECTED_POLL_INTERVAL.millis() * 2)
                .as("the drop came one absence and 30s in — an absence after a listing needs no count and no floor")
                .isLessThan(EXPECTED_FIRST_LISTING_FLOOR.millis());
            assertThat(ctm.provisionReplacementCalls())
                .as("the re-opened deficit re-ages past the debounce before re-dispatch")
                .hasSize(1);

            timeSource.advanceTimeMillis(EXPECTED_DEBOUNCE_WINDOW.millis() + 1);
            triggerAndFireReconcile();

            assertThat(ctm.provisionReplacementCalls()).hasSize(2);
        }

        /// Round 3 (S1, ruling test a) — a create call that resolves 55s after dispatch, followed by one listing
        /// that lags and omits the instance, mints exactly once. Round 2's grace ran from dispatch and had
        /// expired by then, so that one lagged listing minted a duplicate while the instance existed.
        @Test
        void inFlightEntry_createResolvesAt55s_oneLaggedListing_mintsExactlyOnce() {
            var pendingProvision = Promise.<ProvisionDisposition> promise();

            ctm.holdNextProvision(pendingProvision);
            var minted = dispatchOneReplacement();

            ctm.reportInstanceState(minted, ReplacementInstanceState.ABSENT);
            advancePollIntervals(3);
            timeSource.advanceTimeMillis(10_000);
            pendingProvision.succeed(ProvisionDisposition.dispatched());
            await().atMost(2, TimeUnit.SECONDS).until(() -> tickAndCountQueries() >= 1);
            ctm.reportInstanceState(minted, ReplacementInstanceState.PRESENT);
            advancePollIntervals(EXPECTED_ABSENT_LISTINGS + 2);

            assertThat(ctm.provisionReplacementCalls())
                .as("one lagged listing after a slow create is not a deletion")
                .hasSize(1);
            assertThat(reconciler.inFlightProvisioningKeys()).containsExactly(minted);
        }

        /// Round 3 (S1, ruling test b) — listings that fail (the CTM answers them UNKNOWN) never count toward the
        /// absences: after twenty failed listings and five minutes, eleven successful absences are still not a
        /// deletion; the twelfth is.
        @Test
        void inFlightEntry_neverListed_failedListingsDoNotCountTowardAbsences() {
            var minted = dispatchOneReplacement();

            ctm.reportInstanceState(minted, ReplacementInstanceState.UNKNOWN);
            advancePollIntervals(20);
            ctm.reportInstanceState(minted, ReplacementInstanceState.ABSENT);
            advancePollIntervals(EXPECTED_ABSENT_LISTINGS - 1);

            assertThat(reconciler.inFlightProvisioningKeys())
                .as("eleven successful absences after twenty failed listings, long past the floor, are not twelve")
                .containsExactly(minted);
            assertThat(ctm.provisionReplacementCalls()).hasSize(1);

            advanceOnePollInterval();

            assertThat(reconciler.inFlightProvisioningKeys())
                .as("the twelfth successful absence past the floor is a deletion")
                .isEmpty();

            timeSource.advanceTimeMillis(EXPECTED_DEBOUNCE_WINDOW.millis() + 1);
            triggerAndFireReconcile();

            assertThat(ctm.provisionReplacementCalls()).hasSize(2);
        }

        /// Round 3 (S1, ruling test c) — twelve consecutive successful listings omitting a never-listed replacement,
        /// the last of them three minutes after its create resolved, re-dispatch it after the normal debounce;
        /// eleven do not.
        @Test
        void inFlightEntry_neverListed_twelveConsecutiveAbsencesReachingTheFloor_reDispatchAfterDebounce() {
            var minted = dispatchOneReplacement();

            ctm.reportInstanceState(minted, ReplacementInstanceState.ABSENT);
            advancePollIntervals(EXPECTED_ABSENT_LISTINGS - 1);

            assertThat(reconciler.inFlightProvisioningKeys())
                .as("eleven absences, 165s after the create resolved: neither the count nor the floor is met")
                .containsExactly(minted);

            advanceOnePollInterval();

            assertThat(reconciler.inFlightProvisioningKeys())
                .as("the twelfth absence, at the 180s floor, is a deletion")
                .isEmpty();
            assertThat(ctm.provisionReplacementCalls())
                .as("the re-opened deficit re-ages past the debounce before re-dispatch")
                .hasSize(1);

            timeSource.advanceTimeMillis(EXPECTED_DEBOUNCE_WINDOW.millis() + 1);
            triggerAndFireReconcile();

            assertThat(ctm.provisionReplacementCalls()).hasSize(2);
        }

        /// Round 3 (S1) — the floor binds when listings land off the poll grid: twelve absences, the last 175s
        /// after the create resolved, are not yet a deletion; the thirteenth, at 190s, is.
        @Test
        void inFlightEntry_neverListed_twelveAbsencesInsideTheFloor_areNotYetADeletion() {
            var minted = dispatchOneReplacement();

            ctm.reportInstanceState(minted, ReplacementInstanceState.ABSENT);
            advanceAndTick(10_000);
            for (var i = 1; i < EXPECTED_ABSENT_LISTINGS; i++) {
                advanceAndTick(EXPECTED_POLL_INTERVAL.millis());
            }

            assertThat(reconciler.inFlightProvisioningKeys())
                .as("twelve absences, the last 175s after the create resolved, are inside the 3-minute floor")
                .containsExactly(minted);

            advanceAndTick(EXPECTED_POLL_INTERVAL.millis());

            assertThat(reconciler.inFlightProvisioningKeys())
                .as("past the floor, with the count already met, the absence is a deletion")
                .isEmpty();
        }

        /// Round 3 (S1) — the floor runs from when the create call resolved, never from dispatch: after a create
        /// that resolves 55s after dispatch, twelve absences whose last is 165s after it resolved (220s after
        /// dispatch) are not yet a deletion; the thirteenth, 180s after it resolved, is.
        @Test
        void inFlightEntry_slowCreate_floorRunsFromCreateResolution_notFromDispatch() {
            var pendingProvision = Promise.<ProvisionDisposition> promise();

            ctm.holdNextProvision(pendingProvision);
            var minted = dispatchOneReplacement();

            ctm.reportInstanceState(minted, ReplacementInstanceState.ABSENT);
            timeSource.advanceTimeMillis(55_000);
            pendingProvision.succeed(ProvisionDisposition.dispatched());
            await().atMost(2, TimeUnit.SECONDS).until(() -> tickInPlaceAndCountQueries() >= 1);
            for (var i = 1; i < EXPECTED_ABSENT_LISTINGS; i++) {
                advanceAndTick(EXPECTED_POLL_INTERVAL.millis());
            }

            assertThat(reconciler.inFlightProvisioningKeys())
                .as("twelve absences 165s after the create resolved, though 220s after dispatch, are inside the floor")
                .containsExactly(minted);

            advanceAndTick(EXPECTED_POLL_INTERVAL.millis());

            assertThat(reconciler.inFlightProvisioningKeys())
                .as("180s after the create resolved, with the count met, the absence is a deletion")
                .isEmpty();
        }

        @Test
        void inFlightEntry_pastHardCeiling_isReDispatched_evenWhileProviderReportsBooting() {
            ctm.setReplacementCeiling(timeSpan(2).minutes());
            var minted = dispatchOneReplacement();

            ctm.reportInstanceState(minted, ReplacementInstanceState.PRESENT);
            advancePollIntervals(8);

            assertThat(ctm.provisionReplacementCalls())
                .as("at the ceiling (120s), not past it, a booting replacement is kept")
                .hasSize(1);

            // Past the ceiling at the 135s tick (drop + deficit anchor), debounced at 150s.
            advancePollIntervals(2);

            assertThat(reconciler.inFlightProvisioningKeys()).doesNotContain(minted);
            assertThat(ctm.provisionReplacementCalls())
                .as("the per-source ceiling re-dispatches although the provider still reports booting")
                .hasSize(2);
        }

        @Test
        void inFlightEntry_providerCannotAnswer_fallsBackToDefaultCeiling_notImmediatelyAndNotForever() {
            var minted = dispatchOneReplacement();

            ctm.reportInstanceState(minted, ReplacementInstanceState.UNKNOWN);
            advancePollIntervals(20);

            assertThat(ctm.provisionReplacementCalls())
                .as("five minutes of UNKNOWN is not a failure — no re-dispatch before the ceiling")
                .hasSize(1);

            // Default ceiling 600s: past it at the 615s tick (drop + deficit anchor), debounced at 630s.
            advancePollIntervals(22);

            assertThat(ctm.provisionReplacementCalls())
                .as("UNKNOWN is not forever — the ten-minute default ceiling re-dispatches")
                .hasSize(2);
            assertThat(DEFAULT_REPLACEMENT_CEILING.millis()).isEqualTo(600_000L);
        }

        @Test
        void newLeader_inheritedEntryProviderReportsBooting_isNotReDispatched() {
            var inherited = inheritOneReplacement(ReplacementInstanceState.PRESENT);

            advancePollIntervals(12);

            assertThat(ctm.provisionReplacementCalls())
                .as("a new leader keeps an inherited replacement the provider reports booting — no duplicate mint")
                .isEmpty();
            assertThat(reconciler.inFlightProvisioningKeys()).containsExactly(inherited);
            assertThat(ctm.instanceStateQueries())
                .as("the new leader asks the provider about the inherited replacement")
                .contains(inherited);
        }

        @Test
        void newLeader_inheritedEntryProviderReportsFailed_isReDispatchedAfterDebounce() {
            inheritOneReplacement(ReplacementInstanceState.FAILED);

            advanceOnePollInterval();

            assertThat(reconciler.inFlightProvisioningKeys()).isEmpty();
            assertThat(ctm.provisionReplacementCalls()).isEmpty();

            timeSource.advanceTimeMillis(EXPECTED_DEBOUNCE_WINDOW.millis() + 1);
            triggerAndFireReconcile();

            assertThat(ctm.provisionReplacementCalls()).hasSize(1);
        }

        /// Round 2 — the ceiling runs from the mint time in the inherited id's ULID, not from inheritance: a
        /// replacement minted longer ago than the ceiling is dropped by the new leader's first pass and
        /// re-dispatched one debounce later, although the provider still reports it booting.
        @Test
        void newLeader_inheritedEntryMintedLongerAgoThanCeiling_isReDispatchedPromptly() {
            var inherited = inheritReplacement(mintedAt(System.currentTimeMillis() - DEFAULT_REPLACEMENT_CEILING.millis()
                                                        - timeSpan(1).minutes().millis()),
                                               ReplacementInstanceState.PRESENT);

            advanceOnePollInterval();

            assertThat(reconciler.inFlightProvisioningKeys()).doesNotContain(inherited);
            assertThat(ctm.provisionReplacementCalls())
                .as("eleven minutes after its mint, one debounce after inheritance, the replacement is re-dispatched")
                .hasSize(1);
        }

        /// Round 3 (S1) — an inherited never-listed replacement counts absences, and its floor, from inheritance,
        /// not from its mint: minted five minutes ago, twelve absences spanning 175s since inheritance are not a
        /// deletion; the thirteenth, at 190s, is.
        @Test
        void newLeader_inheritedNeverListedEntry_countsAbsencesFromInheritance_notFromMint() {
            var inherited = inheritReplacement(mintedAt(System.currentTimeMillis() - timeSpan(5).minutes().millis()),
                                               ReplacementInstanceState.ABSENT);

            advanceAndTick(10_000);
            for (var i = 1; i < EXPECTED_ABSENT_LISTINGS; i++) {
                advanceAndTick(EXPECTED_POLL_INTERVAL.millis());
            }

            assertThat(reconciler.inFlightProvisioningKeys())
                .as("minted five minutes ago, but its twelve listings span only 175s since this leader inherited it")
                .containsExactly(inherited);

            advanceAndTick(EXPECTED_POLL_INTERVAL.millis());

            assertThat(reconciler.inFlightProvisioningKeys()).isEmpty();
        }

        /// Round 3 (V13) — the inherited age needs no cap: `nanoTime` may have any origin, including a negative
        /// one, and an id minted at the epoch still reads as past the ceiling, because the ceiling check subtracts
        /// modulo 2^64.
        @Test
        void newLeader_inheritedIdMintedAtTheEpoch_onANegativeNanoTimeOrigin_isEvictedAndReDispatched() {
            timeSource.advanceTimeMillis(-8_000_000_000_000L);
            var inherited = inheritReplacement(mintedAt(0L), ReplacementInstanceState.PRESENT);

            advanceOnePollInterval();

            assertThat(reconciler.inFlightProvisioningKeys()).doesNotContain(inherited);
            assertThat(ctm.provisionReplacementCalls()).hasSize(1);
        }

        /// Round 3 (V20, N2) — a query from the prior leadership term that never settles does not hold the
        /// single-flight slot into the next term: `deactivate` clears the outstanding set. The cost N2 names is
        /// that a flap can briefly have two queries out for one id; their answers are applied through
        /// value-guarded `replace`/`remove`, so the second changes nothing once the first has.
        @Test
        void leadershipFlap_queryLeftUnsettledByThePriorTerm_doesNotBlockPollingInTheNextTerm() {
            var inherited = inheritOneReplacement(ReplacementInstanceState.PRESENT);

            ctm.holdInstanceStateAnswers(Promise.<ReplacementInstanceState> promise());
            advanceOnePollInterval();

            assertThat(ctm.instanceStateQueries()).containsExactly(inherited);

            reconciler.deactivate();
            ctm.releaseInstanceStateAnswers();
            leaderTerm.set(3L);
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getLast().runIfLive();
            advanceOnePollInterval();

            assertThat(ctm.instanceStateQueries())
                .as("the next term asks again rather than waiting on a query that will never answer")
                .hasSize(2);
        }

        /// Round 3 (V07) — the sweep tick evicts an entry past its ceiling by itself, with no reconcile pass
        /// between ticks: the eviction does not wait for some other trigger to run a pass.
        @Test
        void inFlightSweep_alone_evictsAnEntryPastItsCeiling() {
            ctm.setReplacementCeiling(timeSpan(2).minutes());
            var minted = dispatchOneReplacement();

            ctm.reportInstanceState(minted, ReplacementInstanceState.PRESENT);
            for (var i = 0; i < 9; i++) {
                advanceAndRunSweepOnly(EXPECTED_POLL_INTERVAL.millis());
            }

            assertThat(reconciler.inFlightProvisioningKeys())
                .as("at 135s, past the 120s ceiling, the sweep alone evicted it")
                .isEmpty();
        }

        /// Round 2 — a configured `<prefix>-<ordinal>` id carries no mint time, so its ceiling restarts at
        /// inheritance: kept through the ceiling, re-dispatched past it.
        @Test
        void newLeader_inheritedIdWithoutUlid_restartsCeilingAtInheritance() {
            ctm.setReplacementCeiling(timeSpan(2).minutes());
            var inherited = inheritReplacement(NodeId.nodeId("aether-test-cluster-node-5").unwrap(),
                                               ReplacementInstanceState.PRESENT);

            advancePollIntervals(8);

            assertThat(reconciler.inFlightProvisioningKeys())
                .as("at the ceiling (120s after inheritance), not past it, the entry is kept")
                .containsExactly(inherited);
            assertThat(ctm.provisionReplacementCalls()).isEmpty();

            // Past the ceiling at the 135s tick (drop + deficit anchor), debounced at 150s.
            advancePollIntervals(2);

            assertThat(ctm.provisionReplacementCalls()).hasSize(1);
        }

        /// Round 2 — clock skew: a mint time in the future (this node's clock behind the minter's) counts as
        /// now. Neither dropped at once nor kept for the skew plus the ceiling.
        @Test
        void newLeader_inheritedIdMintedInTheFuture_isClampedToNow() {
            ctm.setReplacementCeiling(timeSpan(2).minutes());
            var inherited = inheritReplacement(mintedAt(System.currentTimeMillis() + timeSpan(1).hours().millis()),
                                               ReplacementInstanceState.PRESENT);

            advancePollIntervals(8);

            assertThat(reconciler.inFlightProvisioningKeys())
                .as("a future mint time is not an age past the ceiling")
                .containsExactly(inherited);

            advancePollIntervals(2);

            assertThat(ctm.provisionReplacementCalls())
                .as("the ceiling runs from now, not from an hour ahead")
                .hasSize(1);
        }

        /// Rate limiting: the provider is asked only from the sweep tick, never from a reconcile pass,
        /// however many passes the triggers produce. (A guard, not a red-before test: the pre-#1049
        /// reconciler never asked the provider at all.)
        @Test
        void reconcilePasses_neverQueryTheProvider() {
            dispatchOneReplacement();

            for (var i = 0; i < 10; i++) {
                triggerAndFireReconcile();
            }

            assertThat(ctm.instanceStateQueries()).isEmpty();
        }

        /// Rate limiting: a slow provider never accumulates stacked queries for one replacement.
        @Test
        void statusQuery_isSingleFlightPerEntry_whileProviderIsSlow() {
            var minted = dispatchOneReplacement();
            var slowAnswer = Promise.<ReplacementInstanceState> promise();

            ctm.holdInstanceStateAnswers(slowAnswer);
            advancePollIntervals(3);

            assertThat(ctm.instanceStateQueries())
                .as("one outstanding query per entry, however many ticks pass")
                .containsExactly(minted);

            slowAnswer.succeed(ReplacementInstanceState.PRESENT);

            await().atMost(2, TimeUnit.SECONDS).until(() -> tickAndCountQueries() >= 2);
        }

        /// A DISPATCHING entry's create call may not have happened, so an empty listing would say nothing:
        /// the provider is not asked until the provision call resolves.
        @Test
        void dispatchingEntry_isNotPolled_untilItsProvisionCallResolves() {
            var pendingProvision = Promise.<ProvisionDisposition> promise();

            ctm.holdNextProvision(pendingProvision);
            dispatchOneReplacement();
            advancePollIntervals(2);

            assertThat(ctm.instanceStateQueries()).isEmpty();

            pendingProvision.succeed(ProvisionDisposition.dispatched());

            await().atMost(2, TimeUnit.SECONDS).until(() -> tickAndCountQueries() >= 1);
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

            // Recover to full membership: the replacement joins under its minted id, so the recovery
            // pass clears its in-flight entry (identity-match fulfilment) and the deficit anchor
            // (effective >= configured). #1049: an in-flight entry no longer expires on a timer, so
            // fulfilment — not the old × 3 expiry — is what empties the map here.
            seedClusterWithPeers(ctm.provisionReplacementCalls().getFirst());
            reconciler.onSwimMemberHealthy(ctm.provisionReplacementCalls().getFirst(), 1L);
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

    /// Auto-heal-wedge fix — the in-flight placeholder stamped before [`ClusterTopologyManager#provisionReplacement`]
    /// must be removed for a NO-BOOT deferral (circuit-open / no-healthy-peers) so the raw deficit stays
    /// visible and re-pokes; it is kept ONLY for a real [`ProvisionDisposition.Dispatched`] boot. A genuine
    /// boot failure removes it too (existing behavior — regression guard). Before this fix a suppressed
    /// (no-boot) deferral left a phantom placeholder that masked the deficit and permanently wedged auto-heal
    /// once the provisioning circuit tripped.
    @Nested
    class ProvisionDispositionWedgeFix {
        /// Drive the reconciler to its armed deficit state and run the provisioning pass with the
        /// disposition the CTM is currently configured to return. Reach full configured membership (5)
        /// to arm the latch, drop PEER_D (deficit 1), run one suppressed pass that anchors the deficit
        /// run, advance past both provisioning gates, then run the dispatching pass.
        @Contract
        private void driveArmedDeficitProvision() {
            configuredCoreCount.set(5);
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C, PEER_D);
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();

            removePeers(PEER_D);
            triggerAndFireReconcile();
            advancePastProvisioningGates();
            triggerAndFireReconcile();
        }

        /// Circuit-open deferral: the CTM booted nothing, so the placeholder is removed and the raw
        /// deficit stays visible. A subsequent fresh deficit run (breaker still open) re-pokes — proving
        /// no phantom placeholder masked `effectiveCapacity`. Re-poking still honors the deficit-debounce
        /// (the anti-storm gate), so the next provision needs a fresh anchor pass + debounce age. No
        /// provisioning-failure is recorded for a deferral (the CTM stub records none on a Deferred).
        @Test
        void dispatch_circuitOpenDeferral_removesInFlightPlaceholder_andRePokesNextDeficitRun() {
            ctm.deferNextProvision(ProvisionDisposition.DeferralReason.CIRCUIT_OPEN);

            driveArmedDeficitProvision();

            assertThat(ctm.provisionReplacementCalls())
                    .as("the deficit dispatched exactly one provision attempt")
                    .hasSize(1);
            assertThat(reconciler.inFlightProvisioningCount())
                    .as("a no-boot circuit-open deferral removes the in-flight placeholder — nothing is coming")
                    .isZero();
            assertThat(reconciler.inFlightProvisioningSnapshot())
                    .as("the placeholder must not survive a deferral (it would mask the deficit and wedge auto-heal)")
                    .isEmpty();

            // The raw deficit is still visible (in-flight is empty, so effective == raw member count).
            // The dispatch reset the debounce anchor (anti-storm), so the re-poke re-ages: one anchor
            // pass, advance past the debounce window, then the dispatching pass — the SAME sequence a
            // genuine post-dispatch deficit re-run follows. Before the fix the retained placeholder
            // would have masked the deficit and this re-poke would never fire (the permanent wedge).
            triggerAndFireReconcile();
            timeSource.advanceTimeMillis(EXPECTED_DEBOUNCE_WINDOW.millis() + 1);
            triggerAndFireReconcile();
            assertThat(ctm.provisionReplacementCalls())
                    .as("the unmasked deficit re-pokes on the next deficit run — the wedge is gone")
                    .hasSize(2);
        }

        /// No-healthy-peers deferral: identical reconciler action to circuit-open — nothing was booted,
        /// so the placeholder is removed and the deficit stays visible.
        @Test
        void dispatch_noHealthyPeersDeferral_removesInFlightPlaceholder() {
            ctm.deferNextProvision(ProvisionDisposition.DeferralReason.NO_HEALTHY_PEERS);

            driveArmedDeficitProvision();

            assertThat(ctm.provisionReplacementCalls())
                    .as("the deficit dispatched exactly one provision attempt")
                    .hasSize(1);
            assertThat(reconciler.inFlightProvisioningCount())
                    .as("a no-boot no-healthy-peers deferral removes the in-flight placeholder")
                    .isZero();
            assertThat(reconciler.inFlightProvisioningSnapshot())
                    .as("the deficit must stay visible after a deferral")
                    .isEmpty();
        }

        /// A real Dispatched boot KEEPS the placeholder: a VM is genuinely coming, so it counts toward
        /// `effectiveCapacity` and the deficit must NOT re-dispatch while it boots.
        @Test
        void dispatch_dispatchedBoot_keepsInFlightPlaceholder() {
            // RecordingCtm defaults to a Dispatched disposition.
            driveArmedDeficitProvision();

            assertThat(ctm.provisionReplacementCalls())
                    .as("the deficit dispatched exactly one provision attempt")
                    .hasSize(1);
            assertThat(reconciler.inFlightProvisioningCount())
                    .as("a real Dispatched boot keeps the in-flight placeholder — a VM is on its way")
                    .isEqualTo(1);
        }

        /// A genuine boot FAILURE removes the placeholder (regression guard — the CTM records the failure
        /// in its own breaker; the reconciler just stops counting a VM that is not coming).
        @Test
        void dispatch_genuineProvisionFailure_removesInFlightPlaceholder() {
            ctm.failNextProvision(Causes.cause("simulated boot failure"));

            driveArmedDeficitProvision();

            assertThat(ctm.provisionReplacementCalls())
                    .as("the deficit dispatched exactly one provision attempt")
                    .hasSize(1);
            assertThat(reconciler.inFlightProvisioningCount())
                    .as("a genuine boot failure removes the in-flight placeholder (existing behavior)")
                    .isZero();
        }
    }

    /// #131 Model C — the kill-sequence observable contract at the reconciler's counting level:
    /// a co-confirmed death (SWIM-FAULTY ∧ liveness-gone) holds the member in SUSPECT — still
    /// counted — for the eviction-backstop window (the churn cure: a blip that refutes within
    /// the window never leaves the counted set); the count drops only when the backstop
    /// terminalizes the member to DEAD. [`#removePeers`] awaits the settled half of this
    /// sequence for every other scenario; the mid-window hold is asserted here.
    @Nested
    class ModelCKillSequence {
        @Test
        void coConfirmedKill_holdsSuspectCounted_thenDropsOnBackstopDeath() {
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C, PEER_D);

            health.markAbsent(PEER_A);
            membershipFsm.onSwimFaulty(PEER_A, fsmIncarnation.getAndIncrement());
            membershipFsm.onLivenessGone(PEER_A);

            assertThat(membershipFsm.memberStates()).containsEntry(PEER_A, "Suspect");
            assertThat(membershipFsm.countedMembers()).contains(PEER_A);

            awaitDead(PEER_A);

            assertThat(membershipFsm.countedMembers()).doesNotContain(PEER_A);
        }
    }

    /// Wave 2 (cluster-topology-overhaul spec) — worker accounting hygiene. The reconciler's
    /// base set is the CORE-SCOPED [`MembershipFsm#coreCountedMembers`] (W2): workers never fill
    /// a core deficit arithmetically, never manufacture a phantom core surplus, and a dispatched
    /// replacement carries an explicit CORE intent (W4).
    @Nested
    class WorkerAccounting {
        private final NodeId worker1 = NodeId.randomNodeId();
        private final NodeId worker2 = NodeId.randomNodeId();
        private final NodeId worker3 = NodeId.randomNodeId();

        /// W2 — the headline bug: configured 5 cores; 4 cores + 3 workers is a REAL deficit of 1
        /// (the role-blind count of 7 would have hidden it forever — a dead core never replaced
        /// once workers exist). Auto-heal provisions exactly one replacement. The deficit exists
        /// from activation (re-election pre-latch, term > 1) so the assertion does not depend on
        /// the FSM's deferred SUSPECT→DEAD backstop timing.
        @Test
        void coreDeficitWithWorkersPresent_provisionsExactlyOneReplacement() {
            configuredCoreCount.set(5);
            leaderTerm.set(2L);
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C);
            seedWorkers(worker1, worker2, worker3);
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();
            assertThat(reconciler.isArmedForProvisioning()).isTrue();

            advancePastProvisioningGates();
            listener.clear();
            triggerAndFireReconcile();

            var intent = listener.events().getFirst();
            assertThat(intent.clusterMembershipCount()).as("membership count is core-scoped, workers excluded").isEqualTo(4);
            assertThat(intent.provisionCount()).isEqualTo(1);
            assertThat(ctm.provisionReplacementCalls()).hasSize(1);
        }

        /// W4 — the dispatched replacement carries the explicit CORE intent (never inherited or
        /// implied), so the provider boundary stamps `AETHER_ROLE=core` end-to-end.
        @Test
        void provisionDispatch_passesExplicitCoreRole() {
            configuredCoreCount.set(5);
            leaderTerm.set(2L);
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C);
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();

            advancePastProvisioningGates();
            triggerAndFireReconcile();

            assertThat(ctm.provisionReplacementRoles()).containsExactly(NodeRole.CORE);
        }

        /// W2 symmetric half — 5 cores + 3 workers vs configured 5 is NOT a surplus. The
        /// role-blind count (8) would have drained three healthy nodes; the core-scoped count
        /// sees exactly the configured size: no drain, no provision.
        @Test
        void fullCoreMembershipWithWorkers_noPhantomSurplusDrain() {
            configuredCoreCount.set(5);
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C, PEER_D);
            seedWorkers(worker1, worker2, worker3);
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();

            var intent = listener.events().getFirst();
            assertThat(intent.clusterMembershipCount()).as("membership count is core-scoped, workers excluded").isEqualTo(5);
            assertThat(intent.provisionCount()).isZero();
            assertThat(intent.drainCount()).isZero();
            assertThat(ctm.drainNodeCalls()).isEmpty();
            assertThat(ctm.provisionReplacementCalls()).isEmpty();
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
        /// `sampler.currentMembers()` transiently shows fewer than configured while QUIC peers are
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
    /// cluster is healed without any further presence sampler event.
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

        /// #603 — the operator's `aether cluster topology auto-heal disable` (`ClusterTopologyManager
        /// .setAutoHealEnabled`) must suppress replacement provisioning, not just flip the status
        /// route's response. Same genuine-departure-past-debounce setup as the row above (every other
        /// gate open), but with auto-heal turned off: no provision, ever — advancing time further
        /// doesn't unstick it, unlike the debounce cases, because this is an operator override, not a
        /// timer.
        @Test
        void autoHealDisabled_suppressesProvisioning_evenPastDebounceWithGenuineDeparture() {
            configuredCoreCount.set(5);
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C, PEER_D);
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();
            assertThat(reconciler.isReachedFullMembership()).isTrue();
            ctm.setAutoHealEnabled(false, "test: operator disabled during incident");
            listener.clear();

            removePeers(PEER_D);
            triggerAndFireReconcile();
            assertThat(ctm.provisionReplacementCalls()).isEmpty();
            // #603 review Warning 10 — at this point BOTH gates are closed: auto-heal is disabled AND
            // the debounce window has not yet elapsed. Asserting AUTO_HEAL_DISABLED here, not only
            // after the debounce advance below, is what actually pins the evaluation order —
            // suppressionReason() must check the operator override before the debounce timer, or this
            // would read WITHIN_DEBOUNCE instead and the later assertion would still pass by
            // coincidence (debounce is the only gate left closed there).
            assertThat(reconciler.lastProvisioningDecision().unwrap().reason()).isEqualTo("AUTO_HEAL_DISABLED");
            timeSource.advanceTimeMillis(EXPECTED_DEBOUNCE_WINDOW.millis() + 1);
            listener.clear();
            triggerAndFireReconcile();

            assertThat(listener.events().getLast().provisionCount()).isZero();
            assertThat(ctm.provisionReplacementCalls()).isEmpty();
            // #603 review Gap 3 — the #336 management-API surface must attribute this suppression
            // to the operator override, not to the debounce timer it just outlasted.
            assertThat(reconciler.lastProvisioningDecision().unwrap().reason()).isEqualTo("AUTO_HEAL_DISABLED");

            // Re-enabling clears the override immediately — no relatch, no re-debounce needed since
            // the deficit is still the same aged run.
            ctm.setAutoHealEnabled(true, "test: operator re-enabled");
            listener.clear();
            triggerAndFireReconcile();
            assertThat(ctm.provisionReplacementCalls()).hasSize(1);
        }

        /// Row 4 — after the latch, a departure whose debounce has NOT elapsed is suppressed
        /// (`WITHIN_DEBOUNCE`) AND a single re-evaluation follow-up reconcile is scheduled so the
        /// deficit is acted on when the gate clears, without any further presence sampler event.
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

        /// THE auto-heal fix, reproducing the live Docker trace exactly. presence sampler reaches full size 5 at
        /// formation (peakMembershipCount→5), then a priming kill drops membership to 4 BEFORE the
        /// reconciler runs a single pass — so the reconciler is departure-triggered and its FIRST
        /// pass observes clusterMembershipCount=4, NEVER 5. With the latch sourced from presence sampler's
        /// peakMembershipCount() (=5) instead of the per-pass count, that first pass latches
        /// reachedFullMembership=true even at count=4 → COLD_START_NOT_FULL no longer applies → after
        /// the debounce the deficit provisions. (term=1: NOT a re-election; the peak alone latches.)
        @Test
        void peakReachedFullButFirstPassAtDeficit_latchesViaPeak_andProvisionsAfterDebounce() {
            configuredCoreCount.set(5);
            assertThat(leaderTerm.get()).isEqualTo(1L);
            // Formation: presence sampler reaches full size 5 (peak→5).
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C, PEER_D);
            assertThat(sampler.peakMembershipCount()).isEqualTo(5);
            // Priming kill BEFORE any reconcile pass: membership 5 -> 4. peak stays 5.
            removePeers(PEER_D);
            assertThat(sampler.currentMembers()).hasSize(4);
            assertThat(sampler.peakMembershipCount()).isEqualTo(5);

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
            assertThat(sampler.peakMembershipCount()).isEqualTo(4);

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
        /// sweep (armed at provision-dispatch) must ask the provider, drop the placeholder on the
        /// provider-reported failure, and re-evaluate the still-present deficit — re-provisioning a
        /// fresh replacement — without any external event. Previously the deficit was never re-seen
        /// and the cluster stuck at 4/5 forever.
        ///
        /// #1049 acceptance: a provider-reported failure DOES re-dispatch, after the normal deficit
        /// debounce — and the drop no longer waits on the deleted `splitTimeout × 3` (45s) expiry.
        @Test
        void deadReplacement_providerReportsFailed_sweepDropsPlaceholder_andReProvisionsAfterDebounce() {
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
            assertThat(scheduler.tasksByDelay(EXPECTED_POLL_INTERVAL)).hasSize(1);
            listener.clear();

            // The replacement boots then dies (never re-joins presence sampler). No further events arrive.
            // The provider reports the boot failed; one poll interval later the armed sweep asks, and
            // the FAILED answer drops the placeholder (15s — inside the 45s the old expiry needed). The
            // drop re-opens the deficit, which must re-age past the debounce before re-provisioning.
            ctm.reportInstanceState(ctm.provisionReplacementCalls().getFirst(), ReplacementInstanceState.FAILED);
            timeSource.advanceTimeMillis(EXPECTED_POLL_INTERVAL.millis());
            scheduler.tasksByDelay(EXPECTED_POLL_INTERVAL).getFirst().runIfLive();
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

        /// No-double-provision: if the real node joins under its minted id before the sweep fires, the
        /// reconcile pass clears the placeholder (identity-match fulfilment) and the sweep that fires
        /// afterwards provisions nothing (effective == configured). Confirms a sweep tick cannot
        /// double-provision a node that joined meanwhile.
        @Test
        void replacementJoinedUnderMintedId_clearsPlaceholder_sweepProvisionsNothing() {
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

            // The real node joins under its minted id (back to confirmed=5) before the sweep fires.
            seedClusterWithPeers(ctm.provisionReplacementCalls().getFirst());
            triggerAndFireReconcile();
            timeSource.advanceTimeMillis(EXPECTED_POLL_INTERVAL.millis());
            scheduler.tasksByDelay(EXPECTED_POLL_INTERVAL).getFirst().runIfLive();
            triggerAndFireReconcile();

            // Placeholder cleared by fulfilment; no second provision — effective == configured.
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
            var armedSweeps = scheduler.tasksByDelay(EXPECTED_POLL_INTERVAL).size();

            // The real node joins under its minted id so the deficit is gone; fulfilment empties the map.
            seedClusterWithPeers(ctm.provisionReplacementCalls().getFirst());
            triggerAndFireReconcile();
            assertThat(reconciler.inFlightProvisioningCount()).isZero();
            timeSource.advanceTimeMillis(EXPECTED_POLL_INTERVAL.millis());
            scheduler.tasksByDelay(EXPECTED_POLL_INTERVAL).getFirst().runIfLive();

            // Map empty → the sweep does not re-arm: no new sweep future beyond the ones already created.
            assertThat(scheduler.tasksByDelay(EXPECTED_POLL_INTERVAL)).hasSize(armedSweeps);
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
            var sweep = scheduler.tasksByDelay(EXPECTED_POLL_INTERVAL).getFirst();

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
            // STILL tracked (the fake provider reports nothing, so it is not dropped). effective = 5 confirmed + 1 stale
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

        /// (b) Once the exact minted id appears in presence sampler `currentMembers`, the next reconcile clears
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

    /// Mutable SWIM health snapshot source backing the [`PresenceSampler`]. `markHealthy`
    /// adds a peer as `HEALTHY`; `markAbsent` drops it (so the next presence sampler sample sees it gone).
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

    /// #509 probe — post-full-cluster-restart deficit-fill for stable-id members that are merely
    /// slow to rejoin.
    ///
    /// The ticket (2026-07-24) reports that after a full-cluster restart the reconciler sees
    /// `clusterMembers < configured` and provisions EMPTY replacements for configured peers that
    /// have not finished rejoining, `failedPeer=None`, un-gated by auto-heal-off. These tests
    /// reproduce that shape against CURRENT code rather than assuming it still holds: several gates
    /// landed on or after that date (the arm-latch, `reachedFullMembership`, the deficit debounce),
    /// and `MembershipFsm#seed` promotes the whole CONFIGURED core set to MEMBER at wiring time —
    /// which, if it applies here, means a restarted leader counts the slow rejoiners and sees no
    /// deficit at all.
    ///
    /// Whichever way these land they are worth keeping: they pin the restart-with-laggards
    /// behaviour, which nothing else in this class covers.
    @Nested
    class PostRestartSlowRejoin {
        /// The restart shape: the leader's FSM is seeded from CONFIGURED topology (as `AetherNode`
        /// does at wiring), only a quorum actually rejoins, and leadership is gained on a term > 1
        /// so `reachedFullMembership` is pre-latched via the re-election path. Nothing here carries
        /// a death verdict — the missing peers are known stable IDs that simply have not come back.
        @Test
        void reconcile_configuredPeersSeededButNotYetRejoined_doesNotProvisionReplacements() {
            configuredCoreCount.set(5);
            // Config-topology seed: every configured core, including the two that have not rejoined.
            membershipFsm.seed(Set.of(PEER_A, PEER_B, PEER_C, PEER_D));
            // Only PEER_A and PEER_B actually came back and are observed.
            seedClusterWithPeers(PEER_A, PEER_B);
            leaderTerm.set(2L);

            reconciler.activate();
            triggerAndFireReconcile();
            advancePastProvisioningGates();
            triggerAndFireReconcile();

            assertThat(ctm.provisionReplacementCalls()).as(
                "#509: configured peers that are merely slow to rejoin must not be replaced by empty nodes")
                      .isEmpty();
        }

        /// Control: a peer carrying a genuine death verdict MUST still be replaced, otherwise a
        /// "grace" for slow rejoiners would silently disable auto-heal. Without this, the assertion
        /// above could be satisfied by provisioning being broken outright.
        @Test
        void reconcile_configuredPeerConfirmedDead_stillProvisionsReplacement() {
            configuredCoreCount.set(5);
            membershipFsm.seed(Set.of(PEER_A, PEER_B, PEER_C, PEER_D));
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C, PEER_D);
            leaderTerm.set(2L);

            reconciler.activate();
            triggerAndFireReconcile();
            removePeers(PEER_D);
            triggerAndFireReconcile();
            advancePastProvisioningGates();
            triggerAndFireReconcile();

            assertThat(ctm.provisionReplacementCalls()).as("a confirmed-dead peer is a departure and must still auto-heal")
                      .isNotEmpty();
        }
    }

    /// Recording `ClusterTopologyManager` stub. Phase 1.5 verification surface for
    /// `provisionReplacement` / `drainNode` / `reconcile` v2 calls.
    private static final class RecordingCtm implements ClusterTopologyManager {
        @Override public boolean usesExplicitCommunities() { return false; }
        @Override public void installCommunityPlacement(org.pragmatica.aether.deployment.cluster.CommunityPlacementReconciler reconciler) {}
        @Override public org.pragmatica.lang.Promise<org.pragmatica.lang.Unit> provisionPlacementNode(org.pragmatica.aether.slice.kvstore.AetherValue.CommunityPlacementOperationValue operation) {
            return org.pragmatica.lang.Promise.unitPromise();
        }

        private final List<NodeId> drainNodeCalls = new CopyOnWriteArrayList<>();
        private final List<NodeId> provisionReplacementCalls = new CopyOnWriteArrayList<>();
        private final List<NodeRole> provisionReplacementRoles = new CopyOnWriteArrayList<>();
        private final List<DrainReason> drainReasons = new CopyOnWriteArrayList<>();
        private final AtomicInteger reconcileCount = new AtomicInteger(0);
        // Auto-heal-wedge fix: the disposition the next provisionReplacement resolves to. Defaults
        // to a success-valued Dispatched (a real boot is coming → the reconciler KEEPS its in-flight
        // placeholder). Tests flip this to a Deferred (no-boot deferral → placeholder REMOVED) or to
        // a failure (genuine boot failure → placeholder REMOVED) to exercise the three outcomes.
        private final AtomicReference<Promise<ProvisionDisposition>> nextProvisionResult =
            new AtomicReference<>(Promise.success(ProvisionDisposition.dispatched()));
        // #1049 — what the fake provider reports about each replacement's instance: UNKNOWN unless a test
        // says otherwise (a fake that cannot answer must read neither as "exists" nor as "gone"); every
        // status query the reconciler issued; an optional held answer (a slow provider); and the
        // per-source replacement ceiling the reconciler reads.
        private final Map<NodeId, ReplacementInstanceState> instanceStates = new ConcurrentHashMap<>();
        private final List<NodeId> instanceStateQueries = new CopyOnWriteArrayList<>();
        private final AtomicReference<Option<Promise<ReplacementInstanceState>>> heldInstanceStateAnswer =
            new AtomicReference<>(Option.none());
        private final AtomicReference<TimeSpan> replacementCeiling = new AtomicReference<>(DEFAULT_REPLACEMENT_CEILING);

        @Contract
        void reportInstanceState(NodeId nodeId, ReplacementInstanceState state) {
            instanceStates.put(nodeId, state);
        }

        @Contract
        void holdInstanceStateAnswers(Promise<ReplacementInstanceState> answer) {
            heldInstanceStateAnswer.set(Option.some(answer));
        }

        @Contract
        void releaseInstanceStateAnswers() {
            heldInstanceStateAnswer.set(Option.none());
        }

        @Contract
        void holdNextProvision(Promise<ProvisionDisposition> result) {
            nextProvisionResult.set(result);
        }

        @Contract
        void setReplacementCeiling(TimeSpan ceiling) {
            replacementCeiling.set(ceiling);
        }

        List<NodeId> instanceStateQueries() {
            return List.copyOf(instanceStateQueries);
        }

        List<NodeId> drainNodeCalls() {
            return List.copyOf(drainNodeCalls);
        }

        @Contract
        void clear() {
            drainNodeCalls.clear();
            provisionReplacementCalls.clear();
            provisionReplacementRoles.clear();
            drainReasons.clear();
        }

        @Contract
        void deferNextProvision(ProvisionDisposition.DeferralReason reason) {
            nextProvisionResult.set(Promise.success(ProvisionDisposition.deferred(reason)));
        }

        @Contract
        void failNextProvision(Cause cause) {
            nextProvisionResult.set(cause.promise());
        }

        List<NodeId> provisionReplacementCalls() {
            return List.copyOf(provisionReplacementCalls);
        }

        List<NodeRole> provisionReplacementRoles() {
            return List.copyOf(provisionReplacementRoles);
        }

        List<DrainReason> drainReasons() {
            return List.copyOf(drainReasons);
        }

        int reconcileCount() {
            return reconcileCount.get();
        }

        @Override
        public Promise<ProvisionDisposition> provisionReplacement(NodeId newNodeId,
                                                                  Option<NodeId> failedPeer,
                                                                  Set<NodeId> clusterMembers,
                                                                  NodeRole intendedRole) {
            provisionReplacementCalls.add(newNodeId);
            provisionReplacementRoles.add(intendedRole);
            return nextProvisionResult.get();
        }

        @Override
        public Promise<Unit> drainNode(NodeId targetNodeId, DrainReason reason) {
            drainNodeCalls.add(targetNodeId);
            drainReasons.add(reason);
            return Promise.success(unit());
        }

        @Override
        public Promise<ReplacementInstanceState> replacementInstanceState(NodeId nodeId) {
            instanceStateQueries.add(nodeId);
            return heldInstanceStateAnswer.get()
                                          .or(() -> Promise.success(instanceStates.getOrDefault(nodeId,
                                                                                                ReplacementInstanceState.UNKNOWN)));
        }

        @Override
        public TimeSpan replacementCeiling(NodeRole intendedRole) {
            return replacementCeiling.get();
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
        public Promise<Unit> setDesiredCount(SourceName sourceName, NodeRole role, int count) {
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
        public Option<LastProvisionFailure> lastProvisionFailure() {
            return Option.none();
        }

        @Override
        public int resetCircuitBreaker(String reason) {
            return 0;
        }

        // #603 — real mutable state (default enabled, matching production's construction-time
        // default) so a test can flip it and assert the reconciler actually honours the flag,
        // rather than a fake that always reports enabled regardless of what was set.
        private final AtomicBoolean autoHealEnabled = new AtomicBoolean(true);

        @Override
        public boolean isAutoHealEnabled() {
            return autoHealEnabled.get();
        }

        @Override
        public Promise<Boolean> setAutoHealEnabled(boolean enabled, String reason) {
            return Promise.success(autoHealEnabled.getAndSet(enabled));
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
    /// H1 / #257 (cluster-topology-overhaul Wave 8 item 1, pulled forward): the
    /// `ClusterConfigKey` KV-commit notification is wired to [`LeaderReconciler#onConfigChange`]
    /// in `AetherNode` — a config-driven target change (scale up/down, restore-to-N) must
    /// produce a reconcile pass within the debounce window on the leader, riding the standard
    /// CAS-debounce machinery, with `trigger=CONFIG_CHANGE` carried into the intent (the
    /// provisioning-decision log evidence). Deliberately self-contained — uses only
    /// activate/onConfigChange/scheduler/listener, away from the seeding fixtures.
    @Nested
    class ConfigChangeTrigger {
        @Test
        void onConfigChange_leader_dispatchesReconcileWithinDebounce_withConfigChangeTrigger() {
            configuredCoreCount.set(1);
            reconciler.activate();

            reconciler.onConfigChange();

            var debounced = scheduler.tasksByDelay(DEBOUNCE_DELAY);

            assertThat(debounced)
                .as("a committed config change must schedule exactly one debounced reconcile")
                .hasSize(1);

            debounced.getFirst().runIfLive();

            assertThat(listener.events()).hasSize(1);
            assertThat(listener.events().getFirst().trigger())
                .as("the reconcile pass must carry trigger=CONFIG_CHANGE")
                .isEqualTo(ReconcileTrigger.CONFIG_CHANGE);
        }

        @Test
        void onConfigChange_nonLeader_isIgnored() {
            configuredCoreCount.set(1);

            reconciler.onConfigChange();

            assertThat(scheduler.tasksByDelay(DEBOUNCE_DELAY))
                .as("a follower must not reconcile on config change (leader-gated)")
                .isEmpty();
            assertThat(listener.events()).isEmpty();
        }
    }

    /// H1 / #257 completion (live-gate evidence): a deficit born of deaths during a post-churn
    /// lull with an UNCHANGED configured size never re-poked the reconciler — the deficit sat
    /// past the 600s window, then healed in 13s once poked. ANY pass that ends with an
    /// UNRESOLVED confirmed-member deficit must arm the deduped, debounce-spaced
    /// deficit-convergence follow-up ([`ReconcileTrigger#DEFICIT_FOLLOW_UP`]), self-rearming
    /// until the deficit converges to zero; a converged pass arms nothing. Deficits are
    /// created by UNDER-SEEDING (the proven-green `reElection...` pattern) — never via
    /// `removePeers`, whose SUSPECT-still-counts behaviour is the #246 fixture breakage.
    @Nested
    class DeficitFollowUp {
        /// Follow-up delay band for a fresh-anchor / anchor-unset pass: the full deficit-debounce
        /// window plus the short scheduling margin.
        private static final TimeSpan FOLLOW_UP_DELAY =
            timeSpan(EXPECTED_DEBOUNCE_WINDOW.nanos() + DEBOUNCE_DELAY.nanos()).nanos();

        /// A quorum-safe, latched pass suppressed by the debounce gate (deficit > 0, nothing
        /// dispatched) arms exactly one follow-up.
        @Test
        void unresolvedDeficitPass_noDispatch_armsFollowUp() {
            configuredCoreCount.set(5);
            leaderTerm.set(2L);
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C);
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();

            assertThat(ctm.provisionReplacementCalls()).isEmpty();
            assertThat(scheduler.tasksByDelay(FOLLOW_UP_DELAY))
                .as("an unresolved-deficit pass must arm exactly one deficit follow-up")
                .hasSize(1);
        }

        /// A quorum-UNSAFE deficit pass (previously armed nothing — the live-gate gap class)
        /// also arms the follow-up.
        @Test
        void quorumUnsafeDeficitPass_armsFollowUp_noDispatch() {
            configuredCoreCount.set(5);
            seedClusterWithPeers(PEER_A);
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();

            assertThat(ctm.provisionReplacementCalls()).isEmpty();
            assertThat(listener.events().getLast().provisionCount()).isZero();
            assertThat(scheduler.tasksByDelay(FOLLOW_UP_DELAY))
                .as("a quorum-unsafe deficit pass must still arm the follow-up")
                .hasSize(1);
        }

        /// The follow-up fires once the debounce window clears: it re-enters the reconcile path
        /// with `trigger=DEFICIT_FOLLOW_UP` and dispatches the provision — no external membership
        /// event needed. The dispatching pass then RE-ARMS (in-flight-masked raw deficit — the
        /// replacement has not joined yet), proving the convergence loop keeps checking until join.
        @Test
        void followUp_firesAndDispatches_onceDebounceClears_thenRearmsAwaitingJoin() {
            configuredCoreCount.set(5);
            leaderTerm.set(2L);
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C);
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();
            assertThat(scheduler.tasksByDelay(FOLLOW_UP_DELAY)).hasSize(1);
            listener.clear();

            timeSource.advanceTimeMillis(EXPECTED_DEBOUNCE_WINDOW.millis() + 1);
            scheduler.tasksByDelay(FOLLOW_UP_DELAY).getFirst().runIfLive();
            fireDebouncedReconcile();

            assertThat(ctm.provisionReplacementCalls()).hasSize(1);
            assertThat(listener.events().getLast().trigger())
                .as("the follow-up pass must carry trigger=DEFICIT_FOLLOW_UP into the decision log")
                .isEqualTo(ReconcileTrigger.DEFICIT_FOLLOW_UP);
            assertThat(listener.events().getLast().provisionCount()).isEqualTo(1);
            assertThat(scheduler.tasksByDelay(FOLLOW_UP_DELAY))
                .as("the dispatching pass re-arms while the raw member deficit awaits the join (in-flight-masked)")
                .hasSize(2);
        }

        /// Converged pass (confirmed members at configured target) arms nothing — the loop
        /// terminates the moment the deficit reaches zero.
        @Test
        void convergedPass_armsNothing() {
            configuredCoreCount.set(5);
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C, PEER_D);
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();

            assertThat(scheduler.tasksByDelay(FOLLOW_UP_DELAY))
                .as("a converged pass must arm no follow-up")
                .isEmpty();
        }

        /// Hot-loop guard: a second deficient pass while a follow-up is already pending does NOT
        /// arm a second one (single-ref dedupe — at most one outstanding).
        @Test
        void secondDeficitPass_whileFollowUpPending_isDeduped() {
            configuredCoreCount.set(5);
            leaderTerm.set(2L);
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C);
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();
            assertThat(scheduler.tasksByDelay(FOLLOW_UP_DELAY)).hasSize(1);

            triggerAndFireReconcile();

            assertThat(scheduler.tasksByDelay(FOLLOW_UP_DELAY))
                .as("a pending follow-up dedupes further arming")
                .hasSize(1);
        }
    }

    /// #331 — the surplus-convergence follow-up: the symmetric counterpart of [`DeficitFollowUp`]
    /// for OVER-provisioning. Any reconcile pass that ends with `effective > configuredCoreCount`
    /// (a drain was dispatched but the victims have not yet left membership) arms exactly one
    /// [`ReconcileTrigger#SURPLUS_FOLLOW_UP`], self-rearming until the effective count converges
    /// DOWN to the configured target; a converged pass arms nothing. A node sitting in surplus is
    /// HEALTHY and fires no SWIM edge, so without this loop the leader drains one batch and never
    /// re-checks — the 02-chaos 6-where-5-expected convergence gap.
    @Nested
    class SurplusFollowUp {
        /// Follow-up delay band — the full deficit-debounce window plus the short scheduling
        /// margin (same cadence as the deficit follow-up; a surplus has no per-run anchor).
        private static final TimeSpan FOLLOW_UP_DELAY =
            timeSpan(EXPECTED_DEBOUNCE_WINDOW.nanos() + DEBOUNCE_DELAY.nanos()).nanos();

        /// A surplus pass that dispatches a drain (victims have not yet departed → surplus still
        /// open) arms exactly one surplus follow-up.
        @Test
        void unresolvedSurplusPass_afterDrainDispatch_armsFollowUp() {
            configuredCoreCount.set(3);
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C, PEER_D);
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().cancel(false);
            listener.clear();

            reconciler.onSwimMemberHealthy(PEER_A, fsmIncarnation.getAndIncrement());
            fireDebouncedReconcile();

            assertThat(listener.events().getLast().drainCount())
                .as("the surplus pass dispatches a drain")
                .isEqualTo(2);
            assertThat(scheduler.tasksByDelay(FOLLOW_UP_DELAY))
                .as("an unresolved-surplus pass must arm exactly one surplus follow-up")
                .hasSize(1);
        }

        /// The follow-up fires once its delay clears: it re-enters the reconcile path with
        /// `trigger=SURPLUS_FOLLOW_UP` — no external membership event needed. While the surplus is
        /// still open (drained victims have not departed in this fixture) it RE-ARMS, proving the
        /// convergence loop keeps checking.
        @Test
        void followUp_firesWithSurplusTrigger_andRearmsWhileSurplusOpen() {
            configuredCoreCount.set(3);
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C, PEER_D);
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().cancel(false);
            reconciler.onSwimMemberHealthy(PEER_A, fsmIncarnation.getAndIncrement());
            fireDebouncedReconcile();
            assertThat(scheduler.tasksByDelay(FOLLOW_UP_DELAY)).hasSize(1);
            listener.clear();

            scheduler.tasksByDelay(FOLLOW_UP_DELAY).getFirst().runIfLive();
            fireDebouncedReconcile();

            assertThat(listener.events().getLast().trigger())
                .as("the follow-up pass must carry trigger=SURPLUS_FOLLOW_UP into the decision log")
                .isEqualTo(ReconcileTrigger.SURPLUS_FOLLOW_UP);
            assertThat(scheduler.tasksByDelay(FOLLOW_UP_DELAY))
                .as("the still-surplus follow-up pass re-arms until the surplus closes")
                .hasSize(2);
        }

        /// Convergence terminates the loop: once the drained victims actually leave membership and
        /// the effective count returns to the configured target, the next follow-up pass arms
        /// nothing.
        @Test
        void followUp_armsNothing_onceSurplusConvergesToTarget() {
            configuredCoreCount.set(3);
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C, PEER_D);
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().cancel(false);
            reconciler.onSwimMemberHealthy(PEER_A, fsmIncarnation.getAndIncrement());
            fireDebouncedReconcile();
            assertThat(scheduler.tasksByDelay(FOLLOW_UP_DELAY)).hasSize(1);

            // The drained victims now actually depart, closing the surplus (5 -> 3 = target).
            removePeers(PEER_C, PEER_D);
            listener.clear();
            scheduler.tasksByDelay(FOLLOW_UP_DELAY).getFirst().runIfLive();
            fireDebouncedReconcile();

            assertThat(listener.events().getLast().drainCount())
                .as("at target there is no further drain")
                .isZero();
            assertThat(scheduler.tasksByDelay(FOLLOW_UP_DELAY).stream().filter(task -> !task.isDone()).toList())
                .as("a converged surplus pass arms no NEW (live) follow-up — the loop terminates")
                .isEmpty();
        }

        /// A converged pass (members already at the configured target) never arms a surplus
        /// follow-up in the first place.
        @Test
        void atTargetPass_armsNoSurplusFollowUp() {
            configuredCoreCount.set(5);
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C, PEER_D);
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();

            assertThat(scheduler.tasksByDelay(FOLLOW_UP_DELAY))
                .as("an at-target pass must arm no surplus follow-up")
                .isEmpty();
        }

        /// Hot-loop guard: a second surplus pass while a follow-up is already pending does NOT arm
        /// a second one (single-ref dedupe — at most one outstanding).
        @Test
        void secondSurplusPass_whileFollowUpPending_isDeduped() {
            configuredCoreCount.set(3);
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C, PEER_D);
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().cancel(false);
            reconciler.onSwimMemberHealthy(PEER_A, fsmIncarnation.getAndIncrement());
            fireDebouncedReconcile();
            assertThat(scheduler.tasksByDelay(FOLLOW_UP_DELAY)).hasSize(1);

            reconciler.onSwimMemberHealthy(PEER_B, fsmIncarnation.getAndIncrement());
            fireDebouncedReconcile();

            assertThat(scheduler.tasksByDelay(FOLLOW_UP_DELAY))
                .as("a pending surplus follow-up dedupes further arming")
                .hasSize(1);
        }

        /// Deactivation cancels a pending surplus follow-up (a deposed leader must not keep
        /// re-triggering drains).
        @Test
        void deactivate_cancelsPendingSurplusFollowUp() {
            configuredCoreCount.set(3);
            seedClusterWithPeers(PEER_A, PEER_B, PEER_C, PEER_D);
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().cancel(false);
            reconciler.onSwimMemberHealthy(PEER_A, fsmIncarnation.getAndIncrement());
            fireDebouncedReconcile();
            var followUp = scheduler.tasksByDelay(FOLLOW_UP_DELAY).getFirst();

            reconciler.deactivate();

            assertThat(followUp.cancelled())
                .as("deactivation cancels the pending surplus follow-up")
                .isTrue();
        }
    }

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

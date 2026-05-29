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
import org.pragmatica.consensus.topology.MembershipView;
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

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.membership.MembershipConfig.membershipConfig;
import static org.pragmatica.aether.deployment.membership.ntt.LeaderReconciler.leaderReconciler;
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
    private static final TimeSpan EXPECTED_ACTIVATION_DELAY =
        timeSpan(membershipConfig().nttDepartureTimeout().nanos() * 3 / 2).nanos();
    private static final TimeSpan DEBOUNCE_DELAY = timeSpan(100L).millis();

    private TestTimeSource timeSource;
    private ManualScheduler scheduler;
    private RecordingListener listener;
    private MutableIntSupplier configuredCoreCount;
    private RecordingCtm ctm;
    private MutableMembershipView membershipView;
    private LeaderReconciler reconciler;

    @BeforeEach
    void setUp() {
        timeSource = new TestTimeSource();
        scheduler = new ManualScheduler();
        listener = new RecordingListener();
        configuredCoreCount = new MutableIntSupplier(0);
        ctm = new RecordingCtm();
        membershipView = new MutableMembershipView(SELF);
        reconciler = leaderReconciler(membershipConfig(),
                                      membershipView,
                                      configuredCoreCount,
                                      ctm,
                                      timeSource,
                                      scheduler);
        reconciler.setReconcileListener(listener);
    }

    /// Feed N healthy peers into the membership view — the reconciler's
    /// `clusterMembershipCount` reads via `membershipView.coreMemberIds().size()`
    /// (which includes `SELF` automatically).
    @Contract
    private void seedClusterWithPeers(NodeId... peers) {
        for (var peer : peers) {
            membershipView.addMember(peer);
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
            configuredCoreCount.set(5);
            seedClusterWithPeers(PEER_A, PEER_B);
            reconciler.activate();

            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();

            assertThat(listener.events()).hasSize(1);
            var emitted = listener.events().getFirst();
            assertThat(emitted.trigger()).isEqualTo(ReconcileTrigger.LEADER_ACTIVATION);
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

            reconciler.onSwimMemberHealthy(PEER_A);
            scheduler.tasksByDelay(DEBOUNCE_DELAY).getFirst().runIfLive();

            assertThat(listener.events()).hasSize(1);
            var emitted = listener.events().getFirst();
            assertThat(emitted.trigger()).isEqualTo(ReconcileTrigger.MEMBER_APPEARED);
            assertThat(emitted.drainCount()).isEqualTo(2);
            assertThat(emitted.provisionCount()).isZero();
        }

        @Test
        void onSwimMemberHealthy_whileNotLeader_emitsNothing() {
            reconciler.onSwimMemberHealthy(PEER_A);

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
            configuredCoreCount.set(5);
            seedClusterWithPeers(PEER_A, PEER_B);

            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();

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
            configuredCoreCount.set(5);
            seedClusterWithPeers(PEER_A, PEER_B);
            reconciler.activate();
            scheduler.tasksByDelay(EXPECTED_ACTIVATION_DELAY).getFirst().runIfLive();
            assertThat(reconciler.inFlightProvisioningCount()).isEqualTo(2);
            listener.clear();
            seedClusterWithPeers(NodeId.randomNodeId(), NodeId.randomNodeId());
            timeSource.advanceTimeMillis(EXPECTED_ACTIVATION_DELAY.millis() * 3);

            reconciler.onTopologyUnhealthy();
            scheduler.tasksByDelay(DEBOUNCE_DELAY).getFirst().runIfLive();

            assertThat(listener.events()).hasSize(1);
            assertThat(listener.events().getFirst().inFlightProvisioningCount()).isZero();
            assertThat(reconciler.inFlightProvisioningCount()).isZero();
        }
    }

    /// Mutable [`MembershipView`] stub. `coreMemberIds()` always includes `SELF`
    /// (mirroring the universal-self-membership the reconciler previously read from NTT);
    /// `addMember` seeds additional peers. The lifecycle/health projections are not read
    /// by the reconciler, so they return empty/zero sensible defaults.
    private static final class MutableMembershipView implements MembershipView {
        private final Set<NodeId> members = new LinkedHashSet<>();

        MutableMembershipView(NodeId self) {
            members.add(self);
        }

        @Contract
        void addMember(NodeId nodeId) {
            members.add(nodeId);
        }

        @Override
        public Set<NodeId> coreMemberIds() {
            return Set.copyOf(members);
        }

        @Override
        public Set<NodeId> onDutyMemberIds() {
            return Set.copyOf(members);
        }

        @Override
        public int healthyOnDutyCount() {
            return members.size();
        }

        @Override
        public int desiredCoreSize() {
            return members.size();
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

    /// Recording `ClusterTopologyManager` stub. Phase 1.5 verification surface for
    /// `provisionReplacement` / `drainNode` / `reconcile` v2 calls.
    private static final class RecordingCtm implements ClusterTopologyManager {
        private final List<NodeId> drainNodeCalls = new CopyOnWriteArrayList<>();
        private final List<Option<NodeId>> provisionReplacementCalls = new CopyOnWriteArrayList<>();
        private final List<DrainReason> drainReasons = new CopyOnWriteArrayList<>();
        private final AtomicInteger reconcileCount = new AtomicInteger(0);

        List<NodeId> drainNodeCalls() {
            return List.copyOf(drainNodeCalls);
        }

        List<Option<NodeId>> provisionReplacementCalls() {
            return List.copyOf(provisionReplacementCalls);
        }

        List<DrainReason> drainReasons() {
            return List.copyOf(drainReasons);
        }

        int reconcileCount() {
            return reconcileCount.get();
        }

        @Override
        public Promise<Unit> provisionReplacement(Option<NodeId> failedPeer, Set<NodeId> clusterMembers) {
            provisionReplacementCalls.add(failedPeer);
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

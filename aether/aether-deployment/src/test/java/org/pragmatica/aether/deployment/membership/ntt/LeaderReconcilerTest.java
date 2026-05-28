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
import org.pragmatica.swim.SwimObservation.DepartedObserved;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.membership.MembershipConfig.membershipConfig;
import static org.pragmatica.aether.deployment.membership.ntt.LeaderReconciler.leaderReconciler;
import static org.pragmatica.aether.deployment.membership.ntt.LocalQuorumWatcher.localQuorumWatcher;
import static org.pragmatica.aether.deployment.membership.ntt.NodeTopologyTracker.nodeTopologyTracker;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Unit tests for [`LeaderReconciler`] — mechanism in isolation. NTT and
/// [`LocalQuorumWatcher`] are constructed live (same test scheduler) but no
/// SWIM / QUIC wiring is exercised.
class LeaderReconcilerTest {
    private static final NodeId PEER_A = NodeId.randomNodeId();
    private static final NodeId PEER_B = NodeId.randomNodeId();
    private static final TimeSpan PROVISIONING_TIMEOUT = timeSpan(60).seconds();
    private static final TimeSpan EXPECTED_TICK_PERIOD = timeSpan(PROVISIONING_TIMEOUT.nanos() * 3 / 2).nanos();

    private TestTimeSource timeSource;
    private ManualScheduler scheduler;
    private RecordingListener listener;
    private MutableIntSupplier clusterMembershipCount;
    private MutableIntSupplier configuredCoreCount;
    private MutableMembersSupplier currentMembers;
    private RecordingCtm ctm;
    private NodeTopologyTracker ntt;
    private LocalQuorumWatcher localQuorum;
    private LeaderReconciler reconciler;

    @BeforeEach
    void setUp() {
        timeSource = new TestTimeSource();
        scheduler = new ManualScheduler();
        listener = new RecordingListener();
        clusterMembershipCount = new MutableIntSupplier(0);
        configuredCoreCount = new MutableIntSupplier(0);
        currentMembers = new MutableMembersSupplier(Set.of());
        ctm = new RecordingCtm();
        ntt = nodeTopologyTracker(membershipConfig(), timeSource, scheduler);
        localQuorum = localQuorumWatcher(membershipConfig(), timeSource, scheduler);
        reconciler = leaderReconciler(membershipConfig(),
                                      PROVISIONING_TIMEOUT,
                                      ntt,
                                      localQuorum,
                                      clusterMembershipCount,
                                      configuredCoreCount,
                                      currentMembers,
                                      ctm,
                                      timeSource,
                                      scheduler);
        reconciler.setReconcileListener(listener);
    }

    @Nested
    class DefaultState {
        @Test
        void freshReconciler_isNotLeader_andSchedulesNoTicks() {
            assertThat(reconciler.isLeader()).isFalse();
            assertThat(reconciler.inFlightProvisioningCount()).isZero();
            assertThat(reconciler.tickPeriod()).isEqualTo(EXPECTED_TICK_PERIOD);
            // Only the LocalQuorumWatcher / NTT collaborators may have scheduled tasks
            // (they didn't here either — coreCount=0 means quorum-watcher is dormant).
            assertThat(reconciler.inFlightProvisioningSnapshot()).isEmpty();
        }
    }

    @Nested
    class LeaderActivation {
        @Test
        void activate_emitsInitialIntent_andSchedulesFirstTick() {
            configuredCoreCount.set(5);
            clusterMembershipCount.set(3);

            reconciler.activate();

            assertThat(reconciler.isLeader()).isTrue();
            // One LEADER_ACTIVATION intent (the backstop one; no NTT events drained).
            assertThat(listener.events()).hasSize(1);
            var emitted = listener.events().getFirst();
            assertThat(emitted.trigger()).isEqualTo(ReconcileTrigger.LEADER_ACTIVATION);
            assertThat(emitted.clusterMembershipCount()).isEqualTo(3);
            assertThat(emitted.configuredCoreCount()).isEqualTo(5);
            // E2 Phase 1 — shortfall of 2 surfaces 2 provision placeholders; no drain.
            assertThat(emitted.peersToProvision()).hasSize(2);
            assertThat(emitted.peersToDrain()).isEmpty();
            // CTM received 2 provisionReplacement calls, no drainNode calls.
            assertThat(ctm.provisionReplacementCalls()).hasSize(2);
            assertThat(ctm.drainNodeCalls()).isEmpty();

            // The reconciler scheduled exactly one new task (the first periodic tick).
            // (The quorum-watcher will also have scheduled one because coreCount=5 puts
            // us below threshold immediately.)
            var reconcilerTicks = scheduler.tasksByDelay(EXPECTED_TICK_PERIOD);
            assertThat(reconcilerTicks).hasSize(1);
        }

        @Test
        void activate_drainsPreFiredNttEvents_emitsOneIntentPerDrainedPlusBackstop() {
            configuredCoreCount.set(5);
            clusterMembershipCount.set(2);

            // Pre-populate NTT with one fired event.
            ntt.onSwimObservation(new DepartedObserved(PEER_A, 1L));
            scheduler.tasksByDelay(membershipConfig().nttDepartureTimeout()).getFirst().runIfLive();
            assertThat(ntt.firedEventCount()).isEqualTo(1);

            reconciler.activate();

            // Two LEADER_ACTIVATION intents: one per drained event + the backstop.
            assertThat(listener.events()).hasSize(2);
            assertThat(listener.events()).allSatisfy(intent ->
                assertThat(intent.trigger()).isEqualTo(ReconcileTrigger.LEADER_ACTIVATION));
            assertThat(ntt.firedEventCount()).isZero();
        }

        @Test
        void activate_isIdempotent_secondCallNoOp() {
            configuredCoreCount.set(5);
            reconciler.activate();
            var firstCount = listener.events().size();
            var firstTickCount = scheduler.tasksByDelay(EXPECTED_TICK_PERIOD).size();

            reconciler.activate();

            assertThat(listener.events()).hasSize(firstCount);
            assertThat(scheduler.tasksByDelay(EXPECTED_TICK_PERIOD)).hasSize(firstTickCount);
        }
    }

    @Nested
    class LeaderDeactivation {
        @Test
        void deactivate_cancelsPendingTick_clearsLeaderFlag() {
            configuredCoreCount.set(5);
            reconciler.activate();
            var tickTask = scheduler.tasksByDelay(EXPECTED_TICK_PERIOD).getFirst();
            listener.clear();

            reconciler.deactivate();

            assertThat(reconciler.isLeader()).isFalse();
            assertThat(tickTask.cancelled()).isTrue();
            assertThat(reconciler.inFlightProvisioningCount()).isZero();
            assertThat(listener.events()).isEmpty();
        }

        @Test
        void deactivate_isIdempotent_secondCallNoOp() {
            reconciler.activate();
            reconciler.deactivate();

            reconciler.deactivate(); // no exception, no state change

            assertThat(reconciler.isLeader()).isFalse();
        }
    }

    @Nested
    class TopologyUnhealthyIngress {
        @Test
        void onTopologyUnhealthy_whileLeader_emitsNttDrainIntent() {
            configuredCoreCount.set(5);
            clusterMembershipCount.set(4);
            reconciler.activate();
            listener.clear();

            reconciler.onTopologyUnhealthy(new TopologyUnhealthyEvent(PEER_A, timeSource.nanoTime()));

            assertThat(listener.events()).hasSize(1);
            var emitted = listener.events().getFirst();
            assertThat(emitted.trigger()).isEqualTo(ReconcileTrigger.NTT_DRAIN);
            assertThat(emitted.clusterMembershipCount()).isEqualTo(4);
            assertThat(emitted.configuredCoreCount()).isEqualTo(5);
        }

        @Test
        void onTopologyUnhealthy_whileNotLeader_emitsNothing() {
            // No activate() — reconciler is not leader.
            reconciler.onTopologyUnhealthy(new TopologyUnhealthyEvent(PEER_A, timeSource.nanoTime()));

            assertThat(listener.events()).isEmpty();
        }
    }

    @Nested
    class QuorumLossIngress {
        @Test
        void onQuorumLossIntent_emitsQuorumLossReconcileIntent_evenIfNotLeader() {
            // QUORUM_LOSS at E1 is observation-only on every node; not gated by leadership.
            reconciler.onQuorumLossIntent(QuorumLossIntent.quorumLossIntent(timeSource.nanoTime(), 2, 3));

            assertThat(listener.events()).hasSize(1);
            assertThat(listener.events().getFirst().trigger()).isEqualTo(ReconcileTrigger.QUORUM_LOSS);
        }
    }

    @Nested
    class PeriodicTick {
        @Test
        void periodicTickFires_afterTickPeriod_emitsPeriodicTickIntent_andReSchedules() {
            configuredCoreCount.set(5);
            clusterMembershipCount.set(5);
            reconciler.activate();
            listener.clear();

            var firstTick = scheduler.tasksByDelay(EXPECTED_TICK_PERIOD).getFirst();
            timeSource.advanceTimeMillis(EXPECTED_TICK_PERIOD.millis());
            firstTick.runIfLive();

            assertThat(listener.events()).hasSize(1);
            assertThat(listener.events().getFirst().trigger()).isEqualTo(ReconcileTrigger.PERIODIC_TICK);

            // The tick re-armed the next tick.
            var ticks = scheduler.tasksByDelay(EXPECTED_TICK_PERIOD);
            assertThat(ticks).hasSize(2);
            assertThat(ticks.get(1).cancelled()).isFalse();
        }

        @Test
        void periodicTickFires_afterDeactivate_doesNotEmit() {
            configuredCoreCount.set(5);
            reconciler.activate();
            var firstTick = scheduler.tasksByDelay(EXPECTED_TICK_PERIOD).getFirst();
            reconciler.deactivate();
            listener.clear();

            // Race: scheduler-thread fires a tick that survived the cancel-and-set window.
            firstTick.runIfLive();

            // (firstTick was cancelled via cancelPendingTick, so runIfLive is a no-op.
            // But even if it ran, the isLeader-gate makes it emit nothing.)
            assertThat(listener.events()).isEmpty();
        }
    }

    @Nested
    class ReconcileSnapshot {
        @Test
        void underprovisionedSnapshot_intentReflectsObservedAndConfiguredCounts() {
            configuredCoreCount.set(5);
            clusterMembershipCount.set(3);

            reconciler.activate();

            var intent = listener.events().getFirst();
            assertThat(intent.clusterMembershipCount()).isEqualTo(3);
            assertThat(intent.configuredCoreCount()).isEqualTo(5);
            // After reconcile fires we've tracked 2 in-flight provisioning placeholders.
            assertThat(intent.inFlightProvisioningCount()).isEqualTo(2);
            // E2 Phase 1 — shortfall of 2 surfaces 2 provision placeholders; no drain.
            assertThat(intent.peersToProvision()).hasSize(2);
            assertThat(intent.peersToDrain()).isEmpty();
        }

        @Test
        void overprovisionedSnapshot_intentReflectsObservedAndConfiguredCounts() {
            configuredCoreCount.set(3);
            clusterMembershipCount.set(5);
            currentMembers.set(Set.of(PEER_A, PEER_B, NodeId.randomNodeId(), NodeId.randomNodeId(), NodeId.randomNodeId()));

            reconciler.activate();

            var intent = listener.events().getFirst();
            assertThat(intent.clusterMembershipCount()).isEqualTo(5);
            assertThat(intent.configuredCoreCount()).isEqualTo(3);
            // E2 Phase 1 — surplus of 2 surfaces 2 drain victims; no provision.
            assertThat(intent.peersToDrain()).hasSize(2);
            assertThat(intent.peersToProvision()).isEmpty();
            assertThat(ctm.drainNodeCalls()).hasSize(2);
        }
    }

    @Nested
    class InFlightExpiry {
        @Test
        void staleInFlightEntryPastExpiryWindow_isEvictedOnNextReconcile() {
            configuredCoreCount.set(5);
            clusterMembershipCount.set(3);
            reconciler.activate();
            // E2 Phase 1: activate at shortfall=2 inserts 2 in-flight provisioning
            // placeholders for the missing slots.
            assertThat(reconciler.inFlightProvisioningCount()).isEqualTo(2);
            listener.clear();
            // Saturate cluster so the next reconcile produces no new shortfall and the
            // observed inFlight count after eviction is exactly zero.
            clusterMembershipCount.set(5);
            timeSource.advanceTimeMillis(EXPECTED_TICK_PERIOD.millis() * 3);

            reconciler.onTopologyUnhealthy(new TopologyUnhealthyEvent(PEER_B, timeSource.nanoTime()));

            assertThat(listener.events()).hasSize(1);
            // Stale entries past `tickPeriod.nanos()` evicted at the head of the reconcile;
            // shortfall=0 since cluster now matches configured.
            assertThat(listener.events().getFirst().inFlightProvisioningCount()).isZero();
            assertThat(reconciler.inFlightProvisioningCount()).isZero();
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

    private static final class MutableMembersSupplier implements Supplier<Set<NodeId>> {
        private volatile Set<NodeId> value;

        MutableMembersSupplier(Set<NodeId> initial) {
            this.value = Set.copyOf(initial);
        }

        @Override
        public Set<NodeId> get() {
            return value;
        }

        @Contract
        void set(Set<NodeId> next) {
            value = Set.copyOf(next);
        }
    }

    /// Recording `ClusterTopologyManager` stub. Phase 1 verification surface for
    /// `provisionReplacement` / `drainNode` / `reconcile` v2 calls. All other interface
    /// methods return safe defaults (only the v2 surface is exercised by these tests).
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
            return null; // test stub — observer() is never called by LeaderReconciler Phase 1
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

        // TopologyManager surface — not exercised by Phase 1 tests; safe defaults.
        @Override
        @Contract
        public NodeInfo self() {
            return null; // test stub — self() never invoked by LeaderReconciler Phase 1
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

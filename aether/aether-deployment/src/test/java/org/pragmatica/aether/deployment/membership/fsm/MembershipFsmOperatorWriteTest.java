// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.drain.DrainCoordinator;
import org.pragmatica.aether.deployment.drain.DrainCoordinator.DrainReason;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm.LifecycleSnapshotReader;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm.SlotSnapshotReader;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm.TimerScheduler;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmEvent.JoinDeadlineExpired;
import org.pragmatica.swim.SwimObservation.HealthyObserved;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmState.Stopped;

import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmState.Joining;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ProvisioningSlotKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProvisioningSlotValue;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/// E.4 protocol-feedback tests (spec §9 E.4). Exercises the write path through `MembershipFsm`
/// for `JoinDeadlineExpired` events + leader-takeover resume (F7/F8) + self-bootstrap on
/// SWIM/LeaderChange (Bootstrap-correction 2026-05-12). The operator-event channel for
/// `OperatorDrain` / `OperatorDecommission` was deleted as part of the convergence-reconciler
/// Phase 1 migration — operator lifecycle intents now flow through
/// `LifecycleWriter.applyCommand(...)` and are covered by `LifecycleWriterTest`.
class MembershipFsmOperatorWriteTest {
    private static final NodeId SELF = NodeId.nodeId("self-node").unwrap();

    private static final NodeId PEER_A = NodeId.nodeId("peer-a").unwrap();

    private static final long T0 = 1_000L;

    private static HlcTimestamp hlc(long millis) {
        return new HlcTimestamp(HlcTimestamp.pack(millis * 1000L, 0), "test");
    }

    private FakeLifecycleSnapshot lifecycleSnapshot;

    private FakeSlotSnapshot slotSnapshot;

    private RecordingCommandApplier commandApplier;

    private RecordingDrainCoordinator drainCoordinator;

    private RecordingScheduler scheduler;

    private AtomicBoolean leaderFlag;

    @BeforeEach
    void setUp() {
        lifecycleSnapshot = new FakeLifecycleSnapshot();
        slotSnapshot = new FakeSlotSnapshot();
        commandApplier = new RecordingCommandApplier();
        drainCoordinator = new RecordingDrainCoordinator();
        scheduler = new RecordingScheduler();
        leaderFlag = new AtomicBoolean(true);
    }

    private MembershipFsm buildFsm() {
        var config = MembershipFsmConfig.defaultMembershipFsmConfig();
        BooleanSupplier isLeader = leaderFlag::get;
        return MembershipFsm.membershipFsm(SELF,
                                            config,
                                            lifecycleSnapshot,
                                            slotSnapshot,
                                            commandApplier,
                                            drainCoordinator,
                                            scheduler,
                                            isLeader);
    }

    private MembershipFsm startedFsm() {
        var fsm = buildFsm();
        fsm.start().await();
        return fsm;
    }

    @Nested @DisplayName("Non-leader gates")
    class GatingTests {
        @Test void joinDeadlineExpired_nonLeader_noWrite_logsWarning() {
            seedJoining(PEER_A, T0);
            leaderFlag.set(false);
            var fsm = startedFsm();
            var priorState = fsm.get(PEER_A).unwrap();
            fsm.enqueueOperatorEvent(new JoinDeadlineExpired(PEER_A, hlc(T0 + 60_000L)));
            assertThat(commandApplier.calls).isEmpty();
            // Local state unchanged: I1 says state derives from KV; without a write, no mutation.
            assertThat(fsm.get(PEER_A).unwrap()).isEqualTo(priorState);
        }
    }

    @Nested @DisplayName("F2: JOIN_DEADLINE timer fire → DECOMMISSIONED write")
    class JoinDeadlineTimerTests {
        @Test void joinDeadlineExpired_onLeader_writesDecommissioned() {
            // Use a recent timestamp so leader-takeover (F8) does NOT fire JoinDeadlineExpired
            // immediately. We then drive the timer-fire path manually.
            var joinedAt = System.currentTimeMillis() - 1_000L;
            seedJoining(PEER_A, joinedAt);
            var fsm = startedFsm();
            assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(Joining.class);
            // F2: the timer's runnable enqueues JoinDeadlineExpired. We invoke the same path
            // directly to validate leader-writing classification + reducer wiring.
            commandApplier.calls.clear();
            fsm.enqueueOperatorEvent(new JoinDeadlineExpired(PEER_A, hlc(System.currentTimeMillis())));
            assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(Stopped.class);
            assertThat(commandApplier.calls).hasSize(1);
            assertSingleLifecyclePut(commandApplier.calls.get(0), PEER_A, NodeLifecycleState.STOPPED);
        }

        @Test void joinDeadlineExpired_onNonLeader_isNoOp() {
            seedJoining(PEER_A, T0);
            leaderFlag.set(false);
            var fsm = startedFsm();
            commandApplier.calls.clear();
            fsm.enqueueOperatorEvent(new JoinDeadlineExpired(PEER_A, hlc(T0 + 60_000L)));
            assertThat(commandApplier.calls).isEmpty();
        }
    }

    @Nested @DisplayName("Self-bootstrap on NodeLifecycle ACTIVE (Bootstrap-correction 2026-05-12)")
    class SelfBootstrapTests {
        @Test void nodeLifecycleActive_enqueuesSwimHealthySelf_writesOwnOnDuty() {
            var fsm = startedFsm();
            fsm.onSwimObservation(new HealthyObserved(SELF, 0L));
            assertThat(commandApplier.calls).hasSize(1);
            assertSingleLifecyclePut(commandApplier.calls.get(0), SELF, NodeLifecycleState.ON_DUTY);
        }

        @Test void nodeLifecycleActive_onFollower_isDroppedBySingleWriterGate() {
            // On followers the SWIM leader-gate drops the synthetic observation. The leader
            // writes the follower's own ON_DUTY entry (just like for any peer).
            leaderFlag.set(false);
            var fsm = startedFsm();
            fsm.onSwimObservation(new HealthyObserved(SELF, 0L));
            assertThat(commandApplier.calls).isEmpty();
        }
    }

    @Nested @DisplayName("Self-bootstrap on LeaderChange (Bootstrap-correction 2026-05-12 retry trigger)")
    class LeaderChangeBootstrapTests {
        @Test void onLeaderChange_becomesLeader_writesOwnOnDuty() {
            var fsm = startedFsm();
            fsm.onLeaderChange(new LeaderChange(Option.some(SELF), true));
            assertThat(commandApplier.calls).hasSize(1);
            assertSingleLifecyclePut(commandApplier.calls.get(0), SELF, NodeLifecycleState.ON_DUTY);
        }

        @Test void onLeaderChange_becomesLeader_idempotent() {
            var fsm = startedFsm();
            fsm.onSwimObservation(new HealthyObserved(SELF, 0L));
            assertThat(commandApplier.calls).hasSize(1);
            fsm.onLeaderChange(new LeaderChange(Option.some(SELF), true));
            assertThat(commandApplier.calls).hasSize(1);
        }

        @Test void onLeaderChange_followerToFollower_doesNotEnqueue() {
            // LeaderChange with localNodeIsLeader=false (e.g., leader handoff between two
            // peers while this node remains a follower) MUST NOT enqueue a synthetic
            // SwimHealthy(self) — the new leader is responsible for writing this node's
            // ON_DUTY entry. Non-leader trigger gate is enforced inside onLeaderChange itself
            // (not via the SWIM leader-write gate), so we set leaderFlag=true to prove the
            // gate is independent.
            var fsm = startedFsm();
            fsm.onLeaderChange(new LeaderChange(Option.some(PEER_A), false));
            assertThat(commandApplier.calls).isEmpty();
            assertThat(fsm.get(SELF).isEmpty()).isTrue();
        }
    }

    @Nested @DisplayName("F7+F8: leader-takeover resumes in-flight protocols")
    class LeaderTakeoverResumeTests {
        @Test void start_findsDrainingPeer_andRemainingTimeoutPositive_reattachesAwaitDrainAck() {
            // Seed at "now - 1s"; with default 60s drain timeout, remaining ~59s.
            var drainStartedAt = System.currentTimeMillis() - 1_000L;
            seedDraining(PEER_A, drainStartedAt);
            startedFsm();
            assertThat(drainCoordinator.awaitCalls).hasSize(1);
            assertThat(drainCoordinator.awaitCalls.get(0).peer()).isEqualTo(PEER_A);
            // Timeout should be positive (we don't pin it exactly because of test-time drift).
            assertThat(drainCoordinator.awaitCalls.get(0).timeout().millis()).isPositive();
        }

        @Test void start_findsDrainingPeer_andDeadlineElapsed_enqueuesDrainOutcomeFailureImmediately() {
            // Drain started 2 hours ago, well past default 60s timeout.
            var drainStartedAt = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(2);
            seedDraining(PEER_A, drainStartedAt);
            var fsm = startedFsm();
            assertThat(drainCoordinator.awaitCalls).isEmpty();
            // DrainOutcome(false) → FAILED_DRAIN transition + KV write.
            assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(Stopped.class);
        }

        @Test void start_findsDrainingPeer_onFollower_doesNotResume() {
            seedDraining(PEER_A, System.currentTimeMillis() - 1_000L);
            leaderFlag.set(false);
            startedFsm();
            // Follower MUST NOT take over running protocols (single-writer).
            assertThat(drainCoordinator.awaitCalls).isEmpty();
            assertThat(commandApplier.calls).isEmpty();
        }

        @Test void start_findsJoiningPeer_andRemainingDeadlinePositive_schedulesTimer() {
            var joinedAt = System.currentTimeMillis() - 1_000L;
            seedJoining(PEER_A, joinedAt);
            startedFsm();
            assertThat(scheduler.delays).isNotEmpty();
            assertThat(scheduler.delays.get(0).millis()).isPositive();
        }

        @Test void start_findsJoiningPeer_andDeadlineElapsed_enqueuesJoinDeadlineExpiredImmediately() {
            var joinedAt = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(2);
            seedJoining(PEER_A, joinedAt);
            var fsm = startedFsm();
            // Immediate JoinDeadlineExpired → DECOMMISSIONED on the leader.
            assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(Stopped.class);
        }

        @Test void start_findsJoiningPeer_onFollower_doesNotSchedule() {
            seedJoining(PEER_A, System.currentTimeMillis() - 1_000L);
            leaderFlag.set(false);
            startedFsm();
            assertThat(scheduler.delays).isEmpty();
        }
    }

    @Nested @DisplayName("F18: KV write preserves host/port/observedCoreEpoch from prior value")
    class FieldPreservationTests {
        @Test void joinDeadlineExpired_proposedWritePreservesHostPortEpoch() {
            // Use a recent joinedAt so leader-takeover (F8) does NOT fire JoinDeadlineExpired
            // during start() — otherwise the peer transitions to STOPPED before we can
            // exercise the deadline-fire write path explicitly.
            var joinedAt = System.currentTimeMillis() - 1_000L;
            var priorValue = NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.JOINING,
                                                                    joinedAt,
                                                                    "10.1.2.3",
                                                                    9001,
                                                                    org.pragmatica.aether.slice.generation.Epoch.epoch(7L, 0L),
                                                                    HlcTimestamp.ZERO);
            lifecycleSnapshot.put(PEER_A, priorValue);
            var fsm = startedFsm();
            commandApplier.calls.clear();
            fsm.enqueueOperatorEvent(new JoinDeadlineExpired(PEER_A, hlc(System.currentTimeMillis() + 60_000L)));
            assertThat(commandApplier.calls).hasSize(1);
            var batch = commandApplier.calls.get(0);
            var lifecyclePut = batch.stream()
                                    .filter(c -> c instanceof Put<?, ?> p && p.value() instanceof NodeLifecycleValue)
                                    .map(c -> (Put<?, ?>) c)
                                    .findFirst()
                                    .orElseThrow();
            var write = (NodeLifecycleValue) lifecyclePut.value();
            assertThat(write.state()).isEqualTo(NodeLifecycleState.STOPPED);
            assertThat(write.host()).isEqualTo("10.1.2.3");
            assertThat(write.port()).isEqualTo(9001);
            assertThat(write.observedCoreEpoch()).isEqualTo(org.pragmatica.aether.slice.generation.Epoch.epoch(7L, 0L));
        }
    }

    private void seedDraining(NodeId peer, long updatedAt) {
        lifecycleSnapshot.put(peer, NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.DRAINING, updatedAt));
    }

    private void seedJoining(NodeId peer, long updatedAt) {
        lifecycleSnapshot.put(peer, NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.JOINING, updatedAt));
    }

    private static void assertSingleLifecyclePut(List<KVCommand<AetherKey>> commands,
                                                  NodeId expectedPeer,
                                                  NodeLifecycleState expectedState) {
        assertThat(commands).isNotEmpty();
        var lifecyclePuts = commands.stream()
                                    .filter(c -> c instanceof Put<?, ?> p && p.value() instanceof NodeLifecycleValue)
                                    .toList();
        assertThat(lifecyclePuts).as("expected exactly one NodeLifecycleValue Put").hasSize(1);
        var put = (Put<?, ?>) lifecyclePuts.get(0);
        assertThat(put.key()).isInstanceOf(NodeLifecycleKey.class);
        var key = (NodeLifecycleKey) put.key();
        assertThat(key.nodeId()).isEqualTo(expectedPeer);
        var value = (NodeLifecycleValue) put.value();
        assertThat(value.state()).isEqualTo(expectedState);
    }

    private static final class FakeLifecycleSnapshot implements LifecycleSnapshotReader {
        private final Map<NodeLifecycleKey, NodeLifecycleValue> entries = new LinkedHashMap<>();

        void put(NodeId peer, NodeLifecycleValue value) {
            entries.put(NodeLifecycleKey.nodeLifecycleKey(peer), value);
        }

        @Override public void forEachLifecycle(BiConsumer<NodeLifecycleKey, NodeLifecycleValue> consumer) {
            entries.forEach(consumer);
        }
    }

    private static final class FakeSlotSnapshot implements SlotSnapshotReader {
        private final Map<ProvisioningSlotKey, ProvisioningSlotValue> entries = new LinkedHashMap<>();

        @Override public void forEachSlot(BiConsumer<ProvisioningSlotKey, ProvisioningSlotValue> consumer) {
            entries.forEach(consumer);
        }
    }

    /// Hand-rolled `commandApplier` fake. Records every batch passed to it.
    private static final class RecordingCommandApplier
            implements Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> {
        private final List<List<KVCommand<AetherKey>>> calls = new ArrayList<>();

        @Override public Promise<List<Object>> apply(List<KVCommand<AetherKey>> commands) {
            calls.add(List.copyOf(commands));
            return Promise.success(List.of());
        }
    }

    private record PrepareDrainCall(NodeId peer, DrainReason reason) {}

    /// Hand-rolled `DrainCoordinator` fake. Records `prepareDrain` / `awaitDrainAck`
    /// invocations to validate that leader-takeover (F7+F8) reattaches the drain protocol
    /// from KV. `awaitDrainAck` returns an unresolved Promise — tests only check that the
    /// invocation occurred, not the terminal outcome.
    private static final class RecordingDrainCoordinator implements DrainCoordinator {
        private final List<PrepareDrainCall> prepareCalls = new ArrayList<>();

        private final List<AwaitDrainCall> awaitCalls = new ArrayList<>();

        @Override public Promise<Unit> prepareDrain(NodeId nodeId, DrainReason reason) {
            prepareCalls.add(new PrepareDrainCall(nodeId, reason));
            return Promise.unitPromise();
        }

        @Override public Promise<Unit> awaitDrainAck(NodeId nodeId, TimeSpan timeout) {
            awaitCalls.add(new AwaitDrainCall(nodeId, timeout));
            return Promise.promise();
        }

        @Override public void markDrainComplete(NodeId nodeId) {
            // No-op for tests.
        }
    }

    private record AwaitDrainCall(NodeId peer, TimeSpan timeout) {}

    /// Hand-rolled `TimerScheduler` fake. Records the scheduled `(runnable, delay)` pairs
    /// but never fires them (E.4 operator transitions don't emit `ScheduleTimer` for the
    /// reducer's operator-event rows — the timer machinery is exercised in E.6).
    private static final class RecordingScheduler implements TimerScheduler {
        private final List<TimeSpan> delays = new ArrayList<>();

        @Override public ScheduledFuture<?> schedule(Runnable runnable, TimeSpan delay) {
            delays.add(delay);
            return NO_OP_FUTURE;
        }

        private static final ScheduledFuture<?> NO_OP_FUTURE = new ScheduledFuture<>() {
            @Override public long getDelay(TimeUnit unit) {return 0L;}

            @Override public int compareTo(java.util.concurrent.Delayed o) {return 0;}

            @Override public boolean cancel(boolean mayInterruptIfRunning) {return true;}

            @Override public boolean isCancelled() {return false;}

            @Override public boolean isDone() {return false;}

            @Override public Object get() {return null;}

            @Override public Object get(long timeout, TimeUnit unit) {return null;}
        };
    }
}

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
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmEvent.DrainOutcome;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmEvent.JoinDeadlineExpired;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmEvent.OperatorDecommission;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmEvent.OperatorDrain;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmState.Decommissioned;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmState.Draining;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmState.FailedDrain;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmState.Joining;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmState.OnDuty;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ProvisioningSlotKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProvisioningSlotValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

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

/// E.4 operator-write tests (spec §9 E.4). Exercises the write path through `MembershipFsm`
/// for `OperatorDrain` / `OperatorDecommission` events: leader gating, consensus apply,
/// drain coordinator invocation, and the I1 invariant on consensus failure.
class MembershipFsmOperatorWriteTest {
    private static final NodeId SELF = NodeId.nodeId("self-node").unwrap();

    private static final NodeId PEER_A = NodeId.nodeId("peer-a").unwrap();

    private static final long T0 = 1_000L;

    private static final long T1 = 2_000L;

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

    @Nested @DisplayName("Operator drain — leader writes DRAINING + invokes coordinator")
    class LeaderWritesTests {
        @Test void operatorDrain_leader_proposesDrainingKvWrite() {
            seedOnDuty(PEER_A, T0);
            var fsm = startedFsm();
            assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(OnDuty.class);
            fsm.enqueueOperatorEvent(new OperatorDrain(PEER_A, DrainReason.OPERATOR_DRAIN, T1));
            // Drain ack stays pending → only the DRAINING write reaches consensus.
            assertThat(commandApplier.calls).hasSize(1);
            assertSingleLifecyclePut(commandApplier.calls.get(0), PEER_A, NodeLifecycleState.DRAINING);
            assertThat(drainCoordinator.prepareCalls).containsExactly(new PrepareDrainCall(PEER_A,
                                                                                            DrainReason.OPERATOR_DRAIN));
            assertThat(drainCoordinator.awaitCalls).hasSize(1);
            assertThat(drainCoordinator.awaitCalls.get(0).peer()).isEqualTo(PEER_A);
            assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(Draining.class);
        }

        @Test void operatorDecommission_force_leader_proposesDecommissionedKvWrite() {
            seedOnDuty(PEER_A, T0);
            var fsm = startedFsm();
            fsm.enqueueOperatorEvent(new OperatorDecommission(PEER_A, true, T1));
            assertThat(commandApplier.calls).hasSize(1);
            assertSingleLifecyclePut(commandApplier.calls.get(0), PEER_A, NodeLifecycleState.DECOMMISSIONED);
            assertThat(drainCoordinator.prepareCalls).isEmpty();
            assertThat(drainCoordinator.awaitCalls).isEmpty();
            assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(Decommissioned.class);
        }

        @Test void operatorDecommission_graceful_leader_proposesDrainingKvWrite_andInvokesDrainCoordinator() {
            // OperatorDecommission(force=false) from ON_DUTY → enterDraining: writes DRAINING +
            // invokes drain coordinator (spec §5 row OnDuty col OperatorDecommission(force=false)).
            seedOnDuty(PEER_A, T0);
            var fsm = startedFsm();
            fsm.enqueueOperatorEvent(new OperatorDecommission(PEER_A, false, T1));
            assertThat(commandApplier.calls).hasSize(1);
            assertSingleLifecyclePut(commandApplier.calls.get(0), PEER_A, NodeLifecycleState.DRAINING);
            assertThat(drainCoordinator.prepareCalls).containsExactly(new PrepareDrainCall(PEER_A,
                                                                                            DrainReason.OPERATOR_DRAIN));
            assertThat(drainCoordinator.awaitCalls).hasSize(1);
            assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(Draining.class);
        }
    }

    @Nested @DisplayName("Non-leader gates")
    class GatingTests {
        @Test void operatorEvent_nonLeader_noWrite_logsWarning() {
            seedOnDuty(PEER_A, T0);
            leaderFlag.set(false);
            var fsm = startedFsm();
            var priorState = fsm.get(PEER_A).unwrap();
            fsm.enqueueOperatorEvent(new OperatorDrain(PEER_A, DrainReason.OPERATOR_DRAIN, T1));
            assertThat(commandApplier.calls).isEmpty();
            assertThat(drainCoordinator.prepareCalls).isEmpty();
            // Local state unchanged: I1 says state derives from KV; without a write, no mutation.
            assertThat(fsm.get(PEER_A).unwrap()).isEqualTo(priorState);
        }

    }

    @Nested @DisplayName("Consensus failure preserves I1 (state derives from KV)")
    class ConsensusFailureTests {
        @Test void operatorEvent_consensusApplierFails_doesNotMutateLocalState() {
            seedOnDuty(PEER_A, T0);
            var fsm = startedFsm();
            commandApplier.rejectWith(Causes.cause("consensus rejected"));
            var priorState = fsm.get(PEER_A).unwrap();
            fsm.enqueueOperatorEvent(new OperatorDrain(PEER_A, DrainReason.OPERATOR_DRAIN, T1));
            // Write attempted but rejected — local state must remain prior state.
            assertThat(commandApplier.calls).hasSize(1);
            assertThat(fsm.get(PEER_A).unwrap()).isEqualTo(priorState);
        }
    }

    @Nested @DisplayName("Drain outcome feedback transitions (F4 end-to-end)")
    class DrainOutcomeTests {
        @Test void operatorDrain_then_awaitDrainAckSuccess_writesDecommissioned() {
            seedOnDuty(PEER_A, T0);
            var fsm = startedFsm();
            fsm.enqueueOperatorEvent(new OperatorDrain(PEER_A, DrainReason.OPERATOR_DRAIN, T1));
            awaitState(fsm, PEER_A, Draining.class);
            assertThat(commandApplier.calls).hasSize(1);
            // F4: completing the awaitDrainAck Promise drives DrainOutcome(true) →
            // DECOMMISSIONED transition + second consensus write. Promise listeners run on
            // the Pragmatica async executor (virtual thread) — poll for the transition.
            drainCoordinator.completeAckAt(0);
            awaitState(fsm, PEER_A, Decommissioned.class);
            assertThat(commandApplier.calls).hasSize(2);
            assertSingleLifecyclePut(commandApplier.calls.get(1), PEER_A, NodeLifecycleState.DECOMMISSIONED);
        }

        @Test void operatorDrain_then_awaitDrainAckFailure_writesFailedDrain() {
            seedOnDuty(PEER_A, T0);
            var fsm = startedFsm();
            fsm.enqueueOperatorEvent(new OperatorDrain(PEER_A, DrainReason.OPERATOR_DRAIN, T1));
            awaitState(fsm, PEER_A, Draining.class);
            drainCoordinator.failAckAt(0, Causes.cause("drain-hard-deadline"));
            awaitState(fsm, PEER_A, FailedDrain.class);
            assertThat(commandApplier.calls).hasSize(2);
            assertSingleLifecyclePut(commandApplier.calls.get(1), PEER_A, NodeLifecycleState.FAILED_DRAIN);
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
            fsm.enqueueOperatorEvent(new JoinDeadlineExpired(PEER_A, System.currentTimeMillis()));
            assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(Decommissioned.class);
            assertThat(commandApplier.calls).hasSize(1);
            assertSingleLifecyclePut(commandApplier.calls.get(0), PEER_A, NodeLifecycleState.DECOMMISSIONED);
        }

        @Test void joinDeadlineExpired_onNonLeader_isNoOp() {
            seedJoining(PEER_A, T0);
            leaderFlag.set(false);
            var fsm = startedFsm();
            commandApplier.calls.clear();
            fsm.enqueueOperatorEvent(new JoinDeadlineExpired(PEER_A, T0 + 60_000L));
            assertThat(commandApplier.calls).isEmpty();
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
            assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(FailedDrain.class);
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
            assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(Decommissioned.class);
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
        @Test void operatorDrain_proposedWritePreservesHostPortEpoch() {
            var priorValue = NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY,
                                                                    T0,
                                                                    "10.1.2.3",
                                                                    9001,
                                                                    Epoch.epoch(7L, 0L),
                                                                    HlcTimestamp.ZERO);
            lifecycleSnapshot.put(PEER_A, priorValue);
            var fsm = startedFsm();
            fsm.enqueueOperatorEvent(new OperatorDrain(PEER_A, DrainReason.OPERATOR_DRAIN, T1));
            assertThat(commandApplier.calls).hasSize(1);
            var write = (NodeLifecycleValue) ((Put<?, ?>) commandApplier.calls.get(0).get(0)).value();
            assertThat(write.state()).isEqualTo(NodeLifecycleState.DRAINING);
            assertThat(write.host()).isEqualTo("10.1.2.3");
            assertThat(write.port()).isEqualTo(9001);
            assertThat(write.observedCoreEpoch()).isEqualTo(Epoch.epoch(7L, 0L));
        }

        @Test void operatorDecommissionForce_proposedWritePreservesHostPortEpoch() {
            var priorValue = NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY,
                                                                    T0,
                                                                    "10.4.5.6",
                                                                    9007,
                                                                    Epoch.epoch(11L, 0L),
                                                                    HlcTimestamp.ZERO);
            lifecycleSnapshot.put(PEER_A, priorValue);
            var fsm = startedFsm();
            fsm.enqueueOperatorEvent(new OperatorDecommission(PEER_A, true, T1));
            var write = (NodeLifecycleValue) ((Put<?, ?>) commandApplier.calls.get(0).get(0)).value();
            assertThat(write.state()).isEqualTo(NodeLifecycleState.DECOMMISSIONED);
            assertThat(write.host()).isEqualTo("10.4.5.6");
            assertThat(write.port()).isEqualTo(9007);
            assertThat(write.observedCoreEpoch()).isEqualTo(Epoch.epoch(11L, 0L));
        }
    }

    private void seedOnDuty(NodeId peer, long updatedAt) {
        lifecycleSnapshot.put(peer, NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY, updatedAt));
    }

    private void seedDraining(NodeId peer, long updatedAt) {
        lifecycleSnapshot.put(peer, NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.DRAINING, updatedAt));
    }

    private void seedJoining(NodeId peer, long updatedAt) {
        lifecycleSnapshot.put(peer, NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.JOINING, updatedAt));
    }

    /// Poll-based wait for an FSM state transition. Promise listeners run on a virtual-thread
    /// async executor when the Promise resolves with more than one callback attached; the
    /// test thread therefore can race ahead of the FSM. Five-second cap avoids hanging.
    private static void awaitState(MembershipFsm fsm,
                                    NodeId peer,
                                    Class<? extends MembershipFsmState> expected) {
        var deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            var current = fsm.get(peer);
            if (current.isPresent() && expected.isInstance(current.unwrap())) {
                return;
            }
            try {Thread.sleep(5L);} catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(fsm.get(peer).unwrap()).isInstanceOf(expected);
    }

    private static void assertSingleLifecyclePut(List<KVCommand<AetherKey>> commands,
                                                  NodeId expectedPeer,
                                                  NodeLifecycleState expectedState) {
        assertThat(commands).hasSize(1);
        assertThat(commands.get(0)).isInstanceOf(Put.class);
        var put = (Put<?, ?>) commands.get(0);
        assertThat(put.key()).isInstanceOf(NodeLifecycleKey.class);
        var key = (NodeLifecycleKey) put.key();
        assertThat(key.nodeId()).isEqualTo(expectedPeer);
        assertThat(put.value()).isInstanceOf(NodeLifecycleValue.class);
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

    /// Hand-rolled `commandApplier` fake. Records every batch passed to it; can be flipped
    /// into a rejecting mode to exercise the I1 invariant (local state must NOT mutate on
    /// consensus failure).
    private static final class RecordingCommandApplier
            implements Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> {
        private final List<List<KVCommand<AetherKey>>> calls = new ArrayList<>();

        private Option<Cause> failure = Option.none();

        void rejectWith(Cause cause) {
            failure = Option.some(cause);
        }

        @Override public Promise<List<Object>> apply(List<KVCommand<AetherKey>> commands) {
            calls.add(List.copyOf(commands));
            return failure.fold(() -> Promise.success(List.<Object>of()), Cause::promise);
        }
    }

    private record PrepareDrainCall(NodeId peer, DrainReason reason) {}

    /// Hand-rolled `DrainCoordinator` fake. Records `prepareDrain` invocations from the
    /// `InvokeDrain` effect. After F4, the FSM chains `awaitDrainAck` whose Promise the
    /// test resolves manually (success or failure) to drive the `DrainOutcome` feedback.
    /// Default mode = pending: tests must `completeAck(...)` / `failAck(...)` explicitly.
    private static final class RecordingDrainCoordinator implements DrainCoordinator {
        private final List<PrepareDrainCall> prepareCalls = new ArrayList<>();

        private final List<AwaitDrainCall> awaitCalls = new ArrayList<>();

        private final List<Promise<Unit>> pendingAcks = new ArrayList<>();

        @Override public Promise<Unit> prepareDrain(NodeId nodeId, DrainReason reason) {
            prepareCalls.add(new PrepareDrainCall(nodeId, reason));
            return Promise.unitPromise();
        }

        @Override public Promise<Unit> awaitDrainAck(NodeId nodeId, TimeSpan timeout) {
            awaitCalls.add(new AwaitDrainCall(nodeId, timeout));
            var ack = Promise.<Unit>promise();
            pendingAcks.add(ack);
            return ack;
        }

        @Override public void markDrainComplete(NodeId nodeId) {
            // No-op for tests.
        }

        void completeAckAt(int index) {
            pendingAcks.get(index).succeed(Unit.unit());
        }

        void failAckAt(int index, Cause cause) {
            pendingAcks.get(index).fail(cause);
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

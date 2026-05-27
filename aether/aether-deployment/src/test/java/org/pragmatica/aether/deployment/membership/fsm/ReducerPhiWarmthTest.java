// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.drain.DrainCoordinator;
import org.pragmatica.aether.deployment.drain.DrainCoordinator.DrainReason;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm.LifecycleSnapshotReader;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm.SlotSnapshotReader;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm.TimerScheduler;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmEvent.SwimFaulty;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmEvent.TransportUnreachable;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmState.Stopped;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmState.OnDuty;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ProvisioningSlotKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProvisioningSlotValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StopReason;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
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
import java.util.function.BiConsumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;


/// #231 leader-side φ-accrual handoff — the `PhiWarmth` predicate at the `(ON_DUTY, SwimFaulty)`
/// cell (replaces the former aggregator-quorum `ReachabilityGate`).
///
/// The handoff splits liveness ownership per-peer on φ-warmth: φ WARM → φ owns (a still-ponging
/// peer survives a SWIM false-positive → nop); φ COLD → SWIM owns (decommission, matching the
/// pre-handoff permissive behavior). `(ON_DUTY, TransportUnreachable)` is UNGATED — a closed QUIC
/// channel is definitive regardless of warmth. `(JOINING, TransportUnreachable)` stays UNGATED.
class ReducerPhiWarmthTest {
    private static final NodeId PEER = NodeId.nodeId("peer-1").unwrap();

    private static final NodeId SELF = NodeId.nodeId("self-node").unwrap();

    private static final HlcTimestamp T0 = at(1_000L);

    private static final HlcTimestamp T1 = at(2_000L);

    private static HlcTimestamp at(long millis) {
        return new HlcTimestamp(HlcTimestamp.pack(millis * 1000L, 0), new NodeId("test"));
    }

    private static long ms(HlcTimestamp at) {
        return at.physicalMicros() / 1000L;
    }

    private static ClusterMembershipReducer reducer() {
        return ClusterMembershipReducer.clusterMembershipReducer(MembershipFsmConfig.defaultMembershipFsmConfig());
    }

    @Nested @DisplayName("(OnDuty, SwimFaulty) gated by φ-warmth")
    class OnDutySwimFaulty {
        @Test void phiCold_swimFaultyDecommissions_singleWrite() {
            // φ COLD → SWIM owns liveness → decommission fires, exactly one Put(STOPPED). Matches
            // the pre-handoff ALWAYS_CONFIRMED + SwimFaulty → STOPPED behavior.
            var state = MembershipFsmState.onDuty(PEER, ms(T0));

            var outcome = reducer().apply(state, new SwimFaulty(PEER, 1L, T1), PhiWarmth.COLD);

            assertThat(outcome.newState()).isInstanceOf(Stopped.class);
            assertThat(outcome.newState()).isEqualTo(MembershipFsmState.stopped(PEER, ms(T1), StopReason.FORCED, true));
            assertThat(outcome.writes()).hasSize(1);
            assertThat(outcome.writes().get(0)).isInstanceOf(Put.class);
            assertLifecyclePut(outcome.writes().get(0), NodeLifecycleState.STOPPED);
        }

        @Test void phiWarm_swimFaultyIsNop_zeroWrites() {
            // φ WARM → φ owns liveness → a SwimFaulty while φ still hears the peer's pongs is a SWIM
            // false-positive → nop (the peer is ponging, so it is alive).
            var state = MembershipFsmState.onDuty(PEER, ms(T0));

            var outcome = reducer().apply(state, new SwimFaulty(PEER, 1L, T1), PhiWarmth.WARM);

            assertThat(outcome.newState()).isEqualTo(state);
            assertThat(outcome.writes()).isEmpty();
            assertThat(outcome.effects()).isEmpty();
        }
    }

    @Nested @DisplayName("(OnDuty, TransportUnreachable) is UNGATED")
    class OnDutyTransportUnreachable {
        @Test void phiCold_transportUnreachableDecommissions_singleWrite() {
            // A closed QUIC channel is definitive — fires regardless of φ-warmth (φ COLD here).
            var state = MembershipFsmState.onDuty(PEER, ms(T0));

            var outcome = reducer().apply(state, new TransportUnreachable(PEER, T1), PhiWarmth.COLD);

            assertThat(outcome.newState()).isInstanceOf(Stopped.class);
            // swimDriven=false because transport-failure is NOT a SWIM reason.
            assertThat(outcome.newState()).isEqualTo(MembershipFsmState.stopped(PEER, ms(T1), StopReason.FORCED, false));
            assertThat(outcome.writes()).hasSize(1);
            assertLifecyclePut(outcome.writes().get(0), NodeLifecycleState.STOPPED);
        }

        @Test void phiWarm_transportUnreachableStillDecommissions_singleWrite() {
            // Even with φ WARM the transport cell fires — warmth gates only the SwimFaulty cell,
            // never the definitive closed-channel signal.
            var state = MembershipFsmState.onDuty(PEER, ms(T0));

            var outcome = reducer().apply(state, new TransportUnreachable(PEER, T1), PhiWarmth.WARM);

            assertThat(outcome.newState()).isInstanceOf(Stopped.class);
            assertThat(outcome.newState()).isEqualTo(MembershipFsmState.stopped(PEER, ms(T1), StopReason.FORCED, false));
            assertThat(outcome.writes()).hasSize(1);
            assertLifecyclePut(outcome.writes().get(0), NodeLifecycleState.STOPPED);
        }
    }

    @Nested @DisplayName("Scope: JOINING TransportUnreachable stays UNGATED")
    class JoiningTransportUnreachableUngated {
        @Test void joining_transportUnreachable_ignoresWarmth_decommissions() {
            // Even with φ WARM, the JOINING cell fires STOPPED. JOINING has no SWIM-HEALTHY
            // history; transport is its primary detection signal (the original bug; spec §16 S01).
            var state = MembershipFsmState.joining(PEER, ms(T0), Option.some("slot-1"));

            var outcome = reducer().apply(state, new TransportUnreachable(PEER, T1), PhiWarmth.WARM);

            assertThat(outcome.newState()).isInstanceOf(Stopped.class);
            // Durable slots (#230, spec §3.1): a JOINING node stopping writes the lifecycle STOPPED
            // atom + removes the join-deadline (2). The slot atom is NOT deleted — it persists so
            // CTM `classifyOccupancy` → DEAD → `freeSlot` clears the occupant in place and refills.
            assertThat(outcome.writes()).hasSize(2);
            assertLifecyclePut(outcome.writes().get(0), NodeLifecycleState.STOPPED);
            assertThat(outcome.writes().get(1)).isInstanceOf(KVCommand.Remove.class);
        }
    }

    @Nested @DisplayName("End-to-end φ-warmth via MembershipFsm")
    class EndToEnd {
        @Test void phiCold_swimFaultyOnOnDuty_writesStopped() {
            // The FSM is constructed with PhiWarmth.COLD → SWIM owns → the (ON_DUTY, SwimFaulty)
            // cell decommissions, matching the cold-start / never-warmed behavior (new leader,
            // detector has not yet warmed any peer).
            var lifecycleSnapshot = new FakeLifecycleSnapshot();
            lifecycleSnapshot.put(PEER, NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY, ms(T0)));
            var slotSnapshot = new FakeSlotSnapshot();
            var commandApplier = new RecordingCommandApplier();
            var fsm = MembershipFsm.membershipFsm(SELF,
                                                   MembershipFsmConfig.defaultMembershipFsmConfig(),
                                                   lifecycleSnapshot,
                                                   slotSnapshot,
                                                   commandApplier,
                                                   new NoOpDrainCoordinator(),
                                                   new NoOpScheduler(),
                                                   () -> true,
                                                   org.pragmatica.hlc.HlcClock.hlcClock(SELF),
                                                   PhiWarmth.COLD);
            fsm.start().await();
            assertThat(fsm.get(PEER).unwrap()).isInstanceOf(OnDuty.class);

            fsm.onSwimObservation(new org.pragmatica.swim.SwimObservation.FaultyObserved(PEER, 1L));

            assertThat(commandApplier.calls).hasSize(1);
            assertLifecyclePut(commandApplier.calls.get(0).get(0), NodeLifecycleState.STOPPED);
            assertThat(fsm.get(PEER).unwrap()).isInstanceOf(Stopped.class);
        }

        @Test void phiWarm_swimFaultyOnOnDuty_isNop() {
            // The FSM is constructed with PhiWarmth.WARM → φ owns → the (ON_DUTY, SwimFaulty) cell
            // is a nop (the still-ponging peer survives the SWIM false-positive). Confirms the
            // warm branch is wired end-to-end through the FSM, not just at the reducer.
            var lifecycleSnapshot = new FakeLifecycleSnapshot();
            lifecycleSnapshot.put(PEER, NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY, ms(T0)));
            var slotSnapshot = new FakeSlotSnapshot();
            var commandApplier = new RecordingCommandApplier();
            var fsm = MembershipFsm.membershipFsm(SELF,
                                                   MembershipFsmConfig.defaultMembershipFsmConfig(),
                                                   lifecycleSnapshot,
                                                   slotSnapshot,
                                                   commandApplier,
                                                   new NoOpDrainCoordinator(),
                                                   new NoOpScheduler(),
                                                   () -> true,
                                                   org.pragmatica.hlc.HlcClock.hlcClock(SELF),
                                                   PhiWarmth.WARM);
            fsm.start().await();

            fsm.onSwimObservation(new org.pragmatica.swim.SwimObservation.FaultyObserved(PEER, 1L));

            assertThat(commandApplier.calls).isEmpty();
            assertThat(fsm.get(PEER).unwrap()).isInstanceOf(OnDuty.class);
        }
    }

    private static void assertLifecyclePut(KVCommand<AetherKey> command, NodeLifecycleState expected) {
        assertThat(command).isInstanceOf(Put.class);
        var put = (Put<?, ?>) command;
        assertThat(put.key()).isInstanceOf(NodeLifecycleKey.class);
        assertThat(put.value()).isInstanceOf(NodeLifecycleValue.class);
        assertThat(((NodeLifecycleValue) put.value()).state()).isEqualTo(expected);
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

    private static final class RecordingCommandApplier
            implements Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> {
        private final List<List<KVCommand<AetherKey>>> calls = new ArrayList<>();

        @Override public Promise<List<Object>> apply(List<KVCommand<AetherKey>> commands) {
            calls.add(List.copyOf(commands));
            return Promise.success(List.<Object>of());
        }
    }

    private static final class NoOpDrainCoordinator implements DrainCoordinator {
        @Override public Promise<Unit> prepareDrain(NodeId nodeId, DrainReason reason) {
            return Promise.unitPromise();
        }

        @Override public Promise<Unit> awaitDrainAck(NodeId nodeId, TimeSpan timeout) {
            return Promise.unitPromise();
        }

        @Override public void markDrainComplete(NodeId nodeId) {
            // No-op for tests.
        }
    }

    private static final class NoOpScheduler implements TimerScheduler {
        @Override public ScheduledFuture<?> schedule(Runnable runnable, TimeSpan delay) {
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

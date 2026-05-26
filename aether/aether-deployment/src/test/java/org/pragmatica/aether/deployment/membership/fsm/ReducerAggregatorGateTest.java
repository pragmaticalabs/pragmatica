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
import org.pragmatica.cluster.metrics.AggregatedReachabilitySnapshot;
import org.pragmatica.cluster.metrics.AggregatedReachabilitySnapshot.ReachabilityKind;
import org.pragmatica.cluster.metrics.AggregatedReachabilitySnapshot.ReachabilityState;
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


/// Topology-observation refactor Step 4 — aggregator-quorum gate at the two ON_DUTY
/// decommission cells (spec §16 scenarios S04 brief flap, S05 partition, S13 SWIM-only failure,
/// S17 aggregator-quorum lost).
///
/// Six cases covering the gate's truth table at the (state, event) sites that consult it,
/// plus an end-to-end cold-start fallback assertion via `MembershipFsm` with an
/// `Option.none()` supplier.
class ReducerAggregatorGateTest {
    private static final NodeId PEER = NodeId.nodeId("peer-1").unwrap();

    private static final NodeId SELF = NodeId.nodeId("self-node").unwrap();

    private static final HlcTimestamp T0 = at(1_000L);

    private static final HlcTimestamp T1 = at(2_000L);

    private static final long NOW_MS = 2_000L;

    private static HlcTimestamp at(long millis) {
        return new HlcTimestamp(HlcTimestamp.pack(millis * 1000L, 0), new NodeId("test"));
    }

    private static long ms(HlcTimestamp at) {
        return at.physicalMicros() / 1000L;
    }

    private static ClusterMembershipReducer reducer() {
        return ClusterMembershipReducer.clusterMembershipReducer(MembershipFsmConfig.defaultMembershipFsmConfig());
    }

    @Nested @DisplayName("(OnDuty, SwimFaulty) gated by aggregator quorum")
    class OnDutySwimFaulty {
        @Test void gateAllows_swimFaultyDecommissions_singleWrite() {
            // Case 1: confirmed UNREACHABLE → decommission fires, exactly one Put(DECOMMISSIONED).
            var state = MembershipFsmState.onDuty(PEER, ms(T0));
            ReachabilityGate gate = peer -> true;

            var outcome = reducer().apply(state, new SwimFaulty(PEER, 1L, T1), gate);

            assertThat(outcome.newState()).isInstanceOf(Stopped.class);
            assertThat(outcome.newState()).isEqualTo(MembershipFsmState.stopped(PEER, ms(T1), StopReason.FORCED, true));
            assertThat(outcome.writes()).hasSize(1);
            assertThat(outcome.writes().get(0)).isInstanceOf(Put.class);
            assertLifecyclePut(outcome.writes().get(0), NodeLifecycleState.STOPPED);
        }

        @Test void gateBlocks_swimFaultyIsNop_zeroWrites() {
            // Case 2: not confirmed UNREACHABLE (S04 brief flap, S13 SWIM-only failure) → nop.
            var state = MembershipFsmState.onDuty(PEER, ms(T0));
            ReachabilityGate gate = peer -> false;

            var outcome = reducer().apply(state, new SwimFaulty(PEER, 1L, T1), gate);

            assertThat(outcome.newState()).isEqualTo(state);
            assertThat(outcome.writes()).isEmpty();
            assertThat(outcome.effects()).isEmpty();
        }
    }

    @Nested @DisplayName("(OnDuty, TransportUnreachable) gated by aggregator quorum")
    class OnDutyTransportUnreachable {
        @Test void gateAllows_transportUnreachableDecommissions_singleWrite() {
            // Case 3: confirmed UNREACHABLE → decommission fires with transport-failure reason.
            var state = MembershipFsmState.onDuty(PEER, ms(T0));
            ReachabilityGate gate = peer -> true;

            var outcome = reducer().apply(state, new TransportUnreachable(PEER, T1), gate);

            assertThat(outcome.newState()).isInstanceOf(Stopped.class);
            // swimDriven=false because transport-failure is NOT a SWIM reason.
            assertThat(outcome.newState()).isEqualTo(MembershipFsmState.stopped(PEER, ms(T1), StopReason.FORCED, false));
            assertThat(outcome.writes()).hasSize(1);
            assertLifecyclePut(outcome.writes().get(0), NodeLifecycleState.STOPPED);
        }

        @Test void gateBlocks_transportUnreachableIsNop_zeroWrites() {
            // Case 4: not confirmed UNREACHABLE (S05 partition, S17 quorum lost) → nop.
            var state = MembershipFsmState.onDuty(PEER, ms(T0));
            ReachabilityGate gate = peer -> false;

            var outcome = reducer().apply(state, new TransportUnreachable(PEER, T1), gate);

            assertThat(outcome.newState()).isEqualTo(state);
            assertThat(outcome.writes()).isEmpty();
            assertThat(outcome.effects()).isEmpty();
        }
    }

    @Nested @DisplayName("Gate scope: JOINING TransportUnreachable stays UNGATED")
    class JoiningTransportUnreachableUngated {
        @Test void joining_transportUnreachable_ignoresGate_decommissions() {
            // Case 5: even with a `false` gate, the JOINING cell fires DECOMMISSIONED.
            // JOINING has no SWIM-HEALTHY history; transport is its primary detection signal
            // (the original bug we set out to fix; spec §16 scenario S01).
            var state = MembershipFsmState.joining(PEER, ms(T0), Option.some("slot-1"));
            ReachabilityGate gate = peer -> false;  // blocked, but irrelevant here

            var outcome = reducer().apply(state, new TransportUnreachable(PEER, T1), gate);

            assertThat(outcome.newState()).isInstanceOf(Stopped.class);
            // Durable slots (#230, spec §3.1): a JOINING node stopping writes the lifecycle STOPPED
            // atom + removes the join-deadline (2). The slot atom is NOT deleted — it persists so
            // CTM `classifyOccupancy` → DEAD → `freeSlot` clears the occupant in place and refills.
            assertThat(outcome.writes()).hasSize(2);
            assertLifecyclePut(outcome.writes().get(0), NodeLifecycleState.STOPPED);
            assertThat(outcome.writes().get(1)).isInstanceOf(KVCommand.Remove.class);
        }
    }

    @Nested @DisplayName("End-to-end cold-start fallback via MembershipFsm")
    class ColdStartFallback {
        @Test void noSnapshot_swimFaultyOnOnDuty_writesDecommissioned() {
            // Case 6: the supplier returns Option.none() → the FSM falls back to the permissive
            // ALWAYS_CONFIRMED gate → the (ON_DUTY, SwimFaulty) cell decommissions as in the
            // pre-Step-4 reducer. This is the cold-start fallback path (new leader, periodic
            // emission has not yet produced its first snapshot).
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
                                                   Option::none);  // cold-start: no snapshot
            fsm.start().await();
            assertThat(fsm.get(PEER).unwrap()).isInstanceOf(OnDuty.class);

            // SWIM-faulty arrives on a leader with no aggregator snapshot → cold-start fallback
            // → permissive gate → DECOMMISSIONED write.
            fsm.onSwimObservation(new org.pragmatica.swim.SwimObservation.FaultyObserved(PEER, 1L));

            assertThat(commandApplier.calls).hasSize(1);
            assertLifecyclePut(commandApplier.calls.get(0).get(0), NodeLifecycleState.STOPPED);
            assertThat(fsm.get(PEER).unwrap()).isInstanceOf(Stopped.class);
        }

        @Test void quorumSnapshotReachable_swimFaultyOnOnDuty_isNop() {
            // Companion assertion: a snapshot that reports REACHABLE for the peer blocks the
            // SWIM-faulty decommission (S13 SWIM-only failure — confirms the gate is not just
            // a vacuous pass-through under the cold-start fallback).
            var lifecycleSnapshot = new FakeLifecycleSnapshot();
            lifecycleSnapshot.put(PEER, NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY, ms(T0)));
            var slotSnapshot = new FakeSlotSnapshot();
            var commandApplier = new RecordingCommandApplier();
            var reachableSnapshot = snapshotOf(Map.entry(PEER, ReachabilityKind.REACHABLE));
            var fsm = MembershipFsm.membershipFsm(SELF,
                                                   MembershipFsmConfig.defaultMembershipFsmConfig(),
                                                   lifecycleSnapshot,
                                                   slotSnapshot,
                                                   commandApplier,
                                                   new NoOpDrainCoordinator(),
                                                   new NoOpScheduler(),
                                                   () -> true,
                                                   org.pragmatica.hlc.HlcClock.hlcClock(SELF),
                                                   () -> Option.some(reachableSnapshot));
            fsm.start().await();

            fsm.onSwimObservation(new org.pragmatica.swim.SwimObservation.FaultyObserved(PEER, 1L));

            assertThat(commandApplier.calls).isEmpty();
            assertThat(fsm.get(PEER).unwrap()).isInstanceOf(OnDuty.class);
        }
    }

    private static AggregatedReachabilitySnapshot snapshotOf(Map.Entry<NodeId, ReachabilityKind> entry) {
        var states = new LinkedHashMap<NodeId, ReachabilityState>();
        states.put(entry.getKey(), new ReachabilityState(entry.getKey(), entry.getValue(), 1, NOW_MS));
        return new AggregatedReachabilitySnapshot(NOW_MS, states);
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

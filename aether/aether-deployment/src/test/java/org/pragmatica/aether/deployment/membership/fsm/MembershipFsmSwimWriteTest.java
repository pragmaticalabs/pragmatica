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
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm.LifecycleSnapshotReader;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm.SlotSnapshotReader;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm.TimerScheduler;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmState.Decommissioned;
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
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.swim.SwimObservation.DepartedObserved;
import org.pragmatica.swim.SwimObservation.FaultyObserved;
import org.pragmatica.swim.SwimObservation.HealthyObserved;
import org.pragmatica.swim.SwimObservation.SuspectObserved;

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

/// E.5 SWIM-write tests (spec §9 E.5). The structural fix: SWIM observations route through
/// the FSM and the `(ON_DUTY, SwimFaulty) → DECOMMISSIONED` transition produces a consensus
/// write on the leader. Closes the smoking-gun bug where the legacy threshold-gate +
/// phase-suppression machinery prevented this write from ever firing.
class MembershipFsmSwimWriteTest {
    private static final NodeId SELF = NodeId.nodeId("self-node").unwrap();

    private static final NodeId PEER_A = NodeId.nodeId("peer-a").unwrap();

    private static final long T0 = 1_000L;

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

    private MembershipFsm buildFsm(boolean shadowEnabled) {
        var config = MembershipFsmConfig.defaultMembershipFsmConfig().withShadowEnabled(shadowEnabled);
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

    private MembershipFsm startedFsm(boolean shadowEnabled) {
        var fsm = buildFsm(shadowEnabled);
        fsm.start().await();
        return fsm;
    }

    @Nested @DisplayName("Smoking gun: (ON_DUTY, SwimFaulty) on leader → DECOMMISSIONED write")
    class SmokingGunTests {
        @Test void swimFaulty_onDuty_leader_writesDecommissioned() {
            // THE TEST that proves the structural fix works. Pre-state: peer is ON_DUTY.
            // Event: FaultyObserved. Expected: single consensus write Put(NodeLifecycleKey,
            // DECOMMISSIONED). Previously the legacy aggregator + threshold gate + phase
            // suppression caused this transition to never fire — the bug E.5 closes.
            seedOnDuty(PEER_A, T0);
            var fsm = startedFsm(true);
            assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(OnDuty.class);
            fsm.onSwimObservation(new FaultyObserved(PEER_A, 7L));
            assertThat(commandApplier.calls).hasSize(1);
            assertSingleLifecyclePut(commandApplier.calls.get(0), PEER_A, NodeLifecycleState.DECOMMISSIONED);
            assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(Decommissioned.class);
        }
    }

    @Nested @DisplayName("Leader gate: followers drop SWIM observations (spec §6.1)")
    class LeaderGateTests {
        @Test void swimFaulty_onDuty_nonLeader_noWrite() {
            // Spec §6.1: followers MUST NOT advance state from SWIM (each node has its own SWIM
            // view — followers learn via KV notifications). Drop is silent (TRACE log).
            seedOnDuty(PEER_A, T0);
            leaderFlag.set(false);
            var fsm = startedFsm(true);
            var priorState = fsm.get(PEER_A).unwrap();
            fsm.onSwimObservation(new FaultyObserved(PEER_A, 7L));
            assertThat(commandApplier.calls).isEmpty();
            assertThat(fsm.get(PEER_A).unwrap()).isEqualTo(priorState);
        }

        @Test void swimHealthy_joining_nonLeader_noWrite() {
            seedJoining(PEER_A, T0);
            leaderFlag.set(false);
            var fsm = startedFsm(true);
            var priorState = fsm.get(PEER_A).unwrap();
            fsm.onSwimObservation(new HealthyObserved(PEER_A, 1L));
            assertThat(commandApplier.calls).isEmpty();
            assertThat(fsm.get(PEER_A).unwrap()).isEqualTo(priorState);
        }

        @Test void swimDeparted_onDuty_nonLeader_noWrite() {
            seedOnDuty(PEER_A, T0);
            leaderFlag.set(false);
            var fsm = startedFsm(true);
            fsm.onSwimObservation(new DepartedObserved(PEER_A, 7L));
            assertThat(commandApplier.calls).isEmpty();
        }
    }

    @Nested @DisplayName("SwimDeparted == SwimFaulty semantics on leader (spec §4)")
    class SwimDepartedTests {
        @Test void swimDeparted_onDuty_leader_writesDecommissioned() {
            seedOnDuty(PEER_A, T0);
            var fsm = startedFsm(true);
            fsm.onSwimObservation(new DepartedObserved(PEER_A, 7L));
            assertThat(commandApplier.calls).hasSize(1);
            assertSingleLifecyclePut(commandApplier.calls.get(0), PEER_A, NodeLifecycleState.DECOMMISSIONED);
            assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(Decommissioned.class);
        }
    }

    @Nested @DisplayName("SwimHealthy → leader-initiated JOINING promotion (Q1=A)")
    class SwimHealthyPromotionTests {
        @Test void swimHealthy_joining_leader_writesOnDuty() {
            // Q1=A: leader-initiated transition from JOINING → ON_DUTY when SWIM confirms peer
            // is healthy. Reducer cell `(JOINING, SwimHealthy) → ON_DUTY` writes Put(L=ON_DUTY).
            //
            // Use a recent joinedAt so leader-takeover (F8) does NOT fire JoinDeadlineExpired
            // during start() — otherwise the peer transitions to DECOMMISSIONED before we can
            // exercise the SwimHealthy path.
            var joinedAt = System.currentTimeMillis() - 1_000L;
            seedJoining(PEER_A, joinedAt);
            var fsm = startedFsm(true);
            assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(Joining.class);
            // Clear writes produced during start() (the JOINING peer triggers a JOIN_DEADLINE
            // timer schedule effect but no write) — be defensive in case the future evolves.
            commandApplier.calls.clear();
            fsm.onSwimObservation(new HealthyObserved(PEER_A, 1L));
            assertThat(commandApplier.calls).hasSize(1);
            assertSingleLifecyclePut(commandApplier.calls.get(0), PEER_A, NodeLifecycleState.ON_DUTY);
            assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(OnDuty.class);
        }

        @Test void swimHealthy_onDuty_noWrite() {
            // Re-confirmation is a nop per spec §5 row OnDuty col SwimHealthy. Event still flows
            // through the leader path (reducer runs, returns empty writes) — no consensus call.
            seedOnDuty(PEER_A, T0);
            var fsm = startedFsm(true);
            fsm.onSwimObservation(new HealthyObserved(PEER_A, 1L));
            assertThat(commandApplier.calls).isEmpty();
            assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(OnDuty.class);
        }
    }

    @Nested @DisplayName("Bootstrap safety: UNTRACKED + SwimFaulty/Departed = nop (I5)")
    class UnknownPeerTests {
        @Test void swimFaulty_unknownPeer_noWrite() {
            // I5: SwimFaulty for an UNTRACKED peer is a nop (bootstrap-safe). No KV write.
            var fsm = startedFsm(true);
            fsm.onSwimObservation(new FaultyObserved(PEER_A, 7L));
            assertThat(commandApplier.calls).isEmpty();
        }

        @Test void swimDeparted_unknownPeer_noWrite() {
            var fsm = startedFsm(true);
            fsm.onSwimObservation(new DepartedObserved(PEER_A, 7L));
            assertThat(commandApplier.calls).isEmpty();
        }
    }

    @Nested @DisplayName("Suspect / Unknown observations don't translate to FSM events")
    class TransientObservationTests {
        @Test void swimSuspect_noFsmEvent() {
            // SuspectObserved is a SWIM-internal transient (§4) — no MembershipFsmEvent emitted,
            // therefore no reducer invocation, therefore no consensus write.
            seedOnDuty(PEER_A, T0);
            var fsm = startedFsm(true);
            fsm.onSwimObservation(new SuspectObserved(PEER_A, 7L));
            assertThat(commandApplier.calls).isEmpty();
            assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(OnDuty.class);
        }
    }

    @Nested @DisplayName("Feature flag gating: flag off → legacy path stays authoritative")
    class FeatureFlagTests {
        @Test void swimFaulty_flagOff_legacyPathExpected() {
            // Flag off: shouldProcess() returns false, no replay, no leader check, no reducer
            // invocation — legacy HealthReconciler.onSwimObservation handles everything.
            seedOnDuty(PEER_A, T0);
            var fsm = startedFsm(false);
            fsm.onSwimObservation(new FaultyObserved(PEER_A, 7L));
            assertThat(commandApplier.calls).isEmpty();
            // shadowEnabled=false → no replay, no tracking; snapshot is empty.
            assertThat(fsm.snapshot()).isEmpty();
        }
    }

    private void seedOnDuty(NodeId peer, long updatedAt) {
        lifecycleSnapshot.put(peer, NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY, updatedAt));
    }

    private void seedJoining(NodeId peer, long updatedAt) {
        lifecycleSnapshot.put(peer, NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.JOINING, updatedAt));
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

    private static final class RecordingCommandApplier
            implements Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> {
        private final List<List<KVCommand<AetherKey>>> calls = new ArrayList<>();

        @Override public Promise<List<Object>> apply(List<KVCommand<AetherKey>> commands) {
            calls.add(List.copyOf(commands));
            return Promise.success(List.<Object>of());
        }
    }

    /// Minimal DrainCoordinator fake. SWIM transitions in E.5 do NOT exercise the drain path
    /// (no `InvokeDrain` effect emitted by SwimX cells), but the FSM still needs a coordinator
    /// to construct.
    private static final class RecordingDrainCoordinator implements DrainCoordinator {
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

    private static final class RecordingScheduler implements TimerScheduler {
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

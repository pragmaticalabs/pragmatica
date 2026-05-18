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
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmState.OnDuty;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ProvisioningSlotKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProvisioningSlotValue;
import org.pragmatica.cluster.metrics.AggregatedReachabilitySnapshot;
import org.pragmatica.cluster.metrics.AggregatedReachabilitySnapshot.ReachabilityKind;
import org.pragmatica.cluster.metrics.AggregatedReachabilitySnapshot.ReachabilityState;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.consensus.NodeId;
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


/// Topology-observation refactor Step 3 (spec §16 scenarios S01, S02, S03, S04, S14).
///
/// `MembershipFsm.onTransportSnapshot(AggregatedReachabilitySnapshot)` fans the leader-side
/// aggregator's snapshot out as per-peer `TransportReachable` / `TransportUnreachable` events.
/// The reducer's nop cells (Step 2) absorb duplicates — no edge-dedup cache is maintained
/// in the FSM.
class MembershipFsmTransportSnapshotTest {
    private static final NodeId SELF = NodeId.nodeId("self-node").unwrap();

    private static final NodeId PEER_A = NodeId.nodeId("peer-a").unwrap();

    private static final NodeId PEER_B = NodeId.nodeId("peer-b").unwrap();

    private static final NodeId PEER_C = NodeId.nodeId("peer-c").unwrap();

    private static final NodeId PEER_D = NodeId.nodeId("peer-d").unwrap();

    private static final long T0 = 1_000L;

    private static final long NOW_MS = 5_000L;

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

    @Nested @DisplayName("Leader gate: followers drop transport snapshots (spec §6.1)")
    class LeaderGateTests {
        @Test void followerDropsSnapshot_noEventsEnqueued_noKvWrites() {
            seedOnDuty(PEER_A, T0);
            seedOnDuty(PEER_B, T0);
            seedOnDuty(PEER_C, T0);
            leaderFlag.set(false);
            var fsm = startedFsm();
            var snapshot = snapshotOf(
                Map.entry(PEER_A, ReachabilityKind.REACHABLE),
                Map.entry(PEER_B, ReachabilityKind.REACHABLE),
                Map.entry(PEER_C, ReachabilityKind.UNREACHABLE));

            fsm.onTransportSnapshot(snapshot);

            assertThat(commandApplier.calls).isEmpty();
            // FSM state must be unchanged — followers learn via KV notifications only.
            assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(OnDuty.class);
            assertThat(fsm.get(PEER_B).unwrap()).isInstanceOf(OnDuty.class);
            assertThat(fsm.get(PEER_C).unwrap()).isInstanceOf(OnDuty.class);
        }
    }

    @Nested @DisplayName("Leader dispatch: per-kind event fan-out")
    class LeaderDispatchTests {
        @Test void leader_enqueuesEventsPerKind_unknownSuppressed() {
            // Seed peers so the reducer has interesting state: A,B are ON_DUTY (REACHABLE
            // hits nop cell), C is ON_DUTY (UNREACHABLE writes DECOMMISSIONED), D is
            // UNTRACKED (UNKNOWN suppressed entirely).
            seedOnDuty(PEER_A, T0);
            seedOnDuty(PEER_B, T0);
            seedOnDuty(PEER_C, T0);
            var fsm = startedFsm();
            var snapshot = snapshotOf(
                Map.entry(PEER_A, ReachabilityKind.REACHABLE),
                Map.entry(PEER_B, ReachabilityKind.REACHABLE),
                Map.entry(PEER_C, ReachabilityKind.UNREACHABLE),
                Map.entry(PEER_D, ReachabilityKind.UNKNOWN));

            fsm.onTransportSnapshot(snapshot);

            // REACHABLE on ON_DUTY → reducer nop (no consensus write); UNREACHABLE on ON_DUTY
            // → DECOMMISSIONED write; UNKNOWN → suppressed (no event reaches the reducer).
            // Net: exactly 1 consensus write (for PEER_C).
            assertThat(commandApplier.calls).hasSize(1);
            assertSingleLifecyclePut(commandApplier.calls.get(0), PEER_C, NodeLifecycleState.DECOMMISSIONED);
            // PEER_A and PEER_B stay ON_DUTY (nop), PEER_C transitions to DECOMMISSIONED,
            // PEER_D never reaches reducer.
            assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(OnDuty.class);
            assertThat(fsm.get(PEER_B).unwrap()).isInstanceOf(OnDuty.class);
            assertThat(fsm.get(PEER_C).unwrap()).isInstanceOf(Decommissioned.class);
            assertThat(fsm.get(PEER_D).isPresent()).isFalse();
        }
    }

    @Nested @DisplayName("Idempotence: duplicate snapshots absorbed by reducer nop cells")
    class IdempotenceTests {
        @Test void leader_duplicateSnapshot_reducerAbsorbsExtraWrites() {
            seedOnDuty(PEER_A, T0);
            var fsm = startedFsm();
            var snapshot = snapshotOf(Map.entry(PEER_A, ReachabilityKind.UNREACHABLE));

            fsm.onTransportSnapshot(snapshot);
            assertThat(commandApplier.calls).hasSize(1);
            var stateAfterFirst = fsm.get(PEER_A).unwrap();
            assertThat(stateAfterFirst).isInstanceOf(Decommissioned.class);

            // Second invocation: peer is now DECOMMISSIONED in FSM state, so the reducer cell
            // (DECOMMISSIONED, TransportUnreachable) → nop fires and produces NO additional
            // KV write. This is the "no edge-dedup cache needed" property — the reducer's
            // terminal-state nop cell from Step 2 is the dedup mechanism.
            fsm.onTransportSnapshot(snapshot);
            assertThat(commandApplier.calls).hasSize(1);  // still 1 — no new write
            assertThat(fsm.get(PEER_A).unwrap()).isEqualTo(stateAfterFirst);
        }
    }

    @Nested @DisplayName("Not-started guard: snapshot before start() is a no-op")
    class NotStartedTests {
        @Test void notStarted_snapshotIgnored_noEventsEnqueued() {
            seedOnDuty(PEER_A, T0);
            var fsm = buildFsm();  // NOT started
            var snapshot = snapshotOf(
                Map.entry(PEER_A, ReachabilityKind.UNREACHABLE),
                Map.entry(PEER_B, ReachabilityKind.REACHABLE));

            fsm.onTransportSnapshot(snapshot);

            assertThat(commandApplier.calls).isEmpty();
            // FSM never replayed KV — get() returns empty for everything.
            assertThat(fsm.get(PEER_A).isPresent()).isFalse();
            assertThat(fsm.get(PEER_B).isPresent()).isFalse();
        }
    }

    @Nested @DisplayName("End-to-end S02/S14: ON_DUTY + TransportUnreachable → DECOMMISSIONED")
    class EndToEndDecommissionTests {
        @Test void leader_onDutyPeer_unreachable_writesDecommissioned() {
            seedOnDuty(PEER_A, T0);
            var fsm = startedFsm();
            assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(OnDuty.class);

            var snapshot = snapshotOf(Map.entry(PEER_A, ReachabilityKind.UNREACHABLE));
            fsm.onTransportSnapshot(snapshot);

            assertThat(commandApplier.calls).hasSize(1);
            assertSingleLifecyclePut(commandApplier.calls.get(0), PEER_A, NodeLifecycleState.DECOMMISSIONED);
            assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(Decommissioned.class);
        }
    }

    @SafeVarargs
    private static AggregatedReachabilitySnapshot snapshotOf(Map.Entry<NodeId, ReachabilityKind>... entries) {
        var states = new LinkedHashMap<NodeId, ReachabilityState>();
        for (var entry : entries) {
            states.put(entry.getKey(),
                       new ReachabilityState(entry.getKey(), entry.getValue(), 1, NOW_MS));
        }
        return new AggregatedReachabilitySnapshot(NOW_MS, states);
    }

    private void seedOnDuty(NodeId peer, long updatedAt) {
        lifecycleSnapshot.put(peer, NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY, updatedAt));
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

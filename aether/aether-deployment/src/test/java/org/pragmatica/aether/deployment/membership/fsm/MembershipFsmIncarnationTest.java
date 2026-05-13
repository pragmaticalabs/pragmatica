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
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmState.Decommissioned;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmState.OnDuty;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ProvisioningSlotKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProvisioningSlotValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.swim.SwimObservation.DepartedObserved;
import org.pragmatica.swim.SwimObservation.FaultyObserved;
import org.pragmatica.swim.SwimObservation.HealthyObserved;

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

/// RC1 Step 3 incarnation-gate tests (topology-rc1-spec §3.2). Validates the
/// `latestObservedIncarnation` map maintained inside `MembershipFsm`: stale events
/// drop, restart-reset (`incarnation == 0`) admits and resets the map, multi-peer
/// streams are independent, and the map is pruned on terminal Decommissioned
/// transitions.
class MembershipFsmIncarnationTest {
    private static final NodeId SELF = NodeId.nodeId("self-node").unwrap();

    private static final NodeId PEER_A = NodeId.nodeId("peer-a").unwrap();

    private static final NodeId PEER_B = NodeId.nodeId("peer-b").unwrap();

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

    @Nested
    @DisplayName("Stale events drop")
    class StaleEventDropTests {
        @Test
        void swimFaulty_staleIncarnation_dropped() {
            // Seed ON_DUTY; admit a SwimHealthy at incarnation 5 (which is a nop cell
            // but updates the gate map); then deliver a SwimFaulty at incarnation 3 —
            // must be dropped, peer stays ON_DUTY.
            seedOnDuty(PEER_A, T0);
            var fsm = startedFsm();
            fsm.onSwimObservation(new HealthyObserved(PEER_A, 5L));
            commandApplier.calls.clear();

            fsm.onSwimObservation(new FaultyObserved(PEER_A, 3L));

            assertThat(commandApplier.calls).isEmpty();
            assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(OnDuty.class);
        }

        @Test
        void swimDeparted_staleIncarnation_dropped() {
            seedOnDuty(PEER_A, T0);
            var fsm = startedFsm();
            fsm.onSwimObservation(new HealthyObserved(PEER_A, 10L));
            commandApplier.calls.clear();

            fsm.onSwimObservation(new DepartedObserved(PEER_A, 4L));

            assertThat(commandApplier.calls).isEmpty();
            assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(OnDuty.class);
        }

        @Test
        void swimHealthy_staleIncarnation_dropped() {
            // First SwimHealthy at 7 seeds the map; second at 2 is stale and dropped.
            // Reducer would translate the first to nop on UNTRACKED→ON_DUTY (admitting
            // a write); the second must be silently dropped before reducer entry.
            var fsm = startedFsm();
            fsm.onSwimObservation(new HealthyObserved(PEER_A, 7L));
            var writesAfterFirst = commandApplier.calls.size();

            fsm.onSwimObservation(new HealthyObserved(PEER_A, 2L));

            assertThat(commandApplier.calls).hasSize(writesAfterFirst);
        }

        @Test
        void outOfOrderSequence_onlyLatestAdmitted() {
            // Replay an adversarial out-of-order sequence: 4, 7, 2, 9, 5, 8.
            // Expected admits: 4, 7, 9. Drops: 2 (<7), 5 (<9), 8 (<9). Final stored = 9.
            seedOnDuty(PEER_A, T0);
            var fsm = startedFsm();

            long[] sequence = {4L, 7L, 2L, 9L, 5L, 8L};
            for (long inc : sequence) {
                fsm.onSwimObservation(new HealthyObserved(PEER_A, inc));
            }

            // Final delivery at incarnation 1 (stale) must be dropped.
            commandApplier.calls.clear();
            fsm.onSwimObservation(new FaultyObserved(PEER_A, 1L));
            assertThat(commandApplier.calls).isEmpty();

            // Fresh delivery at incarnation 10 must be admitted (we now expect a
            // DECOMMISSIONED write — peer was ON_DUTY).
            fsm.onSwimObservation(new FaultyObserved(PEER_A, 10L));
            assertThat(commandApplier.calls).hasSize(1);
            assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(Decommissioned.class);
        }
    }

    @Nested
    @DisplayName("Restart-reset (incarnation == 0)")
    class RestartResetTests {
        @Test
        void incarnationZero_admittedAndResetsMap() {
            // Build up a high stored incarnation, then deliver incarnation 0 (restart).
            // Must be admitted and reset map to 0; a subsequent incarnation 1 from the
            // restarted peer must then be admitted (it would have been stale relative
            // to the pre-restart stored value of 50).
            var fsm = startedFsm();
            fsm.onSwimObservation(new HealthyObserved(PEER_A, 50L));
            commandApplier.calls.clear();

            // Restart-reset: incarnation 0 admitted.
            fsm.onSwimObservation(new HealthyObserved(PEER_A, 0L));
            // The reducer cell (ON_DUTY, SwimHealthy) is a nop — no write — but the
            // gate must not have dropped. Verify by following up with incarnation 1:
            // pre-fix this would be < 50 and dropped; post-fix the map is reset to 0,
            // so 1 > 0 admits.
            fsm.onSwimObservation(new FaultyObserved(PEER_A, 1L));

            assertThat(commandApplier.calls).hasSize(1);
            assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(Decommissioned.class);
        }

        @Test
        void incarnationZero_noPriorEntry_admitted() {
            // Cold-start case: incarnation 0 arrives for a peer with no map entry.
            // Must be admitted (the map default is 0, so the gate's incoming==0 branch
            // both admits and writes 0 — no-op on the map but the reducer sees the event).
            var fsm = startedFsm();

            fsm.onSwimObservation(new HealthyObserved(PEER_A, 0L));

            // UNTRACKED + SwimHealthy on leader → ON_DUTY write (one consensus call).
            assertThat(commandApplier.calls).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Multi-peer independence")
    class MultiPeerTests {
        @Test
        void perPeerMapsAreIndependent() {
            // Peer A advances to incarnation 10; Peer B at incarnation 2 must still be
            // admitted (B's stored is 0, not A's 10).
            seedOnDuty(PEER_A, T0);
            seedOnDuty(PEER_B, T0);
            var fsm = startedFsm();
            fsm.onSwimObservation(new HealthyObserved(PEER_A, 10L));
            commandApplier.calls.clear();

            fsm.onSwimObservation(new FaultyObserved(PEER_B, 2L));

            assertThat(commandApplier.calls).hasSize(1);
            assertThat(fsm.get(PEER_B).unwrap()).isInstanceOf(Decommissioned.class);
            // Peer A is unaffected.
            assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(OnDuty.class);
        }

        @Test
        void stalePeerA_doesNotBlockFreshPeerB() {
            // Peer A's stream has a stale arrival at the same time peer B fires fresh.
            // The drop on A must not affect B's admission.
            seedOnDuty(PEER_A, T0);
            seedOnDuty(PEER_B, T0);
            var fsm = startedFsm();
            fsm.onSwimObservation(new HealthyObserved(PEER_A, 8L));
            commandApplier.calls.clear();

            // Stale A:
            fsm.onSwimObservation(new FaultyObserved(PEER_A, 3L));
            // Fresh B:
            fsm.onSwimObservation(new FaultyObserved(PEER_B, 1L));

            assertThat(commandApplier.calls).hasSize(1);
            // A unaffected; B decommissioned.
            assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(OnDuty.class);
            assertThat(fsm.get(PEER_B).unwrap()).isInstanceOf(Decommissioned.class);
        }
    }

    @Nested
    @DisplayName("Pruning on Decommissioned")
    class PruningTests {
        @Test
        void decommissioned_pruneAdmitsFreshStream() {
            // Drive peer to Decommissioned via SwimFaulty at incarnation 50; then a
            // brand-new stream from the same NodeId starting at incarnation 1 must be
            // admitted (map entry was pruned). The reducer's revival path is gated
            // separately (handover §H — DECOMMISSIONED stays decommissioned) so we
            // verify pruning by checking the map drops to zero stored value — proxy:
            // a subsequent SwimHealthy at incarnation 1 is NOT dropped by the gate
            // (it might still nop in the reducer, but absence of a stale-drop log /
            // observable state confirms admission).
            seedOnDuty(PEER_A, T0);
            var fsm = startedFsm();
            fsm.onSwimObservation(new FaultyObserved(PEER_A, 50L));
            assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(Decommissioned.class);
            commandApplier.calls.clear();

            // After prune, a fresh stream at incarnation 1 reaches the reducer.
            // Reducer cell (DECOMMISSIONED, SwimHealthy) is nop (H.3), so no write —
            // but the gate must have admitted, which we prove by following with a
            // subsequent FaultyObserved at incarnation 2: stored is now 1 (from prior
            // admit), so 2 > 1 admits — reducer cell (DECOMMISSIONED, SwimFaulty) is
            // also nop, but again admission is the question, not the reducer output.
            //
            // Stronger proof: if the map had NOT been pruned, the stored value would
            // be 50; an incarnation-1 observation would be DROPPED by the gate and
            // never reach the reducer or any visible side effect. We can observe this
            // indirectly by re-seeding ON_DUTY for the peer (via KV-put notification)
            // and verifying that a SwimFaulty at incarnation 2 produces a write —
            // which would be impossible if 2 were gated as stale against the 50.
            applyExternalLifecyclePut(fsm, PEER_A, NodeLifecycleState.ON_DUTY);
            // Now the reducer would, on (ON_DUTY, SwimFaulty), emit a DECOMMISSIONED
            // write — IFF the gate admits the event.
            fsm.onSwimObservation(new FaultyObserved(PEER_A, 2L));

            assertThat(commandApplier.calls).hasSize(1);
            assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(Decommissioned.class);
        }

        @Test
        void externalKvPutDecommissioned_prunesIncarnationEntry() {
            // Leader is not the only path that prunes. A KV-put notification arriving
            // with state=DECOMMISSIONED (from a peer-side write replicated via Rabia)
            // must also prune. Proxy verification mirrors the leader-side prune test.
            seedOnDuty(PEER_A, T0);
            var fsm = startedFsm();
            fsm.onSwimObservation(new HealthyObserved(PEER_A, 99L));
            commandApplier.calls.clear();

            // External KV write transitions peer to DECOMMISSIONED. This must prune
            // the incarnation map entry.
            applyExternalLifecyclePut(fsm, PEER_A, NodeLifecycleState.DECOMMISSIONED);

            // Re-seed ON_DUTY and deliver a low-incarnation SwimFaulty. If the prune
            // worked, stored is 0, incoming 5 admits, reducer writes DECOMMISSIONED.
            applyExternalLifecyclePut(fsm, PEER_A, NodeLifecycleState.ON_DUTY);
            commandApplier.calls.clear();
            fsm.onSwimObservation(new FaultyObserved(PEER_A, 5L));

            assertThat(commandApplier.calls).hasSize(1);
        }
    }

    private void seedOnDuty(NodeId peer, long updatedAt) {
        lifecycleSnapshot.put(peer, NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY, updatedAt));
    }

    private static void applyExternalLifecyclePut(MembershipFsm fsm, NodeId peer, NodeLifecycleState state) {
        var key = NodeLifecycleKey.nodeLifecycleKey(peer);
        var value = NodeLifecycleValue.nodeLifecycleValue(state, System.currentTimeMillis());
        var put = new ValuePut<NodeLifecycleKey, NodeLifecycleValue>(new Put<>(key, value), Option.none());
        fsm.onNodeLifecyclePut(put);
    }

    private static final class FakeLifecycleSnapshot implements LifecycleSnapshotReader {
        private final Map<NodeLifecycleKey, NodeLifecycleValue> entries = new LinkedHashMap<>();

        void put(NodeId peer, NodeLifecycleValue value) {
            entries.put(NodeLifecycleKey.nodeLifecycleKey(peer), value);
        }

        @Override
        public void forEachLifecycle(BiConsumer<NodeLifecycleKey, NodeLifecycleValue> consumer) {
            entries.forEach(consumer);
        }
    }

    private static final class FakeSlotSnapshot implements SlotSnapshotReader {
        private final Map<ProvisioningSlotKey, ProvisioningSlotValue> entries = new LinkedHashMap<>();

        @Override
        public void forEachSlot(BiConsumer<ProvisioningSlotKey, ProvisioningSlotValue> consumer) {
            entries.forEach(consumer);
        }
    }

    private static final class RecordingCommandApplier
            implements Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> {
        private final List<List<KVCommand<AetherKey>>> calls = new ArrayList<>();

        @Override
        public Promise<List<Object>> apply(List<KVCommand<AetherKey>> commands) {
            calls.add(List.copyOf(commands));
            return Promise.success(List.<Object>of());
        }
    }

    private static final class RecordingDrainCoordinator implements DrainCoordinator {
        @Override
        public Promise<Unit> prepareDrain(NodeId nodeId, DrainReason reason) {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> awaitDrainAck(NodeId nodeId, TimeSpan timeout) {
            return Promise.unitPromise();
        }

        @Override
        public void markDrainComplete(NodeId nodeId) {
            // No-op for tests.
        }
    }

    private static final class RecordingScheduler implements TimerScheduler {
        @Override
        public ScheduledFuture<?> schedule(Runnable runnable, TimeSpan delay) {
            return NO_OP_FUTURE;
        }

        private static final ScheduledFuture<?> NO_OP_FUTURE = new ScheduledFuture<>() {
            @Override
            public long getDelay(TimeUnit unit) {return 0L;}

            @Override
            public int compareTo(java.util.concurrent.Delayed o) {return 0;}

            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {return true;}

            @Override
            public boolean isCancelled() {return false;}

            @Override
            public boolean isDone() {return false;}

            @Override
            public Object get() {return null;}

            @Override
            public Object get(long timeout, TimeUnit unit) {return null;}
        };
    }
}

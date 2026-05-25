// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.drain.DrainCoordinator;
import org.pragmatica.aether.deployment.drain.DrainCoordinator.DrainReason;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.ForceOnDuty;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm.LifecycleSnapshotReader;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm.SlotSnapshotReader;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm.TimerScheduler;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmState.Joining;
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
import org.pragmatica.hlc.HlcTimestamp;
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
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;


/// D4 occupant-epoch fence (slot-based-membership-convergence-spec §3.3/§4.3, OQ3=wiring layer;
/// §8.3 "Fence" unit test). The fence lives in `MembershipFsm.resolveLifecycleWrites`: an ON_DUTY
/// promotion write for a peer that is no longer the current occupant of the slot it was bound to
/// is dropped (no-op + audit). This fences a superseded predecessor's late ON_DUTY write on
/// partition-heal (S05/S06), so it never re-projects as a live core.
class MembershipFsmOccupantEpochFenceTest {
    private static final NodeId SELF = NodeId.nodeId("self-node").unwrap();
    private static final NodeId PRED = NodeId.nodeId("predecessor").unwrap();
    private static final NodeId SUCC = NodeId.nodeId("successor").unwrap();
    private static final String SLOT_0 = "0";

    private FakeLifecycleSnapshot lifecycleSnapshot;
    private MutableSlotSnapshot slotSnapshot;
    private RecordingCommandApplier commandApplier;

    @BeforeEach
    void setUp() {
        lifecycleSnapshot = new FakeLifecycleSnapshot();
        slotSnapshot = new MutableSlotSnapshot();
        commandApplier = new RecordingCommandApplier();
    }

    private MembershipFsm startedFsm() {
        var fsm = MembershipFsm.membershipFsm(SELF,
                                              MembershipFsmConfig.defaultMembershipFsmConfig(),
                                              lifecycleSnapshot,
                                              slotSnapshot,
                                              commandApplier,
                                              new NoOpDrainCoordinator(),
                                              new NoOpScheduler(),
                                              (BooleanSupplier) () -> true);
        fsm.start().await();
        return fsm;
    }

    private static ProvisioningSlotValue occupiedBy(NodeId occupant, long epoch) {
        return new ProvisioningSlotValue(1L, 2L, Option.some(occupant), epoch, Option.none());
    }

    private void claimSlot(MembershipFsm fsm, NodeId occupant, long epoch) {
        var value = occupiedBy(occupant, epoch);
        slotSnapshot.put(SLOT_0, value);
        fsm.onProvisioningSlotPut(new ValuePut<>(new Put<>(ProvisioningSlotKey.provisioningSlotKey(SLOT_0), value),
                                                 Option.none()));
    }

    @Test
    void promote_supersededByDifferentOccupant_isFenced() {
        var fsm = startedFsm();
        // PRED bound to slot 0 at epoch 1 (records slotIdToPeer + boundEpoch=1).
        claimSlot(fsm, PRED, 1L);
        // Slot re-occupied by SUCC at epoch 2 — PRED is now a superseded predecessor.
        slotSnapshot.put(SLOT_0, occupiedBy(SUCC, 2L));
        commandApplier.calls.clear();

        var accepted = fsm.applyLifecycleCommand(new ForceOnDuty(PRED,
                                                                 Causes.cause("late partition-heal ON_DUTY"),
                                                                 HlcTimestamp.ZERO)).await().unwrap();

        assertThat(accepted).as("a superseded predecessor's ON_DUTY promotion must be a no-op (fenced)").isFalse();
        assertThat(onDutyWrites()).as("no ON_DUTY lifecycle write may reach consensus for the superseded peer").isEmpty();
        assertThat(fsm.get(PRED).unwrap()).as("a fenced predecessor must not be re-projected as OnDuty").isInstanceOf(Joining.class);
    }

    @Test
    void promote_staleBoundEpochSameOccupantId_isFenced() {
        var fsm = startedFsm();
        // PRED bound at epoch 1.
        claimSlot(fsm, PRED, 1L);
        // Slot still nominally PRED-keyed but occupantEpoch advanced to 2 (CTM re-stamp);
        // boundEpoch (1) < occupantEpoch (2) → fenced.
        slotSnapshot.put(SLOT_0, occupiedBy(PRED, 2L));
        commandApplier.calls.clear();

        var accepted = fsm.applyLifecycleCommand(new ForceOnDuty(PRED,
                                                                 Causes.cause("stale-epoch ON_DUTY"),
                                                                 HlcTimestamp.ZERO)).await().unwrap();

        assertThat(accepted).as("boundEpoch < occupantEpoch must fence the write (no-op)").isFalse();
        assertThat(onDutyWrites()).isEmpty();
    }

    @Test
    void promote_currentOccupantMatchingEpoch_isNotFenced() {
        var fsm = startedFsm();
        // PRED bound at epoch 1 and remains the current occupant at epoch 1 → legitimate promote.
        claimSlot(fsm, PRED, 1L);
        commandApplier.calls.clear();

        var accepted = fsm.applyLifecycleCommand(new ForceOnDuty(PRED,
                                                                 Causes.cause("legitimate promote"),
                                                                 HlcTimestamp.ZERO)).await().unwrap();

        assertThat(accepted).as("the current occupant's promotion must be accepted").isTrue();
        assertThat(onDutyWrites()).as("the ON_DUTY write must reach consensus").isNotEmpty();
    }

    @Test
    void promote_peerWithNoSlot_isNotFenced() {
        // A peer with no bound slot (operator force, self-bootstrap) is never fenced.
        lifecycleSnapshot.put(PRED, NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.JOINING,
                                                                          System.currentTimeMillis()));
        var fsm = startedFsm();
        commandApplier.calls.clear();

        var accepted = fsm.applyLifecycleCommand(new ForceOnDuty(PRED,
                                                                 Causes.cause("no-slot promote"),
                                                                 HlcTimestamp.ZERO)).await().unwrap();

        assertThat(accepted).isTrue();
        assertThat(onDutyWrites()).isNotEmpty();
    }

    private List<NodeId> onDutyWrites() {
        var peers = new ArrayList<NodeId>();
        for (var batch : commandApplier.calls) {
            for (var command : batch) {
                if (command instanceof Put<?, ?> put
                    && put.key() instanceof NodeLifecycleKey key
                    && put.value() instanceof NodeLifecycleValue value
                    && value.state() == NodeLifecycleState.ON_DUTY) {
                    peers.add(key.nodeId());
                }
            }
        }
        return peers;
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

    private static final class MutableSlotSnapshot implements SlotSnapshotReader {
        private final Map<ProvisioningSlotKey, ProvisioningSlotValue> slots = new LinkedHashMap<>();

        void put(String slotId, ProvisioningSlotValue value) {
            slots.put(ProvisioningSlotKey.provisioningSlotKey(slotId), value);
        }

        @Override public void forEachSlot(BiConsumer<ProvisioningSlotKey, ProvisioningSlotValue> consumer) {
            slots.forEach(consumer);
        }
    }

    private static final class RecordingCommandApplier
            implements Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> {
        private final List<List<KVCommand<AetherKey>>> calls = new ArrayList<>();

        @Override public Promise<List<Object>> apply(List<KVCommand<AetherKey>> commands) {
            calls.add(List.copyOf(commands));
            return Promise.success(List.of());
        }
    }

    private static final class NoOpDrainCoordinator implements DrainCoordinator {
        @Override public Promise<Unit> prepareDrain(NodeId nodeId, DrainReason reason) {
            return Promise.unitPromise();
        }

        @Override public Promise<Unit> awaitDrainAck(NodeId nodeId, TimeSpan timeout) {
            return Promise.promise();
        }

        @Override public void markDrainComplete(NodeId nodeId) {
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

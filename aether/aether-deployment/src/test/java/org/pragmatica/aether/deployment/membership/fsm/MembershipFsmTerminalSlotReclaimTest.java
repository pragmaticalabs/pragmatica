// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.drain.DrainCoordinator;
import org.pragmatica.aether.deployment.drain.DrainCoordinator.DrainReason;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm.LifecycleSnapshotReader;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm.SlotSnapshotReader;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm.TimerScheduler;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmState.Joining;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmState.Provisioning;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ProvisioningSlotKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProvisioningSlotValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.cluster.state.kvstore.KVCommand.Remove;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
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
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;


/// #230 flap re-stamp regression. When a slot occupant flaps (JOINING → STOPPED) and its STOPPED
/// lifecycle atom is later GC-removed, the FSM previously re-derived that SAME dead occupant to
/// `PROVISIONING` for its still-durable slot (`applyLifecycleRemoveWithSlot`). A subsequent slot
/// re-put then re-fired `SlotClaimed → JOINING`, which made CTM perpetually re-stamp the slot's
/// FILLING deadline — `freeStaleFillingSlots` (deadline-lapse gated) could never reclaim the slot,
/// wedging chaos recovery at `{HEALTHY=3, FILLING=2}` (never refilling to 5).
///
/// The fix: a lifecycle-removed (terminal) peer ALWAYS goes UNTRACKED, even when it still owns a
/// slot. The durable slot is left for CTM's stale-FILLING reconcile to free (→ EMPTY) and refill
/// with a FRESH occupant. These tests drive the flap on the FSM and assert the corpse is NOT
/// re-tracked, and that the slot refills cleanly with a different occupant.
class MembershipFsmTerminalSlotReclaimTest {
    private static final NodeId SELF = NodeId.nodeId("self-node").unwrap();
    private static final NodeId FLAPPER = NodeId.nodeId("flapper").unwrap();
    private static final NodeId REPLACEMENT = NodeId.nodeId("replacement").unwrap();
    private static final String SLOT_0 = "0";
    private static final long T0 = 1_000L;

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
        return new ProvisioningSlotValue(T0, T0 + 60_000L, Option.some(occupant), epoch, Option.none());
    }

    private void claimSlot(NodeId occupant, long epoch) {
        var value = occupiedBy(occupant, epoch);
        slotSnapshot.put(SLOT_0, value);
        fsm().onProvisioningSlotPut(new ValuePut<>(new Put<>(ProvisioningSlotKey.provisioningSlotKey(SLOT_0), value),
                                                   Option.none()));
    }

    private MembershipFsm sharedFsm;

    private MembershipFsm fsm() {
        return sharedFsm;
    }

    @Test
    void onNodeLifecycleRemove_terminalFlapperWithBoundSlot_goesUntrackedNotProvisioning() {
        sharedFsm = startedFsm();
        // Flapper claims slot 0 at epoch 1 → JOINING.
        claimSlot(FLAPPER, 1L);
        assertThat(sharedFsm.get(FLAPPER).unwrap()).as("slot claim drives JOINING").isInstanceOf(Joining.class);

        // Flapper flaps to STOPPED; the durable slot atom remains assigned to it (reducer D1).
        lifecycleSnapshot.put(FLAPPER, NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.STOPPED, T0));
        // DecommissionedAtomGc removes the STOPPED lifecycle atom past retention.
        sharedFsm.onNodeLifecycleRemove(lifecycleRemove(FLAPPER));

        assertThat(sharedFsm.get(FLAPPER))
                .as("a GC-removed terminal occupant MUST go UNTRACKED — re-binding to PROVISIONING re-stamps "
                    + "the FILLING deadline and starves freeStaleFillingSlots (#230 stuck-at-3 wedge)")
                .isEqualTo(Option.<MembershipFsmState>none());
    }

    @Test
    void onNodeLifecycleRemove_terminalFlapper_slotRefillsWithDifferentOccupant() {
        sharedFsm = startedFsm();
        claimSlot(FLAPPER, 1L);
        lifecycleSnapshot.put(FLAPPER, NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.STOPPED, T0));
        sharedFsm.onNodeLifecycleRemove(lifecycleRemove(FLAPPER));

        // CTM frees the stale-FILLING slot once its deadline lapses and refills with a FRESH
        // occupant at a bumped epoch — modeled here as the next assigned slot put.
        claimSlot(REPLACEMENT, 2L);

        assertThat(sharedFsm.get(FLAPPER))
                .as("the dead flapper is never re-tracked")
                .isEqualTo(Option.<MembershipFsmState>none());
        assertThat(sharedFsm.get(REPLACEMENT).unwrap())
                .as("the slot refills with a different, fresh occupant")
                .isInstanceOf(Joining.class);
    }

    @Test
    void onNodeLifecycleRemove_terminalFlapper_notReDerivedToProvisioning() {
        sharedFsm = startedFsm();
        claimSlot(FLAPPER, 1L);
        lifecycleSnapshot.put(FLAPPER, NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.STOPPED, T0));
        sharedFsm.onNodeLifecycleRemove(lifecycleRemove(FLAPPER));

        assertThat(sharedFsm.snapshot().values())
                .as("no peer may be left in PROVISIONING for a terminal flapper's slot")
                .noneMatch(Provisioning.class::isInstance);
    }

    private static ValueRemove<NodeLifecycleKey, NodeLifecycleValue> lifecycleRemove(NodeId peer) {
        return new ValueRemove<>(new Remove<>(NodeLifecycleKey.nodeLifecycleKey(peer)),
                                 Option.some(NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.STOPPED, T0)));
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

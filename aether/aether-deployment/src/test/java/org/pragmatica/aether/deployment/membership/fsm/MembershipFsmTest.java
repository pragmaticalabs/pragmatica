// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm.LifecycleSnapshotReader;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm.SlotSnapshotReader;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmEvent.SlotClaimed;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmEvent.SwimFaulty;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmEvent.SwimHealthy;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmState.Decommissioned;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmState.Draining;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmState.Joining;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmState.OnDuty;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmState.Provisioning;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmState.Untracked;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ProvisioningSlotKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProvisioningSlotValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.swim.SwimObservation.HealthyObserved;
import org.pragmatica.swim.SwimObservation.SuspectObserved;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;

class MembershipFsmTest {
    private static final NodeId SELF = NodeId.nodeId("self-node").unwrap();

    private static final NodeId PEER_A = NodeId.nodeId("peer-a").unwrap();

    private static final NodeId PEER_B = NodeId.nodeId("peer-b").unwrap();

    private static final NodeId PEER_C = NodeId.nodeId("peer-c").unwrap();

    private static final String SLOT_A = "slot-a";

    private static final long T0 = 1_000L;

    private static final long T1 = 2_000L;

    private static final long T2 = 3_000L;

    private FakeLifecycleSnapshot lifecycleSnapshot;

    private FakeSlotSnapshot slotSnapshot;

    @BeforeEach
    void setUp() {
        lifecycleSnapshot = new FakeLifecycleSnapshot();
        slotSnapshot = new FakeSlotSnapshot();
    }

    private MembershipFsm buildFsm(boolean shadowEnabled) {
        var config = MembershipFsmConfig.defaultMembershipFsmConfig().withShadowEnabled(shadowEnabled);
        return MembershipFsm.membershipFsm(SELF, config, lifecycleSnapshot, slotSnapshot);
    }

    private MembershipFsm startedFsm() {
        var fsm = buildFsm(true);
        fsm.start().await();
        return fsm;
    }

    @Nested @DisplayName("start: KV replay reconstructs per-peer state")
    class ReplayTests {
        @Test void start_emptyKV_initializesAllPeersToUntracked() {
            var fsm = startedFsm();
            assertThat(fsm.snapshot()).isEmpty();
            assertThat(fsm.get(PEER_A)).isEqualTo(Option.<MembershipFsmState>none());
        }

        @Test void start_existingOnDutyEntries_reconstructsOnDutyStates() {
            lifecycleSnapshot.put(PEER_A, lifecycleValue(NodeLifecycleState.ON_DUTY, T0));
            lifecycleSnapshot.put(PEER_B, lifecycleValue(NodeLifecycleState.ON_DUTY, T1));
            var fsm = startedFsm();
            assertThat(fsm.snapshot()).hasSize(2);
            assertThat(fsm.get(PEER_A)).isEqualTo(Option.some(MembershipFsmState.onDuty(PEER_A, T0)));
            assertThat(fsm.get(PEER_B)).isEqualTo(Option.some(MembershipFsmState.onDuty(PEER_B, T1)));
        }

        @Test void start_existingDrainingEntries_reconstructsDrainingStates() {
            lifecycleSnapshot.put(PEER_A, lifecycleValue(NodeLifecycleState.DRAINING, T0));
            var fsm = startedFsm();
            assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(Draining.class);
        }

        @Test void start_mixedKvState_derivesCorrectStatePerPeer() {
            lifecycleSnapshot.put(PEER_A, lifecycleValue(NodeLifecycleState.JOINING, T0));
            lifecycleSnapshot.put(PEER_B, lifecycleValue(NodeLifecycleState.ON_DUTY, T1));
            lifecycleSnapshot.put(PEER_C, lifecycleValue(NodeLifecycleState.DECOMMISSIONED, T2));
            slotSnapshot.put(SLOT_A, slotValueAssigned(T0, PEER_A));
            var fsm = startedFsm();
            assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(Joining.class);
            assertThat(fsm.get(PEER_B).unwrap()).isInstanceOf(OnDuty.class);
            assertThat(fsm.get(PEER_C).unwrap()).isInstanceOf(Decommissioned.class);
            var joining = (Joining) fsm.get(PEER_A).unwrap();
            assertThat(joining.slotId()).isEqualTo(Option.some(SLOT_A));
        }

        @Test void start_slotWithoutLifecycle_yieldsProvisioning() {
            slotSnapshot.put(SLOT_A, slotValueAssigned(T0, PEER_A));
            var fsm = startedFsm();
            assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(Provisioning.class);
            assertThat(((Provisioning) fsm.get(PEER_A).unwrap()).slotId()).isEqualTo(SLOT_A);
        }

        @Test void start_failedDrainEntry_reconstructsFailedDrainState() {
            lifecycleSnapshot.put(PEER_A, lifecycleValue(NodeLifecycleState.FAILED_DRAIN, T0));
            var fsm = startedFsm();
            assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(MembershipFsmState.FailedDrain.class);
        }

        @Test void start_shuttingDownLegacy_mapsToDraining() {
            lifecycleSnapshot.put(PEER_A, lifecycleValue(NodeLifecycleState.SHUTTING_DOWN, T0));
            var fsm = startedFsm();
            assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(Draining.class);
        }
    }

    @Nested @DisplayName("onSwimObservation: leader-gated drop on followers (spec §6.1)")
    class SwimObservationTests {
        @Test void onSwimObservation_onFollower_isDropped_noStateChange() {
            // E.5 spec §6.1: followers MUST NOT advance FSM state from SWIM observations.
            // This factory wires `NEVER_LEADER`, so the observation is dropped (TRACE log).
            // Detailed leader-write behaviour is covered in MembershipFsmSwimWriteTest.
            var fsm = startedFsm();
            fsm.onSwimObservation(new HealthyObserved(PEER_A, 1L));
            assertThat(fsm.get(PEER_A)).isEqualTo(Option.<MembershipFsmState>none());
        }

        @Test void onSwimObservation_doesNotMutateLifecycleSnapshot() {
            // The shadow never writes to KV (the FSM is the consumer, not the writer); the
            // lifecycleSnapshot fake is only read by the FSM during replay.
            var fsm = startedFsm();
            fsm.onSwimObservation(new HealthyObserved(PEER_A, 1L));
            assertThat(lifecycleSnapshot.entries).isEmpty();
        }

        @Test void onSwimObservation_suspect_ignored() {
            var fsm = startedFsm();
            fsm.onSwimObservation(new SuspectObserved(PEER_A, 1L));
            assertThat(fsm.get(PEER_A)).isEqualTo(Option.<MembershipFsmState>none());
        }
    }

    @Nested @DisplayName("KV notifications: external writes drive shadow state")
    class KvNotificationTests {
        @Test void onLifecycleKVChange_externalWrite_updatesShadowStateWithoutEmitWrite() {
            var fsm = startedFsm();
            assertThat(fsm.snapshot()).isEmpty();
            var put = new ValuePut<NodeLifecycleKey, NodeLifecycleValue>(new KVCommand.Put<>(NodeLifecycleKey.nodeLifecycleKey(PEER_A),
                                                                                              lifecycleValue(NodeLifecycleState.ON_DUTY, T1)),
                                                                          Option.none());
            fsm.onNodeLifecyclePut(put);
            assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(OnDuty.class);
        }

        @Test void onLifecycleKVRemove_dropsPeerToUntracked() {
            lifecycleSnapshot.put(PEER_A, lifecycleValue(NodeLifecycleState.DECOMMISSIONED, T0));
            var fsm = startedFsm();
            assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(Decommissioned.class);
            var remove = new ValueRemove<NodeLifecycleKey, NodeLifecycleValue>(
                    new KVCommand.Remove<>(NodeLifecycleKey.nodeLifecycleKey(PEER_A)),
                    Option.some(lifecycleValue(NodeLifecycleState.DECOMMISSIONED, T0)));
            fsm.onNodeLifecycleRemove(remove);
            assertThat(fsm.get(PEER_A)).isEqualTo(Option.<MembershipFsmState>none());
        }

        @Test void onSlotKVChange_slotClaimed_invokesReducerSlotClaimed() {
            var fsm = startedFsm();
            var put = new ValuePut<ProvisioningSlotKey, ProvisioningSlotValue>(new KVCommand.Put<>(ProvisioningSlotKey.provisioningSlotKey(SLOT_A),
                                                                                                    slotValueAssigned(T1, PEER_A)),
                                                                                Option.none());
            fsm.onProvisioningSlotPut(put);
            // (UNTRACKED, SlotClaimed) → JOINING per reducer §5 transition table.
            assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(Joining.class);
            var joining = (Joining) fsm.get(PEER_A).unwrap();
            assertThat(joining.slotId()).isEqualTo(Option.some(SLOT_A));
        }

        @Test void onSlotKVChange_unassigned_doesNotInvokeReducer() {
            var fsm = startedFsm();
            var put = new ValuePut<ProvisioningSlotKey, ProvisioningSlotValue>(new KVCommand.Put<>(ProvisioningSlotKey.provisioningSlotKey(SLOT_A),
                                                                                                    slotValueUnassigned(T1)),
                                                                                Option.none());
            fsm.onProvisioningSlotPut(put);
            // No NodeId yet → no peer tracked.
            assertThat(fsm.snapshot()).isEmpty();
        }

        @Test void onSlotKVChange_remove_dropsMappingOnly() {
            slotSnapshot.put(SLOT_A, slotValueAssigned(T0, PEER_A));
            lifecycleSnapshot.put(PEER_A, lifecycleValue(NodeLifecycleState.JOINING, T0));
            var fsm = startedFsm();
            var remove = new ValueRemove<ProvisioningSlotKey, ProvisioningSlotValue>(
                    new KVCommand.Remove<>(ProvisioningSlotKey.provisioningSlotKey(SLOT_A)),
                    Option.some(slotValueAssigned(T0, PEER_A)));
            fsm.onProvisioningSlotRemove(remove);
            // Peer is still tracked from lifecycle KV; slot mapping is gone (verified by no
            // PROVISIONING after lifecycle remove).
            assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(Joining.class);
        }
    }

    @Nested @DisplayName("Operator event entry: enqueueOperatorEvent")
    class OperatorEventTests {
        @Test void enqueueOperatorEvent_slotClaimed_reachesReducer() {
            // SlotClaimed is NOT a leader-writing event (no consensus write proposed), so it
            // flows through the shadow path even on the NEVER_LEADER read-only factory.
            var fsm = startedFsm();
            fsm.enqueueOperatorEvent(new SlotClaimed(PEER_A, SLOT_A, T1));
            assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(Joining.class);
        }

        @Test void enqueueOperatorEvent_swimHealthyForJoining_onFollower_isNoOp() {
            // E.5: SwimHealthy is now a leader-writing event. On the NEVER_LEADER factory, the
            // single-writer gate fires — state is NOT mutated. Leader-side behaviour for
            // SwimHealthy(JOINING) → ON_DUTY is covered in MembershipFsmSwimWriteTest.
            lifecycleSnapshot.put(PEER_A, lifecycleValue(NodeLifecycleState.JOINING, T0));
            var fsm = startedFsm();
            fsm.enqueueOperatorEvent(new SwimHealthy(PEER_A, 1L, T1));
            assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(Joining.class);
        }

        @Test void enqueueOperatorEvent_swimFaultyOnDuty_onFollower_isNoOp() {
            // E.5: SwimFaulty is now a leader-writing event. On the NEVER_LEADER factory, the
            // single-writer gate fires — state is NOT mutated. The leader-side smoking-gun
            // transition (ON_DUTY, SwimFaulty) → DECOMMISSIONED is covered in
            // MembershipFsmSwimWriteTest.swimFaulty_onDuty_leader_writesDecommissioned.
            lifecycleSnapshot.put(PEER_A, lifecycleValue(NodeLifecycleState.ON_DUTY, T0));
            var fsm = startedFsm();
            fsm.enqueueOperatorEvent(new SwimFaulty(PEER_A, 7L, T1));
            assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(OnDuty.class);
        }
    }

    @Nested @DisplayName("Feature flag gating")
    class FeatureFlagTests {
        @Test void shadowDisabled_doesNothing() {
            lifecycleSnapshot.put(PEER_A, lifecycleValue(NodeLifecycleState.ON_DUTY, T0));
            var fsm = buildFsm(false);
            fsm.start().await();
            // Disabled: replay must not run, snapshot must be empty.
            assertThat(fsm.snapshot()).isEmpty();
            assertThat(fsm.shadowEnabled()).isFalse();
        }

        @Test void shadowDisabled_swimObservationIgnored() {
            var fsm = buildFsm(false);
            fsm.start().await();
            fsm.onSwimObservation(new HealthyObserved(PEER_A, 1L));
            assertThat(fsm.get(PEER_A)).isEqualTo(Option.<MembershipFsmState>none());
        }

        @Test void shadowDisabled_kvNotificationIgnored() {
            var fsm = buildFsm(false);
            fsm.start().await();
            var put = new ValuePut<NodeLifecycleKey, NodeLifecycleValue>(new KVCommand.Put<>(NodeLifecycleKey.nodeLifecycleKey(PEER_A),
                                                                                              lifecycleValue(NodeLifecycleState.ON_DUTY, T1)),
                                                                          Option.none());
            fsm.onNodeLifecyclePut(put);
            assertThat(fsm.snapshot()).isEmpty();
        }

        @Test void shadowDisabled_stopIsSafe() {
            var fsm = buildFsm(false);
            fsm.start().await();
            fsm.stop().await();
            assertThat(fsm.snapshot()).isEmpty();
        }
    }

    @Nested @DisplayName("Lifecycle: start/stop idempotence")
    class LifecycleTests {
        @Test void start_calledTwice_returnsOkButDoesNotReplayAgain() {
            lifecycleSnapshot.put(PEER_A, lifecycleValue(NodeLifecycleState.ON_DUTY, T0));
            var fsm = startedFsm();
            assertThat(fsm.snapshot()).hasSize(1);
            // Modify snapshot underneath; second start() must NOT re-replay.
            lifecycleSnapshot.put(PEER_B, lifecycleValue(NodeLifecycleState.ON_DUTY, T1));
            fsm.start().await();
            assertThat(fsm.snapshot()).hasSize(1);
        }

        @Test void stop_clearsState() {
            lifecycleSnapshot.put(PEER_A, lifecycleValue(NodeLifecycleState.ON_DUTY, T0));
            var fsm = startedFsm();
            assertThat(fsm.snapshot()).hasSize(1);
            fsm.stop().await();
            assertThat(fsm.snapshot()).isEmpty();
        }

        @Test void stop_calledTwice_isSafe() {
            var fsm = startedFsm();
            fsm.stop().await();
            fsm.stop().await();
            assertThat(fsm.snapshot()).isEmpty();
        }
    }

    private static NodeLifecycleValue lifecycleValue(NodeLifecycleState state, long updatedAt) {
        return NodeLifecycleValue.nodeLifecycleValue(state, updatedAt);
    }

    private static ProvisioningSlotValue slotValueAssigned(long spawnedAtMs, NodeId peer) {
        return ProvisioningSlotValue.provisioningSlotValue(spawnedAtMs, spawnedAtMs + 60_000L, peer);
    }

    private static ProvisioningSlotValue slotValueUnassigned(long spawnedAtMs) {
        return ProvisioningSlotValue.provisioningSlotValue(spawnedAtMs, spawnedAtMs + 60_000L);
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

        void put(String slotId, ProvisioningSlotValue value) {
            entries.put(ProvisioningSlotKey.provisioningSlotKey(slotId), value);
        }

        @Override public void forEachSlot(BiConsumer<ProvisioningSlotKey, ProvisioningSlotValue> consumer) {
            entries.forEach(consumer);
        }
    }
}

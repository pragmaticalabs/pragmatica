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
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ProvisioningSlotKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProvisioningSlotValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProvisioningSource;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;


/// RC1 Step 4 — adversarial monotonicity test for the FSM's HLC handling.
///
/// Feeds events whose underlying physical-clock source steps backward, and asserts that the
/// resulting `NodeLifecycleValue.transitionedAt` HLC sequence is monotonically increasing
/// regardless. Also covers the cross-node HLC merge path on `onNodeLifecyclePut` —
/// in-range remote HLCs advance the local clock, drift-exceeding remote HLCs are dropped
/// per the WARN+drop policy.
class MembershipFsmHlcMonotonicityTest {
    private static final NodeId SELF = NodeId.nodeId("self-node").unwrap();

    private static final NodeId PEER_A = NodeId.nodeId("peer-a").unwrap();

    private static final NodeId PEER_B = NodeId.nodeId("peer-b").unwrap();

    private static final String SLOT_A = "slot-a";

    private static final String SLOT_B = "slot-b";

    private FakeLifecycleSnapshot lifecycleSnapshot;

    private FakeSlotSnapshot slotSnapshot;

    @BeforeEach
    void setUp() {
        lifecycleSnapshot = new FakeLifecycleSnapshot();
        slotSnapshot = new FakeSlotSnapshot();
    }

    @Nested @DisplayName("Local event monotonicity under backward physical-clock steps")
    class LocalMonotonicity {
        /// Feeds two `SlotClaimed` events whose underlying physical clock STEPS BACKWARD on the
        /// second invocation. The reducer stamps each emitted `NodeLifecycleValue` with the
        /// event's HLC. The clock's logical counter preserves strict monotonicity even when
        /// physical time regresses — asserted on the captured transitionedAt sequence.
        @Test void backwardPhysicalStep_observedTransitionedAtRemainsMonotonic() {
            // Physical clock: first call returns 2_000_000us, all later calls return 1_500_000us
            // (backward step). The HlcClock's counter MUST advance to preserve monotonicity.
            var physical = new SteppedPhysicalClock(2_000_000L, 1_500_000L, 1_500_000L, 1_500_000L);
            var clock = HlcClock.hlcClock(SELF, physical, 500_000L);
            var reducer = ClusterMembershipReducer.clusterMembershipReducer(MembershipFsmConfig.defaultMembershipFsmConfig());

            var firstAt = clock.now();
            var firstOutcome = reducer.apply(MembershipFsmState.untracked(PEER_A),
                                              new SlotClaimed(PEER_A, SLOT_A, firstAt));
            var secondAt = clock.now();
            var secondOutcome = reducer.apply(MembershipFsmState.untracked(PEER_B),
                                               new SlotClaimed(PEER_B, SLOT_B, secondAt));

            var firstWrite = (NodeLifecycleValue) ((KVCommand.Put<?, ?>) firstOutcome.writes().get(0)).value();
            var secondWrite = (NodeLifecycleValue) ((KVCommand.Put<?, ?>) secondOutcome.writes().get(0)).value();

            assertThat(firstWrite.transitionedAt().compareTo(secondWrite.transitionedAt())).isLessThan(0);
            assertThat(secondWrite.transitionedAt()).isEqualTo(secondAt);
        }

        /// Burst of events under a frozen physical clock — counter increments alone preserve
        /// strict monotonicity across the sequence. Asserts on the `clock.now()` values that
        /// the FSM would stamp events with (rather than re-reading from peer state, which
        /// truncates HLC to wall-clock millis).
        @Test void frozenPhysicalClock_burst_hlcCounterMonotonic() {
            var physical = new SteppedPhysicalClock(5_000_000L, 5_000_000L, 5_000_000L, 5_000_000L);
            var clock = HlcClock.hlcClock(SELF, physical, 500_000L);
            // Start FSM so that downstream wiring is exercised, but rely on direct `clock.now()`
            // for the strict-counter assertion (which the FSM internally uses the same way).
            startedFsmWithClock(clock);

            var observed = new ArrayList<HlcTimestamp>();
            observed.add(clock.now());
            observed.add(clock.now());
            observed.add(clock.now());

            // Physical micros frozen → counter must strictly advance each call.
            assertThat(observed.get(0).physicalMicros()).isEqualTo(observed.get(1).physicalMicros());
            assertThat(observed.get(1).physicalMicros()).isEqualTo(observed.get(2).physicalMicros());
            assertThat(observed.get(1).counter()).isGreaterThan(observed.get(0).counter());
            assertThat(observed.get(2).counter()).isGreaterThan(observed.get(1).counter());
        }
    }

    @Nested @DisplayName("Cross-node HLC merge via NodeLifecyclePut")
    class CrossNodeMerge {
        /// A remote KV-put carrying an HLC slightly ahead of local time advances the local clock
        /// (verified by the next local event having `physicalMicros >= remote.physicalMicros`).
        @Test void remotePutWithInRangeHlc_advancesLocalClock_admitsPut() {
            var physical = new SteppedPhysicalClock(2_000_000L);
            var clock = HlcClock.hlcClock(SELF, physical, 500_000L);
            var fsm = startedFsmWithClock(clock);

            // Remote at 2_200_000us = 200ms ahead — within 500ms drift budget.
            var remoteAt = new HlcTimestamp(HlcTimestamp.pack(2_200_000L, 0), PEER_A);
            var put = new ValuePut<NodeLifecycleKey, NodeLifecycleValue>(
                    new KVCommand.Put<>(NodeLifecycleKey.nodeLifecycleKey(PEER_A),
                                          new NodeLifecycleValue(NodeLifecycleState.ON_DUTY,
                                                                  2_200L,
                                                                  "",
                                                                  0,
                                                                  Epoch.ZERO,
                                                                  remoteAt,
                                                                  ProvisioningSource.UNKNOWN)),
                    Option.none());
            fsm.onNodeLifecyclePut(put);

            // Put admitted: peer is tracked as ON_DUTY.
            assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(MembershipFsmState.OnDuty.class);
            // Local clock advanced: next local `now()` returns a timestamp ≥ remoteAt.
            assertThat(clock.peek().compareTo(remoteAt)).isGreaterThanOrEqualTo(0);
        }

        /// A remote KV-put carrying an HLC beyond the drift budget is DROPPED — peer state is
        /// NOT updated and the local clock is NOT advanced to the drifted remote value.
        @Test void remotePutWithDriftExceedingHlc_isDropped_stateUnchanged() {
            var physical = new SteppedPhysicalClock(2_000_000L);
            var clock = HlcClock.hlcClock(SELF, physical, 500_000L);
            var fsm = startedFsmWithClock(clock);

            // Remote at 3_000_000us = 1s ahead — exceeds 500ms drift budget.
            var remoteAt = new HlcTimestamp(HlcTimestamp.pack(3_000_000L, 0), PEER_A);
            var put = new ValuePut<NodeLifecycleKey, NodeLifecycleValue>(
                    new KVCommand.Put<>(NodeLifecycleKey.nodeLifecycleKey(PEER_A),
                                          new NodeLifecycleValue(NodeLifecycleState.ON_DUTY,
                                                                  3_000L,
                                                                  "",
                                                                  0,
                                                                  Epoch.ZERO,
                                                                  remoteAt,
                                                                  ProvisioningSource.UNKNOWN)),
                    Option.none());
            fsm.onNodeLifecyclePut(put);

            // Drop policy: peer is NOT tracked, local clock is NOT advanced to drifted remote.
            assertThat(fsm.get(PEER_A)).isEqualTo(Option.<MembershipFsmState>none());
            assertThat(clock.peek().physicalMicros()).isLessThan(remoteAt.physicalMicros());
        }

        /// A remote KV-put with the sentinel `HlcTimestamp.ZERO` (pre-RC1 entries / un-stamped
        /// writers) is admitted without a clock merge — preserves backward compatibility.
        @Test void remotePutWithZeroSentinelHlc_admittedWithoutMerge() {
            var physical = new SteppedPhysicalClock(2_000_000L);
            var clock = HlcClock.hlcClock(SELF, physical, 500_000L);
            var fsm = startedFsmWithClock(clock);
            var localBefore = clock.peek();

            var put = new ValuePut<NodeLifecycleKey, NodeLifecycleValue>(
                    new KVCommand.Put<>(NodeLifecycleKey.nodeLifecycleKey(PEER_A),
                                          NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY, 1_000L)),
                    Option.none());
            fsm.onNodeLifecyclePut(put);

            assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(MembershipFsmState.OnDuty.class);
            assertThat(clock.peek()).isEqualTo(localBefore);
        }
    }

    @Nested @DisplayName("Reducer-emitted KV writes carry the event's HLC in transitionedAt")
    class TransitionedAtPropagation {
        @Test void slotClaimed_writesNodeLifecycleValue_withEventHlcInTransitionedAt() {
            var physical = new SteppedPhysicalClock(7_000_000L);
            var clock = HlcClock.hlcClock(SELF, physical, 500_000L);
            // Use a reducer factory test path so we can inspect emitted writes directly.
            var reducer = ClusterMembershipReducer.clusterMembershipReducer(MembershipFsmConfig.defaultMembershipFsmConfig());
            var eventAt = clock.now();
            var outcome = reducer.apply(MembershipFsmState.untracked(PEER_A),
                                         new SlotClaimed(PEER_A, SLOT_A, eventAt));

            // Phase 1 step J co-write: lifecycle Put (JOINING) + JoinDeadlineKey Put.
            assertThat(outcome.writes()).hasSize(2);
            var lifecyclePut = outcome.writes().stream()
                                                .filter(c -> c instanceof KVCommand.Put<?, ?> p
                                                             && p.value() instanceof NodeLifecycleValue)
                                                .map(c -> (KVCommand.Put<?, ?>) c)
                                                .findFirst()
                                                .orElseThrow();
            var value = (NodeLifecycleValue) lifecyclePut.value();
            assertThat(value.state()).isEqualTo(NodeLifecycleState.JOINING);
            assertThat(value.transitionedAt()).isEqualTo(eventAt);
            assertThat(value.updatedAt()).isEqualTo(eventAt.physicalMicros() / 1000L);
        }
    }

    private MembershipFsm startedFsmWithClock(HlcClock clock) {
        var config = MembershipFsmConfig.defaultMembershipFsmConfig();
        var reducer = ClusterMembershipReducer.clusterMembershipReducer(config);
        var fsm = MembershipFsm.membershipFsm(SELF,
                                               config,
                                               reducer,
                                               lifecycleSnapshot,
                                               slotSnapshot,
                                               clock);
        fsm.start().await();
        return fsm;
    }

    /// Deterministic physical-clock source that emits a pre-programmed sequence of microsecond
    /// values. Once exhausted, returns the last value forever (so chained `now()` calls keep
    /// the clock frozen rather than racing system time).
    private static final class SteppedPhysicalClock implements java.util.function.LongSupplier {
        private final long[] values;
        private final AtomicLong cursor = new AtomicLong(0);

        SteppedPhysicalClock(long... values) {
            this.values = values;
        }

        @Override public long getAsLong() {
            var idx = (int) Math.min(cursor.getAndIncrement(), values.length - 1L);
            return values[idx];
        }
    }

    private static final class FakeLifecycleSnapshot implements LifecycleSnapshotReader {
        private final Map<NodeLifecycleKey, NodeLifecycleValue> entries = new LinkedHashMap<>();

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
}

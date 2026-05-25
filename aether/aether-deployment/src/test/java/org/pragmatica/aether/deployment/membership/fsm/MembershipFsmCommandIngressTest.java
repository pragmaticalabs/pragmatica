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
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmState.OnDuty;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmState.Stopped;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StopReason;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;


/// Sovereign command ingress (#230, S01 re-projection fix). Verifies that
/// `MembershipFsm.applyLifecycleCommand` routes a `ForceOnDuty` through the reducer, so a
/// force-decommissioned (`STOPPED + FORCED`) peer is NOT re-promoted to `ON_DUTY` — the bug
/// `DirectLifecycleWriter` exhibits (see `DirectLifecycleWriterForcedTombstoneTest`). Both the
/// in-`fsmStates` path and the `resolveState`-from-KV-on-miss path are covered, plus positive
/// controls (a legal JOINING→ON_DUTY promotion still works; a non-leader is gated).
class MembershipFsmCommandIngressTest {
    private static final NodeId SELF = NodeId.nodeId("self-node").unwrap();
    private static final NodeId PEER_A = NodeId.nodeId("peer-a").unwrap();

    private FakeLifecycleSnapshot lifecycleSnapshot;
    private RecordingCommandApplier commandApplier;
    private AtomicBoolean leaderFlag;

    @BeforeEach
    void setUp() {
        lifecycleSnapshot = new FakeLifecycleSnapshot();
        commandApplier = new RecordingCommandApplier();
        leaderFlag = new AtomicBoolean(true);
    }

    private MembershipFsm startedFsm() {
        var fsm = MembershipFsm.membershipFsm(SELF,
                                              MembershipFsmConfig.defaultMembershipFsmConfig(),
                                              lifecycleSnapshot,
                                              new FakeSlotSnapshot(),
                                              commandApplier,
                                              new NoOpDrainCoordinator(),
                                              new NoOpScheduler(),
                                              (BooleanSupplier) leaderFlag::get);
        fsm.start().await();
        return fsm;
    }

    private static NodeLifecycleValue forciblyStopped(long updatedAt) {
        return NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.STOPPED, updatedAt)
                                 .withStopReason(Option.some(StopReason.FORCED));
    }

    private static ForceOnDuty forceOnDuty(NodeId peer) {
        return new ForceOnDuty(peer, Causes.cause("stale readyCandidate"), HlcTimestamp.ZERO);
    }

    @Test
    void forceOnDuty_onForciblyStoppedPeer_inFsmStates_isNoOp() {
        // Seeded before start(): replay derives Stopped(FORCED) into fsmStates.
        lifecycleSnapshot.put(PEER_A, forciblyStopped(System.currentTimeMillis()));
        var fsm = startedFsm();
        assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(Stopped.class);
        commandApplier.calls.clear();

        var accepted = fsm.applyLifecycleCommand(forceOnDuty(PEER_A)).await().unwrap();

        assertThat(accepted).as("ForceOnDuty against a STOPPED+FORCED peer must be rejected").isFalse();
        assertThat(commandApplier.calls).as("no ON_DUTY write may be proposed").isEmpty();
        assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(Stopped.class);
    }

    @Test
    void forceOnDuty_onForciblyStoppedPeer_absentFromFsmStates_resolvesFromKvAndIsNoOp() {
        // Seeded AFTER start(): fsmStates has no entry, but KV retains STOPPED+FORCED.
        // resolveState must re-derive Stopped from KV rather than default to Untracked.
        var fsm = startedFsm();
        assertThat(fsm.get(PEER_A).isEmpty()).isTrue();
        lifecycleSnapshot.put(PEER_A, forciblyStopped(System.currentTimeMillis()));
        commandApplier.calls.clear();

        var accepted = fsm.applyLifecycleCommand(forceOnDuty(PEER_A)).await().unwrap();

        assertThat(accepted).as("resolveState must see the retained STOPPED+FORCED and reject").isFalse();
        assertThat(commandApplier.calls).isEmpty();
    }

    @Test
    void forceOnDuty_onJoiningPeer_promotesToOnDuty() {
        lifecycleSnapshot.put(PEER_A, NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.JOINING,
                                                                            System.currentTimeMillis()));
        var fsm = startedFsm();
        commandApplier.calls.clear();

        var accepted = fsm.applyLifecycleCommand(forceOnDuty(PEER_A)).await().unwrap();

        assertThat(accepted).as("a legal JOINING→ON_DUTY promotion must be accepted").isTrue();
        assertThat(fsm.get(PEER_A).unwrap()).isInstanceOf(OnDuty.class);
        assertThat(lifecycleStatesWritten()).contains(NodeLifecycleState.ON_DUTY);
    }

    @Test
    void forceOnDuty_onNonLeader_isNoOp() {
        leaderFlag.set(false);
        var fsm = startedFsm();
        commandApplier.calls.clear();

        var accepted = fsm.applyLifecycleCommand(forceOnDuty(PEER_A)).await().unwrap();

        assertThat(accepted).as("a non-leader must not write lifecycle (single-writer)").isFalse();
        assertThat(commandApplier.calls).isEmpty();
    }

    private List<NodeLifecycleState> lifecycleStatesWritten() {
        var states = new ArrayList<NodeLifecycleState>();
        for (var batch : commandApplier.calls) {
            for (var command : batch) {
                if (command instanceof Put<?, ?> put && put.value() instanceof NodeLifecycleValue value) {
                    states.add(value.state());
                }
            }
        }
        return states;
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
        @Override public void forEachSlot(BiConsumer<AetherKey.ProvisioningSlotKey,
                                                      org.pragmatica.aether.slice.kvstore.AetherValue.ProvisioningSlotValue> consumer) {
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

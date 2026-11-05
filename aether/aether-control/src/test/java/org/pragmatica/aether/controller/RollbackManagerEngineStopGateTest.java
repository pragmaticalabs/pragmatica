// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.controller;

import io.netty.buffer.ByteBuf;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.config.RollbackConfig;
import org.pragmatica.aether.controller.RollbackManagerGuardedRecordTest.HeldClusterNode;
import org.pragmatica.aether.controller.RollbackManagerOverridePreservationTest.AlwaysLeaderManager;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.PreviousVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.PreviousVersionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.utility.react.ReactExecutor;
import org.pragmatica.utility.react.ReactFailureSink;
import org.pragmatica.utility.react.ReactGate;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningSink;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/// Exit (ab) for §3.5.1 row 19 (KVStore dispatcher design; D1-react): RollbackManager.onSliceTargetPut keeps the mirror and
/// the version-change count in absorb, and its record derivation (HEAD reads plus the guarded submit) and every refusal
/// retry (§3.4.1 item 3) run behind the engine-alive gate. The seam that records the call is the [HeldClusterNode]'s
/// capture: a derivation that ran submits one command list.
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class RollbackManagerEngineStopGateTest {
    private static final String COMPONENT = "RollbackManager";
    private static final NodeId SELF = NodeId.nodeId("node-1").unwrap();
    private static final ArtifactBase BASE = Artifact.artifact("org.test:gated-slice:1.0.0").unwrap().base();
    private static final SliceTargetKey TARGET_KEY = SliceTargetKey.sliceTargetKey(BASE);
    private static final Version X = Version.version("0.9.0").unwrap();
    private static final Version A = Version.version("1.0.0").unwrap();
    private static final Version B = Version.version("2.0.0").unwrap();

    /// The react executor under test, its engine flag (the gate's engine half) and the hold seam (§5 (ab): "hold the
    /// component's react executor, a latch on its next task"). The hold is an UNDO task, so the gate never drops it.
    private final AtomicBoolean engineStopped = new AtomicBoolean(false);
    private final ReactExecutor executor = ReactExecutor.reactExecutor(COMPONENT,
                                                                       ReactGate.reactGate(() -> !engineStopped.get(),
                                                                                           () -> false),
                                                                       ReactFailureSink.loggingOnly());
    private final List<OperatorWarning> warnings = new CopyOnWriteArrayList<>();
    private KVStore<AetherKey, AetherValue> store;
    private HeldClusterNode held;
    private SliceTargetValue targetA;
    private RollbackManager manager;

    private CountDownLatch hold() throws InterruptedException {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        executor.undo("test-hold", () -> awaitQuietly(started, release));
        assertThat(started.await(10, TimeUnit.SECONDS)).as("hold task started").isTrue();

        return release;
    }

    private void drain() throws InterruptedException {
        var done = new CountDownLatch(1);

        executor.undo("test-drain", done::countDown);
        assertThat(done.await(10, TimeUnit.SECONDS)).as("executor drained").isTrue();
    }

    private static void awaitQuietly(CountDownLatch started, CountDownLatch release) {
        started.countDown();
        try {
            release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @BeforeEach
    void setUp() {
        store = new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
        held = new HeldClusterNode(SELF, store);
        targetA = SliceTargetValue.sliceTargetValue(A, 3);

        RollbackManagerOverridePreservationTest.seedCommittedLeader(store, SELF, 1);
        commit(TARGET_KEY, targetA);
        commit(PreviousVersionKey.previousVersionKey(BASE), PreviousVersionValue.previousVersionValue(BASE, X, A));
        manager = RollbackManager.rollbackManager(SELF,
                                                  () -> RollbackConfig.rollbackConfig(),
                                                  held,
                                                  store,
                                                  new AlwaysLeaderManager(SELF),
                                                  _ -> {},
                                                  OperatorWarningSink.handingOffTo(warnings::add));
        manager.installReactExecutor(executor);
        manager.activate();
    }

    @AfterEach
    void tearDown() {
        executor.close();
    }

    @Test
    void sliceTargetPut_deriveQueuedBeforeEngineStop_isDroppedAndCounted() throws InterruptedException {
        var targetB = commit(TARGET_KEY, targetA.withVersion(B));
        var release = hold();

        manager.onSliceTargetPut(put(targetB, targetA));
        assertThat(executor.queueDepth()).as("the derivation is queued, so the probe reaches the gate").isEqualTo(1);
        assertThat(held.captured()).as("absorb submits nothing").isEmpty();
        engineStopped.set(true);
        release.countDown();
        drain();
        held.settle();

        assertThat(held.captured()).as("no record write submitted after the engine stop").isEmpty();
        assertThat(executor.droppedEngineStopped()).as("dropped count rose by exactly 1").isEqualTo(1);
    }

    @Test
    void sliceTargetPut_engineAlive_submitsOneRecordWrite_dropsNothing() throws InterruptedException {
        var targetB = commit(TARGET_KEY, targetA.withVersion(B));
        var release = hold();

        manager.onSliceTargetPut(put(targetB, targetA));
        assertThat(executor.queueDepth()).isEqualTo(1);
        release.countDown();
        drain();
        held.settle();

        assertThat(held.captured()).as("control arm: exactly one record write").hasSize(1);
        assertThat(executor.droppedEngineStopped()).isZero();
    }

    /// The bounded retry is behind the gate too: a refusal answered after an engine stop queues its retry, which is dropped,
    /// so no attempt is burnt against a dead engine and no exhaustion warning follows.
    @Test
    void refusedWrite_retryQueuedBeforeEngineStop_isDroppedAndCounted() throws InterruptedException {
        var targetB = deliverVersionChange();
        var release = hold();

        refuseFirstWrite(targetB);
        awaitQueued();
        engineStopped.set(true);
        release.countDown();
        drain();
        held.settle();

        assertThat(held.captured()).as("no retry submitted after the engine stop").hasSize(1);
        assertThat(executor.droppedEngineStopped()).as("dropped count rose by exactly 1").isEqualTo(1);
        assertThat(warnings).as("no attempt burnt, no exhaustion warning").isEmpty();
    }

    @Test
    void refusedWrite_engineAlive_retrySubmitsOnce_dropsNothing() throws InterruptedException {
        var targetB = deliverVersionChange();
        var release = hold();

        refuseFirstWrite(targetB);
        awaitQueued();
        release.countDown();
        drain();
        held.settle();

        assertThat(held.captured()).as("control arm: the retry re-derived and submitted").hasSize(2);
        assertThat(executor.droppedEngineStopped()).isZero();
    }

    private SliceTargetValue deliverVersionChange() throws InterruptedException {
        var targetB = commit(TARGET_KEY, targetA.withVersion(B));

        manager.onSliceTargetPut(put(targetB, targetA));
        drain();
        held.awaitCaptured(1);

        return targetB;
    }

    /// A scale change commits (the target moves, the version does not), so the held (A, B) write is refused by its witness.
    private void refuseFirstWrite(SliceTargetValue targetB) {
        commit(TARGET_KEY, targetB.withInstances(targetB.targetInstances() + 1));
        held.applyToStore(0);
    }

    private void awaitQueued() throws InterruptedException {
        var deadline = System.nanoTime() + 5_000_000_000L;

        while (executor.queueDepth() < 1 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(executor.queueDepth()).as("the retry is queued, so the probe reaches the gate").isEqualTo(1);
    }

    private <V extends AetherValue> V commit(AetherKey key, V value) {
        store.process(store.createBatch(List.<KVCommand<AetherKey>>of(new KVCommand.Put<AetherKey, AetherValue>(key, value))));

        return value;
    }

    private static ValuePut<SliceTargetKey, SliceTargetValue> put(SliceTargetValue value, SliceTargetValue replaced) {
        return new ValuePut<>(new KVCommand.Put<>(TARGET_KEY, value), Option.some(replaced));
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }
}

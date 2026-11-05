// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.controller.fsm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.controller.ControlLoop;
import org.pragmatica.aether.controller.ControllerConfig;
import org.pragmatica.aether.controller.fsm.ControlLoopEvents.ActivationTimeReached;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.utility.react.ReactExecutor;
import org.pragmatica.utility.react.ReactFailureSink;
import org.pragmatica.utility.react.ReactGate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/// Exit (ab) and the row's U2 arm for §3.5.1 row 39 (KVStore dispatcher design; D1-react): ControlLoop.onNodeArtifactPut's
/// whole slice cooldown mark (map put plus `CooldownRequested`, whose transition arms the evaluation and expiry timers)
/// runs behind the engine-alive gate. The seam that records the call is the cooldown map plus the FSM state: a mark that
/// ran leaves the slice in [ControlLoopContext#sliceCooldownsSnapshot] and the loop in [ControlLoopState.Cooldown].
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ControlLoopEngineStopGateTest {
    private static final String COMPONENT = "ControlLoop";
    private static final NodeId SELF = NodeId.nodeId("ctl-loop-gate").unwrap();
    private static final NodeId HOST = NodeId.nodeId("ctl-loop-host").unwrap();
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:gated:1.0.0").unwrap();

    /// The react executor under test, its engine flag (the gate's engine half) and the hold seam (§5 (ab): "hold the
    /// component's react executor, a latch on its next task"). The hold is an UNDO task, so the gate never drops it.
    private final AtomicBoolean engineStopped = new AtomicBoolean(false);
    private final ReactExecutor executor = ReactExecutor.reactExecutor(COMPONENT,
                                                                       ReactGate.reactGate(() -> !engineStopped.get(),
                                                                                           () -> false),
                                                                       ReactFailureSink.loggingOnly());
    private final ControlLoop.ControlLoopAdapter loop = (ControlLoop.ControlLoopAdapter) ControlLoop.controlLoop(SELF,
                                                                                                               ControlLoopFsmTest.StubController.INSTANCE,
                                                                                                               new ControlLoopFsmTest.StubMetricsCollector(),
                                                                                                               Option.none(),
                                                                                                               new ControlLoopFsmTest.StubClusterNode(),
                                                                                                               FencedTargetWrites.fencedStore(SELF),
                                                                                                               TimeSpan.timeSpan(60_000)
                                                                                                                       .millis(),
                                                                                                               ControllerConfig.DEFAULT,
                                                                                                               _ -> {});

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
        loop.installReactExecutor(executor);
        loop.activate();
        loop.fsm().dispatch(new ActivationTimeReached());
        assertThat(loop.fsm().current()).as("precondition: evaluating").isInstanceOf(ControlLoopState.Evaluating.class);
    }

    @AfterEach
    void tearDown() {
        loop.stop();
        executor.close();
    }

    @Test
    void nodeArtifactActive_cooldownQueuedBeforeEngineStop_isDroppedAndCounted() throws InterruptedException {
        var release = hold();

        loop.onNodeArtifactPut(active());
        assertThat(executor.queueDepth()).as("the cooldown mark is queued, so the probe reaches the gate").isEqualTo(1);
        engineStopped.set(true);
        release.countDown();
        drain();

        assertThat(loop.ctx().sliceCooldownsSnapshot()).as("no cooldown marked after the engine stop").isEmpty();
        assertThat(loop.fsm().current()).as("no cooldown timers armed").isInstanceOf(ControlLoopState.Evaluating.class);
        assertThat(executor.droppedEngineStopped()).as("dropped count rose by exactly 1").isEqualTo(1);
    }

    @Test
    void nodeArtifactActive_engineAlive_marksOneCooldown_dropsNothing() throws InterruptedException {
        var release = hold();

        loop.onNodeArtifactPut(active());
        assertThat(executor.queueDepth()).isEqualTo(1);
        release.countDown();
        drain();

        assertThat(loop.ctx().sliceCooldownsSnapshot()).as("control arm: exactly one cooldown").containsOnlyKeys(ARTIFACT);
        assertThat(loop.fsm().current()).as("control arm: the cooldown transition ran").isInstanceOf(ControlLoopState.Cooldown.class);
        assertThat(executor.droppedEngineStopped()).isZero();
    }

    /// U2: the protection reset (Warmup entry after a deactivate/activate) runs in absorb after the mark was queued; the mark,
    /// run later, sees the generation moved and marks nothing, so no cooldown predating the reset survives it.
    @Test
    void nodeArtifactActive_protectionResetBeforeMarkRuns_leavesNoCooldown() throws InterruptedException {
        var release = hold();

        loop.onNodeArtifactPut(active());
        loop.deactivate();
        loop.activate();
        assertThat(loop.fsm().current()).as("Warmup entry ran the reset").isInstanceOf(ControlLoopState.Warmup.class);
        release.countDown();
        drain();

        assertThat(loop.ctx().sliceCooldownsSnapshot()).as("no cooldown survives the reset it predates").isEmpty();
    }

    /// v-2653 (verifier): the dispatch runs only when the put landed. A reset before the mark, then the loop back in
    /// Evaluating before the reaction runs: the stale reaction neither marks nor transitions to Cooldown.
    @Test
    void protectionResetThenEvaluating_beforeMarkRuns_noCooldownTransition() throws InterruptedException {
        var release = hold();

        loop.onNodeArtifactPut(active());
        loop.deactivate();
        loop.activate();
        loop.fsm().dispatch(new ActivationTimeReached());
        assertThat(loop.fsm().current()).as("precondition: evaluating again").isInstanceOf(ControlLoopState.Evaluating.class);
        release.countDown();
        drain();

        assertThat(loop.ctx().sliceCooldownsSnapshot()).isEmpty();
        assertThat(loop.fsm().current()).as("a refused mark dispatches nothing").isInstanceOf(ControlLoopState.Evaluating.class);
    }

    private static ValuePut<NodeArtifactKey, NodeArtifactValue> active() {
        return new ValuePut<>(new KVCommand.Put<>(NodeArtifactKey.nodeArtifactKey(HOST, ARTIFACT),
                                                  NodeArtifactValue.nodeArtifactValue(SliceState.ACTIVE)),
                              Option.none());
    }
}

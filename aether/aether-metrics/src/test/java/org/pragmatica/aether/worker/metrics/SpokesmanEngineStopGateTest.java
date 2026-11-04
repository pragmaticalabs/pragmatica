// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.worker.metrics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey.SpokesmanKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.SpokesmanStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SpokesmanValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.NetworkServiceMessage.Broadcast;
import org.pragmatica.consensus.net.NetworkServiceMessage.ConnectNode;
import org.pragmatica.consensus.net.NetworkServiceMessage.DisconnectNode;
import org.pragmatica.consensus.net.NetworkServiceMessage.ListConnectedNodes;
import org.pragmatica.consensus.net.NetworkServiceMessage.Send;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.Server;
import org.pragmatica.utility.react.ReactExecutor;
import org.pragmatica.utility.react.ReactFailureSink;
import org.pragmatica.utility.react.ReactGate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/// Exit (ab+) row 72 (KVStore dispatcher design §5, §3.5.1 rows 72/73; D1-react): a Spokesman Put(ASSIGNED) then Remove,
/// both absorbed before the activation runs, leave no ping timer and no `writeActive`; the deactivation is an ungated undo
/// on the same executor, so an engine stop cannot leak the timer.
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class SpokesmanEngineStopGateTest {
    private static final String COMPONENT = "SpokesmanPingLoop";
    private static final NodeId SELF = NodeId.nodeId("core-1").unwrap();
    private static final NodeId GOVERNOR = NodeId.nodeId("gov-a").unwrap();

    /// The react executor under test, its engine flag (the gate's engine half) and the hold seam (§5 (ab): "hold the
    /// component's react executor, a latch on its next task"). The hold is an UNDO task, so the gate never drops it.
    private final AtomicBoolean engineStopped = new AtomicBoolean(false);
    private final ReactExecutor executor = ReactExecutor.reactExecutor(COMPONENT,
                                                                       ReactGate.reactGate(() -> !engineStopped.get(),
                                                                                           () -> false),
                                                                       ReactFailureSink.loggingOnly());

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

    private final List<SpokesmanValue> activeWrites = new CopyOnWriteArrayList<>();
    private SpokesmanPingLoop loop;

    @BeforeEach
    void setUp() {
        loop = SpokesmanPingLoop.spokesmanPingLoop(SELF,
                                                   new NoopNetwork(),
                                                   TimeSpan.timeSpan(1).hours(),
                                                   () -> 7L,
                                                   Map::of,
                                                   _ -> Option.some(GOVERNOR),
                                                   new RecordingWriter(activeWrites));
        loop.installReactExecutor(executor);
        loop.start();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        loop.stop();
        drain();
        executor.close();
    }

    @Test
    void assignedThenRemove_beforeActivationRuns_leavesNoTimerAndNoWrite() throws InterruptedException {
        var release = hold();

        loop.onSpokesmanPut(assigned());
        loop.onSpokesmanRemove(remove());
        release.countDown();
        drain();

        assertThat(loop.isActive()).as("the remove's deactivation ran after the activation (FIFO): no timer").isFalse();
        assertThat(activeWrites).as("a removed assignment is never resurrected as ACTIVE").isEmpty();
    }

    @Test
    void activeThenRemove_engineStoppedBeforeRelease_undoStillRuns() throws InterruptedException {
        loop.onSpokesmanPut(assigned());
        drain();
        assertThat(loop.isActive()).as("precondition: activated while the engine is alive").isTrue();
        var release = hold();

        loop.onSpokesmanRemove(remove());
        engineStopped.set(true);
        release.countDown();
        drain();

        assertThat(loop.isActive()).as("the deactivation is an undo: never gated").isFalse();
        assertThat(executor.droppedEngineStopped()).isZero();
    }

    @Test
    void assigned_engineAlive_activatesAndWritesOnce() throws InterruptedException {
        var release = hold();

        loop.onSpokesmanPut(assigned());
        release.countDown();
        drain();

        assertThat(loop.isActive()).as("control arm").isTrue();
        assertThat(activeWrites).hasSize(1);
    }

    /// v-2653 (verifier): `stop()`'s deactivation is an undo (U1): with the gate closed (node stopping or engine stopped)
    /// it still runs, so the ping timer cannot outlive the stop.
    @Test
    void stop_withGateClosed_stillDeactivates() throws InterruptedException {
        loop.onSpokesmanPut(assigned());
        drain();
        assertThat(loop.isActive()).as("precondition: activated while the engine is alive").isTrue();

        engineStopped.set(true);
        loop.stop();
        drain();

        assertThat(loop.isActive()).as("stop's deactivation is never gated").isFalse();
        assertThat(executor.droppedEngineStopped()).isZero();
    }

    /// v-2653 (verifier): an emptied or FAILED assignment deactivates through an ungated undo (U1).
    @Test
    void failedAssignment_engineStoppedBeforeRelease_undoStillRuns() throws InterruptedException {
        loop.onSpokesmanPut(assigned());
        drain();
        var release = hold();

        loop.onSpokesmanPut(withValue(assigned().cause().value().withFailure("x")));
        engineStopped.set(true);
        release.countDown();
        drain();

        assertThat(loop.isActive()).as("the FAILED put's deactivation is never gated").isFalse();
        assertThat(executor.droppedEngineStopped()).isZero();
    }

    /// v-2653 (verifier): a Put(ACTIVE) activation is a side effect behind the gate (row 72), like Put(ASSIGNED).
    @Test
    void activeStatus_queuedBeforeEngineStop_isDropped() throws InterruptedException {
        var release = hold();

        loop.onSpokesmanPut(withValue(assigned().cause().value().withStatus(SpokesmanStatus.ACTIVE)));
        engineStopped.set(true);
        release.countDown();
        drain();

        assertThat(loop.isActive()).as("no ping timer armed after the engine stop").isFalse();
        assertThat(executor.droppedEngineStopped()).isEqualTo(1);
    }

    /// v-2653 (verifier): U2 on rows 72/73 counts a later PUT as well as a remove: Put(ASSIGNED) then Put(FAILED), both
    /// absorbed before the activation runs, write no ACTIVE status.
    @Test
    void assignedThenFailedPut_beforeActivationRuns_writesNoActive() throws InterruptedException {
        var release = hold();

        loop.onSpokesmanPut(assigned());
        loop.onSpokesmanPut(withValue(assigned().cause().value().withFailure("x")));
        release.countDown();
        drain();

        assertThat(activeWrites).as("a superseded assignment is never written ACTIVE").isEmpty();
        assertThat(loop.isActive()).isFalse();
    }

    private static ValuePut<SpokesmanKey, SpokesmanValue> withValue(SpokesmanValue value) {
        return new ValuePut<>(new KVCommand.Put<>(SpokesmanKey.spokesmanKey(SELF), value), Option.none());
    }

    private static ValuePut<SpokesmanKey, SpokesmanValue> assigned() {
        var value = SpokesmanValue.spokesmanValue(List.of("pool-a"), Epoch.ZERO, HlcTimestamp.ZERO, 1L);

        return new ValuePut<>(new KVCommand.Put<>(SpokesmanKey.spokesmanKey(SELF), value), Option.none());
    }

    private static ValueRemove<SpokesmanKey, SpokesmanValue> remove() {
        return new ValueRemove<>(new KVCommand.Remove<>(SpokesmanKey.spokesmanKey(SELF)), Option.none());
    }

    private record RecordingWriter(List<SpokesmanValue> activeWrites) implements SpokesmanPingLoop.SpokesmanStatusWriter {
        @Override
        public void writeActive(NodeId self, SpokesmanValue baseValue) {
            activeWrites.add(baseValue);
        }

        @Override
        public void writeFailure(NodeId self, SpokesmanValue baseValue, String reason) {}
    }

    private static final class NoopNetwork implements ClusterNetwork {
        @Override public <M extends ProtocolMessage> Unit broadcast(M message) {return Unit.unit();}
        @Override public void connect(ConnectNode connectNode) {}
        @Override public void disconnect(DisconnectNode disconnectNode) {}
        @Override public void listNodes(ListConnectedNodes listConnectedNodes) {}
        @Override public void handleSend(Send send) {}
        @Override public void handleBroadcast(Broadcast broadcast) {}
        @Override public <M extends ProtocolMessage> Promise<WriteOutcome> send(NodeId nodeId, M message) {return Promise.success(new WriteOutcome.Sent(nodeId));}
        @Override public <M extends ProtocolMessage> Promise<WriteOutcome> send(NodeId nodeId, M message, TimeSpan offlineTtl) {return send(nodeId, message);}
        @Override public Promise<Unit> start() {return Promise.success(Unit.unit());}
        @Override public Promise<Unit> stop() {return Promise.success(Unit.unit());}
        @Override public int connectedNodeCount() {return 0;}
        @Override public Set<NodeId> connectedPeers() {return Set.of();}
        @Override public Option<Server> server() {return Option.none();}
    }
}

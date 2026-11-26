// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.deployment.membership.fsm.WorkerJoinDecision;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.GenerationSnapshotSource;
import org.pragmatica.consensus.topology.MembershipView;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #3754 lane (b1), the gate, grace and monitor rows (TB4, TB4r, TB4s, TB5', TB5c, TB5d, TB5f, TB5h). Every witness answer is
/// driven through a sink double that holds the request open and answers it through the real [DrainSink#synchronous] adapter, or
/// through a recording answer, so the manager's own gate, entry and grace timer are exercised and the pending map is never read
/// or written directly. The registry is a set the sink adds to and the DRAIN-command clear removes from, as the production
/// registry behaves.
class ClusterTopologyManagerDrainFenceTest {
    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId TARGET = nodeId("node-a").unwrap();
    private static final NodeInfo INFO_SELF = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("localhost", 5000).unwrap());
    private static final HlcTimestamp HLC = new HlcTimestamp(HlcTimestamp.pack(1L, 0), SELF);
    private static final Cause WITNESS_REFUSED = Causes.cause("witness refused");
    private static final Cause SINK_FAILED = Causes.cause("sink failed after the gate ran");
    private static final TimeSpan LONG_GRACE = timeSpan(1).hours();

    private final Set<NodeId> registry = ConcurrentHashMap.newKeySet();
    private final CopyOnWriteArrayList<NodeId> registryAdds = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<NodeId> fsmCalls = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<NodeId> clears = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<NodeId> terminated = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Boolean> fsmHeldTheMonitor = new CopyOnWriteArrayList<>();
    private ClusterTopologyManager ctm;

    @BeforeEach
    void resetRecordings() {
        registry.clear();
        registryAdds.clear();
        fsmCalls.clear();
        clears.clear();
        terminated.clear();
        fsmHeldTheMonitor.clear();
    }

    /// TB4: a refused witness leaves no entry and no timer, so the grace expiry reaps nothing; a second drain opens its own request.
    @Test
    void refusedWitness_leavesNoEntryAndNoTimer_graceExpiryReapsNothing() {
        var sink = witnessSink(this::addToRegistry);

        ctm = newCtm(sink, timeSpan(300).millis());
        var outcome = ctm.drainNode(TARGET, DrainReason.OPERATOR_COMMAND);

        sink.refuse(0, WITNESS_REFUSED);
        assertThat(outcome.await()).isEqualTo(WITNESS_REFUSED.<Unit>result());
        settleFor(Duration.ofMillis(900));

        assertThat(clears).as("no timer was armed, so no expiry cleared or reaped anything").isEmpty();
        assertThat(terminated).isEmpty();
        assertThat(registry).isEmpty();

        ctm.drainNode(TARGET, DrainReason.OPERATOR_COMMAND);

        assertThat(sink.requests()).as("a refused attempt left no entry: the next drain opens its own request, never joins")
                                   .isEqualTo(2);
    }

    /// TB4r: the first attempt's gate ran (its timer is armed) and the sink then failed; a re-drain opens a second attempt.
    /// The first timer fires inside the second attempt's grace and must reap nothing; the reap comes at the second's own expiry.
    @Test
    void failedFirstAttempt_itsTimerDoesNotReapTheSecondAttemptBeforeItsOwnFullGrace() {
        var sink = witnessSink(this::addToRegistry);
        var grace = Duration.ofMillis(1000);

        ctm = newCtm(sink, timeSpan(1000).millis());
        var start = System.nanoTime();
        var first = ctm.drainNode(TARGET, DrainReason.OPERATOR_COMMAND);

        sink.acceptThenFail(0, SINK_FAILED);
        assertThat(first.await()).isEqualTo(SINK_FAILED.<Unit>result());
        settleFor(Duration.ofMillis(600));

        var second = ctm.drainNode(TARGET, DrainReason.OPERATOR_COMMAND);

        sink.accept(1);
        assertThat(second.await().isSuccess()).isTrue();
        settleUntil(start, grace.plusMillis(200));

        assertThat(clears).as("the first attempt's timer expired at +1000 ms, 600 ms before the second attempt's grace ends")
                          .isEmpty();

        await().atMost(Duration.ofSeconds(4))
               .until(() -> !clears.isEmpty());

        assertThat(clears).containsExactly(TARGET);
    }

    /// TB4s: a drain of a target already in the registry still arms its grace timer: the arm is not conditioned on the write being fresh.
    @Test
    void drainOfATargetAlreadyInTheRegistry_stillArmsItsGraceTimer() {
        var sink = witnessSink(this::addToRegistry);

        registry.add(TARGET);
        ctm = newCtm(sink, timeSpan(150).millis());
        var outcome = ctm.drainNode(TARGET, DrainReason.OPERATOR_COMMAND);

        sink.accept(0);
        assertThat(outcome.await().isSuccess()).isTrue();
        await().atMost(Duration.ofSeconds(3))
               .until(() -> !clears.isEmpty());

        assertThat(clears).containsExactly(TARGET);
    }

    /// TB5': a join of the id during the witness removes the entry; the accepted witness then finds the gate closed.
    @Test
    void cancelDuringTheWitness_thenAccept_writesNothingAndFailsAttemptCancelled() {
        var sink = witnessSink(this::addToRegistry);

        ctm = newCtm(sink, LONG_GRACE);
        var outcome = ctm.drainNode(TARGET, DrainReason.OPERATOR_COMMAND);

        ctm.onWorkerJoin(WorkerJoinDecision.workerJoinDecision(TARGET, "worker", HLC));
        sink.accept(0);

        assertThat(outcome.await()).isEqualTo(FenceFailure.AttemptCancelled.attemptCancelled().<Unit>result());
        assertThat(registryAdds).isEmpty();
        assertThat(fsmCalls).isEmpty();
        assertThat(registry).isEmpty();
    }

    /// TB5c: a cancel that arrives while the gate's write runs waits for it (both are one step under the manager's monitor), so the
    /// cancel's registry clear comes after the add; afterwards the registry is empty and the map holds no entry.
    @Test
    void cancelDuringTheGatesWrite_waitsForIt_thenRegistryAndMapAreEmpty() throws InterruptedException {
        var writeEntered = new CountDownLatch(1);
        var releaseWrite = new CountDownLatch(1);
        var sink = witnessSink(target -> addAfterLatch(target, writeEntered, releaseWrite));

        ctm = newCtm(sink, LONG_GRACE);
        ctm.drainNode(TARGET, DrainReason.OPERATOR_COMMAND);

        var acceptor = startThread(() -> sink.accept(0));

        assertThat(writeEntered.await(5, TimeUnit.SECONDS)).isTrue();

        var canceller = startThread(() -> ctm.onWorkerJoin(WorkerJoinDecision.workerJoinDecision(TARGET, "worker", HLC)));

        await().atMost(Duration.ofSeconds(5))
               .until(() -> canceller.getState() == Thread.State.BLOCKED);
        assertThat(registry).as("the cancel waits for the write: nothing was cleared yet, nothing was added yet").isEmpty();

        releaseWrite.countDown();
        acceptor.join(5000);
        canceller.join(5000);

        assertThat(acceptor.isAlive() || canceller.isAlive()).isFalse();
        assertThat(registryAdds).containsExactly(TARGET);
        assertThat(registry).as("the cancel's clear ran after the add").isEmpty();
        ctm.drainNode(TARGET, DrainReason.OPERATOR_COMMAND);
        assertThat(sink.requests()).as("the cancel removed the entry: the next drain opens its own request").isEqualTo(2);
    }

    /// TB5d: `deactivate` + `activate` during the witness leaves the old attempt without an entry, so the accepted witness writes nothing.
    @Test
    void deactivateAndActivateDuringTheWitness_thenAccept_writesNothing() {
        var sink = witnessSink(this::addToRegistry);

        ctm = newCtm(sink, LONG_GRACE);
        var outcome = ctm.drainNode(TARGET, DrainReason.OPERATOR_COMMAND);

        ctm.deactivate();
        ctm.activate();
        sink.accept(0);

        assertThat(outcome.await()).isEqualTo(FenceFailure.AttemptCancelled.attemptCancelled().<Unit>result());
        assertThat(registryAdds).isEmpty();
        assertThat(fsmCalls).isEmpty();
        assertThat(registry).isEmpty();
    }

    /// TB5f: through the real synchronous adapter the FSM call is made on a thread that does not hold the manager's monitor: not inside
    /// `drainNode`'s step, not inside the gate. The control proves the probe can answer `true`.
    @Test
    void fsmCallThroughTheSynchronousAdapter_isNeverMadeUnderTheManagersMonitor() {
        ctm = newCtm(DrainSink.synchronous(this::addToRegistry, this::observeFsm), LONG_GRACE);

        synchronized (ctm) {
            observeFsm(TARGET);
        }
        assertThat(fsmHeldTheMonitor).as("control: the probe sees a held monitor").containsExactly(true);
        fsmHeldTheMonitor.clear();

        var outcome = ctm.drainNode(TARGET, DrainReason.OPERATOR_COMMAND);

        assertThat(outcome.await().isSuccess()).as("the adapter did not refuse the request as made under the monitor").isTrue();
        assertThat(registryAdds).containsExactly(TARGET);
        assertThat(fsmHeldTheMonitor).containsExactly(false);
    }

    /// TB5h: the gate the manager hands to the sink answers `ownerMonitorHeld()` by the calling thread: `true` inside `synchronized (ctm)`, else `false`.
    @Test
    void gateHandedToTheSink_reportsTheManagersMonitorByTheCallingThread() {
        var sink = witnessSink(this::addToRegistry);

        ctm = newCtm(sink, LONG_GRACE);
        ctm.drainNode(TARGET, DrainReason.OPERATOR_COMMAND);

        var gate = sink.gate(0);

        assertThat(gate.ownerMonitorHeld()).isFalse();
        synchronized (ctm) {
            assertThat(gate.ownerMonitorHeld()).isTrue();
        }
        assertThat(gate.ownerMonitorHeld()).isFalse();
    }

    private ClusterTopologyManager newCtm(DrainSink sink, TimeSpan grace) {
        var snapshotSource = new StubSnapshotSource();
        var config = new TopologyConfig(SELF, 5, timeSpan(60).seconds(), timeSpan(1).seconds(), List.of(INFO_SELF));
        var observer = TopologyObserver.topologyObserver(config, quietRouter(), snapshotSource).unwrap();
        var autoHeal = AutoHealConfig.autoHealConfig(timeSpan(1).millis(), grace).unwrap();
        var manager = ClusterTopologyManager.clusterTopologyManager(observer,
                                                                    new RecordingLifecycleManager(terminated),
                                                                    autoHeal,
                                                                    DeploymentMap.deploymentMap(),
                                                                    snapshotSource,
                                                                    Option::none,
                                                                    commands -> Promise.success(List.of()),
                                                                    () -> ClusterPhase.NORMAL,
                                                                    sink,
                                                                    this::clearCommand,
                                                                    Option::none,
                                                                    MembershipLiveness.UNWIRED);

        manager.setRetirementRefusal(_ -> Option.none());
        manager.setHierarchyStateWriter(HierarchyStateWriter.unavailable());
        manager.activate();
        return manager;
    }

    private WitnessSink witnessSink(Consumer<NodeId> registryAdd) {
        return WitnessSink.witnessSink(registryAdd, this::observeFsm);
    }

    private void addToRegistry(NodeId target) {
        registry.add(target);
        registryAdds.add(target);
    }

    private void addAfterLatch(NodeId target, CountDownLatch entered, CountDownLatch release) {
        entered.countDown();
        awaitLatch(release);
        addToRegistry(target);
    }

    private void observeFsm(NodeId target) {
        fsmCalls.add(target);
        fsmHeldTheMonitor.add(Thread.holdsLock(ctm));
    }

    private void clearCommand(NodeId target) {
        registry.remove(target);
        clears.add(target);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Thread startThread(Runnable body) {
        var thread = new Thread(body);

        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /// Lets scheduled work run before an ABSENCE is asserted; each caller states the margin it relies on.
    private static void settleFor(Duration window) {
        try {
            Thread.sleep(window.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void settleUntil(long startNanos, Duration sinceStart) {
        settleFor(sinceStart.minus(Duration.ofNanos(System.nanoTime() - startNanos)));
    }

    private static MessageRouter.MutableRouter quietRouter() {
        var router = MessageRouter.mutable();

        router.addRoute(NetworkServiceMessage.ListConnectedNodes.class, _ -> {});
        return router;
    }

    /// A sink that holds every request open: the test answers it. `accept` answers through the real synchronous adapter, so the gate,
    /// the registry add and the FSM call run exactly as in production; `acceptThenFail` runs only the gate and then fails the request.
    private static final class WitnessSink implements DrainSink {
        private final CopyOnWriteArrayList<Held> held = new CopyOnWriteArrayList<>();
        private final Consumer<NodeId> registryAdd;
        private final DrainSink adapter;

        private WitnessSink(Consumer<NodeId> registryAdd, Consumer<NodeId> fsmCall) {
            this.registryAdd = registryAdd;
            this.adapter = DrainSink.synchronous(registryAdd, fsmCall);
        }

        static WitnessSink witnessSink(Consumer<NodeId> registryAdd, Consumer<NodeId> fsmCall) {
            return new WitnessSink(registryAdd, fsmCall);
        }

        @Override
        public Promise<Unit> request(NodeId target, DrainGate gate) {
            var answer = Promise.<Unit> promise();

            held.add(Held.held(target, gate, answer));
            return answer;
        }

        @Override
        public Option<Promise<Unit>> inFlight(NodeId target) {
            return Option.none();
        }

        int requests() {
            return held.size();
        }

        DrainGate gate(int index) {
            return held.get(index).gate();
        }

        void accept(int index) {
            var request = held.get(index);

            request.answer().resolve(adapter.request(request.target(), request.gate()).await());
        }

        void acceptThenFail(int index, Cause cause) {
            var request = held.get(index);

            request.gate().runIfOwned(() -> registryAdd.accept(request.target()));
            request.answer().resolve(cause.<Unit> result());
        }

        void refuse(int index, Cause cause) {
            held.get(index).answer().resolve(cause.<Unit> result());
        }

        private record Held(NodeId target, DrainGate gate, Promise<Unit> answer) {
            static Held held(NodeId target, DrainGate gate, Promise<Unit> answer) {
                return new Held(target, gate, answer);
            }
        }
    }

    private static final class StubSnapshotSource implements GenerationSnapshotSource {
        @Override
        public Option<MembershipView> currentMembershipView() {
            return Option.none();
        }

        @Override
        public long observedRabiaTerm() {
            return 0L;
        }
    }

    /// Records every termination; every node is a plain provider instance.
    private static final class RecordingLifecycleManager implements NodeLifecycleManager {
        private final CopyOnWriteArrayList<NodeId> terminated;

        private RecordingLifecycleManager(CopyOnWriteArrayList<NodeId> terminated) {
            this.terminated = terminated;
        }

        @Override
        public Promise<ActionResult> executeAction(NodeAction action) {
            return Promise.success(new ActionResult.NodeStarted(stubInstance()));
        }

        @Override
        public Promise<InstanceInfo> provisionNode(ProvisionSpec spec) {
            return Promise.success(stubInstance());
        }

        @Override
        public Promise<Unit> terminateNode(NodeId nodeId) {
            terminated.add(nodeId);
            return Promise.unitPromise();
        }

        @Override
        public boolean isCloudManaged() {
            return false;
        }

        private static InstanceInfo stubInstance() {
            return InstanceInfo.instanceInfo(InstanceId.instanceId("stub").unwrap(),
                                             InstanceStatus.RUNNING,
                                             List.of("127.0.0.1"),
                                             InstanceType.ON_DEMAND).unwrap();
        }
    }
}

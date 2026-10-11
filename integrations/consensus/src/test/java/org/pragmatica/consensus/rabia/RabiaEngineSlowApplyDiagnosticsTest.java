/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.consensus.rabia;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.rabia.RabiaEngineApplyContainmentTest.PoisonStateMachine;
import org.pragmatica.consensus.rabia.RabiaEngineApplyContainmentTest.TestClusterNetwork;
import org.pragmatica.consensus.rabia.RabiaEngineApplyContainmentTest.TestCommand;
import org.pragmatica.consensus.rabia.RabiaEngineApplyContainmentTest.TestTopologyManager;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1117 — what the consensus engine tells an operator about a stalled commit.
///
/// Three runs (Heavy 35647686220, 35552814436 and a local probe) share one signature: a `SLOW-APPLY ms=10002` on the
/// new leader's single consensus worker, then a node-side write or the leader's outcome write that is never answered
/// (`ApplyTimeout 30000ms`). The `SLOW-APPLY` line says how long, not where; and nothing exposed how long a batch had
/// been waiting or how many applies had timed out. These pins cover the three signals added for that: the running
/// task's stack, the oldest pending batch's age, and the apply-timeout count.
class RabiaEngineSlowApplyDiagnosticsTest {
    private static final NodeId NODE_1 = nodeId("node-1").unwrap();
    private static final org.pragmatica.serialization.SliceCodec SERIALIZER =
        TestSerializers.stringCommandSerializer(TestCommand.class, TestCommand::value, TestCommand::new);
    private static final NodeId NODE_2 = nodeId("node-2").unwrap();
    private static final NodeId NODE_3 = nodeId("node-3").unwrap();
    private static final int CLUSTER_SIZE = 3;

    private TestTopologyManager topologyManager;
    private TestClusterNetwork network;
    private RecordingMetrics metrics;
    private RabiaEngine<TestCommand> engine;

    @BeforeEach
    void setUp() {
        topologyManager = new TestTopologyManager(NODE_1, CLUSTER_SIZE);
        network = new TestClusterNetwork();
        metrics = new RecordingMetrics();
    }

    @AfterEach
    void tearDown() {
        engine.stop().await();
    }

    private void engine(ProtocolConfig config) {
        engine = new RabiaEngine<>(topologyManager, network, new PoisonStateMachine(), config, metrics, false, RabiaPersistence.inMemory());
    }

    @Test
    void aTaskRunningPastTheThreshold_isReportedWithTheStackOfTheCallHoldingTheWorker_exactlyOnce() throws Exception {
        engine(ProtocolConfig.testConfig());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        engine.safeExecuteForTesting(() -> blockedInTheCallUnderTest(entered, release));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        try {
            var report = engine.slowTaskCheckForTesting(System.nanoTime() + timeSpan(2).seconds().nanos());

            assertThat(report.isPresent())
                .as("a task that has held the worker for 2 s must be reported while it is still running")
                .isTrue();
            assertThat(report.unwrap())
                .as("the report must name the call the worker is blocked in, not only how long it has been blocked")
                .contains("blockedInTheCallUnderTest");
            assertThat(engine.slowTaskCheckForTesting(System.nanoTime() + timeSpan(3).seconds().nanos()).isPresent())
                .as("one task is reported once, however long it keeps running")
                .isFalse();
        } finally {
            release.countDown();
        }

        awaitIdle();

        assertThat(engine.slowTaskCheckForTesting(System.nanoTime() + timeSpan(10).seconds().nanos()).isPresent())
            .as("an idle worker has nothing to report")
            .isFalse();
    }

    @Test
    void theWatchdogReportsARunningSlowTaskOnItsOwn_withoutAnyoneAskingForTheCheck() throws Exception {
        engine(ProtocolConfig.testConfig());
        var reports = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        engine.slowTaskSinkForTesting(reports::add);
        engine.safeExecuteForTesting(() -> blockedInTheCallUnderTest(entered, release));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        try {
            var deadline = System.nanoTime() + timeSpan(8).seconds().nanos();

            while (reports.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }

            assertThat(reports)
                .as("the scheduled watchdog must find the task once it has run past one second")
                .hasSize(1);
            assertThat(reports.getFirst()).contains("blockedInTheCallUnderTest").containsPattern("queueDepth=\\d+ ms=\\d+ thread=");
        } finally {
            release.countDown();
        }
    }

    @Test
    void aTaskThatFinished_isNeverReportedLater() throws Exception {
        engine(ProtocolConfig.testConfig());

        engine.safeExecuteForTesting(() -> {});
        awaitIdle();

        assertThat(engine.slowTaskCheckForTesting(System.nanoTime() + timeSpan(10).seconds().nanos()).isPresent())
            .as("a task that ended long ago must not be reported as still running")
            .isFalse();
    }

    @Test
    void theThresholdIsOneSecond_aTaskIsReportedJustPastItAndNotJustBefore() throws Exception {
        assertThat(RabiaEngine.SLOW_TASK_STACK_THRESHOLD_NANOS).isEqualTo(1_000_000_000L);
        engine(ProtocolConfig.testConfig());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var beforeSubmit = System.nanoTime();

        engine.safeExecuteForTesting(() -> blockedInTheCallUnderTest(entered, release));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        var afterEntered = System.nanoTime();

        try {
            assertThat(engine.slowTaskCheckForTesting(beforeSubmit + 900_000_000L).isPresent())
                .as("running for at most 0.9 s: not slow yet")
                .isFalse();
            assertThat(engine.slowTaskCheckForTesting(afterEntered + 1_100_000_000L).isPresent())
                .as("running for at least 1.1 s: reported")
                .isTrue();
        } finally {
            release.countDown();
        }
    }

    @Test
    void stoppingTheEngine_cancelsTheWatchdog() throws Exception {
        engine(ProtocolConfig.testConfig());
        engine.safeExecuteForTesting(() -> {});
        awaitIdle();

        assertThat(engine.slowTaskWatchdogRunningForTesting()).as("armed by the first task").isTrue();

        engine.stop().await();

        assertThat(engine.slowTaskWatchdogRunningForTesting())
            .as("a stopped engine must not keep a 250 ms fixed-rate task alive")
            .isFalse();
    }

    @Test
    void aYoungTask_isNotReported() throws Exception {
        engine(ProtocolConfig.testConfig());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        engine.safeExecuteForTesting(() -> blockedInTheCallUnderTest(entered, release));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        try {
            assertThat(engine.slowTaskCheckForTesting(System.nanoTime()).isPresent())
                .as("a task that has just started is not slow")
                .isFalse();
        } finally {
            release.countDown();
        }
    }

    @Test
    void theOldestPendingBatchAge_isReadFromTheEngineWhenTheSinkIsRead_notPushedOnArrival() throws Exception {
        engine(ProtocolConfig.testConfig());

        assertThat(metrics.oldestPendingAgeMs.get())
            .as("the engine registers its probe with the sink at construction")
            .isNotNull();
        assertThat(metrics.oldestPendingAgeMs.get().getAsLong()).as("nothing pending").isZero();

        activate();
        engine.apply(List.of(new TestCommand("never-decided")));
        Thread.sleep(300);

        assertThat(metrics.oldestPendingAgeMs.get().getAsLong())
            .as("a batch no peer ever votes on keeps ageing between reads; a value pushed only on arrival would read 0")
            .isGreaterThanOrEqualTo(200L);
    }

    @Test
    void withTwoBatchesPending_theAgeIsTheOlderOnes() throws Exception {
        engine(ProtocolConfig.testConfig());
        activate();
        engine.apply(List.of(new TestCommand("older")));
        Thread.sleep(500);
        engine.apply(List.of(new TestCommand("younger")));
        Thread.sleep(50);

        assertThat(metrics.oldestPendingAgeMs.get().getAsLong())
            .as("the OLDEST pending batch is reported; the younger one is about 50 ms old, the older about 550 ms")
            .isGreaterThanOrEqualTo(450L)
            .as("milliseconds, not microseconds or seconds: about 550 ms, nowhere near 5 s")
            .isLessThan(5_000L);
    }

    @Test
    void aBatchLearnedFromAPeer_ageCountsFromLocalReceipt_notFromTheOriginatorsClock() throws Exception {
        engine(ProtocolConfig.testConfig());
        activate();
        var local = Batch.create(SERIALIZER, List.of(new TestCommand("foreign-clock")));
        // The originator's nanoTime has another origin: a stamp from an hour "ago" (or in the future) is what a peer's
        // JVM produces relative to this one.
        var fromAPeer = new Batch<>(local.id(), local.correlationIds(), System.nanoTime() - 3_600_000_000_000L, local.commands());

        engine.handleNewBatch(new RabiaProtocolMessage.Asynchronous.NewBatch<>(NODE_2, fromAPeer));
        Thread.sleep(300);

        assertThat(metrics.oldestPendingAgeMs.get().getAsLong())
            .as("received 300 ms ago, whatever the sender's clock said; the creation stamp would read an hour")
            .isBetween(250L, 5_000L);

        var future = Batch.create(SERIALIZER, List.of(new TestCommand("future-clock")));
        var fromTheFuture = new Batch<>(future.id(), future.correlationIds(), System.nanoTime() + 3_600_000_000_000L, future.commands());

        engine.handleNewBatch(new RabiaProtocolMessage.Asynchronous.NewBatch<>(NODE_3, fromTheFuture));

        assertThat(metrics.oldestPendingAgeMs.get().getAsLong())
            .as("a stamp from the future must neither hide the older batch nor poison the reading")
            .isBetween(250L, 5_000L);
    }

    @Test
    void aBatchThatWasDecided_noLongerAges() throws Exception {
        engine(ProtocolConfig.testConfig());
        activate();
        var batch = Batch.create(SERIALIZER, List.of(new TestCommand("decided")));

        engine.processPropose(new RabiaProtocolMessage.Synchronous.Propose<>(NODE_1, Phase.ZERO, batch));
        engine.processPropose(new RabiaProtocolMessage.Synchronous.Propose<>(NODE_2, Phase.ZERO, batch));
        Thread.sleep(50);
        engine.processVoteRound1(new RabiaProtocolMessage.Synchronous.VoteRound1(NODE_2, Phase.ZERO, StateValue.V1));
        engine.processVoteRound1(new RabiaProtocolMessage.Synchronous.VoteRound1(NODE_3, Phase.ZERO, StateValue.V1));
        Thread.sleep(50);
        engine.processVoteRound2(new RabiaProtocolMessage.Synchronous.VoteRound2(NODE_2, Phase.ZERO, StateValue.V1));
        engine.processVoteRound2(new RabiaProtocolMessage.Synchronous.VoteRound2(NODE_3, Phase.ZERO, StateValue.V1));
        Thread.sleep(150);

        assertThat(engine.pendingBatchCountForTesting()).as("precondition: the batch was decided and left pending").isZero();
        assertThat(metrics.oldestPendingAgeMs.get().getAsLong())
            .as("a decided batch must stop counting; a leaked arrival stamp would keep the age growing for ever")
            .isZero();
    }

    @Test
    void anApplyThatIsNeverAnswered_countsOneApplyTimeout() throws Exception {
        var shortApply = ProtocolConfig.protocolConfig(timeSpan(60).seconds(),
                                                       timeSpan(5).seconds(),
                                                       100,
                                                       1000,
                                                       timeSpan(300).millis()).unwrap();

        engine(shortApply);
        activate();

        var outcome = engine.apply(List.of(new TestCommand("never-decided"))).await(timeSpan(5).seconds());

        assertThat(outcome.isFailure()).as("an unanswered apply fails").isTrue();
        assertThat(metrics.applyTimeouts.get())
            .as("and is counted as an apply timeout, which is what separates 'never decided' from 'slow commit'")
            .isEqualTo(1);
    }

    private void activate() throws InterruptedException {
        engine.clusterState(ClusterStateNotification.active());
        Thread.sleep(150);
        engine.processSyncResponse(new RabiaProtocolMessage.Synchronous.SyncResponse<>(NODE_2, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
        engine.processSyncResponse(new RabiaProtocolMessage.Synchronous.SyncResponse<>(NODE_3, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
        Thread.sleep(50);
    }

    private static void blockedInTheCallUnderTest(CountDownLatch entered, CountDownLatch release) {
        entered.countDown();
        Result.lift(() -> release.await()).unwrap();
    }

    private void awaitIdle() throws InterruptedException {
        engine.settleForTesting().await(timeSpan(5).seconds());
    }

    private static final class RecordingMetrics implements ConsensusMetrics {
        final AtomicReference<LongSupplier> oldestPendingAgeMs = new AtomicReference<>();
        final AtomicInteger applyTimeouts = new AtomicInteger();

        @Override public void recordApplyTimeout(NodeId nodeId) {
            applyTimeouts.incrementAndGet();
        }

        @Override public void registerOldestPendingBatchAgeProbe(NodeId nodeId, LongSupplier ageMs) {
            oldestPendingAgeMs.set(ageMs);
        }

        @Override public void recordDecision(NodeId nodeId, Phase phase, StateValue stateValue, long durationNs) {}

        @Override public void recordProposal(NodeId nodeId, Phase phase) {}

        @Override public void recordVoteRound1(NodeId nodeId, Phase phase, StateValue stateValue) {}

        @Override public void recordVoteRound2(NodeId nodeId, Phase phase, StateValue stateValue) {}

        @Override public void recordSyncAttempt(NodeId nodeId, boolean success) {}

        @Override public void updatePendingBatches(NodeId nodeId, int count) {}

        @Override public void recordFastPath(NodeId nodeId, Phase phase, StateValue value) {}
    }
}

package org.pragmatica.consensus.rabia;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ConsensusError;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestCommand;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestStateMachine;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestTopologyManager;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestClusterNetwork;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.*;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.NewBatch;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Result;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

class RabiaStopAdmissionTest {
    private static final NodeId A = new NodeId("node-1");
    private static final NodeId B = new NodeId("node-2");
    private static final NodeId C = new NodeId("node-3");

    @Test
    void stopCompletesQueuedRequestsAndRefusesLaterRequests() throws InterruptedException {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var machine = new TestStateMachine() {
            @Override
            public <R> List<R> process(Batch<TestCommand> batch) {
                var results = super.<R>process(batch);
                entered.countDown();
                Result.lift(() -> release.await()).unwrap();
                return results;
            }
        };
        var engine = new RabiaEngine<>(new TestTopologyManager(A, 3), new TestClusterNetwork(), machine,
                                      ProtocolConfig.consensusConfig(timeSpan(60).seconds(), timeSpan(60).seconds()));
        var target = ClusterConfig.clusterConfig(List.of(A, B, C)).unwrap();
        try {
            engine.clusterState(ClusterStateNotification.active());
            engine.processSyncResponse(new SyncResponse<>(B, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
            engine.processSyncResponse(new SyncResponse<>(C, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
            engine.settleForTesting().await().unwrap();
            assertThat(engine.isActive()).isTrue();
            var batch = Batch.create(machine.serializer(), List.of(new TestCommand("blocked")));
            engine.processDecision(new Decision<>(B, 0, Phase.ZERO, StateValue.V1, batch));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var admittedApply = engine.apply(List.of(new TestCommand("admitted")));
            var admittedReconfigure = engine.reconfigure(target);
            var stop = engine.stop();
            assertThat(engine.stop()).isSameAs(stop);
            engine.apply(List.of(new TestCommand("late"))).timeout(timeSpan(1).seconds()).await()
                  .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("late apply succeeded"))
                  .onFailure(cause -> assertThat(cause).isInstanceOf(ConsensusError.NodeInactive.class));
            engine.reconfigure(target).timeout(timeSpan(1).seconds()).await()
                  .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("late reconfiguration succeeded"))
                  .onFailure(cause -> assertThat(cause).isInstanceOf(ConsensusError.NodeInactive.class));
            release.countDown();
            stop.timeout(timeSpan(5).seconds()).await().unwrap();
            admittedApply.timeout(timeSpan(1).seconds()).await()
                         .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("unfinished apply succeeded"))
                         .onFailure(cause -> assertThat(cause).isInstanceOf(ConsensusError.NodeInactive.class));
            admittedReconfigure.timeout(timeSpan(1).seconds()).await()
                               .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("queued reconfiguration succeeded after stop"))
                               .onFailure(cause -> assertThat(cause).isInstanceOf(ConsensusError.NodeInactive.class));
        } finally {
            release.countDown();
            engine.stop().timeout(timeSpan(5).seconds()).await();
        }
    }

    /// Pin for ruling 151b0edfe (union of #1390's rejection path and rc4's `performStop` sweep): an
    /// apply ADMITTED before `stop()` but whose registration task only reaches the worker after it is
    /// refused by `safeExecute`'s `onStopped` callback — at the point of refusal. The request never
    /// registers, so it never enters `correlationMap` and the sweep cannot be what settles it; the
    /// NewBatch assertion proves that. Remove the callback and the caller waits for `applyTimeout`
    /// (60 s here), so the 2 s bound reddens; remove the in-task refusal and the request registers,
    /// broadcasts and is swept instead, so the NewBatch assertion reddens.
    @Test
    void admittedApply_refusedByExecutor_failsAtRefusal_notBySweep() throws InterruptedException {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var machine = new TestStateMachine() {
            @Override
            public <R> List<R> process(Batch<TestCommand> batch) {
                var results = super.<R>process(batch);
                entered.countDown();
                Result.lift(() -> release.await()).unwrap();
                return results;
            }
        };
        var network = new TestClusterNetwork();
        var engine = new RabiaEngine<>(new TestTopologyManager(A, 3), network, machine,
                                      ProtocolConfig.consensusConfig(timeSpan(60).seconds(), timeSpan(60).seconds()));
        var refused = new TestCommand("refused-at-execution");
        try {
            engine.clusterState(ClusterStateNotification.active());
            engine.processSyncResponse(new SyncResponse<>(B, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
            engine.processSyncResponse(new SyncResponse<>(C, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
            engine.settleForTesting().await().unwrap();
            assertThat(engine.isActive()).isTrue();
            var batch = Batch.create(machine.serializer(), List.of(new TestCommand("blocked")));
            engine.processDecision(new Decision<>(B, 0, Phase.ZERO, StateValue.V1, batch));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var admitted = engine.apply(List.of(refused));
            var stop = engine.stop();
            release.countDown();
            admitted.timeout(timeSpan(2).seconds()).await()
                    .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("refused apply succeeded"))
                    .onFailure(cause -> assertThat(cause)
                        .as("refusal must settle the caller at once, not after applyTimeout")
                        .isInstanceOf(ConsensusError.NodeInactive.class));
            stop.timeout(timeSpan(5).seconds()).await().unwrap();
            assertThat(network.getMessages())
                .as("the refused request must never have registered or broadcast — otherwise the sweep, not the refusal, settled it")
                .noneMatch(message -> message instanceof NewBatch<?> newBatch
                                      && newBatch.batch().commands().contains(refused));
        } finally {
            release.countDown();
            engine.stop().timeout(timeSpan(5).seconds()).await();
        }
    }

    /// Pin for rc4's half of ruling 151b0edfe, separate from the refusal pin above: a request ADMITTED
    /// and REGISTERED before `stop()` — its batch broadcast and awaiting a decision that no peer will
    /// vote on — is settled by `performStop`'s `correlationMap` sweep with `NodeInactive`, promptly.
    /// Nothing else settles it: without the sweep the caller waits for `applyTimeout` (60 s here), so
    /// the 2 s bound reddens.
    @Test
    void registeredApplyAwaitingDecision_settledBySweepAtStop() {
        var network = new TestClusterNetwork();
        var machine = new TestStateMachine();
        var engine = new RabiaEngine<>(new TestTopologyManager(A, 3), network, machine,
                                      ProtocolConfig.consensusConfig(timeSpan(60).seconds(), timeSpan(60).seconds()));
        var pending = new TestCommand("registered-awaiting-decision");
        try {
            engine.clusterState(ClusterStateNotification.active());
            engine.processSyncResponse(new SyncResponse<>(B, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
            engine.processSyncResponse(new SyncResponse<>(C, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
            engine.settleForTesting().await().unwrap();
            assertThat(engine.isActive()).isTrue();
            var admitted = engine.apply(List.of(pending));
            engine.settleForTesting().await().unwrap();
            assertThat(network.getMessages())
                .as("precondition: the request registered and broadcast — the refusal path cannot be what settles it")
                .anyMatch(message -> message instanceof NewBatch<?> newBatch
                                     && newBatch.batch().commands().contains(pending));
            engine.stop().timeout(timeSpan(5).seconds()).await().unwrap();
            admitted.timeout(timeSpan(2).seconds()).await()
                    .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("an undecided apply succeeded after stop"))
                    .onFailure(cause -> assertThat(cause)
                        .as("the stop sweep must settle a registered, undecided request at once, not after applyTimeout")
                        .isInstanceOf(ConsensusError.NodeInactive.class));
        } finally {
            engine.stop().timeout(timeSpan(5).seconds()).await();
        }
    }
}

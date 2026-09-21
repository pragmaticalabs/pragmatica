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
}

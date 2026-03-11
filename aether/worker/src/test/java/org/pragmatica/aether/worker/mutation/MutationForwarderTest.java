package org.pragmatica.aether.worker.mutation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.worker.network.WorkerNetwork;
import org.pragmatica.cluster.node.passive.PassiveNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter.DelegateRouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"JBCT-RET-01", "JBCT-EX-01", "unchecked", "rawtypes"})
class MutationForwarderTest {
    private static final NodeId SELF = NodeId.nodeId("worker-1").unwrap();
    private static final NodeId GOVERNOR = NodeId.nodeId("governor-1").unwrap();

    @Mock
    private WorkerNetwork workerNetwork;

    @Mock
    private PassiveNode passiveNode;

    private DelegateRouter delegateRouter;
    private MutationForwarder forwarder;

    @BeforeEach
    void setUp() {
        delegateRouter = DelegateRouter.delegate();
        delegateRouter.quiesce();
        lenient().when(passiveNode.delegateRouter()).thenReturn(delegateRouter);
        forwarder = MutationForwarder.mutationForwarder(SELF, workerNetwork, passiveNode);
    }

    private static WorkerMutation testMutation() {
        var key = AetherKey.SliceTargetKey.sliceTargetKey(
            org.pragmatica.aether.artifact.ArtifactBase.artifactBase("com.example:svc").unwrap());
        var command = new KVCommand.Remove<>(key);
        return WorkerMutation.workerMutation(SELF, "test-corr-1", (KVCommand<AetherKey>) (KVCommand<?>) command);
    }

    @Nested
    class ForwardAsGovernor {
        @Test
        void forward_asGovernor_sendsToCore() {
            forwarder.updateGovernor(Option.some(SELF));

            forwarder.forward(testMutation());

            // When forwarding to core, delegateRouter().route() is called (not workerNetwork.send())
            verify(passiveNode, times(1)).delegateRouter();
            verify(workerNetwork, never()).send(any(NodeId.class), any());
        }
    }

    @Nested
    class ForwardAsFollower {
        @Test
        void forward_asFollower_sendsToGovernor() {
            forwarder.updateGovernor(Option.some(GOVERNOR));

            forwarder.forward(testMutation());

            var captor = ArgumentCaptor.forClass(Object.class);
            verify(workerNetwork).send(any(NodeId.class), captor.capture());
            assertThat(captor.getValue()).isInstanceOf(WorkerMutation.class);
        }
    }

    @Nested
    class ForwardNoGovernor {
        @Test
        void forward_noGovernor_sendsToCore() {
            forwarder.updateGovernor(Option.empty());

            forwarder.forward(testMutation());

            // Core path — delegates to passiveNode, not workerNetwork
            verify(passiveNode, times(1)).delegateRouter();
            verify(workerNetwork, never()).send(any(NodeId.class), any());
        }
    }

    @Nested
    class GovernorRelay {
        @Test
        void onMutationFromFollower_forwardsToCore() {
            forwarder.onMutationFromFollower(testMutation());

            verify(passiveNode, times(1)).delegateRouter();
        }
    }

    @Nested
    class GovernorUpdate {
        @Test
        void updateGovernor_changesForwardingPath() {
            // Initially no governor — sends to core
            forwarder.updateGovernor(Option.empty());
            forwarder.forward(testMutation());
            verify(workerNetwork, never()).send(any(NodeId.class), any());

            // Update to a different governor — sends to governor via workerNetwork
            forwarder.updateGovernor(Option.some(GOVERNOR));
            forwarder.forward(testMutation());
            verify(workerNetwork).send(any(NodeId.class), any());
        }
    }
}

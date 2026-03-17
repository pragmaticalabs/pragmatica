package org.pragmatica.aether.worker.mutation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.cluster.node.passive.PassiveNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.netty.NettyClusterNetwork;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.Message;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.messaging.MessageRouter.DelegateRouter;
import org.pragmatica.messaging.MessageRouter.Entry;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings({"JBCT-RET-01", "JBCT-EX-01", "unchecked", "rawtypes"})
class MutationForwarderTest {
    private static final NodeId SELF = NodeId.nodeId("worker-1").unwrap();
    private static final NodeId GOVERNOR = NodeId.nodeId("governor-1").unwrap();

    private DelegateRouter delegateRouter;
    private List<Message> routedMessages;
    private MutationForwarder forwarder;

    @BeforeEach
    void setUp() {
        routedMessages = new ArrayList<>();
        delegateRouter = DelegateRouter.delegate();
        var mutableRouter = MessageRouter.mutable();
        mutableRouter.addRoute(NetworkServiceMessage.Send.class, routedMessages::add);
        mutableRouter.addRoute(NetworkServiceMessage.Broadcast.class, routedMessages::add);
        delegateRouter.replaceDelegate(mutableRouter);
        var passiveNode = new StubPassiveNode(delegateRouter);
        forwarder = MutationForwarder.mutationForwarder(SELF, passiveNode);
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

            assertThat(routedMessages).hasSize(1);
            assertThat(routedMessages.getFirst()).isInstanceOf(NetworkServiceMessage.Broadcast.class);
        }
    }

    @Nested
    class ForwardAsFollower {
        @Test
        void forward_asFollower_sendsToGovernorViaSend() {
            forwarder.updateGovernor(Option.some(GOVERNOR));

            forwarder.forward(testMutation());

            assertThat(routedMessages).hasSize(1);
            assertThat(routedMessages.getFirst()).isInstanceOf(NetworkServiceMessage.Send.class);
            var send = (NetworkServiceMessage.Send) routedMessages.getFirst();
            assertThat(send.target()).isEqualTo(GOVERNOR);
            assertThat(send.payload()).isInstanceOf(WorkerMutation.class);
        }
    }

    @Nested
    class ForwardNoGovernor {
        @Test
        void forward_noGovernor_sendsToCore() {
            forwarder.updateGovernor(Option.empty());

            forwarder.forward(testMutation());

            assertThat(routedMessages).hasSize(1);
            assertThat(routedMessages.getFirst()).isInstanceOf(NetworkServiceMessage.Broadcast.class);
        }
    }

    @Nested
    class GovernorRelay {
        @Test
        void onMutationFromFollower_forwardsToCore() {
            forwarder.onMutationFromFollower(testMutation());

            assertThat(routedMessages).hasSize(1);
            assertThat(routedMessages.getFirst()).isInstanceOf(NetworkServiceMessage.Broadcast.class);
        }
    }

    @Nested
    class GovernorUpdate {
        @Test
        void updateGovernor_changesForwardingPath() {
            // Initially no governor — sends to core (Broadcast)
            forwarder.updateGovernor(Option.empty());
            forwarder.forward(testMutation());
            assertThat(routedMessages.getFirst()).isInstanceOf(NetworkServiceMessage.Broadcast.class);

            // Update to a different governor — sends to governor via Send
            forwarder.updateGovernor(Option.some(GOVERNOR));
            forwarder.forward(testMutation());
            assertThat(routedMessages).hasSize(2);
            assertThat(routedMessages.get(1)).isInstanceOf(NetworkServiceMessage.Send.class);
        }
    }

    @SuppressWarnings({"JBCT-STY-05"})
    record StubPassiveNode(DelegateRouter delegateRouter) implements PassiveNode<StructuredKey, Object> {
        @Override
        public NettyClusterNetwork network() { return null; }

        @Override
        public KVStore<StructuredKey, Object> kvStore() { return null; }

        @Override
        public List<Entry<?>> routeEntries() { return List.of(); }

        @Override
        public Promise<Unit> start() { return Promise.success(Unit.unit()); }

        @Override
        public Promise<Unit> stop() { return Promise.success(Unit.unit()); }
    }
}

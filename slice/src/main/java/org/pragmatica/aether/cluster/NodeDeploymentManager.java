package org.pragmatica.aether.cluster;

import org.pragmatica.aether.slice.manager.SliceStore;
import org.pragmatica.cluster.net.NodeId;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.cluster.topology.QuorumStateNotification;
import org.pragmatica.message.MessageRouter;

import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

public interface NodeDeploymentManager {
    sealed interface NodeDeploymentState {
        default void onValuePut(ValuePut<?,?> valuePut) {}
        default void onValueRemove(ValueRemove<?,?> valueRemove) {}

        record DormantNodeDeploymentState() implements NodeDeploymentState { }
        record ActiveNodeDeploymentState(Pattern pattern, NodeId self) implements NodeDeploymentState {

            @Override
            public void onValuePut(ValuePut<?,?> valuePut) {

            }

            @Override
            public void onValueRemove(ValueRemove<?,?> valueRemove) {

            }
        }
    }

    static NodeDeploymentManager nodeSliceManager(NodeId self, MessageRouter router, SliceStore sliceStore) {
        record deploymentManager(NodeId self, SliceStore sliceManager, AtomicReference<NodeDeploymentState> state) implements NodeDeploymentManager {

             public void onValuePut(ValuePut<?,?> valuePut) {
                state.get().onValuePut(valuePut);
            }

            public void onValueRemove(ValueRemove<?,?> valueRemove) {
                state.get().onValueRemove(valueRemove);
            }

            public void onQuorumStateChange(QuorumStateNotification quorumStateNotification) {
                switch (quorumStateNotification) {
                    case ESTABLISHED -> state().set(new NodeDeploymentState.ActiveNodeDeploymentState(buildPattern(), self()));
                    case DISAPPEARED -> state().set(new NodeDeploymentState.DormantNodeDeploymentState());
                }
            }
            
            private Pattern buildPattern() {
                // Create pattern based on node ID for slice deployment matching
                return Pattern.compile("slice-" + self.id() + "-.*");
            }
        }

        var deploymentManager = new deploymentManager(self, sliceStore, new AtomicReference<>(new NodeDeploymentState.DormantNodeDeploymentState()));

        var mutableRouter = (MessageRouter.MutableRouter) router;
        mutableRouter.addRoute(ValuePut.class, deploymentManager::onValuePut);
        mutableRouter.addRoute(ValueRemove.class, deploymentManager::onValueRemove);
        mutableRouter.addRoute(QuorumStateNotification.class, deploymentManager::onQuorumStateChange);

        return deploymentManager;
    }
}

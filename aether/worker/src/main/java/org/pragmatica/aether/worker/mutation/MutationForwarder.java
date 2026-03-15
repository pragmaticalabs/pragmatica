package org.pragmatica.aether.worker.mutation;

import org.pragmatica.cluster.node.passive.PassiveNode;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.lang.Option;

import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Forwards mutations from worker nodes to core cluster via the governor.
///
/// Path: Worker -> Governor -> Core (any node, since Rabia is leaderless).
/// If the governor is FAULTY, falls back to sending directly to any core node
/// via the PassiveNode's cluster network.
@SuppressWarnings({"JBCT-RET-01", "JBCT-EX-01"})
public interface MutationForwarder {
    Logger LOG = LoggerFactory.getLogger(MutationForwarder.class);

    /// Forward a mutation. If this node IS the governor, send directly to core.
    /// Otherwise, send to the governor for relay.
    void forward(WorkerMutation mutation);

    /// Called on the governor when receiving a mutation from a follower.
    /// The governor forwards it to any core node.
    void onMutationFromFollower(WorkerMutation mutation);

    /// Update the known governor.
    void updateGovernor(Option<NodeId> governor);

    /// Factory method.
    static MutationForwarder mutationForwarder(NodeId selfId,
                                               PassiveNode<?, ?> passiveNode) {
        record mutationForwarder(NodeId selfId,
                                 PassiveNode<?, ?> passiveNode,
                                 AtomicReference<Option<NodeId>> currentGovernor) implements MutationForwarder {
            @Override
            public void forward(WorkerMutation mutation) {
                var governor = currentGovernor.get();
                if (governor.isEmpty() || isGovernor(governor)) {
                    forwardToCore(mutation);
                    return;
                }
                forwardToGovernor(mutation, governor.unwrap());
            }

            @Override
            public void onMutationFromFollower(WorkerMutation mutation) {
                forwardToCore(mutation);
            }

            @Override
            public void updateGovernor(Option<NodeId> governor) {
                currentGovernor.set(governor);
            }

            private boolean isGovernor(Option<NodeId> governor) {
                return governor.map(selfId::equals)
                               .or(false);
            }

            private void forwardToCore(WorkerMutation mutation) {
                passiveNode.delegateRouter()
                           .route(new NetworkServiceMessage.Broadcast(mutation));
                LOG.trace("Forwarded mutation {} to core cluster", mutation.correlationId());
            }

            private void forwardToGovernor(WorkerMutation mutation, NodeId governorId) {
                passiveNode.delegateRouter()
                           .route(new NetworkServiceMessage.Send(governorId, mutation));
                LOG.trace("Forwarded mutation {} to governor {}", mutation.correlationId(), governorId.id());
            }
        }
        return new mutationForwarder(selfId, passiveNode, new AtomicReference<>(Option.empty()));
    }
}

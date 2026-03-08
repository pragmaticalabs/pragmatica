package org.pragmatica.aether.worker.mutation;

import org.pragmatica.aether.worker.network.WorkerNetwork;
import org.pragmatica.cluster.node.passive.PassiveNode;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.lang.Option;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Forwards mutations from worker nodes to core cluster via the governor.
///
/// Path: Worker -> Governor -> Core (any node, since Rabia is leaderless).
/// If the governor is FAULTY, falls back to sending directly to any core node
/// via the PassiveNode's cluster network.
@SuppressWarnings({"JBCT-RET-01", "JBCT-EX-01"})
public final class MutationForwarder {
    private static final Logger LOG = LoggerFactory.getLogger(MutationForwarder.class);

    private final NodeId selfId;
    private final WorkerNetwork workerNetwork;
    private final PassiveNode<?, ?> passiveNode;
    private volatile Option<NodeId> currentGovernor = Option.empty();

    private MutationForwarder(NodeId selfId, WorkerNetwork workerNetwork, PassiveNode<?, ?> passiveNode) {
        this.selfId = selfId;
        this.workerNetwork = workerNetwork;
        this.passiveNode = passiveNode;
    }

    /// Factory method.
    public static MutationForwarder mutationForwarder(NodeId selfId,
                                                      WorkerNetwork workerNetwork,
                                                      PassiveNode<?, ?> passiveNode) {
        return new MutationForwarder(selfId, workerNetwork, passiveNode);
    }

    /// Update the known governor.
    public void updateGovernor(Option<NodeId> governor) {
        currentGovernor = governor;
    }

    /// Forward a mutation. If this node IS the governor, send directly to core.
    /// Otherwise, send to the governor for relay.
    public void forward(WorkerMutation mutation) {
        var governor = currentGovernor;
        if (governor.isEmpty() || isGovernor(governor)) {
            forwardToCore(mutation);
            return;
        }
        forwardToGovernor(mutation, governor.unwrap());
    }

    /// Called on the governor when receiving a mutation from a follower.
    /// The governor forwards it to any core node.
    public void onMutationFromFollower(WorkerMutation mutation) {
        forwardToCore(mutation);
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
        workerNetwork.send(governorId, mutation);
        LOG.trace("Forwarded mutation {} to governor {}", mutation.correlationId(), governorId.id());
    }
}

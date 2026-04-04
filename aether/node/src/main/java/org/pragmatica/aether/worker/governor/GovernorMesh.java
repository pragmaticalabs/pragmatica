package org.pragmatica.aether.worker.governor;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter.DelegateRouter;

import java.util.Map;


/// Mesh network between community governors for cross-community DHT traffic.
/// Each governor maintains connections to governors of other communities.
/// DHT messages between communities are multiplexed over these governor connections.
///
/// Phase 2a provides the infrastructure. Full wiring happens in Phase 2b (multi-group).
public interface GovernorMesh {
    @SuppressWarnings("JBCT-RET-01") void registerGovernor(String communityId, NodeId governorId);
    @SuppressWarnings("JBCT-RET-01") void registerGovernor(String communityId, NodeId governorId, String tcpAddress);
    @SuppressWarnings("JBCT-RET-01") void unregisterGovernor(String communityId);
    Option<NodeId> governorFor(String communityId);
    Map<String, NodeId> allGovernors();
    boolean hasGovernor(String communityId);

    static GovernorMesh governorMesh() {
        return new GovernorMeshInstance();
    }

    static GovernorMesh governorMesh(DelegateRouter delegateRouter) {
        return new GovernorMeshInstance(delegateRouter);
    }
}

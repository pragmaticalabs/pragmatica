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
    /// Register a governor for a community (no TCP address).
    @SuppressWarnings("JBCT-RET-01") void registerGovernor(String communityId, NodeId governorId);

    /// Register a governor for a community with TCP address for cross-community connectivity.
    @SuppressWarnings("JBCT-RET-01") void registerGovernor(String communityId, NodeId governorId, String tcpAddress);

    /// Unregister a governor when it steps down or its community dissolves.
    @SuppressWarnings("JBCT-RET-01") void unregisterGovernor(String communityId);

    /// Get the governor for a community.
    Option<NodeId> governorFor(String communityId);

    /// Get all known community-governor mappings.
    Map<String, NodeId> allGovernors();

    /// Check if a governor is known for the given community.
    boolean hasGovernor(String communityId);

    /// Create a governor mesh without network connectivity (registry only).
    static GovernorMesh governorMesh() {
        return new GovernorMeshInstance();
    }

    /// Create a governor mesh with cluster network delegate router for peer management.
    static GovernorMesh governorMesh(DelegateRouter delegateRouter) {
        return new GovernorMeshInstance(delegateRouter);
    }
}

package org.pragmatica.aether.worker.governor;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import java.util.Map;

/// Mesh network between community governors for cross-community DHT traffic.
/// Each governor maintains connections to governors of other communities.
/// DHT messages between communities are multiplexed over these governor connections.
///
/// Phase 2a provides the infrastructure. Full wiring happens in Phase 2b (multi-group).
public interface GovernorMesh {
    /// Register a governor for a community.
    @SuppressWarnings("JBCT-RET-01") // Mutating operation
    void registerGovernor(String communityId, NodeId governorId);

    /// Unregister a governor when it steps down or its community dissolves.
    @SuppressWarnings("JBCT-RET-01") // Mutating operation
    void unregisterGovernor(String communityId);

    /// Get the governor for a community.
    Option<NodeId> governorFor(String communityId);

    /// Get all known community-governor mappings.
    Map<String, NodeId> allGovernors();

    /// Check if a governor is known for the given community.
    boolean hasGovernor(String communityId);

    /// Create a governor mesh.
    static GovernorMesh governorMesh() {
        return new GovernorMeshInstance();
    }
}

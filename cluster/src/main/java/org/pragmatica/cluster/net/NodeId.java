package org.pragmatica.cluster.net;

import org.pragmatica.utility.ULID;

/// Cluster node ID
public interface NodeId {
    String id();

    /// Create new node ID from the given string.
    static NodeId create(String id) {
        record nodeId(String id) implements NodeId {}
        return new nodeId(id);
    }

    /// Automatically generate unique node ID.
    static NodeId createRandom() {
        return create(ULID.randomULID().encoded());
    }
}

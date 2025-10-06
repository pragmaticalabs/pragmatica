package org.pragmatica.aether.slice.kvstore;

import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.cluster.net.NodeId;

/// Value type stored in the consensus KVStore
public sealed interface AetherValue {
    /// Blueprints contain information about the exact number of nodes which need to be deployed.
    record BlueprintValue(long instanceCount) implements AetherValue {}
    /// Deployment Vector (NodeId/Artifact) contains the current state of the loaded slice
    record SliceNodeValue(SliceState state) implements AetherValue {}
    /// Endpoint locator points to node where endpoint is available
    record EndpointValue(NodeId nodeId) implements AetherValue {}
}

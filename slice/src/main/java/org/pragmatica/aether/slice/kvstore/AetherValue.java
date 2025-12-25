package org.pragmatica.aether.slice.kvstore;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.routing.Binding;
import org.pragmatica.cluster.net.NodeId;

import java.util.List;

/// Value type stored in the consensus KVStore
public sealed interface AetherValue {
    /// Blueprints contain information about the exact number of nodes which need to be deployed (deprecated).
    @Deprecated
    record BlueprintValue(long instanceCount) implements AetherValue {}

    /// Application blueprint contains the expanded blueprint with full dependency resolution
    record AppBlueprintValue(ExpandedBlueprint blueprint) implements AetherValue {}

    /// Deployment Vector (NodeId/Artifact) contains the current state of the loaded slice
    record SliceNodeValue(SliceState state) implements AetherValue {}

    /// Endpoint locator points to node where endpoint is available
    record EndpointValue(NodeId nodeId) implements AetherValue {}

    /// Route definition for HTTP routing.
    /// Contains full route information for self-registration by slices.
    record RouteValue(
        Artifact artifact,
        String methodName,
        String httpMethod,
        String pathPattern,
        List<Binding> bindings
    ) implements AetherValue {

        /// Check if this route matches another route (same target).
        /// Used for idempotent registration validation.
        public boolean matches(RouteValue other) {
            return artifact.equals(other.artifact)
                && methodName.equals(other.methodName)
                && httpMethod.equalsIgnoreCase(other.httpMethod)
                && pathPattern.equals(other.pathPattern)
                && bindings.equals(other.bindings);
        }
    }
}

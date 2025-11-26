package org.pragmatica.aether.slice.kvstore;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.cluster.net.NodeId;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.cluster.state.kvstore.StructuredPattern;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.lang.utils.Causes;

/// Aether KV-Store structured keys for cluster state management
public sealed interface AetherKey extends StructuredKey {

    /// String representation of the key
    String asString();

    /// Blueprint-key format:
    /// ```
    /// blueprint/{groupId}:{artifactId}:{version}
    ///```
    record BlueprintKey(Artifact artifact) implements AetherKey {
        @Override
        public boolean matches(StructuredPattern pattern) {
            return switch (pattern) {
                case AetherKeyPattern.BlueprintPattern blueprintPattern ->
                    blueprintPattern.matches(this);
                default -> false;
            };
        }

        @Override
        public String asString() {
            return "blueprint/" + artifact.asString();
        }

        @Override
        public String toString() {
            return asString();
        }

        public static Result<BlueprintKey> blueprintKey(String key) {
            if (!key.startsWith("blueprint/")) {
                return BLUEPRINT_KEY_FORMAT_ERROR.apply(key).result();
            }

            var artifactPart = key.substring(10); // Remove "blueprint/"
            return Artifact.artifact(artifactPart)
                           .map(BlueprintKey::new);
        }
    }

    /// Slice-node-key format:
    /// ```
    /// slices/{nodeId}/{groupId}:{artifactId}:{version}
    ///```
    record SliceNodeKey(Artifact artifact, NodeId nodeId) implements AetherKey {
        @Override
        public boolean matches(StructuredPattern pattern) {
            return switch (pattern) {
                case AetherKeyPattern.SliceNodePattern sliceNodePattern ->
                    sliceNodePattern.matches(this);
                default -> false;
            };
        }

        public boolean isForNode(NodeId nodeId) {
            return this.nodeId.equals(nodeId);
        }

        @Override
        public String asString() {
            return "slices/" + nodeId.id() + "/" + artifact.asString();
        }

        @Override
        public String toString() {
            return asString();
        }

        public static Result<SliceNodeKey> sliceNodeKey(String key) {
            var parts = key.split("/");

            if (parts.length != 3) {
                return SLICE_KEY_FORMAT_ERROR.apply(key).result();
            }

            if (!"slices".equals(parts[0])) {
                return SLICE_KEY_FORMAT_ERROR.apply(key).result();
            }

            if (parts[1].isEmpty()) {
                return SLICE_KEY_FORMAT_ERROR.apply(key).result();
            }

            return Artifact.artifact(parts[2])
                           .map(artifact -> new SliceNodeKey(artifact, NodeId.nodeId(parts[1])));
        }
    }

    /// Endpoint-key format (for slice instance endpoints):
    /// ```
    /// endpoints/{groupId}:{artifactId}:{version}/{methodName}:{instanceNumber}
    ///```
    record EndpointKey(Artifact artifact, MethodName methodName, int instanceNumber) implements AetherKey {
        @Override
        public boolean matches(StructuredPattern pattern) {
            return switch (pattern) {
                case AetherKeyPattern.EndpointPattern endpointPattern ->
                    endpointPattern.matches(this);
                default -> false;
            };
        }
        @Override
        public String asString() {
            return "endpoints/"
                    + artifact.asString()
                    + "/"
                    + methodName.name()
                    + ":"
                    + instanceNumber;
        }

        @Override
        public String toString() {
            return asString();
        }

        public static Result<EndpointKey> endpointKey(String key) {
            if (!key.startsWith("endpoints/")) {
                return ENDPOINT_KEY_FORMAT_ERROR.apply(key).result();
            }

            var content = key.substring(10); // Remove "endpoints/"
            var slashIndex = content.indexOf('/');
            if (slashIndex == -1) {
                return ENDPOINT_KEY_FORMAT_ERROR.apply(key).result();
            }

            var artifactPart = content.substring(0, slashIndex);
            var endpointPart = content.substring(slashIndex + 1);

            var colonIndex = endpointPart.lastIndexOf(':');
            if (colonIndex == -1) {
                return ENDPOINT_KEY_FORMAT_ERROR.apply(key).result();
            }

            var methodNamePart = endpointPart.substring(0, colonIndex);
            var instancePart = endpointPart.substring(colonIndex + 1);

            return Result.all(
                    Artifact.artifact(artifactPart),
                    MethodName.methodName(methodNamePart),
                    Number.parseInt(instancePart)
            ).map(EndpointKey::new);
        }
    }

    Fn1<Cause, String> BLUEPRINT_KEY_FORMAT_ERROR = Causes.forValue("Invalid blueprint key format: %s");
    Fn1<Cause, String> SLICE_KEY_FORMAT_ERROR = Causes.forValue("Invalid slice key format: %s");
    Fn1<Cause, String> ENDPOINT_KEY_FORMAT_ERROR = Causes.forValue("Invalid endpoint key format: %s");

    /// Aether KV-Store structured patterns for key matching
    sealed interface AetherKeyPattern extends StructuredPattern {
        /// Pattern for blueprint keys: blueprint/*
        record BlueprintPattern() implements AetherKeyPattern {
            public boolean matches(BlueprintKey key) {
                return false;
            }
        }

        /// Pattern for slice-node keys: slices/*/*
        record SliceNodePattern() implements AetherKeyPattern {
            public boolean matches(SliceNodeKey key) {
                return false;
            }
        }

        /// Pattern for endpoint keys: endpoints/*/*
        record EndpointPattern() implements AetherKeyPattern {
            public boolean matches(EndpointKey key) {
                return false;
            }
        }
    }
}
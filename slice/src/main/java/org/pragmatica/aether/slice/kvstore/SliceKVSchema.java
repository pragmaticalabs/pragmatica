package org.pragmatica.aether.slice.kvstore;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactId;
import org.pragmatica.aether.artifact.GroupId;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.EntryPointId;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.cluster.net.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.lang.utils.Causes;

// TODO: rework as StructuredKey and provide StructuredPattern
public final class SliceKVSchema {

    private SliceKVSchema() {
    }

    /// Slice-node-key format:
    /// ```
    /// slices/{nodeId}/{groupId}:{artifactId}:{version}
    ///```
    public record SliceNodeKey(Artifact artifact, NodeId nodeId) {
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

    public record SliceStateValue(SliceState state, long timestamp, long version) {
        public String asString() {
            return state.name() + ":" + timestamp + ":" + version;
        }

        @Override
        public String toString() {
            return asString();
        }

        public static Result<SliceStateValue> sliceStateValue(String value) {
            var parts = value.split(":");
            if (parts.length != 3) {
                return Result.failure(Causes.cause("Invalid slice state value format: " + value));
            }

            return Result.all(
                    SliceState.sliceState(parts[0]),
                    Number.parseLong(parts[1]),
                    Number.parseLong(parts[2])
            ).map(SliceStateValue::new);
        }
    }

    /// Endpoint-key format (for slice instance endpoints):
    /// ```
    /// endpoints/{groupId}:{artifactId}:{version}/{endpointName}:{instanceNumber}
    ///```
    public record EndpointKey(Artifact artifact, EntryPointId entryPointId, int instanceNumber) {

        public String asString() {
            return "endpoints/"
                    + artifact.asString()
                    + "/"
                    + entryPointId.id()
                    + ":"
                    + instanceNumber;
        }

        @Override
        public String toString() {
            return asString();
        }

        public static Result<EndpointKey> endpointKey(String key) {
            if (!key.startsWith("endpoints/")) {
                return Result.failure(Causes.cause("Invalid endpoint key format: " + key));
            }

            var content = key.substring(10); // Remove "endpoints/"
            var slashIndex = content.indexOf('/');
            if (slashIndex == -1) {
                return Result.failure(Causes.cause("Invalid endpoint key format: " + key));
            }

            var artifactPart = content.substring(0, slashIndex);
            var endpointPart = content.substring(slashIndex + 1);

            var colonIndex = endpointPart.lastIndexOf(':');
            if (colonIndex == -1) {
                return Result.failure(Causes.cause("Invalid endpoint key format: " + key));
            }

            var entryPointPart = endpointPart.substring(0, colonIndex);
            var instancePart = endpointPart.substring(colonIndex + 1);

            return Result.all(
                    Artifact.artifact(artifactPart),
                    EntryPointId.entryPointId(entryPointPart),
                    Number.parseInt(instancePart)
            ).map(EndpointKey::new);
        }
    }

    public record EndpointValue(NodeId nodeId, int instanceNumber, SliceState state, long timestamp) {

        public String asString() {
            return nodeId.id() + ":" + instanceNumber + ":" + state.name() + ":" + timestamp;
        }

        @Override
        public String toString() {
            return asString();
        }

        public static Result<EndpointValue> endpointValue(String value) {
            var parts = value.split(":");
            if (parts.length != 4) {
                return Result.failure(Causes.cause("Invalid endpoint value format: " + value));
            }

            return Result.all(
                    Result.success(NodeId.nodeId(parts[0])),
                    Number.parseInt(parts[1]),
                    SliceState.sliceState(parts[2]),
                    Number.parseLong(parts[3])
            ).map(EndpointValue::new);
        }
    }

    private static Result<Artifact> parseArtifact(String artifactString) {
        var parts = artifactString.split(":");
        if (parts.length != 3) {
            return ARTIFACT_FORMAT_ERROR.apply(artifactString).result();
        }

        return Result.all(
                GroupId.groupId(parts[0]),
                ArtifactId.artifactId(parts[1]),
                Version.version(parts[2])
        ).map(Artifact::artifact);
    }

    private static final Fn1<Cause, String> SLICE_KEY_FORMAT_ERROR = Causes.forValue(
            "Invalid slice key format: {0}, expected slices/{{nodeId}}/{{groupId}}:{{artifactId}}:{{version}}");
    private static final Fn1<Cause, String> ARTIFACT_FORMAT_ERROR = Causes.forValue(
            "Invalid artifact format: {0}, expected {{groupId}}:{{artifactId}}:{{version}}");
}
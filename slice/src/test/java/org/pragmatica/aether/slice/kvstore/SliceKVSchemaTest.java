package org.pragmatica.aether.slice.kvstore;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactId;
import org.pragmatica.aether.artifact.GroupId;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.EntryPointId;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.SliceKVSchema.EndpointKey;
import org.pragmatica.aether.slice.kvstore.SliceKVSchema.EndpointValue;
import org.pragmatica.aether.slice.kvstore.SliceKVSchema.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.SliceKVSchema.SliceStateValue;
import org.pragmatica.cluster.net.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

class SliceKVSchemaTest {

    @Test
    void slice_node_key_serialization_roundtrip() {
        var groupId = GroupId.groupId("com.example").unwrap();
        var artifactId = ArtifactId.artifactId("test-slice").unwrap();
        var version = Version.version(1, 0, 0, Option.none()).unwrap();
        var artifact = Artifact.artifact(groupId, artifactId, version);
        var nodeId = NodeId.nodeId("42");
        var key = new SliceNodeKey(artifact, nodeId);

        var serialized = key.asString();
        assertThat(serialized).isEqualTo("slices/42/com.example:test-slice:1.0.0");

        var deserialized = SliceNodeKey.sliceNodeKey(serialized);
        assertThat(deserialized.isSuccess()).isTrue();
        assertThat(deserialized.unwrap()).isEqualTo(key);
        assertThat(deserialized.unwrap().artifact()).isEqualTo(artifact);
        assertThat(deserialized.unwrap().nodeId()).isEqualTo(nodeId);
    }

    @Test
    void slice_node_key_invalid_format_returns_failure() {
        var result1 = SliceNodeKey.sliceNodeKey("invalid-key");
        assertThat(result1.isFailure()).isTrue();

        var result2 = SliceNodeKey.sliceNodeKey("slice-invalid");
        assertThat(result2.isFailure()).isTrue();

        var result3 = SliceNodeKey.sliceNodeKey("slice-42-invalid-artifact");
        assertThat(result3.isFailure()).isTrue();
    }

    @Test
    void slice_state_value_serialization_roundtrip() {
        var state = SliceState.ACTIVE;
        var timestamp = System.currentTimeMillis();
        var version = 5L;
        var value = new SliceStateValue(state, timestamp, version);

        var serialized = value.asString();
        assertThat(serialized).isEqualTo("ACTIVE:" + timestamp + ":" + version);

        var deserialized = SliceStateValue.sliceStateValue(serialized);
        assertThat(deserialized.isSuccess()).isTrue();
        assertThat(deserialized.unwrap()).isEqualTo(value);
        assertThat(deserialized.unwrap().state()).isEqualTo(state);
        assertThat(deserialized.unwrap().timestamp()).isEqualTo(timestamp);
        assertThat(deserialized.unwrap().version()).isEqualTo(version);
    }

    @Test
    void slice_state_value_invalid_format_returns_failure() {
        var result1 = SliceStateValue.sliceStateValue("invalid-format");
        assertThat(result1.isFailure()).isTrue();

        var result2 = SliceStateValue.sliceStateValue("ACTIVE:123");
        assertThat(result2.isFailure()).isTrue();

        var result3 = SliceStateValue.sliceStateValue("INVALID:123:456");
        assertThat(result3.isFailure()).isTrue();
    }

    @Test
    void endpoint_key_serialization_roundtrip() {
        var groupId = GroupId.groupId("org.example").unwrap();
        var artifactId = ArtifactId.artifactId("web-service").unwrap();
        var version = Version.version(2, 1, 0, Option.none()).unwrap();
        var artifact = Artifact.artifact(groupId, artifactId, version);
        var entryPointId = EntryPointId.entryPointId("api").unwrap();
        var instanceNumber = 1;
        var key = new EndpointKey(artifact, entryPointId, instanceNumber);

        var serialized = key.asString();
        assertThat(serialized).isEqualTo("endpoints/org.example:web-service:2.1.0/api:1");

        var deserialized = EndpointKey.endpointKey(serialized);
        assertThat(deserialized.isSuccess()).isTrue();
        assertThat(deserialized.unwrap()).isEqualTo(key);
        assertThat(deserialized.unwrap().artifact()).isEqualTo(artifact);
        assertThat(deserialized.unwrap().entryPointId()).isEqualTo(entryPointId);
        assertThat(deserialized.unwrap().instanceNumber()).isEqualTo(instanceNumber);
    }

    @Test
    void endpoint_key_with_complex_entry_point_id() {
        var groupId = GroupId.groupId("com.test").unwrap();
        var artifactId = ArtifactId.artifactId("service").unwrap();
        var version = Version.version(1, 0, 0, Option.none()).unwrap();
        var artifact = Artifact.artifact(groupId, artifactId, version);
        var entryPointId = EntryPointId.entryPointId("restapiv2").unwrap();
        var instanceNumber = 3;
        var key = new EndpointKey(artifact, entryPointId, instanceNumber);

        var serialized = key.asString();
        assertThat(serialized).isEqualTo("endpoints/com.test:service:1.0.0/restapiv2:3");

        var deserialized = EndpointKey.endpointKey(serialized);
        assertThat(deserialized.isSuccess()).isTrue();
        assertThat(deserialized.unwrap()).isEqualTo(key);
    }

    @Test
    void endpoint_key_invalid_format_returns_failure() {
        var result1 = EndpointKey.endpointKey("invalid-key");
        assertThat(result1.isFailure()).isTrue();

        var result2 = EndpointKey.endpointKey("endpoints/no-slash");
        assertThat(result2.isFailure()).isTrue();

        var result3 = EndpointKey.endpointKey("endpoints/invalid:artifact/api-no-colon");
        assertThat(result3.isFailure()).isTrue();
    }

    @Test
    void endpoint_value_serialization_roundtrip() {
        var nodeId = NodeId.nodeId("123");
        var instanceNumber = 5;
        var state = SliceState.ACTIVE;
        var timestamp = System.currentTimeMillis();
        var value = new EndpointValue(nodeId, instanceNumber, state, timestamp);

        var serialized = value.asString();
        assertThat(serialized).isEqualTo("123:5:ACTIVE:" + timestamp);

        var deserialized = EndpointValue.endpointValue(serialized);
        assertThat(deserialized.isSuccess()).isTrue();
        assertThat(deserialized.unwrap()).isEqualTo(value);
        assertThat(deserialized.unwrap().nodeId()).isEqualTo(nodeId);
        assertThat(deserialized.unwrap().instanceNumber()).isEqualTo(instanceNumber);
        assertThat(deserialized.unwrap().state()).isEqualTo(state);
        assertThat(deserialized.unwrap().timestamp()).isEqualTo(timestamp);
    }

    @Test
    void endpoint_value_invalid_format_returns_failure() {
        var result1 = EndpointValue.endpointValue("invalid-format");
        assertThat(result1.isFailure()).isTrue();

        var result2 = EndpointValue.endpointValue("123:5:INVALID");
        assertThat(result2.isFailure()).isTrue();

        var result3 = EndpointValue.endpointValue("123:not-number:ACTIVE:123");
        assertThat(result3.isFailure()).isTrue();
    }

    @Test
    void keys_equality_and_hashing() {
        var groupId = GroupId.groupId("com.test").unwrap();
        var artifactId = ArtifactId.artifactId("slice").unwrap();
        var version = Version.version(1, 0, 0, Option.none()).unwrap();
        var artifact = Artifact.artifact(groupId, artifactId, version);
        var nodeId = NodeId.nodeId("1");
        var entryPoint = EntryPointId.entryPointId("api").unwrap();

        var key1 = new SliceNodeKey(artifact, nodeId);
        var key2 = new SliceNodeKey(artifact, nodeId);
        var key3 = new SliceNodeKey(artifact, NodeId.nodeId("2"));

        assertThat(key1).isEqualTo(key2);
        assertThat(key1).isNotEqualTo(key3);
        assertThat(key1.hashCode()).isEqualTo(key2.hashCode());

        var endpointKey1 = new EndpointKey(artifact, entryPoint, 1);
        var endpointKey2 = new EndpointKey(artifact, entryPoint, 1);
        var endpointKey3 = new EndpointKey(artifact, EntryPointId.entryPointId("other").unwrap(), 1);

        assertThat(endpointKey1).isEqualTo(endpointKey2);
        assertThat(endpointKey1).isNotEqualTo(endpointKey3);
        assertThat(endpointKey1.hashCode()).isEqualTo(endpointKey2.hashCode());
    }
}
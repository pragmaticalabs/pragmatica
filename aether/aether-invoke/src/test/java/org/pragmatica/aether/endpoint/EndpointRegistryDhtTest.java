package org.pragmatica.aether.endpoint;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey.EndpointKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.EndpointValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EndpointRegistryDhtTest {
    private EndpointRegistry registry;
    private Artifact artifact;
    private MethodName method;
    private NodeId nodeId;

    @BeforeEach
    void setUp() {
        registry = EndpointRegistry.endpointRegistry();
        artifact = Artifact.artifact("org.example:test-slice:1.0.0").unwrap();
        method = MethodName.methodName("process").unwrap();
        nodeId = new NodeId("test-node");
    }

    @Test
    void registerEndpoint_directRegistration_endpointAvailable() {
        var key = new EndpointKey(artifact, method, 0);
        var value = EndpointValue.endpointValue(nodeId);

        registry.registerEndpoint(key, value);

        var selected = registry.selectEndpoint(artifact, method);
        assertThat(selected.isPresent()).isTrue();
        assertThat(selected.unwrap().nodeId()).isEqualTo(nodeId);
    }

    @Test
    void unregisterEndpoint_afterRegistration_endpointRemoved() {
        var key = new EndpointKey(artifact, method, 0);
        var value = EndpointValue.endpointValue(nodeId);

        registry.registerEndpoint(key, value);
        registry.unregisterEndpoint(key);

        var selected = registry.selectEndpoint(artifact, method);
        assertThat(selected.isEmpty()).isTrue();
    }

    @Test
    void onNodeArtifactPut_registersEndpoints() {
        var naKey = NodeArtifactKey.nodeArtifactKey(nodeId, artifact);
        var naValue = NodeArtifactValue.activeNodeArtifactValue(0, List.of(method.name()));
        var command = new KVCommand.Put<>(naKey, naValue);
        var notification = new ValuePut<>(command, Option.none());

        registry.onNodeArtifactPut(notification);

        var selected = registry.selectEndpoint(artifact, method);
        assertThat(selected.isPresent()).isTrue();
        assertThat(selected.unwrap().nodeId()).isEqualTo(nodeId);
    }

    @Test
    void onNodeArtifactRemove_unregistersEndpoints() {
        var naKey = NodeArtifactKey.nodeArtifactKey(nodeId, artifact);
        var naValue = NodeArtifactValue.activeNodeArtifactValue(0, List.of(method.name()));
        var putCommand = new KVCommand.Put<>(naKey, naValue);
        registry.onNodeArtifactPut(new ValuePut<>(putCommand, Option.none()));

        var removeCommand = new KVCommand.Remove<NodeArtifactKey>(naKey);
        registry.onNodeArtifactRemove(new ValueRemove<>(removeCommand, Option.none()));

        var selected = registry.selectEndpoint(artifact, method);
        assertThat(selected.isEmpty()).isTrue();
    }
}

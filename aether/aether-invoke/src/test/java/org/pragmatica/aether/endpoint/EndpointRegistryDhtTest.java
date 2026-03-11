package org.pragmatica.aether.endpoint;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.dht.MapSubscription;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey.EndpointKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.EndpointValue;
import org.pragmatica.consensus.NodeId;

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
    void asMapSubscription_onPut_registersEndpoint() {
        var subscription = registry.asMapSubscription();
        var key = new EndpointKey(artifact, method, 0);
        var value = EndpointValue.endpointValue(nodeId);

        subscription.onPut(key, value);

        var selected = registry.selectEndpoint(artifact, method);
        assertThat(selected.isPresent()).isTrue();
        assertThat(selected.unwrap().nodeId()).isEqualTo(nodeId);
    }

    @Test
    void asMapSubscription_onRemove_unregistersEndpoint() {
        var subscription = registry.asMapSubscription();
        var key = new EndpointKey(artifact, method, 0);
        var value = EndpointValue.endpointValue(nodeId);

        subscription.onPut(key, value);
        subscription.onRemove(key);

        var selected = registry.selectEndpoint(artifact, method);
        assertThat(selected.isEmpty()).isTrue();
    }

    @Test
    void asMapSubscription_returnsCorrectType() {
        MapSubscription<EndpointKey, EndpointValue> subscription = registry.asMapSubscription();

        assertThat(subscription).isNotNull();
    }
}

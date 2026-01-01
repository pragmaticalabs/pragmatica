package org.pragmatica.aether.endpoint;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.EndpointKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.EndpointValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

class EndpointRegistryTest {

    private EndpointRegistry registry;
    private Artifact artifact;
    private MethodName methodName;
    private NodeId node1;
    private NodeId node2;
    private NodeId node3;

    @BeforeEach
    void setUp() {
        registry = EndpointRegistry.endpointRegistry();
        artifact = Artifact.artifact("org.example:test-slice:1.0.0").unwrap();
        methodName = MethodName.methodName("process").unwrap();
        node1 = NodeId.randomNodeId();
        node2 = NodeId.randomNodeId();
        node3 = NodeId.randomNodeId();
    }

    // === Registration Tests ===

    @Test
    void registry_starts_empty() {
        assertThat(registry.allEndpoints()).isEmpty();
    }

    @Test
    void endpoint_put_registers_endpoint() {
        registerEndpoint(artifact, methodName, 1, node1);

        var endpoints = registry.findEndpoints(artifact, methodName);
        assertThat(endpoints).hasSize(1);
        assertThat(endpoints.getFirst().nodeId()).isEqualTo(node1);
        assertThat(endpoints.getFirst().instanceNumber()).isEqualTo(1);
    }

    @Test
    void multiple_endpoints_can_be_registered() {
        registerEndpoint(artifact, methodName, 1, node1);
        registerEndpoint(artifact, methodName, 2, node2);
        registerEndpoint(artifact, methodName, 3, node3);

        var endpoints = registry.findEndpoints(artifact, methodName);
        assertThat(endpoints).hasSize(3);
    }

    @Test
    void endpoint_remove_unregisters_endpoint() {
        registerEndpoint(artifact, methodName, 1, node1);
        registerEndpoint(artifact, methodName, 2, node2);

        removeEndpoint(artifact, methodName, 1);

        var endpoints = registry.findEndpoints(artifact, methodName);
        assertThat(endpoints).hasSize(1);
        assertThat(endpoints.getFirst().instanceNumber()).isEqualTo(2);
    }

    @Test
    void removing_nonexistent_endpoint_is_safe() {
        removeEndpoint(artifact, methodName, 99);
        assertThat(registry.allEndpoints()).isEmpty();
    }

    // === Lookup Tests ===

    @Test
    void find_returns_empty_for_unknown_artifact() {
        registerEndpoint(artifact, methodName, 1, node1);

        var otherArtifact = Artifact.artifact("org.other:other-slice:1.0.0").unwrap();
        var endpoints = registry.findEndpoints(otherArtifact, methodName);

        assertThat(endpoints).isEmpty();
    }

    @Test
    void find_returns_empty_for_unknown_method() {
        registerEndpoint(artifact, methodName, 1, node1);

        var otherMethod = MethodName.methodName("otherMethod").unwrap();
        var endpoints = registry.findEndpoints(artifact, otherMethod);

        assertThat(endpoints).isEmpty();
    }

    @Test
    void find_filters_by_artifact_and_method() {
        var method1 = MethodName.methodName("methodOne").unwrap();
        var method2 = MethodName.methodName("methodTwo").unwrap();

        registerEndpoint(artifact, method1, 1, node1);
        registerEndpoint(artifact, method2, 1, node2);

        var endpoints1 = registry.findEndpoints(artifact, method1);
        var endpoints2 = registry.findEndpoints(artifact, method2);

        assertThat(endpoints1).hasSize(1);
        assertThat(endpoints1.getFirst().nodeId()).isEqualTo(node1);

        assertThat(endpoints2).hasSize(1);
        assertThat(endpoints2.getFirst().nodeId()).isEqualTo(node2);
    }

    // === Load Balancing Tests ===

    @Test
    void select_returns_empty_when_no_endpoints() {
        var result = registry.selectEndpoint(artifact, methodName);
        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void select_returns_single_endpoint_when_only_one() {
        registerEndpoint(artifact, methodName, 1, node1);

        var result = registry.selectEndpoint(artifact, methodName);

        assertThat(result.isPresent()).isTrue();
        assertThat(result.unwrap().nodeId()).isEqualTo(node1);
    }

    @Test
    void select_uses_round_robin_across_endpoints() {
        registerEndpoint(artifact, methodName, 1, node1);
        registerEndpoint(artifact, methodName, 2, node2);
        registerEndpoint(artifact, methodName, 3, node3);

        // Collect several selections
        var selections = new java.util.ArrayList<NodeId>();
        for (int i = 0; i < 6; i++) {
            var selected = registry.selectEndpoint(artifact, methodName);
            assertThat(selected.isPresent()).isTrue();
            selections.add(selected.unwrap().nodeId());
        }

        // Should have hit each node at least once
        assertThat(selections).contains(node1, node2, node3);

        // Should show round-robin pattern (each appears twice in 6 selections)
        var node1Count = selections.stream().filter(n -> n.equals(node1)).count();
        var node2Count = selections.stream().filter(n -> n.equals(node2)).count();
        var node3Count = selections.stream().filter(n -> n.equals(node3)).count();

        assertThat(node1Count).isEqualTo(2);
        assertThat(node2Count).isEqualTo(2);
        assertThat(node3Count).isEqualTo(2);
    }

    @Test
    void select_round_robin_is_per_artifact_method() {
        var method1 = MethodName.methodName("methodOne").unwrap();
        var method2 = MethodName.methodName("methodTwo").unwrap();

        registerEndpoint(artifact, method1, 1, node1);
        registerEndpoint(artifact, method1, 2, node2);
        registerEndpoint(artifact, method2, 1, node3);

        // Selecting from method1 shouldn't affect method2's counter
        registry.selectEndpoint(artifact, method1);
        registry.selectEndpoint(artifact, method1);

        var result = registry.selectEndpoint(artifact, method2);
        assertThat(result.isPresent()).isTrue();
        assertThat(result.unwrap().nodeId()).isEqualTo(node3);
    }

    // === All Endpoints Tests ===

    @Test
    void all_endpoints_returns_copy() {
        registerEndpoint(artifact, methodName, 1, node1);

        var all1 = registry.allEndpoints();
        registerEndpoint(artifact, methodName, 2, node2);
        var all2 = registry.allEndpoints();

        assertThat(all1).hasSize(1);
        assertThat(all2).hasSize(2);
    }

    // === Endpoint Record Tests ===

    @Test
    void endpoint_to_key_creates_correct_key() {
        var endpoint = new EndpointRegistry.Endpoint(artifact, methodName, 5, node1);
        var key = endpoint.toKey();

        assertThat(key.artifact()).isEqualTo(artifact);
        assertThat(key.methodName()).isEqualTo(methodName);
        assertThat(key.instanceNumber()).isEqualTo(5);
    }

    // === Non-Endpoint Events Ignored ===

    @Test
    void ignores_non_endpoint_key_put() {
        // Send a blueprint key instead of endpoint key
        var blueprintKey = new AetherKey.BlueprintKey(artifact);
        var blueprintValue = new AetherValue.BlueprintValue(3);
        var command = new KVCommand.Put<AetherKey, AetherValue>(blueprintKey, blueprintValue);
        var notification = new ValuePut<>(command, Option.none());

        registry.onValuePut(notification);

        assertThat(registry.allEndpoints()).isEmpty();
    }

    @Test
    void ignores_non_endpoint_key_remove() {
        registerEndpoint(artifact, methodName, 1, node1);

        // Try to remove using a blueprint key
        var blueprintKey = new AetherKey.BlueprintKey(artifact);
        var command = new KVCommand.Remove<AetherKey>(blueprintKey);
        var notification = new ValueRemove<AetherKey, AetherValue>(command, Option.none());

        registry.onValueRemove(notification);

        // Endpoint should still be there
        assertThat(registry.allEndpoints()).hasSize(1);
    }

    // === Helper Methods ===

    private void registerEndpoint(Artifact artifact, MethodName method, int instance, NodeId nodeId) {
        var key = new EndpointKey(artifact, method, instance);
        var value = new EndpointValue(nodeId);
        var command = new KVCommand.Put<AetherKey, AetherValue>(key, value);
        var notification = new ValuePut<>(command, Option.none());
        registry.onValuePut(notification);
    }

    private void removeEndpoint(Artifact artifact, MethodName method, int instance) {
        var key = new EndpointKey(artifact, method, instance);
        var command = new KVCommand.Remove<AetherKey>(key);
        var notification = new ValueRemove<AetherKey, AetherValue>(command, Option.none());
        registry.onValueRemove(notification);
    }
}

package org.pragmatica.aether.endpoint;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.EndpointKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.EndpointValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndpointRegistryTest {
    private EndpointRegistry registry;
    private Artifact artifact;
    private MethodName method;
    private NodeId nodeA;
    private NodeId nodeB;
    private NodeId nodeC;

    @BeforeEach
    void setUp() {
        registry = EndpointRegistry.endpointRegistry();
        artifact = Artifact.artifact("org.example:my-slice:1.0.0").unwrap();
        method = MethodName.methodName("processRequest").unwrap();
        nodeA = new NodeId("node-a");
        nodeB = new NodeId("node-b");
        nodeC = new NodeId("node-c");
    }

    private void registerEndpoint(Artifact artifact, MethodName method, int instance, NodeId nodeId) {
        var key = new EndpointKey(artifact, method, instance);
        var value = EndpointValue.endpointValue(nodeId);
        var put = new KVCommand.Put<AetherKey, AetherValue>(key, value);
        registry.onValuePut(new ValuePut<>(put, Option.none()));
    }

    @Nested
    class AffinitySelection {
        @Test
        void selectEndpointByAffinity_affinityNodeAvailable_returnsAffinityEndpoint() {
            registerEndpoint(artifact, method, 0, nodeA);
            registerEndpoint(artifact, method, 1, nodeB);
            registerEndpoint(artifact, method, 2, nodeC);

            var result = registry.selectEndpointByAffinity(artifact, method, nodeB);

            assertTrue(result.isPresent());
            assertEquals(nodeB, result.unwrap().nodeId());
        }

        @Test
        void selectEndpointByAffinity_affinityNodeUnavailable_fallsBackToRoundRobin() {
            registerEndpoint(artifact, method, 0, nodeA);
            registerEndpoint(artifact, method, 1, nodeB);

            var unknownNode = new NodeId("node-unknown");
            var result = registry.selectEndpointByAffinity(artifact, method, unknownNode);

            assertTrue(result.isPresent());
            // Should fall back to round-robin, which will select one of the available nodes
            var selectedNode = result.unwrap().nodeId();
            assertTrue(selectedNode.equals(nodeA) || selectedNode.equals(nodeB));
        }

        @Test
        void selectEndpointByAffinity_noEndpoints_returnsNone() {
            var result = registry.selectEndpointByAffinity(artifact, method, nodeA);

            assertTrue(result.isEmpty());
        }
    }
}

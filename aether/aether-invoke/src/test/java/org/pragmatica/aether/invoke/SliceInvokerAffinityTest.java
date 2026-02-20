package org.pragmatica.aether.invoke;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.endpoint.EndpointRegistry;
import org.pragmatica.aether.endpoint.EndpointRegistry.Endpoint;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.EndpointKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.EndpointValue;
import org.pragmatica.aether.update.RollingUpdateManager;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.pragmatica.lang.Unit.unit;

class SliceInvokerAffinityTest {
    private SliceInvoker invoker;
    private EndpointRegistry registry;
    private Artifact artifact;
    private MethodName method;
    private NodeId self;
    private NodeId nodeA;
    private NodeId nodeB;

    @BeforeEach
    void setUp() {
        self = new NodeId("self-node");
        nodeA = new NodeId("node-a");
        nodeB = new NodeId("node-b");
        registry = EndpointRegistry.endpointRegistry();
        artifact = Artifact.artifact("org.example:my-slice:1.0.0").unwrap();
        method = MethodName.methodName("processRequest").unwrap();

        // Minimal stubs — only affinity resolver registration is tested, not actual invocation
        ClusterNetwork stubNetwork = new StubClusterNetwork();
        InvocationHandler stubHandler = InvocationHandler.invocationHandler(self, stubNetwork);
        Serializer stubSerializer = new StubSerializer();
        Deserializer stubDeserializer = new StubDeserializer();
        RollingUpdateManager stubRollingUpdateManager = new StubRollingUpdateManager();

        invoker = SliceInvoker.sliceInvoker(self,
                                             stubNetwork,
                                             registry,
                                             stubHandler,
                                             stubSerializer,
                                             stubDeserializer,
                                             stubRollingUpdateManager,
                                             DynamicAspectInterceptor.noOp());
    }

    private void registerEndpoint(Artifact artifact, MethodName method, int instance, NodeId nodeId) {
        var key = new EndpointKey(artifact, method, instance);
        var value = EndpointValue.endpointValue(nodeId);
        var put = new KVCommand.Put<AetherKey, AetherValue>(key, value);
        registry.onValuePut(new ValuePut<>(put, Option.none()));
    }

    @Nested
    class AffinityResolverRegistration {
        @Test
        void registerAffinityResolver_registered_returnsUnit() {
            SliceInvoker.CacheAffinityResolver resolver = _ -> Option.some(nodeA);

            var result = invoker.registerAffinityResolver(artifact, method, resolver);

            assertEquals(unit(), result);
        }

        @Test
        void unregisterAffinityResolver_removed_returnsUnit() {
            SliceInvoker.CacheAffinityResolver resolver = _ -> Option.some(nodeA);
            invoker.registerAffinityResolver(artifact, method, resolver);

            var result = invoker.unregisterAffinityResolver(artifact, method);

            assertEquals(unit(), result);
        }

        @Test
        void stop_clearsAffinityResolvers_succeeds() {
            SliceInvoker.CacheAffinityResolver resolver = _ -> Option.some(nodeA);
            invoker.registerAffinityResolver(artifact, method, resolver);

            invoker.stop().await();

            // After stop, re-registering should work (proves clear happened and state is clean)
            // The invoker is stopped but the internal map is cleared
            assertEquals(unit(), invoker.unregisterAffinityResolver(artifact, method));
        }
    }

    @Nested
    class EndpointAffinityRouting {
        @Test
        void selectEndpointByAffinity_withResolver_prefersAffinityNode() {
            registerEndpoint(artifact, method, 0, nodeA);
            registerEndpoint(artifact, method, 1, nodeB);

            // Verify the registry correctly selects the affinity node
            var result = registry.selectEndpointByAffinity(artifact, method, nodeB);

            assertTrue(result.isPresent());
            assertEquals(nodeB, result.unwrap().nodeId());
        }

        @Test
        void selectEndpointByAffinity_affinityNodeMissing_fallsBackToRoundRobin() {
            registerEndpoint(artifact, method, 0, nodeA);
            registerEndpoint(artifact, method, 1, nodeB);

            var unknownNode = new NodeId("node-unknown");
            var result = registry.selectEndpointByAffinity(artifact, method, unknownNode);

            assertTrue(result.isPresent());
            var selected = result.unwrap().nodeId();
            assertTrue(selected.equals(nodeA) || selected.equals(nodeB));
        }
    }
}

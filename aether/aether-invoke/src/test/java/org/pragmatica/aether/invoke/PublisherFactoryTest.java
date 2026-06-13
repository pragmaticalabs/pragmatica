// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.invoke;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.endpoint.TopicSubscriptionRegistry;
import org.pragmatica.aether.resource.TopicConfig;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.aether.slice.Publisher;
import org.pragmatica.aether.slice.blueprint.BlueprintNamespace;
import org.pragmatica.aether.slice.blueprint.TopicAddressResolver;
import org.pragmatica.aether.slice.kvstore.AetherKey.TopicSubscriptionKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopicSubscriptionValue;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.resource.ResourceVersion;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;

import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class PublisherFactoryTest {
    private PublisherFactory factory;

    @BeforeEach
    void setUp() {
        factory = new PublisherFactory();
    }

    @Nested
    class Metadata {
        @Test
        void resourceType_returnsPublisherClass() {
            assertEquals(Publisher.class, factory.resourceType());
        }

        @Test
        void configType_returnsTopicConfigClass() {
            assertEquals(TopicConfig.class, factory.configType());
        }
    }

    @Nested
    class Provisioning {
        @Test
        void provision_withoutContext_fails() {
            var config = new TopicConfig("orders");

            var result = factory.provision(config).await();

            result.onSuccess(_ -> fail("Expected failure"));
        }

        @Test
        void provision_withExtensions_createsTopicPublisher() {
            var registry = TopicSubscriptionRegistry.topicSubscriptionRegistry();
            var invocations = new CopyOnWriteArrayList<Object>();
            SliceInvoker stubInvoker = new MinimalStubSliceInvoker(invocations);

            var context = ProvisioningContext.provisioningContext()
                                             .withExtension(TopicSubscriptionRegistry.class, registry)
                                             .withExtension(SliceInvoker.class, stubInvoker);
            var config = new TopicConfig("orders");

            var result = factory.provision(config, context).await();

            result.onFailure(_ -> fail("Expected success"))
                  .onSuccess(publisher -> {
                      // Verify it's a functional publisher by publishing a message
                      var publishResult = publisher.publish("test").await();
                      publishResult.onFailure(_ -> fail("Publish should succeed"));
                  });
        }
    }

    /// RC2 #274 — the publisher (provisioned here from the slice-id) and the subscriber (registered
    /// by the deployment FSM) MUST resolve the same bare topic name to the same blueprint-derived
    /// namespace, or co-deployed pub/sub silently stops delivering. The publisher reads its owning
    /// slice's [Artifact] from the provisioning context's slice-id extension and resolves via the
    /// same [TopicAddressResolver] the subscriber uses.
    @Nested
    class NamespaceAlignment {
        private static final MethodName METHOD = MethodName.methodName("onMessage").unwrap();
        private static final NodeId NODE = new NodeId("node-a");

        private TopicSubscriptionRegistry registry;
        private CopyOnWriteArrayList<Object> invocations;

        @BeforeEach
        void setUpAlignment() {
            registry = TopicSubscriptionRegistry.topicSubscriptionRegistry();
            invocations = new CopyOnWriteArrayList<>();
        }

        private void registerBareSubscriptionFor(Artifact subscriberArtifact, String bareTopic) {
            var address = TopicAddressResolver.resolve(subscriberArtifact, bareTopic).unwrap();
            var key = TopicSubscriptionKey.topicSubscriptionKey(address, subscriberArtifact, METHOD);
            var value = TopicSubscriptionValue.topicSubscriptionValue(NODE);
            var put = new KVCommand.Put<>(key, value);
            registry.onSubscriptionPut(new ValuePut<>(put, Option.none()));
        }

        private Publisher<Object> provisionPublisherFor(String sliceArtifact, String bareTopic) {
            var context = ProvisioningContext.provisioningContext()
                                             .withExtension(TopicSubscriptionRegistry.class, registry)
                                             .withExtension(SliceInvoker.class, new MinimalStubSliceInvoker(invocations))
                                             .withExtension(String.class, sliceArtifact);

            @SuppressWarnings("unchecked")
            var publisher = (Publisher<Object>) factory.provision(new TopicConfig(bareTopic), context)
                                                       .await()
                                                       .onFailure(_ -> fail("Provisioning should succeed"))
                                                       .unwrap();
            return publisher;
        }

        @Test
        void provision_bareTopic_resolvesPublisherToBlueprintNamespace() {
            var artifact = Artifact.artifact("org.example:my-slice:1.0.0").unwrap();
            var expectedNamespace = BlueprintNamespace.deriveNamespace(artifact).unwrap();
            var expectedAddress = ResourceAddress.resourceAddress(expectedNamespace,
                                                                  "orders",
                                                                  ResourceVersion.defaultVersion()).unwrap();

            registerBareSubscriptionFor(artifact, "orders");
            var publisher = provisionPublisherFor(artifact.asString(), "orders");

            // The subscriber is stored at the blueprint-derived address (not DEFAULT_NAMESPACE).
            assertEquals(1, registry.findSubscribers(expectedAddress.asString()).size());
            // A publish from the co-deployed publisher reaches it → both resolved the same address.
            publisher.publish("order-1").await().onFailure(_ -> fail("Publish should succeed"));
            assertEquals(1, invocations.size());
        }

        @Test
        void publish_coDeployedBareTopic_reachesSubscriber() {
            var artifact = Artifact.artifact("org.example:my-slice:1.0.0").unwrap();

            registerBareSubscriptionFor(artifact, "orders");
            var publisher = provisionPublisherFor(artifact.asString(), "orders");

            publisher.publish("order-1").await().onFailure(_ -> fail("Publish should succeed"));

            assertEquals(1, invocations.size());
            assertEquals(artifact, invocations.getFirst());
        }

        @Test
        void publish_subscriberInDifferentBlueprint_isNotReached() {
            var publisherArtifact = Artifact.artifact("org.example:publisher-slice:1.0.0").unwrap();
            var subscriberArtifact = Artifact.artifact("org.example:subscriber-slice:1.0.0").unwrap();

            // Both declare the bare topic "orders" but live in different blueprints → different namespaces.
            registerBareSubscriptionFor(subscriberArtifact, "orders");
            var publisher = provisionPublisherFor(publisherArtifact.asString(), "orders");

            publisher.publish("order-1").await().onFailure(_ -> fail("Publish should succeed"));

            assertTrue(invocations.isEmpty());
        }
    }

    /// Minimal stub for SliceInvoker — only implements methods needed for provisioning test.
    /// Records the invoked slice [Artifact] so routing/alignment tests can assert WHICH subscriber
    /// was reached.
    private static final class MinimalStubSliceInvoker implements SliceInvoker {
        private final CopyOnWriteArrayList<Object> invocations;

        MinimalStubSliceInvoker(CopyOnWriteArrayList<Object> invocations) {
            this.invocations = invocations;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <R> Promise<R> invoke(Artifact slice, MethodName method, Object request, TypeToken<R> responseType) {
            invocations.add(slice);
            return (Promise<R>) Promise.unitPromise();
        }

        @Override
        public org.pragmatica.lang.Result<Unit> verifyEndpointExists(Artifact artifact, MethodName method) {
            return org.pragmatica.lang.Result.unitResult();
        }

        @Override
        public Promise<Unit> invoke(Artifact slice, MethodName method, Object request) {
            return Promise.unitPromise();
        }

        @Override
        public <R> Promise<R> invokeWithRetry(Artifact slice, MethodName method, Object request,
                                               TypeToken<R> responseType, int maxRetries) {
            return invoke(slice, method, request, responseType);
        }

        @Override
        public <R> Promise<R> invokeLocal(Artifact slice, MethodName method, Object request,
                                           TypeToken<R> responseType) {
            return invoke(slice, method, request, responseType);
        }

        @Override
        public void onInvokeResponse(InvocationMessage.InvokeResponse response) {}

        @Override
        public void onNodeRemoved(org.pragmatica.consensus.topology.MembershipDecision.NodeRemoved event) {}

        @Override
        public void onNodeDecommissioned(org.pragmatica.consensus.topology.MembershipDecision.NodeDecommissioned event) {}

        @Override
        public void onSelfShutdown(org.pragmatica.consensus.topology.TransportObservation.SelfShutdown event) {}

        @Override
        public Promise<Unit> stop() {
            return Promise.unitPromise();
        }

        @Override
        public int pendingCount() {
            return 0;
        }

        @Override
        public Unit setFailureListener(SliceFailureListener listener) {
            return Unit.unit();
        }

        @Override
        public Unit registerAffinityResolver(Artifact artifact, MethodName method,
                                              CacheAffinityResolver resolver) {
            return Unit.unit();
        }

        @Override
        public Unit unregisterAffinityResolver(Artifact artifact, MethodName method) {
            return Unit.unit();
        }
    }
}

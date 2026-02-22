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
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;

import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /// Minimal stub for SliceInvoker — only implements methods needed for provisioning test.
    private static final class MinimalStubSliceInvoker implements SliceInvoker {
        private final CopyOnWriteArrayList<Object> invocations;

        MinimalStubSliceInvoker(CopyOnWriteArrayList<Object> invocations) {
            this.invocations = invocations;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <R> Promise<R> invoke(Artifact slice, MethodName method, Object request, TypeToken<R> responseType) {
            invocations.add(request);
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
        public void onNodeRemoved(org.pragmatica.consensus.topology.TopologyChangeNotification.NodeRemoved event) {}

        @Override
        public void onNodeDown(org.pragmatica.consensus.topology.TopologyChangeNotification.NodeDown event) {}

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

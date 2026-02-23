package org.pragmatica.aether.invoke;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.endpoint.TopicSubscriptionRegistry;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey.TopicSubscriptionKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopicSubscriptionValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;

import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

class TopicPublisherTest {
    private static final Cause SUBSCRIBER_FAILED = Causes.cause("Subscriber processing failed");

    private TopicSubscriptionRegistry registry;
    private CopyOnWriteArrayList<InvocationRecord> invocations;
    private StubSliceInvoker stubInvoker;
    private Artifact artifact;
    private MethodName method;
    private NodeId nodeA;
    private NodeId nodeB;

    record InvocationRecord(Artifact artifact, MethodName method, Object message) {}

    @BeforeEach
    void setUp() {
        registry = TopicSubscriptionRegistry.topicSubscriptionRegistry();
        invocations = new CopyOnWriteArrayList<>();
        stubInvoker = new StubSliceInvoker(invocations, Option.none());
        artifact = Artifact.artifact("org.example:my-slice:1.0.0").unwrap();
        method = MethodName.methodName("onMessage").unwrap();
        nodeA = new NodeId("node-a");
        nodeB = new NodeId("node-b");
    }

    private void registerSubscription(String topicName, Artifact artifact, MethodName method, NodeId nodeId) {
        var key = TopicSubscriptionKey.topicSubscriptionKey(topicName, artifact, method);
        var value = TopicSubscriptionValue.topicSubscriptionValue(nodeId);
        var put = new KVCommand.Put<>(key, value);
        registry.onSubscriptionPut(new ValuePut<>(put, Option.none()));
    }

    @Nested
    class Publish {
        @Test
        void publish_noSubscribers_returnsUnitPromise() {
            var publisher = new TopicPublisher<>("orders", registry, stubInvoker);

            var result = publisher.publish("test-message").await();

            result.onFailure(_ -> fail("Expected success"))
                  .onSuccess(unit -> assertEquals(Unit.unit(), unit));
        }

        @Test
        void publish_singleSubscriber_invokesSliceInvoker() {
            registerSubscription("orders", artifact, method, nodeA);
            var publisher = new TopicPublisher<>("orders", registry, stubInvoker);

            var result = publisher.publish("order-123").await();

            result.onFailure(_ -> fail("Expected success"));
            assertEquals(1, invocations.size());
            assertEquals(artifact, invocations.getFirst().artifact());
            assertEquals(method, invocations.getFirst().method());
            assertEquals("order-123", invocations.getFirst().message());
        }

        @Test
        void publish_multipleSubscribers_invokesAll() {
            var artifact2 = Artifact.artifact("org.example:billing:1.0.0").unwrap();
            var method2 = MethodName.methodName("handleOrder").unwrap();

            registerSubscription("orders", artifact, method, nodeA);
            registerSubscription("orders", artifact2, method2, nodeB);

            var publisher = new TopicPublisher<>("orders", registry, stubInvoker);

            var result = publisher.publish("order-456").await();

            result.onFailure(_ -> fail("Expected success"));
            assertEquals(2, invocations.size());
        }

        @Test
        void publish_subscriberFailure_completesSuccessfully() {
            // allOf collects results without propagating individual failures
            registerSubscription("orders", artifact, method, nodeA);
            var failingInvoker = new StubSliceInvoker(invocations, Option.some(SUBSCRIBER_FAILED));
            var publisher = new TopicPublisher<>("orders", registry, failingInvoker);

            var result = publisher.publish("order-789").await();

            result.onFailure(_ -> fail("Expected success — allOf does not propagate subscriber failures"));
            assertEquals(1, invocations.size());
        }
    }

    /// Minimal stub implementing only the invoke method used by TopicPublisher.
    private static final class StubSliceInvoker implements SliceInvoker {
        private final CopyOnWriteArrayList<InvocationRecord> invocations;
        private final Option<Cause> failureCause;

        StubSliceInvoker(CopyOnWriteArrayList<InvocationRecord> invocations, Option<Cause> failureCause) {
            this.invocations = invocations;
            this.failureCause = failureCause;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <R> Promise<R> invoke(Artifact slice, MethodName method, Object request, TypeToken<R> responseType) {
            invocations.add(new InvocationRecord(slice, method, request));
            return failureCause.fold(() -> (Promise<R>) Promise.unitPromise(),
                                     Cause::promise);
        }

        // --- Unused methods below — minimal stubs for compilation ---

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
        public void onInvokeResponse(org.pragmatica.aether.invoke.InvocationMessage.InvokeResponse response) {}

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

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
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey.TopicSubscriptionKey;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.resource.ResourceVersion;
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

import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
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
        registerSubscriptionAt(resourceAddress(topicName), artifact, method, nodeId);
    }

    private void registerSubscriptionAt(ResourceAddress address, Artifact artifact, MethodName method, NodeId nodeId) {
        var key = TopicSubscriptionKey.topicSubscriptionKey(address, artifact, method);
        var value = TopicSubscriptionValue.topicSubscriptionValue(nodeId);
        var put = new KVCommand.Put<>(key, value);
        registry.onSubscriptionPut(new ValuePut<>(put, Option.none()));
    }

    private static ResourceAddress resourceAddress(String topicName) {
        return ResourceAddress.resourceAddress(ResourceAddress.DEFAULT_NAMESPACE, topicName, ResourceVersion.defaultVersion()).unwrap();
    }

    /// Full routing identity (`namespace:name:version`) for a bare topic name in the default namespace.
    private static String routingKey(String topicName) {
        return resourceAddress(topicName).asString();
    }

    private static final String PUBLISHER_SLICE = "org.example:order-publisher:1.0.0";

    private TopicPublisher<String> publisher(String topicName, String topicAddress, SliceInvoker invoker) {
        return TopicPublisher.topicPublisher(topicName, topicAddress, PUBLISHER_SLICE, registry, invoker, Option.none());
    }

    /// A publisher whose WARN rate limit runs on a clock the test advances by hand.
    private TopicPublisher<String> publisher(String topicName, AtomicLong clockNanos, Option<MeterRegistry> meters) {
        return TopicPublisher.topicPublisher(topicName,
                                             routingKey(topicName),
                                             PUBLISHER_SLICE,
                                             registry,
                                             stubInvoker,
                                             meters,
                                             clockNanos::get);
    }

    @Nested
    class Publish {
        @Test
        void publish_noSubscribers_returnsUnitPromise() {
            var publisher = publisher("orders", routingKey("orders"), stubInvoker);

            var result = publisher.publish("test-message").await();

            result.onFailure(_ -> fail("Expected success"))
                  .onSuccess(unit -> assertEquals(Unit.unit(), unit));
        }

        /// #1216: the empty-subscriber branch returned success with no log line, which is exactly
        /// what hid a release-long address mismatch. The contract (success, zero deliveries) is kept
        /// by the test above; this one pins that the branch is now LOUD, naming the three things an
        /// operator needs to compare against the `topic-sub/` keys: topic, resolved address, publisher.
        @Test
        void publish_noSubscribers_isObservable() {
            var warnings = new ArrayList<String>();
            var detach = LogCapture.warningsOf(TopicPublisher.class, warnings);

            try {
                publisher("orders", routingKey("orders"), stubInvoker).publish("test-message").await();
            } finally {
                detach.run();
            }

            assertThat(warnings).describedAs("one WARN for the first undelivered publish").hasSize(1);
            assertThat(warnings.getFirst()).contains("'orders'")
                                           .contains(routingKey("orders"))
                                           .contains(PUBLISHER_SLICE)
                                           .contains("no subscribers")
                                           .contains("0 more undelivered");
            assertThat(invocations).isEmpty();
        }

        /// rev1421 MEDIUM-1: 10,000 undelivered publishes produced 10,000 identical WARN lines. The
        /// limit is one line per publisher per [TopicPublisher#WARN_PERIOD]; the publishes it
        /// suppresses are counted into the next line, so nothing is lost, only compressed.
        @Test
        void publish_repeatedUndelivered_warnsOncePerPeriod_andReportsTheSuppressedCount() {
            var clock = new AtomicLong();
            var publisher = publisher("orders", clock, Option.none());
            var warnings = new ArrayList<String>();
            var detach = LogCapture.warningsOf(TopicPublisher.class, warnings);

            try {
                publisher.publish("m1").await();
                publisher.publish("m2").await();
                publisher.publish("m3").await();
                assertThat(warnings).describedAs("three publishes inside one period: one WARN").hasSize(1);

                clock.addAndGet(TopicPublisher.WARN_PERIOD.nanos() - 1);
                publisher.publish("m4").await();
                assertThat(warnings).describedAs("still inside the period").hasSize(1);

                clock.addAndGet(1);
                publisher.publish("m5").await();
            } finally {
                detach.run();
            }

            assertThat(warnings).describedAs("the period elapsed: a second WARN").hasSize(2);
            assertThat(warnings.get(1)).contains("3 more undelivered");
        }

        /// rev1421 MEDIUM-2: the node's `MeterRegistry` reaches the publisher through provisioning, so
        /// every undelivered publish counts — the rate-limited WARN compresses, the counter does not.
        @Test
        void publish_noSubscribers_incrementsTheUndeliveredCounter() {
            var meters = new SimpleMeterRegistry();
            var publisher = publisher("orders", new AtomicLong(), Option.some(meters));

            publisher.publish("m1").await();
            publisher.publish("m2").await();
            publisher.publish("m3").await();

            var counter = meters.find(TopicPublisher.UNDELIVERED_COUNTER)
                                .tags("topic", "orders", "address", routingKey("orders"), "slice", PUBLISHER_SLICE)
                                .counter();

            assertThat(counter).describedAs("counter registered with topic, address and slice tags").isNotNull();
            assertThat(counter.count()).isEqualTo(3.0);
        }

        @Test
        void publish_withSubscriber_doesNotCount() {
            registerSubscription("orders", artifact, method, nodeA);
            var meters = new SimpleMeterRegistry();
            var publisher = publisher("orders", new AtomicLong(), Option.some(meters));

            publisher.publish("m1").await();

            assertThat(meters.find(TopicPublisher.UNDELIVERED_COUNTER).counter().count()).isZero();
            assertEquals(1, invocations.size());
        }

        /// The control for the pin above: a delivered publish must not WARN, or the line would be
        /// noise rather than signal.
        @Test
        void publish_withSubscriber_doesNotWarn() {
            registerSubscription("orders", artifact, method, nodeA);
            var warnings = new ArrayList<String>();
            var detach = LogCapture.warningsOf(TopicPublisher.class, warnings);

            try {
                publisher("orders", routingKey("orders"), stubInvoker).publish("order-1").await();
            } finally {
                detach.run();
            }

            assertThat(warnings).isEmpty();
            assertEquals(1, invocations.size());
        }

        @Test
        void publish_singleSubscriber_invokesSliceInvoker() {
            registerSubscription("orders", artifact, method, nodeA);
            var publisher = publisher("orders", routingKey("orders"), stubInvoker);

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

            var publisher = publisher("orders", routingKey("orders"), stubInvoker);

            var result = publisher.publish("order-456").await();

            result.onFailure(_ -> fail("Expected success"));
            assertEquals(2, invocations.size());
        }

        @Test
        void publish_subscriberFailure_completesSuccessfully() {
            // allOf collects results without propagating individual failures
            registerSubscription("orders", artifact, method, nodeA);
            var failingInvoker = new StubSliceInvoker(invocations, Option.some(SUBSCRIBER_FAILED));
            var publisher = publisher("orders", routingKey("orders"), failingInvoker);

            var result = publisher.publish("order-789").await();

            result.onFailure(_ -> fail("Expected success — allOf does not propagate subscriber failures"));
            assertEquals(1, invocations.size());
        }

        @Test
        void publish_sameBareNameDifferentNamespace_doesNotCrossDeliver() {
            var nsA = ResourceAddress.resourceAddress("ns-a", "events", "1.0.0").unwrap();
            var nsB = ResourceAddress.resourceAddress("ns-b", "events", "1.0.0").unwrap();
            var artifactA = Artifact.artifact("org.example:slice-a:1.0.0").unwrap();
            var artifactB = Artifact.artifact("org.example:slice-b:1.0.0").unwrap();

            registerSubscriptionAt(nsA, artifactA, method, nodeA);
            registerSubscriptionAt(nsB, artifactB, method, nodeB);

            var publisher = publisher("events", nsA.asString(), stubInvoker);

            var result = publisher.publish("event-1").await();

            result.onFailure(_ -> fail("Expected success"));
            assertEquals(1, invocations.size());
            assertEquals(artifactA, invocations.getFirst().artifact());
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

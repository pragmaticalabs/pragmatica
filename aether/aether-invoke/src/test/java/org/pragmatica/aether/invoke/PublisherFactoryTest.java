// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.endpoint.TopicSubscriptionRegistry;
import org.pragmatica.aether.resource.ResourceProvisioningError;
import org.pragmatica.aether.resource.SpiResourceProvider;
import org.pragmatica.aether.resource.TopicConfig;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.aether.slice.Publisher;
import org.pragmatica.aether.slice.blueprint.BlueprintNamespace;
import org.pragmatica.aether.slice.blueprint.OwningBlueprintResolver;
import org.pragmatica.aether.slice.blueprint.TopicAddressResolver;
import org.pragmatica.aether.slice.kvstore.AetherKey.TopicSubscriptionKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopicSubscriptionValue;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.resource.ResourceVersion;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.config.ConfigError;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
                                 var publishResult = publisher.publish("test")
                                                              .await();

                                 publishResult.onFailure(_ -> fail("Publish should succeed"));
                             });
        }
    }

    /// #386 D1/D5 — the declared durability class selects the provisioned publisher: DURABLE
    /// topics get the stream-backed publisher (topic + DLQ streams activated eagerly at provision,
    /// in one step), EPHEMERAL topics keep the RPC fan-out `TopicPublisher`. A durable declaration
    /// that bypassed parse validation fails provisioning loudly instead of silently downgrading.
    @Nested
    class DurableTierProvisioning {
        private static final org.pragmatica.serialization.Serializer NOOP_SERIALIZER = new org.pragmatica.serialization.Serializer() {
            @Override
            public <T> void write(io.netty.buffer.ByteBuf byteBuf, T object) {
                byteBuf.writeBytes(String.valueOf(object).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        };

        @Test
        void provision_durableTopic_createsStreamBackedPublisher_andActivatesBothStreams() throws Exception {
            var manager = org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager();

            try {
                var config = TopicConfig.topicConfig("orders",
                                                     org.pragmatica.aether.resource.TopicDurability.DURABLE,
                                                     Option.none(),
                                                     Option.none(),
                                                     Option.none(),
                                                     Option.none())
                                        .unwrap();
                var context = ProvisioningContext.provisioningContext()
                                                 .withExtension(org.pragmatica.aether.stream.StreamPartitionManager.class,
                                                                manager)
                                                 .withExtension(org.pragmatica.serialization.Serializer.class,
                                                                NOOP_SERIALIZER);
                var publisher = factory.provision(config, context)
                                       .await()
                                       .onFailure(cause -> fail(cause.message()))
                                       .unwrap();

                assertTrue(publisher instanceof org.pragmatica.aether.stream.topic.DurableTopicPublisher<?>);
                // Bare name, no slice-id extension -> default-namespace address backs the stream pair.
                assertTrue(manager.partitionBuffer("topic:default:orders:1.0.0", 0).isPresent());
                assertTrue(manager.partitionBuffer("topic:default:orders:1.0.0.dlq", 0).isPresent());
            } finally {
                manager.close();
            }
        }

        @Test
        void provision_durableTopic_failsLoudly_whenDeclarationBypassedValidation() throws Exception {
            var manager = org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager();

            try {
                // Canonical-constructor bypass with replicas=1 — the §3-invalid shape the factory rejects.
                var config = new TopicConfig("orders",
                                             org.pragmatica.aether.resource.TopicDurability.DURABLE,
                                             Option.none(),
                                             Option.some(1),
                                             Option.none(),
                                             Option.none());
                var context = ProvisioningContext.provisioningContext()
                                                 .withExtension(org.pragmatica.aether.stream.StreamPartitionManager.class,
                                                                manager)
                                                 .withExtension(org.pragmatica.serialization.Serializer.class,
                                                                NOOP_SERIALIZER);

                factory.provision(config, context)
                       .await()
                       .onSuccess(_ -> fail("a §3-invalid durable declaration must not provision"));
            } finally {
                manager.close();
            }
        }
    }

    /// RC2 #274 + #1216 — the publisher (provisioned here from the slice-id) and the subscriber
    /// (registered by the deployment FSM) MUST resolve the same bare topic name to the same
    /// BLUEPRINT-derived namespace, or co-deployed pub/sub silently stops delivering.
    ///
    /// #1216 REWROTE THIS FIXTURE BECAUSE IT SPECIFIED THE DEFECT RATHER THAN MISSING IT. Its
    /// co-deployment test passed ONE artifact to both ends, which is not co-deployment at all, and
    /// its sibling passed two distinct SLICES, asserted non-delivery, and commented that they lived
    /// in different blueprints — while the namespace derivation never saw a blueprint. That second
    /// test therefore asserted the production failure AS CORRECT BEHAVIOUR: any two co-deployed
    /// distinct slices could never agree on an address, and `TopicPublisher` reported success having
    /// delivered nothing.
    ///
    /// Both tests below now use the SAME two distinct slice artifacts, so slice-distinctness is held
    /// constant and the OWNING BLUEPRINT is the only variable that differs between them. That pairing
    /// is what makes them statements about blueprints; either one alone is satisfied by the defect.
    @Nested
    class NamespaceAlignment {
        private static final MethodName METHOD = MethodName.methodName("onMessage").unwrap();
        private static final NodeId NODE = new NodeId("node-a");

        private static final Artifact BLUEPRINT = Artifact.artifact("org.example:orders-app:1.0.0").unwrap();
        private static final Artifact OTHER_BLUEPRINT = Artifact.artifact("org.example:billing-app:1.0.0").unwrap();
        private static final Artifact PUBLISHER_SLICE = Artifact.artifact("org.example:order-intake:1.0.0").unwrap();
        private static final Artifact SUBSCRIBER_SLICE = Artifact.artifact("org.example:order-audit:1.0.0").unwrap();

        private TopicSubscriptionRegistry registry;
        private CopyOnWriteArrayList<Object> invocations;

        @BeforeEach
        void setUpAlignment() {
            registry = TopicSubscriptionRegistry.topicSubscriptionRegistry();
            invocations = new CopyOnWriteArrayList<>();
        }

        /// What `NodeDeploymentState` writes: the address scoped to the blueprint OWNING the
        /// subscribing slice, keyed by the subscribing slice's own artifact.
        private void registerBareSubscriptionFor(Option<Artifact> owningBlueprint,
                                                 Artifact subscriberArtifact,
                                                 String bareTopic) {
            var address = TopicAddressResolver.resolve(owningBlueprint, subscriberArtifact, bareTopic).unwrap();
            var key = TopicSubscriptionKey.topicSubscriptionKey(address, subscriberArtifact, METHOD, NODE);
            var value = TopicSubscriptionValue.topicSubscriptionValue(NODE);
            var put = new KVCommand.Put<>(key, value);

            registry.onSubscriptionPut(new ValuePut<>(put, Option.none()));
        }

        /// A publisher provisioned as a real node provisions one: the slice-id extension plus the
        /// node-registered [OwningBlueprintResolver]. Passing [Option#none] models a runtime with no
        /// resolver registered at all (see [#provision_noResolver_bothEndsFallBackToSliceCoordinates]).
        private Publisher<Object> provisionPublisherFor(Option<Artifact> owningBlueprint,
                                                        Artifact sliceArtifact,
                                                        String bareTopic) {
            var context = ProvisioningContext.provisioningContext()
                                             .withExtension(TopicSubscriptionRegistry.class, registry)
                                             .withExtension(SliceInvoker.class,
                                                            new MinimalStubSliceInvoker(invocations))
                                             .withExtension(String.class, sliceArtifact.asString())
                                             .withExtension(OwningBlueprintResolver.class,
                                                            _ -> owningBlueprint);
            @SuppressWarnings("unchecked")
            var publisher = (Publisher<Object>) factory.provision(new TopicConfig(bareTopic),
                                                                  context)
                                                       .await()
                                                       .onFailure(_ -> fail("Provisioning should succeed"))
                                                       .unwrap();

            return publisher;
        }

        @Test
        void provision_bareTopic_resolvesPublisherToBlueprintNamespace() {
            var blueprintNamespace = BlueprintNamespace.deriveNamespace(BLUEPRINT).unwrap();
            var sliceNamespace = BlueprintNamespace.deriveNamespace(PUBLISHER_SLICE).unwrap();
            var expectedAddress = ResourceAddress.resourceAddress(blueprintNamespace,
                                                                  "orders",
                                                                  ResourceVersion.defaultVersion())
                                                 .unwrap();

            // The two namespaces must genuinely differ, or the assertions below pass for the wrong
            // reason — this is the discriminator the pre-#1216 fixture lacked.
            assertTrue(!blueprintNamespace.equals(sliceNamespace));

            registerBareSubscriptionFor(Option.some(BLUEPRINT), PUBLISHER_SLICE, "orders");
            var publisher = provisionPublisherFor(Option.some(BLUEPRINT), PUBLISHER_SLICE, "orders");

            // The subscriber is stored at the BLUEPRINT-derived address, not the slice-derived one.
            assertEquals(1, registry.findSubscribers(expectedAddress.asString()).size());
            assertEquals(0,
                         registry.findSubscribers(ResourceAddress.resourceAddress(sliceNamespace,
                                                                                   "orders",
                                                                                   ResourceVersion.defaultVersion())
                                                                 .unwrap()
                                                                 .asString())
                                 .size());
            publisher.publish("order-1").await().onFailure(_ -> fail("Publish should succeed"));
            assertEquals(1, invocations.size());
        }

        /// THE ACCEPTANCE TEST FOR #1216: two DISTINCT slice artifacts co-deployed in ONE blueprint.
        /// The pre-#1216 version of this test passed one artifact to both ends and so never exercised
        /// co-deployment; pointed at two artifacts it failed `expected: <1> but was: <0>` while the
        /// publish itself still reported SUCCESS, which is the production symptom exactly.
        @Test
        void publish_coDeployedBareTopic_reachesSubscriber() {
            registerBareSubscriptionFor(Option.some(BLUEPRINT), SUBSCRIBER_SLICE, "orders");
            var publisher = provisionPublisherFor(Option.some(BLUEPRINT), PUBLISHER_SLICE, "orders");

            publisher.publish("order-1").await().onFailure(_ -> fail("Publish should succeed"));
            assertEquals(1, invocations.size());
            assertEquals(SUBSCRIBER_SLICE, invocations.getFirst());
        }

        /// The reverse direction: either co-deployed slice may be the publisher.
        @Test
        void publish_coDeployedBareTopic_reachesSubscriber_inTheReverseDirection() {
            registerBareSubscriptionFor(Option.some(BLUEPRINT), PUBLISHER_SLICE, "orders");
            var publisher = provisionPublisherFor(Option.some(BLUEPRINT), SUBSCRIBER_SLICE, "orders");

            publisher.publish("order-1").await().onFailure(_ -> fail("Publish should succeed"));
            assertEquals(1, invocations.size());
            assertEquals(PUBLISHER_SLICE, invocations.getFirst());
        }

        /// Genuinely about two BLUEPRINTS: same two distinct slices as the co-deployment test above,
        /// differing ONLY in their owner. Before #1216 this test used two slices in ONE (unnamed)
        /// blueprint and passed because co-deployment was broken — it specified the defect.
        @Test
        void publish_subscriberInDifferentBlueprint_isNotReached() {
            registerBareSubscriptionFor(Option.some(OTHER_BLUEPRINT), SUBSCRIBER_SLICE, "orders");
            var publisher = provisionPublisherFor(Option.some(BLUEPRINT), PUBLISHER_SLICE, "orders");

            publisher.publish("order-1").await().onFailure(_ -> fail("Publish should succeed"));
            assertTrue(invocations.isEmpty());
        }

        /// #1216: the undelivered publish above is the ticket's exact shape, and it used to leave no
        /// trace. Through the REAL factory, the WARN must carry what the factory alone knows — the
        /// bare topic name from the config and the publishing slice from the provisioning context —
        /// plus the address the factory resolved, so an operator can set it against `topic-sub/`.
        @Test
        void provision_undeliveredPublish_warnsNamingTopicResolvedAddressAndPublishingSlice() {
            var expectedAddress = TopicAddressResolver.resolve(Option.some(BLUEPRINT), PUBLISHER_SLICE, "orders")
                                                      .unwrap()
                                                      .asString();
            registerBareSubscriptionFor(Option.some(OTHER_BLUEPRINT), SUBSCRIBER_SLICE, "orders");
            var publisher = provisionPublisherFor(Option.some(BLUEPRINT), PUBLISHER_SLICE, "orders");
            var warnings = new ArrayList<String>();
            var detach = LogCapture.warningsOf(TopicPublisher.class, warnings);

            try {
                publisher.publish("order-1").await().onFailure(_ -> fail("Publish should succeed"));
            } finally {
                detach.run();
            }

            assertEquals(1, warnings.size(), "one WARN for the one undelivered publish: " + warnings);
            var line = warnings.getFirst();
            assertTrue(line.contains("'orders'"), "names the bare topic: " + line);
            assertTrue(line.contains(expectedAddress), "names the resolved address: " + line);
            assertTrue(line.contains(PUBLISHER_SLICE.asString()), "names the publishing slice: " + line);
            assertTrue(!line.contains(PublisherFactory.UNSCOPED_PUBLISHER), "the slice id was in the context, so no placeholder: " + line);
        }

        /// rev1421 MEDIUM-2: the factory hands the node's `MeterRegistry` (a provisioning-context
        /// extension, #278) to the publisher, so undelivered publishes are counted per topic,
        /// address and publishing slice — the surface that survives a flood the WARN rate-limits.
        @Test
        void provision_undeliveredPublish_countsOnTheContextsMeterRegistry() {
            var meters = new SimpleMeterRegistry();
            var expectedAddress = TopicAddressResolver.resolve(Option.some(BLUEPRINT), PUBLISHER_SLICE, "orders")
                                                      .unwrap()
                                                      .asString();
            registerBareSubscriptionFor(Option.some(OTHER_BLUEPRINT), SUBSCRIBER_SLICE, "orders");
            var context = ProvisioningContext.provisioningContext()
                                             .withExtension(TopicSubscriptionRegistry.class, registry)
                                             .withExtension(SliceInvoker.class, new MinimalStubSliceInvoker(invocations))
                                             .withExtension(String.class, PUBLISHER_SLICE.asString())
                                             .withExtension(OwningBlueprintResolver.class, _ -> Option.some(BLUEPRINT))
                                             .withExtension(MeterRegistry.class, meters);
            @SuppressWarnings("unchecked")
            var publisher = (Publisher<Object>) factory.provision(new TopicConfig("orders"), context)
                                                       .await()
                                                       .onFailure(_ -> fail("Provisioning should succeed"))
                                                       .unwrap();

            publisher.publish("order-1").await().onFailure(_ -> fail("Publish should succeed"));
            publisher.publish("order-2").await().onFailure(_ -> fail("Publish should succeed"));

            var counter = meters.find(TopicPublisher.UNDELIVERED_COUNTER)
                                .tags("topic", "orders", "address", expectedAddress, "slice", PUBLISHER_SLICE.asString())
                                .counter();
            assertTrue(counter != null, "counter registered on the context's MeterRegistry with the factory's tags");
            assertEquals(2.0, counter.count());
            assertTrue(invocations.isEmpty());
        }

        /// A runtime with no resolver registered (unit test, minimal runtime): both ends scope to the
        /// slice's own coordinates. A SINGLE slice therefore still reaches itself — the behaviour
        /// that existed before #1216 and must not regress — while two distinct slices do not, because
        /// with no deployment behind them there is no blueprint to share.
        @Test
        void provision_noResolver_bothEndsFallBackToSliceCoordinates() {
            registerBareSubscriptionFor(Option.none(), PUBLISHER_SLICE, "orders");
            var publisher = provisionPublisherFor(Option.none(), PUBLISHER_SLICE, "orders");

            publisher.publish("order-1").await().onFailure(_ -> fail("Publish should succeed"));
            assertEquals(1, invocations.size());
            assertEquals(PUBLISHER_SLICE, invocations.getFirst());
        }
    }

    /// #396 — a typed-topic publisher migrated to the single-source `Topic<T>` constant no longer
    /// carries a `resources.toml [section]`; its topic name is generated into the slice manifest from
    /// the constant, and the provisioning path defaults a missing `TopicConfig` section to a topic
    /// named after the provisioned section. This proves that end-to-end through the real SPI
    /// provisioning path: a publisher provisioned BY NAME through [SpiResourceProvider] — whose config
    /// loader has NO matching section (the author removed `topic_name`) — still delivers a published
    /// fact to a co-addressed subscriber, and correctly delivers nothing when no subscriber is
    /// registered (guarding `TopicPublisher`'s silent no-op on empty subscribers).
    @Nested
    class TopicNameFallbackDelivery {
        private static final MethodName METHOD = MethodName.methodName("onClickEvent").unwrap();
        private static final NodeId NODE = new NodeId("node-a");
        private static final String CLICK_EVENTS = "click-events";
        // Production loaders type a missing section as ConfigError.SectionNotFound (both TOML
        // binders run a hasSection check first), so the stub models absence with exactly that
        // cause: the fallback is absence-discriminating — an existing-but-invalid topic section
        // (durable-pubsub §3 violation, mistyped durability enum) fails provisioning loudly
        // instead of silently downgrading to an ephemeral default.
        private static final Cause NO_SECTION = ConfigError.sectionNotFound(CLICK_EVENTS);

        record ClickEvent(String shortCode) {}

        @Test
        void publish_reachesSubscriber_whenProvisionedByNameWithNoConfigSection() {
            var registry = TopicSubscriptionRegistry.topicSubscriptionRegistry();
            var invocations = new CopyOnWriteArrayList<Object>();
            var artifact = Artifact.artifact("org.pragmatica.aether.example:url-shortener-url-shortener:1.0.0").unwrap();

            registerSubscriber(registry, artifact);
            var publisher = provisionByName(registry, invocations, artifact);

            publisher.publish(new ClickEvent("A1")).await().onFailure(cause -> fail(cause.message()));
            assertEquals(1, invocations.size());
            assertEquals(artifact, invocations.getFirst());
        }

        @Test
        void publish_deliversNothing_whenNoSubscriberRegistered() {
            var registry = TopicSubscriptionRegistry.topicSubscriptionRegistry();
            var invocations = new CopyOnWriteArrayList<Object>();
            var artifact = Artifact.artifact("org.pragmatica.aether.example:url-shortener-url-shortener:1.0.0").unwrap();
            var publisher = provisionByName(registry, invocations, artifact);
            // TopicPublisher silently no-ops on empty subscribers — publish still succeeds.
            publisher.publish(new ClickEvent("A1")).await().onFailure(cause -> fail(cause.message()));
            assertTrue(invocations.isEmpty());
        }

        /// The second genuine ABSENCE shape: a minimal runtime with no global ConfigService at all
        /// (the zero-arg provider's loader fails `ConfigServiceNotAvailable` before any section
        /// lookup). The topic name is derivable from the section, so provisioning must fall back
        /// exactly as it does for a missing section — refusing here would break topic publishers
        /// on every runtime that never installs a ConfigService.
        @Test
        void publish_reachesSubscriber_whenNoConfigServiceAtAll() {
            var registry = TopicSubscriptionRegistry.topicSubscriptionRegistry();
            var invocations = new CopyOnWriteArrayList<Object>();
            var artifact = Artifact.artifact("org.pragmatica.aether.example:url-shortener-url-shortener:1.0.0").unwrap();

            registerSubscriber(registry, artifact);
            var publisher = provisionByName(registry,
                                            invocations,
                                            artifact,
                                            ResourceProvisioningError.ConfigServiceNotAvailable.INSTANCE);

            publisher.publish(new ClickEvent("A1")).await().onFailure(cause -> fail(cause.message()));
            assertEquals(1, invocations.size());
        }

        private void registerSubscriber(TopicSubscriptionRegistry registry, Artifact artifact) {
            var address = TopicAddressResolver.resolve(artifact, CLICK_EVENTS).unwrap();
            var key = TopicSubscriptionKey.topicSubscriptionKey(address, artifact, METHOD, NODE);
            var value = TopicSubscriptionValue.topicSubscriptionValue(NODE);

            registry.onSubscriptionPut(new ValuePut<>(new KVCommand.Put<>(key, value), Option.none()));
        }

        private Publisher<Object> provisionByName(TopicSubscriptionRegistry registry,
                                                  CopyOnWriteArrayList<Object> invocations,
                                                  Artifact artifact) {
            return provisionByName(registry, invocations, artifact, NO_SECTION);
        }

        private Publisher<Object> provisionByName(TopicSubscriptionRegistry registry,
                                                  CopyOnWriteArrayList<Object> invocations,
                                                  Artifact artifact,
                                                  Cause loaderFailure) {
            var provider = SpiResourceProvider.spiResourceProvider((section, configClass) -> loaderFailure.result());
            var context = ProvisioningContext.provisioningContext()
                                             .withExtension(TopicSubscriptionRegistry.class, registry)
                                             .withExtension(SliceInvoker.class,
                                                            new MinimalStubSliceInvoker(invocations))
                                             .withExtension(String.class,
                                                            artifact.asString());
            @SuppressWarnings("unchecked")
            var publisher = (Publisher<Object>) provider.provide(Publisher.class, CLICK_EVENTS, context)
                                                        .await()
                                                        .onFailure(cause -> fail("Provisioning should succeed: " + cause.message()))
                                                        .unwrap();

            return publisher;
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
        public <R> Promise<R> invokeWithRetry(Artifact slice,
                                              MethodName method,
                                              Object request,
                                              TypeToken<R> responseType,
                                              int maxRetries) {
            return invoke(slice, method, request, responseType);
        }

        @Override
        public <R> Promise<R> invokeLocal(Artifact slice,
                                          MethodName method,
                                          Object request,
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
        public Unit registerAffinityResolver(Artifact artifact, MethodName method, CacheAffinityResolver resolver) {
            return Unit.unit();
        }

        @Override
        public Unit unregisterAffinityResolver(Artifact artifact, MethodName method) {
            return Unit.unit();
        }
    }
}

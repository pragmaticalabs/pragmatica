// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.stream;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.endpoint.EndpointRegistry;
import org.pragmatica.aether.endpoint.TopicSubscriptionRegistry;
import org.pragmatica.aether.invoke.InvocationHandler;
import org.pragmatica.aether.invoke.SliceInvoker;
import org.pragmatica.aether.slice.DefaultSliceBridge;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey.TopicSubscriptionKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopicSubscriptionValue;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.topic.ContextualEvent;
import org.pragmatica.aether.slice.topic.MessageContext;
import org.pragmatica.aether.stream.DeadLetterHandler;
import org.pragmatica.aether.stream.DeadLetterHandler.DeadLetterEntry;
import org.pragmatica.aether.stream.StreamConsumerRuntime;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.topic.DurableGroupIdentity;
import org.pragmatica.aether.stream.topic.DurableTopicPublisher;
import org.pragmatica.aether.stream.topic.TopicCodecsStream;
import org.pragmatica.aether.stream.topic.TopicEventEnvelope;
import org.pragmatica.aether.update.DeploymentManager;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.serialization.SliceCodec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

/// #1295, composed (adopted from review rev1310's probe). Everything on the delivery path is REAL: the
/// stream store, the consumer runtime with its retry and dead-letter handling, [StreamConsumerManager],
/// [SliceInvoker], [InvocationHandler], [DefaultSliceBridge] and [DurableTopicPublisher]. Only the network
/// (invocations counted), serializers and deployment manager are mocks.
///
/// The target is partition 1 of a 2-partition topic, so a context whose partition was hard-coded to 0
/// fails here (rev1310 M4 survived every test that used partition 0). Before #1295 the 2-arg subscriber
/// made 5 delivery attempts (1 + 4 retries), each a ClassCastException, and its event was dead-lettered.
class DurableTopicContextDeliveryTest {
    record AppEvent(String id) {}

    private static final SliceCodec.TypeCodec<AppEvent> APP_EVENT_CODEC = new SliceCodec.TypeCodec<>(AppEvent.class,
                                                                                                     SliceCodec.deterministicTag(AppEvent.class.getName()),
                                                                                                     (codec, buf, value) -> codec.write(buf,
                                                                                                                                        value.id()),
                                                                                                     (codec, buf) -> new AppEvent(codec.read(buf)));
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:orders:1.0.0").unwrap();
    private static final MethodName ON_PLACED = MethodName.methodName("onPlaced").unwrap();
    private static final MethodName ON_PLACED_WITH_CONTEXT = MethodName.methodName("onPlacedWithContext").unwrap();
    private static final NodeId SELF = NodeId.nodeId("node-1").unwrap();
    private static final String TOPIC_ADDRESS = "org.example:order-events:1.0.0";
    private static final String TOPIC_STREAM = "topic:" + TOPIC_ADDRESS;
    private static final int PARTITIONS = 2;
    private static final int TARGET_PARTITION = 1;

    private final SliceCodec nodeCodec = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), TopicCodecsStream.CODECS);
    private final SliceCodec sliceCodec = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), List.of(APP_EVENT_CODEC));
    private final List<Object> bareSeen = new CopyOnWriteArrayList<>();
    private final List<ContextualEvent> contextualSeen = new CopyOnWriteArrayList<>();
    private final AtomicInteger contextualAttempts = new AtomicInteger();
    private final AtomicInteger contextualFailuresToInject = new AtomicInteger();

    private StreamPartitionManager partitions;
    private StreamConsumerRuntime runtime;
    private DeadLetterHandler deadLetters;
    private ClusterNetwork network;

    @BeforeEach
    void setUp() {
        partitions = StreamPartitionManager.streamPartitionManager();
        partitions.createStream(StreamConfig.streamConfig(TOPIC_STREAM,
                                                          PARTITIONS,
                                                          RetentionPolicy.retentionPolicy(10_000, 1024 * 1024, 600_000),
                                                          "earliest"))
                  .onFailure(cause -> fail(cause.message()));
        deadLetters = DeadLetterHandler.deadLetterHandler();
        runtime = StreamConsumerRuntime.streamConsumerRuntime(partitions, deadLetters);
        network = mock(ClusterNetwork.class);
    }

    @AfterEach
    void tearDown() throws Exception {
        runtime.close();
        partitions.close();
    }

    @Test
    void twoArgSubscriber_receivesThePublishersMessageIdOnPartitionOne_onceAndNeverDeadLettered() throws InterruptedException {
        wireAndReconcile();
        var published = publish(new AppEvent("order-42"));

        awaitQuiet(15_000);

        assertThat(deadLettersFor(ON_PLACED_WITH_CONTEXT)).as("a 2-arg subscriber must not dead-letter").isEmpty();
        assertThat(contextualAttempts.get()).as("2-arg delivery attempts").isEqualTo(1);
        assertThat(contextualSeen).containsExactly(ContextualEvent.contextualEvent(new AppEvent("order-42"),
                                                                                   MessageContext.messageContext(published.messageId(),
                                                                                                                 TOPIC_ADDRESS,
                                                                                                                 TARGET_PARTITION,
                                                                                                                 published.offset())));
        assertThat(bareSeen).as("the 1-arg subscriber gets the bare event once").containsExactly(new AppEvent("order-42"));
        assertThat(deadLettersFor(ON_PLACED)).isEmpty();
        assertThat(networkSends()).as("no network send on the delivery path").isZero();
    }

    /// A retried delivery carries the SAME context — the delivered event's partition and offset and the
    /// publisher's messageId — on every attempt.
    @Test
    void everyRetryAttempt_carriesTheDeliveredEventsContext() throws InterruptedException {
        wireAndReconcile();
        publish(new AppEvent("warm-up"));
        awaitAttempts(1, 10_000);
        contextualFailuresToInject.set(2);
        var published = publish(new AppEvent("order-43"));

        awaitAttempts(4, 15_000);
        awaitQuiet(3_000);
        var expected = MessageContext.messageContext(published.messageId(), TOPIC_ADDRESS, TARGET_PARTITION, published.offset());
        var forTarget = contextualSeen.stream()
                                      .filter(contextual -> contextual.event()
                                                                      .equals(new AppEvent("order-43")))
                                      .toList();

        assertThat(published.offset()).as("the retried event is not at offset 0").isEqualTo(1L);
        assertThat(forTarget).as("1 attempt + 2 injected-failure retries").hasSize(3);
        assertThat(forTarget).allSatisfy(contextual -> assertThat(contextual.context()).isEqualTo(expected));
        assertThat(deadLettersFor(ON_PLACED_WITH_CONTEXT)).isEmpty();
    }

    private record Published(String messageId, long offset) {}

    private Published publish(AppEvent event) {
        var envelope = new AtomicReference<TopicEventEnvelope>();
        var offset = new AtomicReference<Long>();

        new DurableTopicPublisher<AppEvent>(sliceCodec, captured -> appendToTarget(captured, envelope, offset))
            .publish(event)
            .await()
            .onFailure(cause -> fail(cause.message()));

        return new Published(envelope.get().messageId(), offset.get());
    }

    private Promise<Unit> appendToTarget(TopicEventEnvelope captured,
                                         AtomicReference<TopicEventEnvelope> envelope,
                                         AtomicReference<Long> offset) {
        envelope.set(captured);

        return partitions.publishLocal(TOPIC_STREAM, TARGET_PARTITION, nodeCodec.encode(captured), System.currentTimeMillis())
                         .onSuccess(offset::set)
                         .async()
                         .mapToUnit();
    }

    private void wireAndReconcile() {
        var handler = InvocationHandler.invocationHandler(SELF, network);
        var bridge = DefaultSliceBridge.defaultSliceBridge(ARTIFACT, () -> List.of(bareSubscriber(), contextualSubscriber()), sliceCodec);

        handler.registerSlice(ARTIFACT, bridge);
        var invoker = SliceInvoker.sliceInvoker(SELF,
                                                network,
                                                EndpointRegistry.endpointRegistry(),
                                                handler,
                                                mock(Serializer.class),
                                                mock(Deserializer.class),
                                                mock(DeploymentManager.class));
        var topics = TopicSubscriptionRegistry.topicSubscriptionRegistry();

        subscribe(topics, ON_PLACED);
        subscribe(topics, ON_PLACED_WITH_CONTEXT);
        StreamConsumerManager.SlicePlacement placement = _ -> Map.of(SELF, SliceState.ACTIVE);

        StreamConsumerManager.streamConsumerManager(StreamConsumerRegistry.streamConsumerRegistry(),
                                                    runtime,
                                                    invoker,
                                                    handler,
                                                    nodeCodec,
                                                    new SelfOwnsEverything(),
                                                    placement,
                                                    SELF,
                                                    TopicGroupDeclarationSource.topicGroupDeclarationSource(topics, _ -> true))
                             .reconcile();
    }

    private static final class SelfOwnsEverything implements StreamConsumerManager.PartitionOwnership {
        @Override
        public Option<Integer> partitionCount(String streamName) {
            return Option.some(PARTITIONS);
        }

        @Override
        public Option<NodeId> ownerOf(String streamName, int partition) {
            return Option.some(SELF);
        }

        @Override
        public List<NodeId> liveMembers() {
            return List.of(SELF);
        }
    }

    private static void subscribe(TopicSubscriptionRegistry topics, MethodName method) {
        var key = TopicSubscriptionKey.topicSubscriptionKey(ResourceAddress.resourceAddress(TOPIC_ADDRESS).unwrap(),
                                                            ARTIFACT,
                                                            method);

        topics.onSubscriptionPut(new ValuePut<>(new KVCommand.Put<>(key, TopicSubscriptionValue.topicSubscriptionValue(SELF)),
                                                Option.none()));
    }

    private SliceMethod<Unit, AppEvent> bareSubscriber() {
        return new SliceMethod<>(ON_PLACED, this::recordBare, new TypeToken<Unit>() {}, new TypeToken<AppEvent>() {});
    }

    private Promise<Unit> recordBare(AppEvent event) {
        bareSeen.add(event);
        return Promise.unitPromise();
    }

    /// The generated-adapter shape: the declared parameter is [ContextualEvent] and the argument is cast to it,
    /// which is exactly what threw on a bare event before #1295.
    private SliceMethod<Unit, ContextualEvent> contextualSubscriber() {
        return new SliceMethod<>(ON_PLACED_WITH_CONTEXT,
                                 this::recordContextual,
                                 new TypeToken<Unit>() {},
                                 new TypeToken<ContextualEvent>() {});
    }

    private Promise<Unit> recordContextual(ContextualEvent contextual) {
        contextualAttempts.incrementAndGet();
        contextualSeen.add(ContextualEvent.contextualEvent((AppEvent) contextual.event(), contextual.context()));

        return contextualFailuresToInject.getAndUpdate(remaining -> Math.max(0, remaining - 1)) > 0
               ? Causes.cause("injected failure").<Unit> promise()
               : Promise.unitPromise();
    }

    private List<DeadLetterEntry> deadLettersFor(MethodName method) {
        var group = DurableGroupIdentity.groupId(ARTIFACT, method);

        return deadLetters.read(TOPIC_STREAM, 100)
                          .stream()
                          .filter(entry -> entry.failingGroup()
                                                .equals(group))
                          .toList();
    }

    private long networkSends() {
        return mockingDetails(network).getInvocations()
                                      .stream()
                                      .filter(call -> call.getMethod()
                                                          .getName()
                                                          .startsWith("send") || call.getMethod()
                                                                                     .getName()
                                                                                     .startsWith("broadcast"))
                                      .count();
    }

    private void awaitAttempts(int attempts, long maxMs) throws InterruptedException {
        var deadline = System.currentTimeMillis() + maxMs;

        while (contextualAttempts.get() < attempts && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(50);
        }
    }

    /// Waits until attempts, dead letters and bare deliveries stop changing for 2.5s — longer than the
    /// runtime's early retry backoff steps — or until `maxMs` elapses.
    private void awaitQuiet(long maxMs) throws InterruptedException {
        var deadline = System.currentTimeMillis() + maxMs;
        var last = -1L;
        var stableSince = System.currentTimeMillis();

        while (System.currentTimeMillis() < deadline) {
            var now = contextualAttempts.get() * 1000L + deadLetters.read(TOPIC_STREAM, 100)
                                                                     .size() + bareSeen.size() * 100_000L;

            if (now != last) {
                last = now;
                stableSince = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - stableSince > 2_500) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
    }
}

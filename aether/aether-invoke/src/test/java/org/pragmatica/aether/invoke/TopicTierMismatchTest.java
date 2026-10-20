// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.endpoint.EndpointRegistry;
import org.pragmatica.aether.endpoint.TopicSubscriptionRegistry;
import org.pragmatica.aether.slice.DefaultSliceBridge;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.SliceDefect;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.aether.slice.TopicTierMismatch;
import org.pragmatica.aether.slice.kvstore.AetherKey.EndpointKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.TopicSubscriptionKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.EndpointValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopicSubscriptionValue;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.resource.ResourceVersion;
import org.pragmatica.aether.slice.topic.ContextualEvent;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1337: an EPHEMERAL publisher (`TopicPublisher`, RPC fan-out) reaches a subscriber whose handler is the generated
/// context adapter -- parameter type `ContextualEvent`, exactly the shape the factory generator emits. Real chain, nothing on the
/// path mocked: `TopicPublisher` -> `SliceInvoker` -> `InvocationHandler` -> `DefaultSliceBridge`.
///
/// Before the fix the bare event reached the adapter as a `ClassCastException` tagged `SliceDefect.MethodThrew` (which the
/// all-instances-failed detector reads as a broken slice version) and the publish still returned `Success`, in silence.
/// Now the delivery is refused with a typed non-defect `TopicTierMismatch`; the publish keeps its documented at-most-once
/// result, and the loss is counted per topic and named once per subscriber in an operator event.
class TopicTierMismatchTest {
    private static final NodeId SELF = new NodeId("self");
    private static final Artifact SUBSCRIBER = Artifact.artifact("org.example:order-sub:1.0.0").unwrap();
    private static final MethodName METHOD = MethodName.methodName("onOrderPlaced").unwrap();
    private static final String PUBLISHER_SLICE = "org.example:order-pub:1.0.0";

    /// One wired node: the subscriber's bridge behind a real invoker, a registry holding its subscription row, and a
    /// publisher over them whose operator events and meters are captured.
    private record Harness(SliceInvoker invoker,
                           TopicPublisher<String> publisher,
                           SimpleMeterRegistry meters,
                           List<OperatorWarning> events,
                           AtomicInteger handlerCalls) {
        static <P> Harness wired(TypeToken<P> parameterType) {
            var handlerCalls = new AtomicInteger();
            var method = new SliceMethod<Unit, P>(METHOD,
                                                  _ -> {
                                                      handlerCalls.incrementAndGet();
                                                      return Promise.unitPromise();
                                                  },
                                                  new TypeToken<Unit>() {},
                                                  parameterType);
            var network = new StubClusterNetwork();
            var handler = InvocationHandler.invocationHandler(SELF, network);
            var codec = FrameworkCodecs.frameworkCodecs();
            var bridge = DefaultSliceBridge.defaultSliceBridge(SUBSCRIBER, () -> List.of(method), codec);
            var endpoints = EndpointRegistry.endpointRegistry();
            var invoker = SliceInvoker.sliceInvoker(SELF, network, endpoints, handler, codec, codec, new StubDeploymentManager());
            var registry = TopicSubscriptionRegistry.topicSubscriptionRegistry();
            var address = ResourceAddress.resourceAddress(ResourceAddress.DEFAULT_NAMESPACE, "orders", ResourceVersion.defaultVersion()).unwrap();

            handler.registerSlice(SUBSCRIBER, bridge);
            endpoints.registerEndpoint(new EndpointKey(SUBSCRIBER, METHOD, 0), EndpointValue.endpointValue(SELF));
            registry.onSubscriptionPut(new ValuePut<>(new KVCommand.Put<>(TopicSubscriptionKey.topicSubscriptionKey(address, SUBSCRIBER, METHOD, SELF),
                                                                          TopicSubscriptionValue.topicSubscriptionValue(SELF)),
                                                      Option.none()));
            var meters = new SimpleMeterRegistry();
            var events = new CopyOnWriteArrayList<OperatorWarning>();
            var publisher = TopicPublisher.<String>topicPublisher("orders",
                                                                  address.asString(),
                                                                  PUBLISHER_SLICE,
                                                                  registry,
                                                                  invoker,
                                                                  Option.some(meters),
                                                                  OperatorWarningSink.handingOffTo(events::add));

            return new Harness(invoker, publisher, meters, events, handlerCalls);
        }

        double refusals() {
            return counter(TopicPublisher.REASON_TIER_MISMATCH);
        }

        double failures() {
            return counter(TopicPublisher.REASON_FAILED);
        }

        private double counter(String reason) {
            return Option.option(meters.find(TopicPublisher.DELIVERY_UNDELIVERED_COUNTER)
                                       .tag("reason", reason)
                                       .counter())
                         .map(counter -> counter.count())
                         .or(0.0);
        }

        List<OperatorWarning> eventsAfter(int expected) {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

            while (events.size() < expected && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }

            return List.copyOf(events);
        }
    }

    @Test
    void invoke_toContextSubscriber_failsWithTierMismatch_notADefect() {
        var harness = Harness.wired(new TypeToken<ContextualEvent>() {});

        var cause = harness.invoker().invoke(SUBSCRIBER, METHOD, "order-1", new TypeToken<Unit>() {})
                           .await()
                           .fold(c -> c, _ -> org.junit.jupiter.api.Assertions.fail("a bare event must not reach a context handler"));

        assertThat(TopicTierMismatch.isTierMismatch(cause)).as("failure was: %s", cause).isTrue();
        assertThat(cause).isNotInstanceOf(SliceDefect.class);
        assertThat(cause.message()).doesNotContain("ClassCastException");
        assertThat(harness.handlerCalls().get()).isZero();
    }

    @Test
    void ephemeralPublish_toContextSubscriber_keepsItsResult_butRefusesCountsAndNamesTheLoss() {
        var harness = Harness.wired(new TypeToken<ContextualEvent>() {});

        var result = harness.publisher().publish("order-1").await();
        var events = harness.eventsAfter(1);

        assertThat(result.isSuccess()).as("ephemeral publish stays at-most-once: %s", result).isTrue();
        assertThat(harness.handlerCalls().get()).as("the handler must never see the bare event").isZero();
        assertThat(harness.refusals()).isEqualTo(1.0);
        assertThat(harness.failures()).isZero();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().code()).isEqualTo(OperatorWarningCode.TOPIC_TIER_MISMATCH);
        assertThat(events.getFirst().subject()).isEqualTo("default:orders:1.0.0/org.example:order-sub:onOrderPlaced");
        assertThat(events.getFirst().message()).contains("'orders'", PUBLISHER_SLICE, "org.example:order-sub:1.0.0", "onOrderPlaced");
    }

    @Test
    void ephemeralPublish_repeatedRefusals_areAllCounted_butRaisedOnce() {
        var harness = Harness.wired(new TypeToken<ContextualEvent>() {});

        harness.publisher().publish("order-1").await();
        harness.publisher().publish("order-2").await();
        harness.publisher().publish("order-3").await();
        var events = harness.eventsAfter(1);

        assertThat(harness.refusals()).isEqualTo(3.0);
        assertThat(events).as("one event per subscriber while the mismatch stands").hasSize(1);
    }

    /// A hot ephemeral publisher fails every publish identically: the counter is exact (one per publish), the event is raised once, and
    /// the invoker's own `SLICE-INVOKE-FAIL` WARN is written once per (subscriber slice, method) per period, not once per publish.
    @Test
    void burstOfRefusals_countsEveryOne_butLogsTheInvokeFailureOnce() {
        var harness = Harness.wired(new TypeToken<ContextualEvent>() {});
        var warnings = new java.util.ArrayList<String>();
        var detach = LogCapture.warningsOf(SliceInvokerImpl.class, warnings);

        try {
            for (var i = 0; i < 25; i++) {
                harness.publisher().publish("order-" + i).await();
            }
        } finally {
            detach.run();
        }

        assertThat(harness.refusals()).isEqualTo(25.0);
        assertThat(warnings.stream().filter(line -> line.contains("SLICE-INVOKE-FAIL")).count()).as("WARN lines: %s", warnings).isEqualTo(1);
    }

    /// v-2339 F3: after the period the next WARN is written and carries how many refusals were held back; the budget is per subscriber
    /// SLICE and method, so two versions of one slice share it.
    @Test
    void invokeFailureLog_afterThePeriod_carriesTheSuppressedCount_andTwoVersionsShareOneBudget() {
        var invoker = (SliceInvokerImpl) Harness.wired(new TypeToken<ContextualEvent>() {}).invoker();
        var clock = new java.util.concurrent.atomic.AtomicLong(1_000_000_000_000L);
        var v1 = Artifact.artifact("org.example:order-sub:1.0.0").unwrap();
        var v2 = Artifact.artifact("org.example:order-sub:2.0.0").unwrap();
        var cause = TopicTierMismatch.FACTORY.apply("onOrderPlaced");
        var warnings = new java.util.ArrayList<String>();
        var detach = LogCapture.warningsOf(SliceInvokerImpl.class, warnings);

        invoker.tierMismatchNanos = clock::get;
        try {
            invoker.logInvokeFailure(v1, METHOD, cause);
            invoker.logInvokeFailure(v2, METHOD, cause);
            invoker.logInvokeFailure(v1, METHOD, cause);
            assertThat(warnings).as("one budget for both versions: %s", warnings).hasSize(1);

            clock.addAndGet(java.util.concurrent.TimeUnit.SECONDS.toNanos(61));
            invoker.logInvokeFailure(v2, METHOD, cause);
        } finally {
            detach.run();
        }

        assertThat(warnings).hasSize(2);
        assertThat(warnings.get(1)).contains("(2 more tier-mismatch refusals since the previous line)");
        assertThat(warnings.getFirst()).contains("(0 more");
    }

    /// Another subscriber method is another budget.
    @Test
    void invokeFailureLog_anotherMethod_hasItsOwnBudget() {
        var invoker = (SliceInvokerImpl) Harness.wired(new TypeToken<ContextualEvent>() {}).invoker();
        var warnings = new java.util.ArrayList<String>();
        var detach = LogCapture.warningsOf(SliceInvokerImpl.class, warnings);

        try {
            invoker.logInvokeFailure(SUBSCRIBER, METHOD, TopicTierMismatch.FACTORY.apply("a"));
            invoker.logInvokeFailure(SUBSCRIBER, MethodName.methodName("onOther").unwrap(), TopicTierMismatch.FACTORY.apply("b"));
        } finally {
            detach.run();
        }

        assertThat(warnings).hasSize(2);
    }

    /// Control, same harness: a bare-typed handler is delivered, counts nothing and raises nothing, so the red above comes
    /// from the ContextualEvent parameter and not from the harness.
    @Test
    void control_ephemeralPublish_toBareSubscriber_isDelivered() {
        var harness = Harness.wired(new TypeToken<String>() {});

        var result = harness.publisher().publish("order-1").await();

        assertThat(result.isSuccess()).isTrue();
        assertThat(harness.handlerCalls().get()).isEqualTo(1);
        assertThat(harness.refusals()).isZero();
        assertThat(harness.failures()).isZero();
        assertThat(harness.events()).isEmpty();
    }
}

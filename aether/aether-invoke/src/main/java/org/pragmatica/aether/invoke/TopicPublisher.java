// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.pragmatica.aether.endpoint.TopicSubscriptionRegistry;
import org.pragmatica.aether.endpoint.TopicSubscriptionRegistry.TopicSubscriber;
import org.pragmatica.aether.slice.Publisher;
import org.pragmatica.aether.slice.TopicTierMismatch;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.RateLimiter;
import org.pragmatica.lang.utils.TimeSource;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.OperatorWarnings;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Publisher that routes a message to all subscribers of a topic, identified by its fully-qualified
/// [org.pragmatica.aether.slice.resource.ResourceAddress] string (`namespace:name:version`).
///
/// `topicAddress` is the canonical address string, NOT the bare topic name — so a publish in one
/// blueprint/namespace never reaches subscribers that merely share the same bare topic name in a
/// different namespace (RC2 #274). The publisher and subscriber resolve the same declared topic to
/// the same address via `TopicAddressResolver`.
///
/// `topicName` (the bare declared name) and `publisherSlice` (the publishing slice's coordinates, or a
/// placeholder when provisioned outside a deployment) exist only for the empty-subscriber WARN and
/// counter below; routing uses `topicAddress` alone. `undelivered`, `warnLimiter` and `suppressedWarns`
/// are the observability state behind that branch — build instances through [#topicPublisher].
///
/// `meters`, `sink` and `openRefusals` are the state behind the per-subscriber branch (#1337): a delivery
/// that a subscriber refused or failed is counted on [#DELIVERY_UNDELIVERED_COUNTER], and a tier-mismatch
/// refusal also raises an operator event, once per subscriber until a delivery to it succeeds again.
public record TopicPublisher<T>(String topicName,
                                String topicAddress,
                                String publisherSlice,
                                TopicSubscriptionRegistry registry,
                                SliceInvoker invoker,
                                Option<Counter> undelivered,
                                RateLimiter warnLimiter,
                                AtomicLong suppressedWarns,
                                Option<MeterRegistry> meters,
                                OperatorWarningSink sink,
                                Set<String> openRefusals) implements Publisher<T>, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(TopicPublisher.class);

    private static final TypeToken<Unit> UNIT_TYPE_TOKEN = new TypeToken<>() {};

    /// Counter name for undelivered publishes; tags `topic`, `address`, `slice` — bounded by deployed
    /// slices × declared topics.
    public static final String UNDELIVERED_COUNTER = "aether.topic.publish.undelivered";
    /// Counter name for a delivery to ONE subscriber that did not happen (#1337); tags `topic`, `address`, `slice` and
    /// `reason` (`tier-mismatch` or `failed`). Distinct from [#UNDELIVERED_COUNTER], which counts publishes that found
    /// no subscriber at all.
    public static final String DELIVERY_UNDELIVERED_COUNTER = "aether.topic.delivery.undelivered";
    static final String REASON_TIER_MISMATCH = "tier-mismatch";
    static final String REASON_FAILED = "failed";
    /// One WARN per publisher per this period; every other undelivered publish in the window is
    /// counted and reported in the next line (rev1421 MEDIUM-1: 10,000 publishes produced 10,000
    /// identical lines in 366 ms, burying the line that was meant to be found).
    static final TimeSpan WARN_PERIOD = TimeSpan.timeSpan(60).seconds();

    public static <T> TopicPublisher<T> topicPublisher(String topicName,
                                                       String topicAddress,
                                                       String publisherSlice,
                                                       TopicSubscriptionRegistry registry,
                                                       SliceInvoker invoker,
                                                       Option<MeterRegistry> meters) {
        return topicPublisher(topicName,
                              topicAddress,
                              publisherSlice,
                              registry,
                              invoker,
                              meters,
                              OperatorWarningSink.logOnly());
    }

    /// `sink` is the node's operator-event port; [OperatorWarningSink#logOnly] when the publisher is built outside a node.
    public static <T> TopicPublisher<T> topicPublisher(String topicName,
                                                       String topicAddress,
                                                       String publisherSlice,
                                                       TopicSubscriptionRegistry registry,
                                                       SliceInvoker invoker,
                                                       Option<MeterRegistry> meters,
                                                       OperatorWarningSink sink) {
        return topicPublisher(topicName,
                              topicAddress,
                              publisherSlice,
                              registry,
                              invoker,
                              meters,
                              sink,
                              TimeSource.system());
    }

    /// The clock is an input so the rate limit can be pinned without waiting a minute.
    static <T> TopicPublisher<T> topicPublisher(String topicName,
                                                String topicAddress,
                                                String publisherSlice,
                                                TopicSubscriptionRegistry registry,
                                                SliceInvoker invoker,
                                                Option<MeterRegistry> meters,
                                                TimeSource timeSource) {
        return topicPublisher(topicName,
                              topicAddress,
                              publisherSlice,
                              registry,
                              invoker,
                              meters,
                              OperatorWarningSink.logOnly(),
                              timeSource);
    }

    static <T> TopicPublisher<T> topicPublisher(String topicName,
                                                String topicAddress,
                                                String publisherSlice,
                                                TopicSubscriptionRegistry registry,
                                                SliceInvoker invoker,
                                                Option<MeterRegistry> meters,
                                                OperatorWarningSink sink,
                                                TimeSource timeSource) {
        var undelivered = meters.map(registry_ -> registry_.counter(UNDELIVERED_COUNTER,
                                                                    "topic",
                                                                    topicName,
                                                                    "address",
                                                                    topicAddress,
                                                                    "slice",
                                                                    publisherSlice));
        // Constant configuration (one permit per WARN_PERIOD): always representable, so the #1316 refusal cannot occur.
        // A null injected `timeSource` is the only refusal left: it now fails here, at construction, where it used to fail on first use.
        var warnLimiter = RateLimiter.builder().rate(1).period(WARN_PERIOD).timeSource(timeSource).unwrap();

        return new TopicPublisher<>(topicName,
                                    topicAddress,
                                    publisherSlice,
                                    registry,
                                    invoker,
                                    undelivered,
                                    warnLimiter,
                                    new AtomicLong(),
                                    meters,
                                    sink,
                                    ConcurrentHashMap.newKeySet());
    }

    /// #1216: a publish that finds no subscriber still SUCCEEDS with zero deliveries — that contract is
    /// unchanged, because a publisher must not fail when its consumers are simply not deployed. What
    /// changed is that it is no longer silent: the address mismatch that hid #1216 for a full release
    /// produced exactly this branch on every publish, with no line anywhere naming the topic, the
    /// address the publisher resolved, or the slice that published. Now every undelivered publish
    /// increments [#UNDELIVERED_COUNTER] (when the node supplied its `MeterRegistry`), and at most one
    /// WARN per [#WARN_PERIOD] names all three plus how many publishes the previous window suppressed,
    /// so an operator can set the address against the `topic-sub/` keys without the log drowning.
    ///
    /// #1337: the same holds per subscriber. The ephemeral tier is documented at-most-once, "dispatch is attempted to
    /// currently-registered subscriber groups, nothing is persisted or queued" ([org.pragmatica.aether.resource.TopicDurability]),
    /// and `TopicPublisherTest.publish_subscriberFailure_completesSuccessfully` pins that one subscriber's failure does not
    /// fail the publish. So the result stays `Success`, and a delivery a subscriber refused or failed is made visible instead:
    /// counted on [#DELIVERY_UNDELIVERED_COUNTER], and, when the subscriber refused the delivery as a tier mismatch (its
    /// handler is a durable-topic handler), raised as a `topic-tier-mismatch` operator event.
    @Override
    public Promise<Unit> publish(T message) {
        var subscribers = registry.findSubscribers(topicAddress);

        closeRefusalsOfGoneSubscribers(subscribers);
        if (subscribers.isEmpty()) {
            reportUndelivered();

            return Promise.unitPromise();
        }

        var deliveries = new ArrayList<Promise<Unit>>(subscribers.size());

        for (var subscriber : subscribers) {
            deliveries.add(deliverToSubscriber(subscriber, message));
        }

        return Promise.allOf(deliveries).map(_ -> Unit.unit());
    }

    private void reportUndelivered() {
        undelivered.onPresent(Counter::increment);
        if (warnLimiter.tryAcquire()) {
            log.warn("Topic '{}' published by {} has no subscribers at address {} — delivered to nobody; {} more undelivered since the previous line (#1216)",
                     topicName,
                     publisherSlice,
                     topicAddress,
                     suppressedWarns.getAndSet(0));
        } else {
            suppressedWarns.incrementAndGet();
            log.debug("Topic '{}' published by {} has no subscribers at address {} — WARN suppressed within {}",
                      topicName,
                      publisherSlice,
                      topicAddress,
                      WARN_PERIOD);
        }
    }

    /// #1337: a subscriber whose row records that its handler needs the delivery context cannot be served by this ephemeral
    /// publisher, so the delivery is refused here, with no RPC, as the same `TopicTierMismatch` the bridge would answer. A row
    /// that records no requirement (one written before the tier existed, or a bare handler) goes through [SliceInvoker], where
    /// the bridge refuses a context handler itself.
    private Promise<Unit> deliverToSubscriber(TopicSubscriber subscriber, T message) {
        return subscriber.tier()
                         .requiresDurable()
               ? refuseWithoutDelivery(subscriber)
               : invoker.invoke(subscriber.artifact(),
                                subscriber.methodName(),
                                message,
                                UNIT_TYPE_TOKEN)
                        .replaceResult(result -> settle(subscriber, result));
    }

    private Promise<Unit> refuseWithoutDelivery(TopicSubscriber subscriber) {
        return Promise.resolved(settle(subscriber,
                                       TopicTierMismatch.FACTORY.apply(subscriber.methodName().name()).<Unit> result()));
    }

    /// FER (degrade forward): a failed delivery is absorbed here into a counter and, for a tier mismatch, an operator
    /// event, because the ephemeral contract is at-most-once and the publish must not fail for one subscriber (see
    /// [#publish]). The guarantee it earns is visibility, not delivery: the loss is counted per topic and reason, and a
    /// mismatch is named once per subscriber; nothing is retried.
    private Result<Unit> settle(TopicSubscriber subscriber, Result<Unit> result) {
        return result.onSuccess(_ -> reportDelivered(subscriber))
                     .onFailure(cause -> reportRefused(subscriber, cause))
                     .recover(_ -> Unit.unit());
    }

    private Unit reportDelivered(TopicSubscriber subscriber) {
        return openRefusals.isEmpty()
               ? Unit.unit()
               : closeRefusal(subjectOf(subscriber), "a delivery to it succeeded");
    }

    /// #1337 (F1): an open refusal is keyed by the subscriber SLICE (version-independent) and its method, so redeploying the
    /// subscriber at a new version, which is how the mismatch is actually fixed, reaches the same subject. A refusal whose
    /// subscriber is no longer registered at all (undeployed) is closed here too, on the next publish.
    private Unit closeRefusalsOfGoneSubscribers(List<TopicSubscriber> subscribers) {
        if (openRefusals.isEmpty()) {
            return Unit.unit();
        }

        var present = subscribers.stream().map(this::subjectOf).collect(Collectors.toSet());

        openRefusals.stream()
                    .filter(subject -> !present.contains(subject))
                    .toList()
                    .forEach(subject -> closeRefusal(subject, "its subscriber is no longer registered"));

        return Unit.unit();
    }

    private Unit closeRefusal(String subject, String why) {
        return openRefusals.remove(subject)
               ? raiseResolved(subject, why)
               : Unit.unit();
    }

    /// The publisher is released (its slice is undeployed or replaced): nothing will deliver again to the subscribers it was
    /// refusing, so their open warnings are closed rather than left standing.
    @Override
    @Contract
    public void close() {
        openRefusals.stream().toList().forEach(subject -> closeRefusal(subject, "the publisher was released"));
    }

    private Unit reportRefused(TopicSubscriber subscriber, Cause cause) {
        return TopicTierMismatch.isTierMismatch(cause)
               ? reportTierMismatch(subscriber, cause)
               : countUndelivered(REASON_FAILED);
    }

    private Unit reportTierMismatch(TopicSubscriber subscriber, Cause cause) {
        countUndelivered(REASON_TIER_MISMATCH);

        return openRefusals.add(subjectOf(subscriber))
               ? raiseMismatch(subscriber)
               : logRepeatedMismatch(subscriber, cause);
    }

    private Unit countUndelivered(String reason) {
        meters.map(registry_ -> registry_.counter(DELIVERY_UNDELIVERED_COUNTER,
                                                  "topic",
                                                  topicName,
                                                  "address",
                                                  topicAddress,
                                                  "slice",
                                                  publisherSlice,
                                                  "reason",
                                                  reason))
              .onPresent(Counter::increment);

        return Unit.unit();
    }

    private Unit raiseMismatch(TopicSubscriber subscriber) {
        return OperatorWarnings.raise(log,
                                      sink,
                                      OperatorWarningCode.TOPIC_TIER_MISMATCH,
                                      subjectOf(subscriber),
                                      "Topic '{}' published by {} was refused by subscriber {}.{} at address {}: the handler takes a ContextualEvent (a durable-topic handler) but this publisher delivers on the ephemeral tier, so the event was NOT delivered to it (#1337). Declare the topic durable in the publisher slice's resources.toml, or drop the MessageContext parameter from the subscriber",
                                      topicName,
                                      publisherSlice,
                                      subscriber.artifact(),
                                      subscriber.methodName().name(),
                                      topicAddress);
    }

    private Unit raiseResolved(String subject, String why) {
        return OperatorWarnings.raise(log,
                                      sink,
                                      OperatorWarningCode.TOPIC_TIER_MISMATCH_RESOLVED,
                                      subject,
                                      "Topic '{}' published by {}: the tier-mismatch refusal of {} is over, because {}",
                                      topicName,
                                      publisherSlice,
                                      subject,
                                      why);
    }

    private Unit logRepeatedMismatch(TopicSubscriber subscriber, Cause cause) {
        log.debug("Topic '{}' published by {} was refused again by {}.{}: {}",
                  topicName,
                  publisherSlice,
                  subscriber.artifact(),
                  subscriber.methodName().name(),
                  cause.message());

        return Unit.unit();
    }

    /// Topic address, subscriber slice WITHOUT its version, and method.
    private String subjectOf(TopicSubscriber subscriber) {
        return topicAddress + "/" + subscriber.artifact()
                                              .base()
                                              .asString() + ":" + subscriber.methodName()
                                                                            .name();
    }
}

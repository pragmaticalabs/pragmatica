// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.aether.endpoint.TopicSubscriptionRegistry;
import org.pragmatica.aether.endpoint.TopicSubscriptionRegistry.TopicSubscriber;
import org.pragmatica.aether.slice.Publisher;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.RateLimiter;
import org.pragmatica.lang.utils.TimeSource;

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
public record TopicPublisher<T>(String topicName,
                                String topicAddress,
                                String publisherSlice,
                                TopicSubscriptionRegistry registry,
                                SliceInvoker invoker,
                                Option<Counter> undelivered,
                                RateLimiter warnLimiter,
                                AtomicLong suppressedWarns) implements Publisher<T> {
    private static final Logger log = LoggerFactory.getLogger(TopicPublisher.class);

    private static final TypeToken<Unit> UNIT_TYPE_TOKEN = new TypeToken<>() {};

    /// Counter name for undelivered publishes; tags `topic`, `address`, `slice` — bounded by deployed
    /// slices × declared topics.
    public static final String UNDELIVERED_COUNTER = "aether.topic.publish.undelivered";
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
        return topicPublisher(topicName, topicAddress, publisherSlice, registry, invoker, meters, TimeSource.system());
    }

    /// The clock is an input so the rate limit can be pinned without waiting a minute.
    static <T> TopicPublisher<T> topicPublisher(String topicName,
                                                String topicAddress,
                                                String publisherSlice,
                                                TopicSubscriptionRegistry registry,
                                                SliceInvoker invoker,
                                                Option<MeterRegistry> meters,
                                                TimeSource timeSource) {
        var undelivered = meters.map(registry_ -> registry_.counter(UNDELIVERED_COUNTER,
                                                                    "topic",
                                                                    topicName,
                                                                    "address",
                                                                    topicAddress,
                                                                    "slice",
                                                                    publisherSlice));
        var warnLimiter = RateLimiter.builder().rate(1).period(WARN_PERIOD).timeSource(timeSource);

        return new TopicPublisher<>(topicName,
                                    topicAddress,
                                    publisherSlice,
                                    registry,
                                    invoker,
                                    undelivered,
                                    warnLimiter,
                                    new AtomicLong());
    }

    /// #1216: a publish that finds no subscriber still SUCCEEDS with zero deliveries — that contract is
    /// unchanged, because a publisher must not fail when its consumers are simply not deployed. What
    /// changed is that it is no longer silent: the address mismatch that hid #1216 for a full release
    /// produced exactly this branch on every publish, with no line anywhere naming the topic, the
    /// address the publisher resolved, or the slice that published. Now every undelivered publish
    /// increments [#UNDELIVERED_COUNTER] (when the node supplied its `MeterRegistry`), and at most one
    /// WARN per [#WARN_PERIOD] names all three plus how many publishes the previous window suppressed,
    /// so an operator can set the address against the `topic-sub/` keys without the log drowning.
    @Override
    public Promise<Unit> publish(T message) {
        var subscribers = registry.findSubscribers(topicAddress);

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

    private Promise<Unit> deliverToSubscriber(TopicSubscriber subscriber, T message) {
        return invoker.invoke(subscriber.artifact(), subscriber.methodName(), message, UNIT_TYPE_TOKEN);
    }
}

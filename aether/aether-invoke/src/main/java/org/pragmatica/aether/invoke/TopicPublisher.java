// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import java.util.ArrayList;

import org.pragmatica.aether.endpoint.TopicSubscriptionRegistry;
import org.pragmatica.aether.endpoint.TopicSubscriptionRegistry.TopicSubscriber;
import org.pragmatica.aether.slice.Publisher;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;

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
/// placeholder when provisioned outside a deployment) exist only for the empty-subscriber WARN below;
/// routing uses `topicAddress` alone.
public record TopicPublisher<T>(String topicName,
                                String topicAddress,
                                String publisherSlice,
                                TopicSubscriptionRegistry registry,
                                SliceInvoker invoker) implements Publisher<T> {
    private static final Logger log = LoggerFactory.getLogger(TopicPublisher.class);

    private static final TypeToken<Unit> UNIT_TYPE_TOKEN = new TypeToken<>() {};

    /// #1216: a publish that finds no subscriber still SUCCEEDS with zero deliveries — that contract is
    /// unchanged, because a publisher must not fail when its consumers are simply not deployed. What
    /// changed is that it is no longer silent: the address mismatch that hid #1216 for a full release
    /// produced exactly this branch on every publish, with no line anywhere naming the topic, the
    /// address the publisher resolved, or the slice that published. Now it WARNs with all three, so an
    /// operator reading the log can compare the resolved address against the `topic-sub/` keys.
    @Override
    public Promise<Unit> publish(T message) {
        var subscribers = registry.findSubscribers(topicAddress);

        if (subscribers.isEmpty()) {
            log.warn("Topic '{}' published by {} has no subscribers at address {} — delivered to nobody (#1216)",
                     topicName,
                     publisherSlice,
                     topicAddress);

            return Promise.unitPromise();
        }

        var deliveries = new ArrayList<Promise<Unit>>(subscribers.size());

        for (var subscriber : subscribers) {
            deliveries.add(deliverToSubscriber(subscriber, message));
        }

        return Promise.allOf(deliveries).map(_ -> Unit.unit());
    }

    private Promise<Unit> deliverToSubscriber(TopicSubscriber subscriber, T message) {
        return invoker.invoke(subscriber.artifact(), subscriber.methodName(), message, UNIT_TYPE_TOKEN);
    }
}

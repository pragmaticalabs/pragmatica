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


/// Publisher that routes a message to all subscribers of a topic, identified by its fully-qualified
/// [org.pragmatica.aether.slice.resource.ResourceAddress] string (`namespace:name:version`).
///
/// `topicAddress` is the canonical address string, NOT the bare topic name — so a publish in one
/// blueprint/namespace never reaches subscribers that merely share the same bare topic name in a
/// different namespace (RC2 #274). The publisher and subscriber resolve the same declared topic to
/// the same address via `TopicAddressResolver`.
public record TopicPublisher<T>(String topicAddress, TopicSubscriptionRegistry registry, SliceInvoker invoker) implements Publisher<T> {
    private static final TypeToken<Unit> UNIT_TYPE_TOKEN = new TypeToken<>() {};

    @Override
    public Promise<Unit> publish(T message) {
        var subscribers = registry.findSubscribers(topicAddress);

        if (subscribers.isEmpty()) {
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

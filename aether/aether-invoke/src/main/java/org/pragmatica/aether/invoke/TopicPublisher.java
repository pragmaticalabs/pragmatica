// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import org.pragmatica.aether.endpoint.TopicSubscriptionRegistry;
import org.pragmatica.aether.endpoint.TopicSubscriptionRegistry.TopicSubscriber;
import org.pragmatica.aether.slice.Publisher;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;

import java.util.ArrayList;


public record TopicPublisher<T>(String topicName, TopicSubscriptionRegistry registry, SliceInvoker invoker) implements Publisher<T> {
    private static final TypeToken<Unit> UNIT_TYPE_TOKEN = new TypeToken<>() {};

    @Override
    public Promise<Unit> publish(T message) {
        var subscribers = registry.findSubscribers(topicName);

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

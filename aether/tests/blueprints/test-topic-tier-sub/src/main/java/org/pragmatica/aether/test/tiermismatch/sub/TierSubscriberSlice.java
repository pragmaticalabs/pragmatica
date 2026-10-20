// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.test.tiermismatch.sub;

import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.aether.slice.topic.MessageContext;
import org.pragmatica.aether.test.tiermismatch.TierBareSubscriber;
import org.pragmatica.aether.test.tiermismatch.TierContextSubscriber;
import org.pragmatica.aether.test.tiermismatch.TierOrder;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// #1337 fixture, subscriber half: a context handler on `tier-orders` (declared durable) and a bare handler on
/// `tier-bare` (ephemeral). Both are unroutable, so only the runtime's delivery path can invoke them; `status`
/// reports what each received on this instance.
@Slice
public interface TierSubscriberSlice {
    record StatusRequest() {}

    record TierStatus(String instanceId, List<String> contextOrders, List<String> bareOrders) {
        public static TierStatus tierStatus(String instanceId, List<String> contextOrders, List<String> bareOrders) {
            return new TierStatus(instanceId, contextOrders, bareOrders);
        }
    }

    Promise<TierStatus> status(StatusRequest request);

    @TierContextSubscriber
    Promise<Unit> onContextOrder(TierOrder event, MessageContext context);

    @TierBareSubscriber
    Promise<Unit> onBareOrder(TierOrder event);

    static TierSubscriberSlice tierSubscriberSlice() {
        return new tierSubscriberSlice(UUID.randomUUID().toString(),
                                       new ConcurrentLinkedQueue<>(),
                                       new ConcurrentLinkedQueue<>());
    }

    record tierSubscriberSlice(String instanceId, Queue<String> contextOrders, Queue<String> bareOrders) implements TierSubscriberSlice {
        @Override
        public Promise<TierStatus> status(StatusRequest request) {
            return Promise.success(TierStatus.tierStatus(instanceId, List.copyOf(contextOrders), List.copyOf(bareOrders)));
        }

        @Override
        public Promise<Unit> onContextOrder(TierOrder event, MessageContext context) {
            contextOrders.add(event.orderId());

            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> onBareOrder(TierOrder event) {
            bareOrders.add(event.orderId());

            return Promise.unitPromise();
        }
    }
}

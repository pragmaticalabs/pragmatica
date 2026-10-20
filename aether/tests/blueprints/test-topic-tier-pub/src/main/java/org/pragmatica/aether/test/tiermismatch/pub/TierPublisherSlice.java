// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.test.tiermismatch.pub;

import org.pragmatica.aether.slice.Publisher;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.aether.test.tiermismatch.TierBarePublisher;
import org.pragmatica.aether.test.tiermismatch.TierContextPublisher;
import org.pragmatica.aether.test.tiermismatch.TierOrder;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


/// #1337 fixture, publisher half: publishes each order to TWO ephemeral topics. `tier-orders` is consumed by a
/// durable-declared (context) handler in the other slice - the mismatch; `tier-bare` is consumed by a bare handler -
/// the control that shows the harness delivers when the tiers agree.
@Slice
public interface TierPublisherSlice {
    record PublishOrder(String orderId) {
        public static Result<PublishOrder> publishOrder(String orderId) {
            return success(new PublishOrder(orderId));
        }

        public TierOrder event() {
            return new TierOrder(orderId);
        }
    }

    record PublishResponse(String status) {
        public static PublishResponse published() {
            return new PublishResponse("published");
        }
    }

    Promise<PublishResponse> publishOrder(PublishOrder request);

    static TierPublisherSlice tierPublisherSlice(@TierContextPublisher Publisher<TierOrder> contextPublisher,
                                                 @TierBarePublisher Publisher<TierOrder> barePublisher) {
        return new tierPublisherSlice(contextPublisher, barePublisher);
    }

    record tierPublisherSlice(Publisher<TierOrder> contextPublisher,
                              Publisher<TierOrder> barePublisher) implements TierPublisherSlice {
        @Override
        public Promise<PublishResponse> publishOrder(PublishOrder request) {
            return Promise.all(contextPublisher.publish(request.event()),
                               barePublisher.publish(request.event()))
                          .map((_, _) -> PublishResponse.published());
        }
    }
}

// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.test.durabletopic;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.aether.slice.Publisher;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Result.success;


/// Durable pub/sub fixture (#386) — the first slice in the repository to publish to and subscribe
/// from topics declared `durability = "durable"`.
///
/// Its job is to make the COMPOSED durable path observable from outside: publish appends an
/// envelope to the replicated `topic:<address>` stream and resolves at the min-sync floor, dispatch
/// rides StreamConsumerManager placement serially per (group x partition), the handler's promise IS
/// the ack, and a handler that keeps failing exhausts the bounded retries into a group-attributed
/// dead-letter stream. Every one of those steps is unit-tested in isolation; nothing until now
/// exercised them joined up on a real cluster.
///
/// Everything the runtime delivers is recorded in memory and reported over HTTP, so the forge test
/// asserts on DELIVERY rather than on log lines.
///
/// The two subscribers on `poison-events` are the whole group-attribution argument. They bind to the
/// SAME topic, so they are two consumer groups over one event sequence: [#onPoisonFailing] can never
/// ack, and [#onPoisonHealthy] always can. If group attribution is real, the failing group's event
/// goes to the DLQ while the healthy group processes the identical event untouched — separate
/// cursors, separate retry budgets, no cross-group interference. If it is not, the healthy group's
/// counts will show it.
///
/// Non-vacuity note for the delivery arm: with the slice deployed on EVERY node and the topic
/// carrying exactly one partition, a correctly gated consumer records each event once
/// cluster-wide, while an ungated one records it once per node. Summing [#OrderStatus#count] across
/// nodes therefore discriminates between them; a per-node assertion would not.
@Slice
public interface DurableTopicSlice {
    record PublishOrder(String orderId, int sequence) {
        public static Result<PublishOrder> publishOrder(String orderId, int sequence) {
            return success(new PublishOrder(orderId, sequence));
        }

        public OrderPlaced event() {
            return new OrderPlaced(orderId, sequence);
        }
    }

    record PublishPoison(String payload) {
        public static Result<PublishPoison> publishPoison(String payload) {
            return success(new PublishPoison(payload));
        }
    }

    record PublishResponse(String status) {
        public static PublishResponse published() {
            return new PublishResponse("published");
        }
    }

    record StatusRequest() {}

    /// What `order-events` delivered to THIS node, in arrival order. The list order is the ordering
    /// evidence: the fixture publishes ascending `sequence` values into a single-partition topic, so
    /// serial per-(group x partition) dispatch means the sequences come back ascending here.
    record OrderStatus(int count, List<OrderPlaced> orders) {
        public static OrderStatus orderStatus(List<OrderPlaced> orders) {
            return new OrderStatus(orders.size(), orders);
        }
    }

    /// The two groups over `poison-events`, side by side.
    ///
    /// `failingAttempts` counts INVOCATIONS of the never-acking handler, so it measures the retry
    /// budget directly — the spec's bounded 5 attempts before dead-lettering. `healthyCount` is the
    /// other group's progress over the identical events: it must advance normally, and it is the
    /// assertion that catches a DLQ implementation which stalls the shared partition instead of only
    /// the failing group's cursor.
    record PoisonStatus(int failingAttempts, int healthyCount, List<String> healthyPayloads) {
        public static PoisonStatus poisonStatus(int failingAttempts, List<String> healthyPayloads) {
            return new PoisonStatus(failingAttempts, healthyPayloads.size(), healthyPayloads);
        }
    }

    Promise<PublishResponse> publishOrder(PublishOrder request);
    Promise<OrderStatus> orderStatus(StatusRequest request);
    Promise<PublishResponse> publishPoison(PublishPoison request);
    Promise<PoisonStatus> poisonStatus(StatusRequest request);

    /// Durable delivery for `order-events`. Not an HTTP route — absent from `routes.toml` on purpose,
    /// so the only thing that can invoke it is the runtime's dispatch path. If it were routable the
    /// delivery proof would be vacuous.
    @OrderEventSubscriber
    Promise<Unit> onOrderPlaced(OrderPlaced event);

    /// The healthy group over `poison-events`. Also deliberately unroutable.
    @PoisonHealthySubscriber
    Promise<Unit> onPoisonHealthy(String event);

    /// The failing group over `poison-events`: records the attempt, then refuses. Also deliberately
    /// unroutable. Returning a failed promise is the runtime's "not acked" signal, so this drives the
    /// retry-then-dead-letter path without any fault injection in the runtime itself.
    @PoisonFailingSubscriber
    Promise<Unit> onPoisonFailing(String event);

    static DurableTopicSlice durableTopicSlice(@OrderEventPublisher Publisher<OrderPlaced> orderPublisher,
                                               @PoisonEventPublisher Publisher<String> poisonPublisher) {
        return new durableTopicSlice(orderPublisher,
                                     poisonPublisher,
                                     new ConcurrentLinkedQueue<>(),
                                     new ConcurrentLinkedQueue<>(),
                                     new AtomicInteger());
    }

    record durableTopicSlice(Publisher<OrderPlaced> orderPublisher,
                             Publisher<String> poisonPublisher,
                             Queue<OrderPlaced> deliveredOrders,
                             Queue<String> healthyPayloads,
                             AtomicInteger failingAttempts) implements DurableTopicSlice {
        @Override
        public Promise<PublishResponse> publishOrder(PublishOrder request) {
            return orderPublisher.publish(request.event())
                                 .map(_ -> PublishResponse.published());
        }

        @Override
        public Promise<OrderStatus> orderStatus(StatusRequest request) {
            return Promise.success(OrderStatus.orderStatus(List.copyOf(deliveredOrders)));
        }

        @Override
        public Promise<PublishResponse> publishPoison(PublishPoison request) {
            return poisonPublisher.publish(request.payload())
                                  .map(_ -> PublishResponse.published());
        }

        @Override
        public Promise<PoisonStatus> poisonStatus(StatusRequest request) {
            return Promise.success(PoisonStatus.poisonStatus(failingAttempts.get(), List.copyOf(healthyPayloads)));
        }

        @Override
        public Promise<Unit> onOrderPlaced(OrderPlaced event) {
            return Promise.success(deliveredOrders.add(event))
                          .mapToUnit();
        }

        @Override
        public Promise<Unit> onPoisonHealthy(String event) {
            return Promise.success(healthyPayloads.add(event))
                          .mapToUnit();
        }

        @Override
        public Promise<Unit> onPoisonFailing(String event) {
            failingAttempts.incrementAndGet();

            return new PoisonRefused(event).promise();
        }
    }

    /// The refusal the failing group answers with. A named cause rather than an anonymous one so the
    /// attempt count and the dead-letter envelope's `lastFailureCause` are attributable to this
    /// fixture when a forge run is being diagnosed.
    record PoisonRefused(String event) implements Cause {
        @Override
        public String message() {
            return "test-durable-topic fixture refuses every poison-events delivery: " + event;
        }
    }
}

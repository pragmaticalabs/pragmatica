// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.test.streamconsumer;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.pragmatica.aether.slice.StreamPublisher;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Result.success;


/// Declarative stream-consumer fixture (#488) — the first [org.pragmatica.aether.slice.StreamSubscriber]
/// usage in the repository.
///
/// The point of this slice is to make declarative delivery OBSERVABLE. It publishes into
/// `streams.consumer-events` through an ordinary [StreamPublisher], and declares
/// [#onConsumerEvent] as a `@ConsumerEventSubscriber` method — the runtime is supposed to invoke it
/// for every event on the partitions this node consumes. Whatever actually arrives is appended to
/// an in-memory queue that `POST /api/stream-consumer/received` reports back, so a test can assert
/// on delivery instead of on log lines.
///
/// Non-vacuity: before #488 is wired, nothing reads the `StreamRegistrationKey` this slice's
/// deployment writes, so `received` reports zero no matter how many events are published.
///
/// The slice carries TWO independent streams on purpose:
///
///   - `streams.consumer-events` is `String`-typed and proves declarative delivery (#488).
///   - `streams.order-events` is [OrderPlaced]-typed and proves codec scoping (#526): an
///     application-defined record can be published and consumed at all. Until that fix, resources
///     were provisioned with the node-wide codec — which knows framework types only — so the
///     publish threw "No codec registered for class" before routing was ever reached. Every other
///     stream blueprint in the corpus is `String`-typed, which is exactly why nothing caught it;
///     this stream is the control's counterpart.
///
/// Keeping them separate means a regression in either mechanism cannot mask the other.
@Slice
public interface ConsumerSlice {
    record PublishRequest(String payload) {
        public static Result<PublishRequest> publishRequest(String payload) {
            return success(new PublishRequest(Option.option(payload).or("")));
        }
    }

    record PublishResponse(String status) {
        public static PublishResponse published() {
            return new PublishResponse("published");
        }
    }

    record ReceivedRequest() {}

    /// What the runtime actually delivered to [#onConsumerEvent] on THIS node. `count` is the total
    /// delivered here; a cluster-wide assertion sums it across nodes, which is what distinguishes
    /// correct partition-ownership gating (each event delivered once) from ungated delivery (each
    /// event delivered once per node running the slice).
    record ReceivedResponse(int count, List<String> events) {
        public static ReceivedResponse receivedResponse(List<String> events) {
            return new ReceivedResponse(events.size(), events);
        }
    }

    /// Publish request for the application-typed stream. Kept separate from the [OrderPlaced] event
    /// itself so the HTTP surface stays a plain request/response pair while the STREAM carries the
    /// application record — which is the type whose encoding #526 is about.
    record PublishOrderRequest(String orderId, String customer, int amount) {
        public OrderPlaced event() {
            return new OrderPlaced(orderId, customer, amount);
        }
    }

    /// The [OrderPlaced] events the runtime delivered to [#onOrderPlaced] on THIS node. A non-zero
    /// count proves the full application-typed round trip: the slice's codec encoded the record on
    /// publish AND decoded it on delivery.
    record ReceivedOrdersResponse(int count, List<OrderPlaced> orders) {
        public static ReceivedOrdersResponse receivedOrdersResponse(List<OrderPlaced> orders) {
            return new ReceivedOrdersResponse(orders.size(), orders);
        }
    }

    Promise<PublishResponse> publish(PublishRequest request);
    Promise<ReceivedResponse> received(ReceivedRequest request);
    Promise<PublishResponse> publishOrder(PublishOrderRequest request);
    Promise<ReceivedOrdersResponse> receivedOrders(ReceivedRequest request);

    /// The declarative consumer. Not an HTTP route — it is absent from `routes.toml` on purpose;
    /// the only thing that may call it is the framework's stream-delivery path.
    @ConsumerEventSubscriber
    Promise<Unit> onConsumerEvent(String event);

    /// The application-typed declarative consumer. Also deliberately unroutable.
    @OrderEventSubscriber
    Promise<Unit> onOrderPlaced(OrderPlaced event);

    static ConsumerSlice consumerSlice(@EventStreamPublisher StreamPublisher<String> publisher,
                                       @OrderStreamPublisher StreamPublisher<OrderPlaced> orderPublisher) {
        return new consumerSlice(publisher, orderPublisher, new ConcurrentLinkedQueue<>(), new ConcurrentLinkedQueue<>());
    }

    record consumerSlice(StreamPublisher<String> publisher,
                         StreamPublisher<OrderPlaced> orderPublisher,
                         Queue<String> delivered,
                         Queue<OrderPlaced> deliveredOrders) implements ConsumerSlice {
        @Override
        public Promise<PublishResponse> publish(PublishRequest request) {
            return publisher.publish(request.payload())
                            .map(_ -> PublishResponse.published());
        }

        @Override
        public Promise<ReceivedResponse> received(ReceivedRequest request) {
            return Promise.success(ReceivedResponse.receivedResponse(List.copyOf(delivered)));
        }

        @Override
        public Promise<PublishResponse> publishOrder(PublishOrderRequest request) {
            return orderPublisher.publish(request.event())
                                 .map(_ -> PublishResponse.published());
        }

        @Override
        public Promise<ReceivedOrdersResponse> receivedOrders(ReceivedRequest request) {
            return Promise.success(ReceivedOrdersResponse.receivedOrdersResponse(List.copyOf(deliveredOrders)));
        }

        @Override
        public Promise<Unit> onConsumerEvent(String event) {
            return Promise.success(delivered.add(event))
                          .mapToUnit();
        }

        @Override
        public Promise<Unit> onOrderPlaced(OrderPlaced event) {
            return Promise.success(deliveredOrders.add(event))
                          .mapToUnit();
        }
    }
}

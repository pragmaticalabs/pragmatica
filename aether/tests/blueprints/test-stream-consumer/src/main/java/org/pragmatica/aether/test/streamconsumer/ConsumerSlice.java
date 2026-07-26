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
/// The event type is [String] deliberately. App-defined record types cannot be published to a
/// stream at all today: `StreamAccess`/`StreamPublisher` are provisioned with the node-wide codec
/// (`AetherNode` registers `Serializer` as an SPI runtime extension, and
/// `SpiResourceProvider.enrichWithRuntimeExtensions` unconditionally overwrites any slice-supplied
/// one), and that codec is built once from a fixed list with no late registration. That is a
/// separate publish-side defect, not part of #488.
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

    Promise<PublishResponse> publish(PublishRequest request);
    Promise<ReceivedResponse> received(ReceivedRequest request);

    /// The declarative consumer. Not an HTTP route — it is absent from `routes.toml` on purpose;
    /// the only thing that may call it is the framework's stream-delivery path.
    @ConsumerEventSubscriber
    Promise<Unit> onConsumerEvent(String event);

    static ConsumerSlice consumerSlice(@EventStreamPublisher StreamPublisher<String> publisher) {
        return new consumerSlice(publisher, new ConcurrentLinkedQueue<>());
    }

    record consumerSlice(StreamPublisher<String> publisher, Queue<String> delivered) implements ConsumerSlice {
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
        public Promise<Unit> onConsumerEvent(String event) {
            return Promise.success(delivered.add(event))
                          .mapToUnit();
        }
    }
}

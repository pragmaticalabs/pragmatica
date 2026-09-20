// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.topic;

import org.pragmatica.aether.slice.Publisher;
import org.pragmatica.aether.slice.StreamPublisher;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.utility.KSUID;


/// The durable tier's typed publisher (durable-pubsub-spec §5): `publish` resolves when the event
/// is durably appended at the declared floor — owner append plus `min-sync − 1` peer acks, carried
/// by the underlying stream publisher's replication barrier — NOT when subscribers process it.
/// Publisher latency is bounded by replication latency and is independent of subscribers: the log
/// severs subscriber processing from the publisher's lifetime, so nothing dangles.
///
/// A publish resolves in one of three ways (#1236):
/// - **success** — the event is in the log at the declared floor;
/// - **failure** — the event is NOT in the log (e.g. `NOT_ENOUGH_REPLICAS`, checked BEFORE the append);
///   a retry is a new publish. One known exception (#1235): a WAL fsync failure AFTER the ring append is
///   reported as a failure while the event is already visible in the owner's ring;
/// - [org.pragmatica.aether.slice.PublishOutcomeUnknown] — the owner appended but the floor was not
///   confirmed (e.g. peer acks timed out): the event MAY be in the log and visible to consumers. Retry
///   only with the same message ID, or downstream dedup sees two distinct events.
///
/// Each publish wraps the payload in a [TopicEventEnvelope]: a `messageId` (the idempotency key of
/// §8 — it survives a DLQ redrive where offsets cannot), the publish timestamp, and the serialized
/// payload bytes. Subscribers decode the envelope, then the payload with the subscription's own type.
/// The keyless [#publish(Object)] mints a fresh time-sortable KSUID per call; the keyed
/// [#publish(Object, String)] uses the caller's key (#1237), so a retry after an outcome-unknown result
/// carries the same `messageId` — the necessary condition for dedup to collapse it (see
/// [Publisher#publish(Object, String)] for what is not yet sufficient). Retrying through the keyless
/// overload writes a second copy under an identity nothing can match.
public record DurableTopicPublisher<T>(Serializer serializer, StreamPublisher<TopicEventEnvelope> stream) implements Publisher<T> {
    private static final Cause BLANK_IDEMPOTENCY_KEY = Causes.cause("Durable publish idempotency key must not be blank: it becomes the message ID that deduplication matches retries on");

    @Override
    public Promise<Unit> publish(T message) {
        return publishAs(KSUID.ksuid().toString(),
                         message);
    }

    @Override
    public Promise<Unit> publish(T message, String idempotencyKey) {
        return Verify.ensure(idempotencyKey, Verify.Is::present, BLANK_IDEMPOTENCY_KEY)
                     .async()
                     .flatMap(messageId -> publishAs(messageId, message));
    }

    private Promise<Unit> publishAs(String messageId, T message) {
        return stream.publish(envelope(messageId, message));
    }

    private TopicEventEnvelope envelope(String messageId, T message) {
        return new TopicEventEnvelope(messageId, System.currentTimeMillis(), serializer.encode(message));
    }
}

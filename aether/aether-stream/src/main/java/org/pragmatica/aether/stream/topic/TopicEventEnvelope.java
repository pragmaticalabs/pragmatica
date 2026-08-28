// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.topic;

import java.util.Arrays;

import org.pragmatica.serialization.Codec;


/// Wire shape of one durable-topic event as appended to the `topic:<address>` stream
/// (durable-pubsub-spec §8).
///
/// Carries exactly what the stream position cannot: the publisher-assigned `messageId`
/// (time-sortable unique id, the idempotency key that SURVIVES a DLQ redrive — offsets do not)
/// and the publish timestamp. Source `(topic, partition, offset)` are positional facts of where
/// the envelope sits and are never duplicated inside it.
@Codec
public record TopicEventEnvelope(String messageId, long publishedAtMs, byte[] payload) {
    public TopicEventEnvelope {
        payload = payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof TopicEventEnvelope other
               && publishedAtMs == other.publishedAtMs
               && messageId.equals(other.messageId)
               && Arrays.equals(payload, other.payload);
    }

    @Override
    public int hashCode() {
        int result = messageId.hashCode();

        result = 31 * result + Long.hashCode(publishedAtMs);
        result = 31 * result + Arrays.hashCode(payload);

        return result;
    }
}

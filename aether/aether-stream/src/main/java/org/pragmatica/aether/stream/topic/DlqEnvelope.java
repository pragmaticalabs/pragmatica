// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.topic;

import java.util.Arrays;

import org.pragmatica.serialization.Codec;


/// Wire shape of one dead-lettered durable-topic event as appended to the `topic:<address>.dlq`
/// stream (durable-pubsub-spec §9).
///
/// Carries the original payload plus everything redrive and diagnosis need: the `messageId` (the
/// idempotency key, surviving the redrive where offsets cannot), the source position the event
/// died at, the FAILING GROUP (redrive is group-targeted — re-injection reaches only the group
/// that exhausted its retries, so groups that already processed the event are untouched by
/// construction), the attempt count, the last failure cause, and both ends of the failure window.
@Codec
public record DlqEnvelope(String messageId,
                          String sourceTopic,
                          int sourcePartition,
                          long sourceOffset,
                          String failingGroup,
                          int attemptCount,
                          String lastFailureCause,
                          long publishedAtMs,
                          long deadLetteredAtMs,
                          byte[] payload) {
    public DlqEnvelope {
        payload = payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof DlqEnvelope other
               && sourcePartition == other.sourcePartition
               && sourceOffset == other.sourceOffset
               && attemptCount == other.attemptCount
               && publishedAtMs == other.publishedAtMs
               && deadLetteredAtMs == other.deadLetteredAtMs
               && messageId.equals(other.messageId)
               && sourceTopic.equals(other.sourceTopic)
               && failingGroup.equals(other.failingGroup)
               && lastFailureCause.equals(other.lastFailureCause)
               && Arrays.equals(payload, other.payload);
    }

    @Override
    public int hashCode() {
        int result = messageId.hashCode();

        result = 31 * result + sourceTopic.hashCode();
        result = 31 * result + sourcePartition;
        result = 31 * result + Long.hashCode(sourceOffset);
        result = 31 * result + failingGroup.hashCode();
        result = 31 * result + attemptCount;
        result = 31 * result + lastFailureCause.hashCode();
        result = 31 * result + Long.hashCode(publishedAtMs);
        result = 31 * result + Long.hashCode(deadLetteredAtMs);
        result = 31 * result + Arrays.hashCode(payload);

        return result;
    }
}

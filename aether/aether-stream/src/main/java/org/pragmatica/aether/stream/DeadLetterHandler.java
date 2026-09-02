// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.Arrays;
import java.util.List;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


public interface DeadLetterHandler {
    /// Failure-aware append (durable-pubsub-spec §12): resolves when the sink has ACCEPTED the
    /// entry — for a durable sink that means durably stored, for the in-memory default merely
    /// recorded in process memory. The consumer runtime holds the source cursor until this promise
    /// resolves successfully (spec §9 — an event is never skipped past a dead-letter sink that has
    /// not accepted it), which the previous fire-and-forget `void record` made unimplementable:
    /// a sink whose write can fail had no channel to say so.
    Promise<Unit> append(String streamName,
                         int partition,
                         long offset,
                         String failingGroup,
                         byte[] payload,
                         String errorMessage,
                         int attemptCount);

    List<DeadLetterEntry> read(String streamName, int maxCount);

    /// `failingGroup` is the consumer group whose retries exhausted (durable-pubsub-spec §9 —
    /// redrive is group-targeted, so the attribution must survive into the entry; a dead letter
    /// without its group could only be redriven by re-publishing, duplicating to groups that
    /// already processed the event).
    record DeadLetterEntry(String streamName,
                           int partition,
                           long offset,
                           String failingGroup,
                           byte[] payload,
                           String errorMessage,
                           int attemptCount,
                           long timestamp) {
        public DeadLetterEntry {
            payload = payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof DeadLetterEntry other
                   && partition == other.partition
                   && offset == other.offset
                   && attemptCount == other.attemptCount
                   && timestamp == other.timestamp
                   && streamName.equals(other.streamName)
                   && failingGroup.equals(other.failingGroup)
                   && Arrays.equals(payload, other.payload)
                   && errorMessage.equals(other.errorMessage);
        }

        @Override
        public int hashCode() {
            int result = streamName.hashCode();

            result = 31 * result + failingGroup.hashCode();
            result = 31 * result + partition;
            result = 31 * result + Long.hashCode(offset);
            result = 31 * result + Arrays.hashCode(payload);
            result = 31 * result + errorMessage.hashCode();
            result = 31 * result + attemptCount;
            result = 31 * result + Long.hashCode(timestamp);

            return result;
        }

        public static DeadLetterEntry deadLetterEntry(String streamName,
                                                      int partition,
                                                      long offset,
                                                      String failingGroup,
                                                      byte[] payload,
                                                      String errorMessage,
                                                      int attemptCount,
                                                      long timestamp) {
            return new DeadLetterEntry(streamName,
                                       partition,
                                       offset,
                                       failingGroup,
                                       payload,
                                       errorMessage,
                                       attemptCount,
                                       timestamp);
        }
    }

    /// The volatile default: entries live in process memory ONLY and are lost on restart —
    /// suitable for Forge and tests, a documented data-loss surface anywhere else (rc3 audit note
    /// on #386). Production durable sinks arrive with the durable-pubsub D3 work.
    static DeadLetterHandler deadLetterHandler() {
        return new InMemoryDeadLetterHandler();
    }
}

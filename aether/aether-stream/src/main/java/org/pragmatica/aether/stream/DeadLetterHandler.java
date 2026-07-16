// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.Arrays;
import java.util.List;

import org.pragmatica.lang.Contract;


public interface DeadLetterHandler {
    @Contract
    void record(String streamName, int partition, long offset, byte[] payload, String errorMessage, int attemptCount);

    List<DeadLetterEntry> read(String streamName, int maxCount);

    record DeadLetterEntry(String streamName,
                           int partition,
                           long offset,
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
                   && Arrays.equals(payload, other.payload)
                   && errorMessage.equals(other.errorMessage);
        }

        @Override
        public int hashCode() {
            int result = streamName.hashCode();

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
                                                      byte[] payload,
                                                      String errorMessage,
                                                      int attemptCount,
                                                      long timestamp) {
            return new DeadLetterEntry(streamName, partition, offset, payload, errorMessage, attemptCount, timestamp);
        }
    }

    static DeadLetterHandler deadLetterHandler() {
        return new InMemoryDeadLetterHandler();
    }
}

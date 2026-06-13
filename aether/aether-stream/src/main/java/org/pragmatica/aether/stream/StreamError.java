// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.aether.slice.ResourceCapacityExhausted;
import org.pragmatica.lang.Cause;


public sealed interface StreamError extends Cause {
    /// `General` implements {@link ResourceCapacityExhausted} so the ONE capacity-class constant —
    /// `STREAM_MEMORY_EXCEEDED` — is classified TRANSIENT by the slice-loading / resource-provisioning
    /// path (retry, then `DeploymentFailed` after MAX_RETRIES; spec §6 / decision #7). Every other
    /// constant overrides the marker predicate to false, so only off-heap budget exhaustion is
    /// retryable; genuine config errors (e.g. `AHSE_REQUIRED_FOR_STRONG`) stay fatal. Enum identity is
    /// preserved — `cause == STREAM_MEMORY_EXCEEDED` checks elsewhere are unaffected (spec §8).
    enum General implements StreamError, ResourceCapacityExhausted {
        BUFFER_CLOSED("Ring buffer is closed"),
        BUFFER_EMPTY("Ring buffer is empty"),
        STREAM_ALREADY_EXISTS("Stream already exists"),
        STREAM_CLOSED("Stream has been closed"),
        CONSUMER_ALREADY_SUBSCRIBED("Consumer group already subscribed to this partition"),
        CONSUMER_NOT_FOUND("Consumer group not found for this partition"),
        CONSUMER_STALLED("Consumer is stalled due to processing failure"),
        CONSUMER_RUNTIME_CLOSED("Consumer runtime has been closed"),
        STREAM_MEMORY_EXCEEDED("Total off-heap memory limit exceeded"),
        CONSENSUS_PATH_UNAVAILABLE("Consensus publish path not configured for STRONG consistency stream"),
        BUFFER_FULL("Ring buffer is full, STRONG consistency prevents eviction"),
        AHSE_REQUIRED_FOR_STRONG("STRONG consistency requires AHSE storage (EvictionListener must not be NOOP)"),
        STREAM_CONFIG_COMMIT_FAILED("Stream config consensus commit failed"),
        PARTITION_NOT_LOCAL("Stream partition is not owned by this node");
        private final String message;
        General(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
        /// Only `STREAM_MEMORY_EXCEEDED` is a transient capacity shortage (the pool may clear as other
        /// streams are destroyed / right-sized); every other constant is a non-capacity error.
        @Override
        public boolean transientCapacity() {
            return this == STREAM_MEMORY_EXCEEDED;
        }
    }

    record EventTooLarge(int eventSize, long maxSize) implements StreamError {
        @Override
        public String message() {
            return "Event size %d exceeds maximum %d".formatted(eventSize, maxSize);
        }
    }

    record CursorExpired(long requestedOffset, long tailOffset) implements StreamError {
        @Override
        public String message() {
            return "Cursor at offset %d has expired, oldest available is %d".formatted(requestedOffset, tailOffset);
        }
    }

    record StreamNotFound(String streamName) implements StreamError {
        @Override
        public String message() {
            return "Stream not found: " + streamName;
        }
    }

    record PartitionOutOfRange(String streamName, int partition, int partitionCount) implements StreamError {
        @Override
        public String message() {
            return "Partition %d out of range for stream '%s' (partitions: %d)".formatted(partition,
                                                                                          streamName,
                                                                                          partitionCount);
        }
    }

    record EventProcessingFailed(String streamName, int partition, long offset, String reason) implements StreamError {
        @Override
        public String message() {
            return "Event processing failed at %s[%d]@%d: %s".formatted(streamName, partition, offset, reason);
        }
    }
}

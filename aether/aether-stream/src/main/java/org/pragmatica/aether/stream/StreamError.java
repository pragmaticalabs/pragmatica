// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.aether.slice.ResourceCapacityExhausted;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;
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

    /// Ownership-fence rejection (#345 item 1d-ii, spec §8): an append carries a `presented` owner
    /// epoch STRICTLY older than the `(stream, partition)` domain high-water `current`, so the writer
    /// is a deposed owner and the append is rejected at the replica's commit point with NO mutation.
    /// Unlike the silent CP-applier guard (1a), this DATA-plane reject IS surfaced to the caller, which
    /// re-resolves the owner and retries against the current owner/epoch.
    record StaleEpochAppend(String streamName, int partition, Epoch presented, Epoch current) implements StreamError {
        @Override
        public String message() {
            return "Stale-epoch stream append rejected for %s[%d]: presented owner epoch %s is older than the partition high-water %s".formatted(streamName,
                                                                                                                                                 partition,
                                                                                                                                                 presented,
                                                                                                                                                 current);
        }
    }

    /// Linearizable-read owner mismatch (#345 item 1e): a `LINEARIZABLE` read landed on `actual` but the
    /// committed `StreamPartitionOwnershipValue.owner` for the `(stream, partition)` arc is `expected`, so
    /// `actual` is NOT the authoritative owner (a stale committed view, or a routing race during a
    /// reshuffle). The read is rejected unserved so the client re-resolves the owner and retries — the
    /// read-side analogue of {@link StaleEpochAppend}.
    record NotCurrentOwner(String streamName, int partition, NodeId expected, NodeId actual) implements StreamError {
        @Override
        public String message() {
            return "Linearizable read rejected for %s[%d]: committed owner is %s but the read landed on %s".formatted(streamName,
                                                                                                                      partition,
                                                                                                                      expected,
                                                                                                                      actual);
        }
    }

    /// Linearizable-read epoch fence (#345 item 1e): the read landed on the committed owner, but the
    /// committed ownership epoch has advanced beyond what the read holder `presented` — the owner is a
    /// deposed owner whose committed record is now stale relative to the partition high-water `current`.
    /// Mirrors {@link StaleEpochAppend} on the read path: the read is rejected so the client re-resolves
    /// and retries against the current owner/epoch.
    record StaleEpochRead(String streamName, int partition, Epoch presented, Epoch current) implements StreamError {
        @Override
        public String message() {
            return "Stale-epoch linearizable read rejected for %s[%d]: presented owner epoch %s is older than the partition high-water %s".formatted(streamName,
                                                                                                                                                      partition,
                                                                                                                                                      presented,
                                                                                                                                                      current);
        }
    }

    /// Linearizable-read catch-up gate (#345 item 1e): the read landed on the committed owner, but this
    /// freshly-promoted owner has NOT yet applied up to the handover offset (its local watermark /
    /// CAUGHT_UP signal lags the committed handover), so serving now could miss events the prior owner
    /// committed. The read is rejected (NOT blocked) so the client retries once the new owner has caught
    /// up — the new owner reuses the EXISTING failover-recovery catch-up machinery to close the gap.
    record OwnerCatchupPending(String streamName, int partition) implements StreamError {
        @Override
        public String message() {
            return "Linearizable read rejected for %s[%d]: the committed owner has not yet caught up to the handover offset".formatted(streamName,
                                                                                                                                       partition);
        }
    }
}

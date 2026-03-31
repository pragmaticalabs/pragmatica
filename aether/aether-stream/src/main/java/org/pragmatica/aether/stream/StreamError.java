package org.pragmatica.aether.stream;

import org.pragmatica.lang.Cause;

/// Error types for stream ring buffer operations.
public sealed interface StreamError extends Cause {
    enum General implements StreamError {
        BUFFER_CLOSED("Ring buffer is closed"),
        BUFFER_EMPTY("Ring buffer is empty"),
        STREAM_ALREADY_EXISTS("Stream already exists"),
        STREAM_CLOSED("Stream has been closed"),
        CONSUMER_ALREADY_SUBSCRIBED("Consumer group already subscribed to this partition"),
        CONSUMER_NOT_FOUND("Consumer group not found for this partition"),
        CONSUMER_STALLED("Consumer is stalled due to processing failure"),
        CONSUMER_RUNTIME_CLOSED("Consumer runtime has been closed"),
        STREAM_MEMORY_EXCEEDED("Total off-heap memory limit exceeded"),
        CONSENSUS_PATH_UNAVAILABLE("Consensus publish path not configured for STRONG consistency stream");
        private final String message;
        General(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
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

package org.pragmatica.aether.infra.streaming;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;

/**
 * Error types for streaming service operations.
 */
public sealed interface StreamingError extends Cause {
    /**
     * Topic does not exist.
     */
    record TopicNotFound(String topic) implements StreamingError {
        @Override
        public String message() {
            return "Topic not found: " + topic;
        }
    }

    /**
     * Topic already exists.
     */
    record TopicAlreadyExists(String topic) implements StreamingError {
        @Override
        public String message() {
            return "Topic already exists: " + topic;
        }
    }

    /**
     * Consumer group does not exist.
     */
    record ConsumerGroupNotFound(String groupId) implements StreamingError {
        @Override
        public String message() {
            return "Consumer group not found: " + groupId;
        }
    }

    /**
     * Invalid partition number.
     */
    record InvalidPartition(String topic, int partition, int totalPartitions) implements StreamingError {
        @Override
        public String message() {
            return "Invalid partition " + partition + " for topic " + topic + " (total partitions: " + totalPartitions
                   + ")";
        }
    }

    /**
     * Message exceeds size limit.
     */
    record MessageTooLarge(long actualSize, long maxSize) implements StreamingError {
        @Override
        public String message() {
            return "Message size " + actualSize + " exceeds maximum " + maxSize;
        }
    }

    /**
     * Invalid configuration.
     */
    record InvalidConfiguration(String reason) implements StreamingError {
        @Override
        public String message() {
            return "Invalid streaming configuration: " + reason;
        }
    }

    /**
     * Operation failed due to internal error.
     */
    record OperationFailed(String operation, Option<Throwable> cause) implements StreamingError {
        @Override
        public String message() {
            return "Streaming operation failed: " + operation + cause.fold(() -> "", c -> " - " + c.getMessage());
        }
    }

    /**
     * No messages available for consumption.
     */
    record NoMessagesAvailable(String topic, String groupId) implements StreamingError {
        @Override
        public String message() {
            return "No messages available for topic " + topic + " in group " + groupId;
        }
    }

    // Factory methods
    static TopicNotFound topicNotFound(String topic) {
        return new TopicNotFound(topic);
    }

    static TopicAlreadyExists topicAlreadyExists(String topic) {
        return new TopicAlreadyExists(topic);
    }

    static ConsumerGroupNotFound consumerGroupNotFound(String groupId) {
        return new ConsumerGroupNotFound(groupId);
    }

    static InvalidPartition invalidPartition(String topic, int partition, int totalPartitions) {
        return new InvalidPartition(topic, partition, totalPartitions);
    }

    static MessageTooLarge messageTooLarge(long actualSize, long maxSize) {
        return new MessageTooLarge(actualSize, maxSize);
    }

    static InvalidConfiguration invalidConfiguration(String reason) {
        return new InvalidConfiguration(reason);
    }

    static OperationFailed operationFailed(String operation) {
        return new OperationFailed(operation, Option.none());
    }

    static OperationFailed operationFailed(String operation, Throwable cause) {
        return new OperationFailed(operation, Option.option(cause));
    }

    static NoMessagesAvailable noMessagesAvailable(String topic, String groupId) {
        return new NoMessagesAvailable(topic, groupId);
    }
}

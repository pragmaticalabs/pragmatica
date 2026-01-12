package org.pragmatica.aether.infra.outbox;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

/**
 * Error types for outbox operations.
 */
public sealed interface OutboxError extends Cause {
    /**
     * Message not found.
     */
    record MessageNotFound(String messageId) implements OutboxError {
        @Override
        public String message() {
            return "Message not found: " + messageId;
        }
    }

    /**
     * Message already exists.
     */
    record MessageAlreadyExists(String messageId) implements OutboxError {
        @Override
        public String message() {
            return "Message already exists: " + messageId;
        }
    }

    /**
     * Invalid message state for operation.
     */
    record InvalidMessageState(String messageId, OutboxMessageStatus currentStatus, String operation) implements OutboxError {
        @Override
        public String message() {
            return "Cannot " + operation + " message '" + messageId + "' in status " + currentStatus;
        }
    }

    /**
     * Message delivery failed.
     */
    record DeliveryFailed(String messageId, Option<Throwable> cause) implements OutboxError {
        @Override
        public String message() {
            return "Delivery failed for message: " + messageId + cause.fold(() -> "", c -> " - " + c.getMessage());
        }
    }

    /**
     * Invalid configuration.
     */
    record InvalidConfiguration(String reason) implements OutboxError {
        @Override
        public String message() {
            return "Invalid outbox configuration: " + reason;
        }
    }

    /**
     * Topic not found.
     */
    record TopicNotFound(String topic) implements OutboxError {
        @Override
        public String message() {
            return "Topic not found: " + topic;
        }
    }

    // Factory methods
    static MessageNotFound messageNotFound(String messageId) {
        return new MessageNotFound(messageId);
    }

    static MessageAlreadyExists messageAlreadyExists(String messageId) {
        return new MessageAlreadyExists(messageId);
    }

    static InvalidMessageState invalidMessageState(String messageId, OutboxMessageStatus status, String operation) {
        return new InvalidMessageState(messageId, status, operation);
    }

    static DeliveryFailed deliveryFailed(String messageId) {
        return new DeliveryFailed(messageId, Option.none());
    }

    static DeliveryFailed deliveryFailed(String messageId, Throwable cause) {
        return new DeliveryFailed(messageId, Option.option(cause));
    }

    static <T> Result<T> invalidConfiguration(String reason) {
        return new InvalidConfiguration(reason).result();
    }

    static TopicNotFound topicNotFound(String topic) {
        return new TopicNotFound(topic);
    }
}

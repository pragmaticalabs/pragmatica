package org.pragmatica.aether.infra.outbox;

import org.pragmatica.lang.Option;

import java.time.Instant;

import static org.pragmatica.lang.Option.none;

/**
 * Message stored in the outbox for reliable delivery.
 * <p>
 * Note: The payload byte array is not defensively copied for performance.
 * Callers must not mutate the array after construction.
 *
 * @param id            Unique message identifier
 * @param topic         Message topic/destination
 * @param key           Optional message key for ordering
 * @param payload       Message payload (must not be mutated after construction)
 * @param status        Current message status
 * @param retryCount    Number of delivery attempts
 * @param createdAt     When the message was created
 * @param scheduledAt   When the message should be delivered
 * @param processedAt   When the message was last processed
 * @param errorMessage  Last error message if failed
 */
public record OutboxMessage(String id,
                            String topic,
                            Option<String> key,
                            byte[] payload,
                            OutboxMessageStatus status,
                            int retryCount,
                            Instant createdAt,
                            Instant scheduledAt,
                            Option<Instant> processedAt,
                            Option<String> errorMessage) {
    /**
     * Creates a new pending outbox message.
     */
    public static OutboxMessage outboxMessage(String id, String topic, byte[] payload) {
        return outboxMessage(id, topic, none(), payload, Instant.now());
    }

    /**
     * Creates a new pending outbox message with key.
     */
    public static OutboxMessage outboxMessage(String id, String topic, Option<String> key, byte[] payload) {
        return outboxMessage(id, topic, key, payload, Instant.now());
    }

    /**
     * Creates a new pending outbox message with scheduled time.
     */
    public static OutboxMessage outboxMessage(String id,
                                              String topic,
                                              Option<String> key,
                                              byte[] payload,
                                              Instant scheduledAt) {
        return new OutboxMessage(id,
                                 topic,
                                 key,
                                 payload,
                                 OutboxMessageStatus.PENDING,
                                 0,
                                 Instant.now(),
                                 scheduledAt,
                                 none(),
                                 none());
    }

    /**
     * Creates a copy with updated status.
     */
    public OutboxMessage withStatus(OutboxMessageStatus newStatus) {
        return new OutboxMessage(id,
                                 topic,
                                 key,
                                 payload,
                                 newStatus,
                                 retryCount,
                                 createdAt,
                                 scheduledAt,
                                 processedAt,
                                 errorMessage);
    }

    /**
     * Creates a copy marked as processing.
     */
    public OutboxMessage markProcessing() {
        return new OutboxMessage(id,
                                 topic,
                                 key,
                                 payload,
                                 OutboxMessageStatus.PROCESSING,
                                 retryCount,
                                 createdAt,
                                 scheduledAt,
                                 Option.option(Instant.now()),
                                 errorMessage);
    }

    /**
     * Creates a copy marked as delivered.
     */
    public OutboxMessage markDelivered() {
        return new OutboxMessage(id,
                                 topic,
                                 key,
                                 payload,
                                 OutboxMessageStatus.DELIVERED,
                                 retryCount,
                                 createdAt,
                                 scheduledAt,
                                 Option.option(Instant.now()),
                                 none());
    }

    /**
     * Creates a copy marked as failed with error message.
     */
    public OutboxMessage markFailed(String error) {
        return new OutboxMessage(id,
                                 topic,
                                 key,
                                 payload,
                                 OutboxMessageStatus.FAILED,
                                 retryCount + 1,
                                 createdAt,
                                 scheduledAt,
                                 Option.option(Instant.now()),
                                 Option.option(error));
    }

    /**
     * Creates a copy rescheduled for retry.
     */
    public OutboxMessage reschedule(Instant nextAttempt) {
        return new OutboxMessage(id,
                                 topic,
                                 key,
                                 payload,
                                 OutboxMessageStatus.PENDING,
                                 retryCount + 1,
                                 createdAt,
                                 nextAttempt,
                                 processedAt,
                                 errorMessage);
    }

    /**
     * Checks if message can be retried.
     */
    public boolean canRetry(int maxRetries) {
        return retryCount < maxRetries && status != OutboxMessageStatus.CANCELLED;
    }

    /**
     * Checks if message is ready for processing.
     */
    public boolean isReady() {
        return status == OutboxMessageStatus.PENDING && !scheduledAt.isAfter(Instant.now());
    }
}

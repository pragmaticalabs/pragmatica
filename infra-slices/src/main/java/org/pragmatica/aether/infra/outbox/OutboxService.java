package org.pragmatica.aether.infra.outbox;

import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.time.Instant;
import java.util.List;

/**
 * Outbox service for reliable message delivery.
 * Implements the transactional outbox pattern.
 */
public interface OutboxService extends Slice {
    /**
     * Adds a message to the outbox for delivery.
     *
     * @param message The message to add
     * @return The added message
     */
    Promise<OutboxMessage> add(OutboxMessage message);

    /**
     * Gets a message by ID.
     *
     * @param messageId Message identifier
     * @return The message if found
     */
    Promise<Option<OutboxMessage>> get(String messageId);

    /**
     * Gets messages ready for processing.
     *
     * @param limit Maximum number of messages to return
     * @return List of ready messages
     */
    Promise<List<OutboxMessage>> getReady(int limit);

    /**
     * Gets messages by topic.
     *
     * @param topic Topic name
     * @return List of messages for the topic
     */
    Promise<List<OutboxMessage>> getByTopic(String topic);

    /**
     * Gets messages by status.
     *
     * @param status Message status
     * @return List of messages with the status
     */
    Promise<List<OutboxMessage>> getByStatus(OutboxMessageStatus status);

    /**
     * Marks a message as processing.
     *
     * @param messageId Message identifier
     * @return Updated message
     */
    Promise<OutboxMessage> markProcessing(String messageId);

    /**
     * Marks a message as delivered.
     *
     * @param messageId Message identifier
     * @return Updated message
     */
    Promise<OutboxMessage> markDelivered(String messageId);

    /**
     * Marks a message as failed.
     *
     * @param messageId Message identifier
     * @param error Error message
     * @return Updated message
     */
    Promise<OutboxMessage> markFailed(String messageId, String error);

    /**
     * Reschedules a message for retry.
     *
     * @param messageId Message identifier
     * @param nextAttempt When to retry
     * @return Updated message
     */
    Promise<OutboxMessage> reschedule(String messageId, Instant nextAttempt);

    /**
     * Cancels a pending message.
     *
     * @param messageId Message identifier
     * @return Updated message
     */
    Promise<OutboxMessage> cancel(String messageId);

    /**
     * Deletes a message.
     *
     * @param messageId Message identifier
     * @return true if deleted
     */
    Promise<Boolean> delete(String messageId);

    /**
     * Cleans up old delivered messages.
     *
     * @param olderThan Delete messages older than this
     * @return Number of deleted messages
     */
    Promise<Integer> cleanup(Instant olderThan);

    /**
     * Gets message count by status.
     *
     * @param status Message status
     * @return Number of messages
     */
    Promise<Long> countByStatus(OutboxMessageStatus status);

    /**
     * Gets total message count.
     *
     * @return Total number of messages
     */
    Promise<Long> count();

    /**
     * Creates an in-memory outbox service.
     */
    static OutboxService outboxService(OutboxConfig config) {
        return InMemoryOutboxService.inMemoryOutboxService(config);
    }

    /**
     * Creates an in-memory outbox service with default configuration.
     */
    static OutboxService outboxService() {
        return outboxService(OutboxConfig.DEFAULT);
    }

    // ========== Slice Lifecycle ==========
    @Override
    default Promise<Unit> start() {
        return Promise.success(Unit.unit());
    }

    @Override
    default Promise<Unit> stop() {
        return Promise.success(Unit.unit());
    }

    @Override
    default List<SliceMethod< ?, ?>> methods() {
        return List.of();
    }
}

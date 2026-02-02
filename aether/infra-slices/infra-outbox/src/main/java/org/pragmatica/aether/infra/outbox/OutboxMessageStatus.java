package org.pragmatica.aether.infra.outbox;
/**
 * Status of an outbox message in its lifecycle.
 */
public enum OutboxMessageStatus {
    /**
     * Message is pending delivery.
     */
    PENDING,
    /**
     * Message is currently being processed.
     */
    PROCESSING,
    /**
     * Message was successfully delivered.
     */
    DELIVERED,
    /**
     * Message delivery failed after all retries.
     */
    FAILED,
    /**
     * Message was cancelled before delivery.
     */
    CANCELLED
}

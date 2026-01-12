package org.pragmatica.aether.infra.pubsub;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Message for pub/sub communication.
 *
 * @param id        Unique message identifier
 * @param payload   Message payload
 * @param headers   Message headers
 * @param timestamp Message creation timestamp
 */
public record Message(String id, byte[] payload, Map<String, String> headers, Instant timestamp) {
    /**
     * Create a message with generated ID and current timestamp.
     *
     * @param payload Message payload
     * @return New message
     */
    public static Message message(byte[] payload) {
        return new Message(UUID.randomUUID()
                               .toString(),
                           payload,
                           Map.of(),
                           Instant.now());
    }

    /**
     * Create a message with generated ID, headers, and current timestamp.
     *
     * @param payload Message payload
     * @param headers Message headers
     * @return New message
     */
    public static Message message(byte[] payload, Map<String, String> headers) {
        return new Message(UUID.randomUUID()
                               .toString(),
                           payload,
                           headers,
                           Instant.now());
    }

    /**
     * Create a message from string payload.
     *
     * @param payload String payload (UTF-8 encoded)
     * @return New message
     */
    public static Message message(String payload) {
        return message(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Get payload as string (UTF-8 decoded).
     *
     * @return Payload as string
     */
    public String payloadAsString() {
        return new String(payload, java.nio.charset.StandardCharsets.UTF_8);
    }
}

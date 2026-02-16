package org.pragmatica.aether.infra.pubsub;

import org.pragmatica.lang.Result;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.pragmatica.lang.Result.success;

/// Message for pub/sub communication.
///
/// @param id        Unique message identifier
/// @param payload   Message payload
/// @param headers   Message headers
/// @param timestamp Message creation timestamp
public record Message(String id, byte[] payload, Map<String, String> headers, Instant timestamp) {
    /// Factory returning Result for validation.
    public static Result<Message> message(String id, byte[] payload, Map<String, String> headers, Instant timestamp) {
        return success(new Message(id, payload, headers, timestamp));
    }

    /// Create a message with generated ID and current timestamp.
    ///
    /// @param payload Message payload
    /// @return New message
    public static Message message(byte[] payload) {
        return message(generateId(), payload, Map.of(), Instant.now()).unwrap();
    }

    /// Create a message with generated ID, headers, and current timestamp.
    ///
    /// @param payload Message payload
    /// @param headers Message headers
    /// @return New message
    public static Message message(byte[] payload, Map<String, String> headers) {
        return message(generateId(), payload, headers, Instant.now()).unwrap();
    }

    /// Create a message from string payload.
    ///
    /// @param payload String payload (UTF-8 encoded)
    /// @return New message
    public static Message message(String payload) {
        return message(payload.getBytes(StandardCharsets.UTF_8));
    }

    /// Get payload as string (UTF-8 decoded).
    ///
    /// @return Payload as string
    public String payloadAsString() {
        return new String(payload, StandardCharsets.UTF_8);
    }

    private static String generateId() {
        return UUID.randomUUID()
                   .toString();
    }
}

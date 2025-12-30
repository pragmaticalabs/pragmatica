package org.pragmatica.http.websocket;
/**
 * WebSocket message types.
 */
public sealed interface WebSocketMessage {
    /**
     * Connection opened.
     */
    record Open() implements WebSocketMessage {}

    /**
     * Text message received.
     */
    record Text(String content) implements WebSocketMessage {}

    /**
     * Binary message received.
     */
    record Binary(byte[] content) implements WebSocketMessage {}

    /**
     * Connection closed.
     */
    record Close() implements WebSocketMessage {}
}

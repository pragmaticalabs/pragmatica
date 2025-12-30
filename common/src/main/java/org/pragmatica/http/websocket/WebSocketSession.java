package org.pragmatica.http.websocket;
/**
 * WebSocket session for sending messages and managing connection.
 */
public interface WebSocketSession {
    /**
     * Unique session identifier.
     */
    String id();

    /**
     * Send a text message.
     */
    void send(String text);

    /**
     * Send a binary message.
     */
    void send(byte[] binary);

    /**
     * Close the connection.
     */
    void close();

    /**
     * Check if the session is still open.
     */
    boolean isOpen();
}

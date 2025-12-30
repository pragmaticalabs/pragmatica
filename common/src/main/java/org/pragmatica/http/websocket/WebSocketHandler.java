package org.pragmatica.http.websocket;
/**
 * Handler for WebSocket messages.
 * <p>
 * All message types (Open, Text, Binary, Close) are delivered through the single
 * {@link #handle(WebSocketSession, WebSocketMessage)} method.
 */
public interface WebSocketHandler {
    /**
     * Handle a WebSocket message.
     *
     * @param session the session that received the message
     * @param message the message (Open, Text, Binary, or Close)
     */
    void handle(WebSocketSession session, WebSocketMessage message);
}

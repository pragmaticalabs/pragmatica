package org.pragmatica.http.websocket;

import java.util.function.Supplier;

/**
 * WebSocket endpoint configuration.
 *
 * @param path    the WebSocket path (e.g., "/ws/events")
 * @param handler supplier for WebSocket handler (called for each new connection)
 */
public record WebSocketEndpoint(String path, Supplier<WebSocketHandler> handler) {
    public static WebSocketEndpoint webSocketEndpoint(String path, Supplier<WebSocketHandler> handler) {
        return new WebSocketEndpoint(path, handler);
    }

    public static WebSocketEndpoint webSocketEndpoint(String path, WebSocketHandler handler) {
        return new WebSocketEndpoint(path, () -> handler);
    }
}

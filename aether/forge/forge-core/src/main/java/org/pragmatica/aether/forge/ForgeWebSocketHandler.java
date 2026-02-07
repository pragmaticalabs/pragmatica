package org.pragmatica.aether.forge;

import org.pragmatica.http.websocket.WebSocketHandler;
import org.pragmatica.http.websocket.WebSocketMessage;
import org.pragmatica.http.websocket.WebSocketSession;

import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WebSocket handler for Forge dashboard real-time status updates.
 * Manages connected clients and broadcasts full status JSON.
 */
public class ForgeWebSocketHandler implements WebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(ForgeWebSocketHandler.class);
    private static final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void handle(WebSocketSession session, WebSocketMessage message) {
        switch (message) {
            case WebSocketMessage.Open _ -> onOpen(session);
            case WebSocketMessage.Text _ -> {}
            case WebSocketMessage.Binary _ -> {}
            case WebSocketMessage.Close _ -> onClose(session);
        }
    }

    private void onOpen(WebSocketSession session) {
        sessions.put(session.id(), session);
        log.info("Forge dashboard client connected: {}", session.id());
    }

    private void onClose(WebSocketSession session) {
        sessions.remove(session.id());
        log.info("Forge dashboard client disconnected: {}", session.id());
    }

    /**
     * Broadcast a message to all connected Forge dashboard clients.
     */
    public static void broadcast(String message) {
        sessions.values()
                .forEach(session -> {
                    if (session.isOpen()) {
                        session.send(message);
                    }
                });
    }

    /**
     * Get the number of connected Forge dashboard clients.
     */
    public static int connectedClients() {
        return (int) sessions.values()
                            .stream()
                            .filter(WebSocketSession::isOpen)
                            .count();
    }
}

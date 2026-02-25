package org.pragmatica.aether.api;

import org.pragmatica.http.websocket.WebSocketHandler;
import org.pragmatica.http.websocket.WebSocketMessage;
import org.pragmatica.http.websocket.WebSocketSession;

import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Reusable WebSocket handler for broadcasting status updates.
/// Instance-based (not static) to support independent session pools
/// across management server and Forge dashboard.
@SuppressWarnings("JBCT-RET-01")
public class StatusWebSocketHandler implements WebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(StatusWebSocketHandler.class);
    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final WebSocketAuthenticator authenticator;

    public StatusWebSocketHandler(WebSocketAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    public void handle(WebSocketSession session, WebSocketMessage message) {
        switch (message) {
            case WebSocketMessage.Open _ -> onOpen(session);
            case WebSocketMessage.Text text -> onText(session, text.content());
            case WebSocketMessage.Binary _ -> {}
            case WebSocketMessage.Close _ -> onClose(session);
        }
    }

    private void onOpen(WebSocketSession session) {
        sessions.put(session.id(), session);
        authenticator.onOpen(session);
        log.info("Status client connected: {}", session.id());
    }

    private void onText(WebSocketSession session, String text) {
        authenticator.onMessage(session, text);
    }

    private void onClose(WebSocketSession session) {
        authenticator.onClose(session);
        sessions.remove(session.id());
        log.info("Status client disconnected: {}", session.id());
    }

    /// Broadcast a message to all connected and authenticated clients.
    public void broadcast(String message) {
        sessions.values()
                .removeIf(session -> !session.isOpen());
        sessions.values()
                .forEach(session -> sendIfAuthenticated(session, message));
    }

    private void sendIfAuthenticated(WebSocketSession session, String message) {
        if (authenticator.isAuthenticated(session.id())) {
            session.send(message);
        }
    }

    /// Get the number of connected clients.
    public int connectedClients() {
        sessions.values()
                .removeIf(session -> !session.isOpen());
        return sessions.size();
    }
}

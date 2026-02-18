package org.pragmatica.aether.api;

import org.pragmatica.http.websocket.WebSocketHandler;
import org.pragmatica.http.websocket.WebSocketMessage;
import org.pragmatica.http.websocket.WebSocketSession;

import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// WebSocket handler for dashboard real-time updates.
///
///
/// Manages connected dashboard clients and broadcasts metrics updates.
/// Uses pragmatica-lite's WebSocket API.
@SuppressWarnings("JBCT-RET-01")
public class DashboardWebSocketHandler implements WebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(DashboardWebSocketHandler.class);
    private static final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    private final DashboardMetricsPublisher metricsPublisher;

    public DashboardWebSocketHandler(DashboardMetricsPublisher metricsPublisher) {
        this.metricsPublisher = metricsPublisher;
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
        log.info("Dashboard client connected: {}", session.id());
        // Send initial state snapshot
        var initialState = metricsPublisher.buildInitialState();
        session.send(initialState);
    }

    private void onText(WebSocketSession session, String message) {
        log.debug("Received from dashboard client {}: {}", session.id(), message);
        // Parse client messages (subscribe, set threshold, get history)
        if (message.contains("\"type\":\"SUBSCRIBE\"")) {
            log.debug("Client {} subscribed to streams", session.id());
        } else if (message.contains("\"type\":\"SET_THRESHOLD\"")) {
            metricsPublisher.handleSetThreshold(message);
        } else if (message.contains("\"type\":\"GET_HISTORY\"")) {
            var history = metricsPublisher.buildHistoryResponse(message);
            session.send(history);
        }
    }

    private void onClose(WebSocketSession session) {
        sessions.remove(session.id());
        log.info("Dashboard client disconnected: {}", session.id());
    }

    /// Broadcast a message to all connected dashboard clients.
    public static void broadcast(String message) {
        sessions.values()
                .forEach(session -> {
                    if (session.isOpen()) {
                        session.send(message);
                    }
                });
    }

    /// Get the number of connected dashboard clients.
    public static int connectedClients() {
        return (int) sessions.values()
                            .stream()
                            .filter(WebSocketSession::isOpen)
                            .count();
    }
}

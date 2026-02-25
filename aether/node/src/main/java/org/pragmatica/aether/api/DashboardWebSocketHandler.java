package org.pragmatica.aether.api;

import org.pragmatica.http.websocket.WebSocketHandler;
import org.pragmatica.http.websocket.WebSocketMessage;
import org.pragmatica.http.websocket.WebSocketSession;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

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
    private static final AtomicReference<WebSocketAuthenticator> authenticatorRef = new AtomicReference<>();

    private final DashboardMetricsPublisher metricsPublisher;
    private final WebSocketAuthenticator authenticator;

    public DashboardWebSocketHandler(DashboardMetricsPublisher metricsPublisher,
                                     WebSocketAuthenticator authenticator) {
        this.metricsPublisher = metricsPublisher;
        this.authenticator = authenticator;
        authenticatorRef.set(authenticator);
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
        if (authenticator.onOpen(session)) {
            session.send(metricsPublisher.buildInitialState());
        }
    }

    private void onText(WebSocketSession session, String message) {
        if (authenticator.onMessage(session, message)) {
            return;
        }
        log.debug("Received from dashboard client {}: {}", session.id(), message);
        handleClientMessage(session, message);
    }

    @SuppressWarnings("JBCT-PAT-01")
    private void handleClientMessage(WebSocketSession session, String message) {
        if (message.contains("\"type\":\"SUBSCRIBE\"")) {
            log.debug("Client {} subscribed to streams", session.id());
        } else if (message.contains("\"type\":\"SET_THRESHOLD\"")) {
            metricsPublisher.handleSetThreshold(message);
        } else if (message.contains("\"type\":\"GET_HISTORY\"")) {
            session.send(metricsPublisher.buildHistoryResponse(message));
        }
    }

    private void onClose(WebSocketSession session) {
        authenticator.onClose(session);
        sessions.remove(session.id());
        log.info("Dashboard client disconnected: {}", session.id());
    }

    /// Broadcast a message to all connected and authenticated dashboard clients.
    public static void broadcast(String message) {
        var auth = authenticatorRef.get();
        sessions.values()
                .forEach(session -> sendIfAuthenticated(session, message, auth));
    }

    private static void sendIfAuthenticated(WebSocketSession session, String message, WebSocketAuthenticator auth) {
        if (session.isOpen() && (auth == null || auth.isAuthenticated(session.id()))) {
            session.send(message);
        }
    }

    /// Get the number of connected dashboard clients.
    public static int connectedClients() {
        return (int) sessions.values()
                            .stream()
                            .filter(WebSocketSession::isOpen)
                            .count();
    }
}

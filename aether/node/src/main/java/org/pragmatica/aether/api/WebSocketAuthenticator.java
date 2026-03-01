package org.pragmatica.aether.api;

import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.RouteSecurityPolicy;
import org.pragmatica.aether.http.security.AuditLog;
import org.pragmatica.aether.http.security.SecurityValidator;
import org.pragmatica.http.websocket.WebSocketSession;
import org.pragmatica.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Shared WebSocket authentication logic.
///
/// When security is enabled, clients must send an auth message as their first message:
/// `{"type":"AUTH","apiKey":"..."}`
///
/// Until authenticated, all other messages are rejected.
/// Sessions that don't authenticate within 5 seconds are closed.
@SuppressWarnings({"JBCT-RET-01", "JBCT-RET-03"})
public final class WebSocketAuthenticator {
    private static final Logger log = LoggerFactory.getLogger(WebSocketAuthenticator.class);
    private static final long AUTH_TIMEOUT_MS = 5_000;
    private static final JsonMapper JSON = JsonMapper.defaultJsonMapper();
    private static final String AUTH_TYPE = "AUTH";

    private final SecurityValidator securityValidator;
    private final boolean securityEnabled;
    private final Set<String> authenticatedSessions = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> pendingSessions = new ConcurrentHashMap<>();

    private WebSocketAuthenticator(SecurityValidator securityValidator, boolean securityEnabled) {
        this.securityValidator = securityValidator;
        this.securityEnabled = securityEnabled;
    }

    public static WebSocketAuthenticator webSocketAuthenticator(SecurityValidator securityValidator,
                                                                boolean securityEnabled) {
        return new WebSocketAuthenticator(securityValidator, securityEnabled);
    }

    /// Handle new connection. Returns true if session is immediately authorized (no auth needed).
    public boolean onOpen(WebSocketSession session) {
        if (!securityEnabled) {
            authenticatedSessions.add(session.id());
            return true;
        }
        pendingSessions.put(session.id(), System.currentTimeMillis());
        session.send("{\"type\":\"AUTH_REQUIRED\"}");
        startAuthTimeout(session);
        return false;
    }

    /// Handle text message. Returns true if message was consumed by auth (caller should skip normal processing).
    public boolean onMessage(WebSocketSession session, String text) {
        if (!securityEnabled || authenticatedSessions.contains(session.id())) {
            return false;
        }
        if (pendingSessions.containsKey(session.id())) {
            return handleAuthMessage(session, text);
        }
        session.send("{\"type\":\"AUTH_FAILED\",\"reason\":\"not_authenticated\"}");
        session.close();
        return true;
    }

    /// Check if a session is authenticated.
    public boolean isAuthenticated(String sessionId) {
        return ! securityEnabled || authenticatedSessions.contains(sessionId);
    }

    /// Remove session on close.
    public void onClose(WebSocketSession session) {
        authenticatedSessions.remove(session.id());
        pendingSessions.remove(session.id());
    }

    private void startAuthTimeout(WebSocketSession session) {
        Thread.startVirtualThread(() -> waitAndExpireSession(session));
    }

    @SuppressWarnings("JBCT-PAT-01")
    private void waitAndExpireSession(WebSocketSession session) {
        try{
            Thread.sleep(AUTH_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread()
                  .interrupt();
            return;
        }
        if (pendingSessions.remove(session.id()) != null) {
            log.warn("WebSocket auth timeout for session {}", session.id());
            AuditLog.wsAuthFailure(session.id(), "timeout");
            session.send("{\"type\":\"AUTH_FAILED\",\"reason\":\"timeout\"}");
            session.close();
        }
    }

    @SuppressWarnings("JBCT-PAT-01")
    private boolean handleAuthMessage(WebSocketSession session, String text) {
        var parsed = JSON.readString(text, AuthMessage.class);
        if (parsed.isFailure() || !AUTH_TYPE.equals(parsed.unwrap()
                                                          .type())) {
            session.send("{\"type\":\"AUTH_FAILED\",\"reason\":\"invalid_message\"}");
            return true;
        }
        var apiKey = parsed.unwrap()
                           .apiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            AuditLog.wsAuthFailure(session.id(), "empty_key");
            session.send("{\"type\":\"AUTH_FAILED\",\"reason\":\"missing_key\"}");
            session.close();
            return true;
        }
        return validateApiKeyAndRespond(session, apiKey);
    }

    @SuppressWarnings("JBCT-PAT-01")
    private boolean validateApiKeyAndRespond(WebSocketSession session, String apiKey) {
        var headers = Map.of("x-api-key", List.of(apiKey));
        var context = HttpRequestContext.httpRequestContext("/ws", "GET", Map.of(), headers, "ws-auth");
        var result = securityValidator.validate(context, RouteSecurityPolicy.apiKeyRequired());
        if (result.isSuccess()) {
            acceptSession(session,
                          result.unwrap()
                                .principal()
                                .value());
        } else {
            rejectSession(session);
        }
        return true;
    }

    private void acceptSession(WebSocketSession session, String principal) {
        pendingSessions.remove(session.id());
        authenticatedSessions.add(session.id());
        AuditLog.wsAuthSuccess(session.id(), principal);
        session.send("{\"type\":\"AUTH_SUCCESS\"}");
    }

    private void rejectSession(WebSocketSession session) {
        pendingSessions.remove(session.id());
        AuditLog.wsAuthFailure(session.id(), "invalid_key");
        session.send("{\"type\":\"AUTH_FAILED\",\"reason\":\"invalid_key\"}");
        session.close();
    }

    record AuthMessage(String type, String apiKey) {}
}

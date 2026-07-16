// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.security.SecurityValidator;
import org.pragmatica.http.websocket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/// #293 — the dashboard's INITIAL_STATE must be delivered on the AUTHENTICATED path (it was only ever
/// pushed on the security-disabled onOpen branch). Verifies the `onAuthSuccess` callback fires exactly
/// once on a successful authentication and never on failure or when security is disabled.
class WebSocketAuthenticatorTest {
    private static final String VALID_KEY = "dashboard-key-value-1";

    private static String authMessage(String key) {
        return "{\"type\":\"AUTH\",\"apiKey\":\"" + key + "\"}";
    }

    @Test
    void onMessage_successfulAuth_runsOnAuthSuccessCallbackOnce() {
        var authenticator = WebSocketAuthenticator.webSocketAuthenticator(SecurityValidator.apiKeyValidator(Set.of(VALID_KEY)),
                                                                          true);
        var session = new RecordingSession("s1");
        var initialStateSends = new AtomicInteger();

        authenticator.onOpen(session);
        var consumed = authenticator.onMessage(session, authMessage(VALID_KEY), initialStateSends::incrementAndGet);

        assertThat(consumed).isTrue();
        assertThat(authenticator.isAuthenticated(session.id())).isTrue();
        assertThat(initialStateSends.get()).as("INITIAL_STATE pushed exactly once after auth").isEqualTo(1);
        assertThat(session.sent).anyMatch(msg -> msg.contains("AUTH_SUCCESS"));
    }

    @Test
    void onMessage_invalidKey_doesNotRunCallback() {
        var authenticator = WebSocketAuthenticator.webSocketAuthenticator(SecurityValidator.apiKeyValidator(Set.of(VALID_KEY)),
                                                                          true);
        var session = new RecordingSession("s2");
        var initialStateSends = new AtomicInteger();

        authenticator.onOpen(session);
        authenticator.onMessage(session, authMessage("wrong-key-value-99"), initialStateSends::incrementAndGet);

        assertThat(authenticator.isAuthenticated(session.id())).isFalse();
        assertThat(initialStateSends.get()).as("no INITIAL_STATE on failed auth").isEqualTo(0);
        assertThat(session.sent).anyMatch(msg -> msg.contains("AUTH_FAILED"));
    }

    @Test
    void onMessage_securityDisabled_callbackNotInvokedViaAuthPath() {
        var authenticator = WebSocketAuthenticator.webSocketAuthenticator(SecurityValidator.apiKeyValidator(Set.of(VALID_KEY)),
                                                                          false);
        var session = new RecordingSession("s3");
        var initialStateSends = new AtomicInteger();

        authenticator.onOpen(session);
        // With security disabled onMessage returns false (not consumed) — the handler sent INITIAL_STATE
        // on the onOpen path instead, so the auth-path callback must not fire.
        var consumed = authenticator.onMessage(session, authMessage(VALID_KEY), initialStateSends::incrementAndGet);

        assertThat(consumed).isFalse();
        assertThat(initialStateSends.get()).isEqualTo(0);
    }

    private static final class RecordingSession implements WebSocketSession {
        private final String id;
        private final List<String> sent = new ArrayList<>();
        private boolean open = true;

        RecordingSession(String id) {
            this.id = id;
        }

        @Override public String id() {return id;}
        @Override public void send(String text) {sent.add(text);}
        @Override public void send(byte[] binary) {}
        @Override public void close() {open = false;}
        @Override public boolean isOpen() {return open;}
    }
}

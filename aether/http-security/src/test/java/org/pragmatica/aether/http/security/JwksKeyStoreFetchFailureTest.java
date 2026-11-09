// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.security;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// #2926 (CTO ruling): an unknown `kid` refreshes the key set once. When that refresh FAILS the key set could not be
/// read -- the server's failure ([SecurityError.JwksFetchFailed], 503) -- and must not be reported as an unknown key
/// ([SecurityError.KeyNotFound], 401 invalid_token). When the refresh succeeds and the kid is absent, it IS an unknown key.
class JwksKeyStoreFetchFailureTest {
    @Test
    void unreachableJwks_isJwksFetchFailed_notKeyNotFound() {
        try (var store = new JwksKeyStore("http://127.0.0.1:1/.well-known/jwks.json", 3600)) {
            assertThat(failureOf(store)).isInstanceOf(SecurityError.JwksFetchFailed.class);
        }
    }

    @Test
    void jwksAnsweringHttp500_isJwksFetchFailed() throws Exception {
        assertFetchOutcome(500, "boom", SecurityError.JwksFetchFailed.class);
    }

    /// CONTROL: a healthy JWKS that simply lacks the kid keeps the 401 unknown-key outcome.
    @Test
    void healthyJwksWithoutTheKid_isKeyNotFound() throws Exception {
        assertFetchOutcome(200, "{\"keys\":[]}", SecurityError.KeyNotFound.class);
    }

    private static Cause failureOf(JwksKeyStore store) {
        return store.findKey("k1", "RS256").fold(cause -> cause, _ -> Causes.cause("a key was found"));
    }

    private static void assertFetchOutcome(int status, String body, Class<? extends SecurityError> expected) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/jwks",
                             exchange -> {
                                 var bytes = body.getBytes(StandardCharsets.UTF_8);
                                 exchange.sendResponseHeaders(status, bytes.length);
                                 exchange.getResponseBody().write(bytes);
                                 exchange.close();
                             });
        server.start();

        try (var store = new JwksKeyStore("http://127.0.0.1:" + server.getAddress().getPort() + "/jwks", 3600)) {
            assertThat(failureOf(store)).isInstanceOf(expected);
        } finally {
            server.stop(0);
        }
    }
}

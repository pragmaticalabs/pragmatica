// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.http.security;

import org.pragmatica.aether.config.ApiKeyEntry;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.http.security.AdminGrant.RequestScope;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/// The one test-grant fixture: a live [AdminGrant] for a test's write, obtained the way a request obtains it. A test
/// ADMIN [ApiKeyEntry] validates a built [HttpRequestContext] through the public [HttpAuthenticator#apiKeyValidator]
/// inside a fresh [RequestScope], and the write runs inside that same scope, so the grant is live exactly there and dead
/// once the fixture returns. There is no main-scope factory and no test widens `AdminGrant#mint`.
public final class TestAdminGrants {
    private static final String ADMIN_KEY = "test-admin-key";
    private static final HttpAuthenticator ADMIN_AUTHENTICATOR = HttpAuthenticator.apiKeyValidator(Map.of(ADMIN_KEY,
                                                                                                          ApiKeyEntry.apiKeyEntry("test-admin",
                                                                                                                                  Set.of("service"),
                                                                                                                                  "ADMIN")));

    private TestAdminGrants() {}

    /// Runs `write` with a live ADMIN grant, inside the request scope that minted it. Void by contract: the write is a
    /// side effect, and the scope unbinds when this returns or throws.
    @Contract
    public static void withAdminGrant(Consumer<AdminGrant> write) {
        RequestScope.requestScope()
                    .run(() -> write.accept(mintedGrant()));
    }

    private static AdminGrant mintedGrant() {
        return ADMIN_AUTHENTICATOR.authenticate(adminRequest(),
                                                SecurityPolicy.authenticated())
                                  .map(Authenticated::grant)
                                  .or(Option.none())
                                  .or(TestAdminGrants::unminted);
    }

    // A fixture fault, never a business path: the test ADMIN key always matches, so no grant means the fixture broke.
    private static AdminGrant unminted() {
        throw new IllegalStateException("The test ADMIN key minted no grant");
    }

    private static HttpRequestContext adminRequest() {
        return HttpRequestContext.httpRequestContext("/test",
                                                     "GET",
                                                     Map.of(),
                                                     Map.of("X-API-Key", List.of(ADMIN_KEY)),
                                                     new byte[0],
                                                     "test-admin-grant");
    }
}

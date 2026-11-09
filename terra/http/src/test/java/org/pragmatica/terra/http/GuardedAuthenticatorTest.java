// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra.http;

import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.http.security.HttpAuthenticator;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.testlogging.LogCapture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2880: [GuardedAuthenticator] turns provider defects into a generic refusal plus an ERROR log naming the provider.
class GuardedAuthenticatorTest {
    private static final String LEAK = "db-password=hunter2";

    private LogCapture capture;

    @BeforeEach
    void setUp() {
        capture = LogCapture.capture(GuardedAuthenticator.class, Level.ERROR, false);
    }

    @AfterEach
    void tearDown() {
        capture.close();
    }

    private static Result<SecurityContext> validate(HttpAuthenticator authenticator) {
        var request = HttpRequestContext.httpRequestContext("/", "GET", Map.of(), Map.of(), "test");

        return new GuardedAuthenticator("test-provider", authenticator).validate(request, SecurityPolicy.authenticated());
    }

    @Test
    void validate_throwing_isTheGenericCause_andLogsProviderAndException() {
        validate((_, _) -> { throw new IllegalStateException(LEAK); })
            .onSuccess(_ -> fail("must not authenticate"))
            .onFailure(cause -> assertThat(cause).isSameAs(GuardedAuthenticator.UNAVAILABLE));

        assertThat(capture.messages(Level.ERROR)).anyMatch(message -> message.contains("test-provider"));
        assertThat(capture.events()).anyMatch(event -> event.getThrown() instanceof IllegalStateException);
    }

    @Test
    void validate_returningNull_isTheGenericCause() {
        validate((_, _) -> null).onSuccess(_ -> fail("must not authenticate"))
                                .onFailure(cause -> assertThat(cause).isSameAs(GuardedAuthenticator.UNAVAILABLE));
    }

    @Test
    void validate_delegatesResults_control() {
        assertThat(validate((_, _) -> SecurityContext.securityContext("u", Set.of(), Map.of())).isSuccess()).isTrue();
        assertThat(capture.messages(Level.ERROR)).isEmpty();
    }

    @Test
    void close_throwingOrNull_isAFailedPromise_neverAThrow() {
        var throwing = new HttpAuthenticator() {
            @Override public Promise<org.pragmatica.lang.Unit> close() { throw new IllegalStateException(LEAK); }
            @Override public Result<SecurityContext> validate(HttpRequestContext request, SecurityPolicy policy) { return null; }
        };
        var nulling = new HttpAuthenticator() {
            @Override public Promise<org.pragmatica.lang.Unit> close() { return null; }
            @Override public Result<SecurityContext> validate(HttpRequestContext request, SecurityPolicy policy) { return null; }
        };

        assertThat(new GuardedAuthenticator("p", throwing).close().await(timeSpan(5).seconds()).isFailure()).isTrue();
        assertThat(new GuardedAuthenticator("p", nulling).close().await(timeSpan(5).seconds()).isFailure()).isTrue();
    }

    @Test
    void close_delegates_control() {
        assertThat(new GuardedAuthenticator("p", HttpAuthenticator.denyUnlessPublicValidator()).close().await(timeSpan(5).seconds()).isSuccess()).isTrue();
    }
}

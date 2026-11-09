// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra.launcher;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.http.security.HttpAuthenticator;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.terra.http.HttpAuthenticatorFactory;
import org.pragmatica.testlogging.LogCapture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// #2880 fix round: an authenticator provider that misbehaves is a refusal, never a raw throw. At launch a factory that
/// throws or returns nothing refuses the launch naming the provider class (and never the exception's message); per
/// request an authenticator that throws answers a generic failure and an ERROR log naming the provider.
class LaunchHttpAuthenticatorFailureTest {
    private static final String LEAK = "db-password=hunter2";

    private LogCapture capture;

    @BeforeEach
    void setUp() {
        capture = LogCapture.capture("org.pragmatica.terra.http.GuardedAuthenticator", Level.ERROR, false);
    }

    @AfterEach
    void tearDown() {
        capture.close();
    }

    private static Result<LaunchHttp> launch(HttpAuthenticatorFactory factory) {
        return LaunchHttp.launchHttp(Path.of("."),
                                     ConfigurationProvider.builder()
                                                          .withDefaults(Map.of("http.security_mode", "custom", "http.authenticator", "bad"))
                                                          .build(),
                                     () -> Result.success(List.of(factory)));
    }

    private static void assertRefusedNaming(Result<LaunchHttp> launch, Class<?> provider) {
        launch.onSuccess(_ -> fail("launch must be refused"))
              .onFailure(cause -> assertThat(cause.message()).contains(provider.getName()).doesNotContain(LEAK));
    }

    private static Result<SecurityContext> validate(HttpAuthenticator authenticator) {
        var request = HttpRequestContext.httpRequestContext("/", "GET", Map.of(), Map.of(), "test");

        return authenticator.validate(request, SecurityPolicy.authenticated());
    }

    @Test
    void factoryNameThrowing_refusesTheLaunch_namingTheProvider() {
        assertRefusedNaming(launch(new ThrowingName()), ThrowingName.class);
    }

    @Test
    void factoryNameNull_refusesTheLaunch_namingTheProvider() {
        assertRefusedNaming(launch(new NullName()), NullName.class);
    }

    @Test
    void factoryAuthenticatorThrowing_refusesTheLaunch_namingTheProvider() {
        assertRefusedNaming(launch(new ThrowingFactory()), ThrowingFactory.class);
    }

    @Test
    void factoryReturningNullResult_refusesTheLaunch_namingTheProvider() {
        assertRefusedNaming(launch(new NullResultFactory()), NullResultFactory.class);
    }

    @Test
    void factoryReturningNullAuthenticator_refusesTheLaunch_namingTheProvider() {
        assertRefusedNaming(launch(new NullAuthenticatorFactory()), NullAuthenticatorFactory.class);
    }

    @Test
    void factoryReturningAFailure_refusesTheLaunch_asAnInvalidAuthenticator() {
        launch(new FailingFactory()).onSuccess(_ -> fail("launch must be refused"))
                                    .onFailure(cause -> assertThat(cause).isInstanceOf(TerraLaunchError.class)
                                                                         .extracting(Cause::message)
                                                                         .isEqualTo("Invalid authenticator: issuer is missing"));
    }

    @Test
    void authenticatorThrowingPerRequest_answersAGenericFailure_andLogsTheProvider() {
        var authenticator = launch(new ThrowingAuthenticatorFactory()).unwrap().authenticator().apply();

        validate(authenticator).onSuccess(_ -> fail("a throwing authenticator must not authenticate"))
                               .onFailure(cause -> assertThat(cause.message()).doesNotContain(LEAK)
                                                                              .doesNotContain("IllegalStateException")
                                                                              .doesNotContain("at org."));
        assertThat(capture.messages(Level.ERROR)).anyMatch(message -> message.contains(ThrowingAuthenticatorFactory.class.getName()));
    }

    @Test
    void authenticatorReturningNullPerRequest_answersAGenericFailure() {
        var authenticator = launch(new NullValidateFactory()).unwrap().authenticator().apply();

        assertThat(validate(authenticator).isFailure()).isTrue();
    }

    @Test
    void wellBehavedProvider_isStillUsed_control() {
        var authenticator = launch(new GoodFactory()).unwrap().authenticator().apply();

        assertThat(validate(authenticator).isSuccess()).isTrue();
        assertThat(capture.messages(Level.ERROR)).isEmpty();
    }

    private static HttpAuthenticator accepting() {
        return (_, _) -> SecurityContext.securityContext("custom-user", java.util.Set.of(), Map.of());
    }

    private static final class ThrowingName implements HttpAuthenticatorFactory {
        @Override public String name() { throw new IllegalStateException(LEAK); }
        @Override public Result<HttpAuthenticator> authenticator(ConfigurationProvider settings) { return Result.success(accepting()); }
    }

    private static final class NullName implements HttpAuthenticatorFactory {
        @Override public String name() { return null; }
        @Override public Result<HttpAuthenticator> authenticator(ConfigurationProvider settings) { return Result.success(accepting()); }
    }

    private static final class ThrowingFactory implements HttpAuthenticatorFactory {
        @Override public String name() { return "bad"; }
        @Override public Result<HttpAuthenticator> authenticator(ConfigurationProvider settings) { throw new IllegalStateException(LEAK); }
    }

    private static final class FailingFactory implements HttpAuthenticatorFactory {
        @Override public String name() { return "bad"; }
        @Override public Result<HttpAuthenticator> authenticator(ConfigurationProvider settings) { return Causes.cause("issuer is missing").result(); }
    }

    private static final class NullResultFactory implements HttpAuthenticatorFactory {
        @Override public String name() { return "bad"; }
        @Override public Result<HttpAuthenticator> authenticator(ConfigurationProvider settings) { return null; }
    }

    private static final class NullAuthenticatorFactory implements HttpAuthenticatorFactory {
        @Override public String name() { return "bad"; }
        @Override public Result<HttpAuthenticator> authenticator(ConfigurationProvider settings) { return Result.success(null); }
    }

    private static final class ThrowingAuthenticatorFactory implements HttpAuthenticatorFactory {
        @Override public String name() { return "bad"; }
        @Override public Result<HttpAuthenticator> authenticator(ConfigurationProvider settings) {
            return Result.success((_, _) -> { throw new IllegalStateException(LEAK); });
        }
    }

    private static final class NullValidateFactory implements HttpAuthenticatorFactory {
        @Override public String name() { return "bad"; }
        @Override public Result<HttpAuthenticator> authenticator(ConfigurationProvider settings) {
            return Result.success((_, _) -> null);
        }
    }

    private static final class GoodFactory implements HttpAuthenticatorFactory {
        @Override public String name() { return "bad"; }
        @Override public Result<HttpAuthenticator> authenticator(ConfigurationProvider settings) { return Result.success(accepting()); }
    }
}

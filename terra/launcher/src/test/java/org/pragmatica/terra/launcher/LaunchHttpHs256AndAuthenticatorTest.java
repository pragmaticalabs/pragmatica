// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra.launcher;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.http.security.HttpAuthenticator;
import org.pragmatica.aether.http.security.SecurityError;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.lang.Result;
import org.pragmatica.terra.http.HttpAuthenticatorFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// #2880: Terra `[jwt] algorithm = "HS256"` and the `custom` authenticator selected by name through ServiceLoader.
class LaunchHttpHs256AndAuthenticatorTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private static ConfigurationProvider settings(Map<String, String> values) {
        return ConfigurationProvider.builder().withDefaults(values).build();
    }

    private static Result<LaunchHttp> hs256(Map<String, String> extra) {
        var values = new java.util.HashMap<>(Map.of("http.security_mode", "jwt", "jwt.algorithm", "HS256"));

        values.putAll(extra);

        return LaunchHttp.launchHttp(Path.of("."), settings(values));
    }

    private static String token(String headerJson, byte[] key) {
        var payload = "{\"sub\":\"svc-9\",\"exp\":" + (System.currentTimeMillis() / 1000 + 3600) + "}";
        var signed = B64.encodeToString(headerJson.getBytes(StandardCharsets.UTF_8)) + "." + B64.encodeToString(payload.getBytes(StandardCharsets.UTF_8));

        return signed + "." + B64.encodeToString(mac(signed, key));
    }

    @SuppressWarnings("JBCT-EX-01")
    private static byte[] mac(String signed, byte[] key) {
        try {
            var mac = Mac.getInstance("HmacSHA256");

            mac.init(new SecretKeySpec(key, "HmacSHA256"));

            return mac.doFinal(signed.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static Result<?> validate(HttpAuthenticator auth, String authorization) {
        var request = HttpRequestContext.httpRequestContext("/", "GET", Map.of(), Map.of("Authorization", List.of(authorization)), "test");

        return auth.validate(request, SecurityPolicy.bearerTokenRequired());
    }

    private static void assertRefused(Result<LaunchHttp> launch, String reason) {
        launch.onSuccess(_ -> fail("launch must be refused"))
              .onFailure(cause -> assertThat(cause.message()).contains(reason).doesNotContain(SECRET));
    }

    @Test
    void hs256_withSecret_verifiesATokenSignedWithIt() {
        var auth = hs256(Map.of("jwt.secret", SECRET)).unwrap().authenticator().apply();

        assertThat(validate(auth, "Bearer " + token("{\"alg\":\"HS256\"}", SECRET.getBytes(StandardCharsets.UTF_8))).isSuccess()).isTrue();
    }

    @Test
    void hs256_refusesATokenSignedWithAnotherSecret_andOneNamingAnotherAlgorithm() {
        var auth = hs256(Map.of("jwt.secret", SECRET)).unwrap().authenticator().apply();

        assertThat(validate(auth, "Bearer " + token("{\"alg\":\"HS256\"}", "another-0123456789abcdef0123456789".getBytes(StandardCharsets.UTF_8))).isFailure()).isTrue();
        assertThat(validate(auth, "Bearer " + token("{\"alg\":\"RS256\"}", SECRET.getBytes(StandardCharsets.UTF_8))).isFailure()).isTrue();
        assertThat(validate(auth, "Bearer " + token("{}", SECRET.getBytes(StandardCharsets.UTF_8))).isFailure()).isTrue();
    }

    @Test
    void hs256_withoutSecret_refusesTheLaunch() {
        assertRefused(hs256(Map.of()), "requires jwt.secret");
    }

    @Test
    void hs256_withShortSecret_refusesTheLaunch_withoutPrintingIt() {
        assertRefused(hs256(Map.of("jwt.secret", "short-secret")), "too short");
        hs256(Map.of("jwt.secret", "short-secret")).onFailure(cause -> assertThat(cause.message()).doesNotContain("short-secret"));
    }

    @Test
    void hs256_withJwksUrl_refusesTheLaunch() {
        assertRefused(hs256(Map.of("jwt.secret", SECRET, "jwt.jwks_url", "https://auth.example.com/jwks.json")), "never reads a JWKS");
    }

    @Test
    void jwksMode_withASecret_refusesTheLaunch() {
        var launch = LaunchHttp.launchHttp(Path.of("."),
                                           settings(Map.of("http.security_mode", "jwt",
                                                           "jwt.jwks_url", "https://auth.example.com/jwks.json",
                                                           "jwt.secret", SECRET)));

        assertRefused(launch, "mode is JWKS");
    }

    @Test
    void hs256_unknownAlgorithm_refusesTheLaunch() {
        assertRefused(hs256(Map.of("jwt.secret", SECRET, "jwt.algorithm", "HS512")), "algorithm must be HS256");
    }

    @Test
    void custom_withOneInstalledProvider_authenticatesEndToEnd() {
        var launch = LaunchHttp.launchHttp(Path.of("."),
                                           settings(Map.of("http.security_mode", "custom",
                                                           "http.authenticator", "test-header",
                                                           "authenticator.test-header.token", "let-me-in"))).unwrap();
        var auth = launch.authenticator().apply();
        var accepted = HttpRequestContext.httpRequestContext("/", "GET", Map.of(), Map.of("X-Test-Token", List.of("let-me-in")), "test");
        var refused = HttpRequestContext.httpRequestContext("/", "GET", Map.of(), Map.of("X-Test-Token", List.of("nope")), "test");

        assertThat(launch.config().defaultPolicy()).isEqualTo(SecurityPolicy.authenticated());
        assertThat(auth.validate(accepted, SecurityPolicy.authenticated()).unwrap().principal().value()).contains("custom-user");
        assertThat(auth.validate(refused, SecurityPolicy.authenticated()).isFailure()).isTrue();
    }

    @Test
    void custom_withNoInstalledProvider_refusesTheLaunch_namingWhatIsInstalled() {
        var launch = LaunchHttp.launchHttp(Path.of("."),
                                           settings(Map.of("http.security_mode", "custom", "http.authenticator", "absent")),
                                           () -> Result.success(List.of()));

        assertRefused(launch, "No authenticator named 'absent' is installed (installed: none)");
    }

    @Test
    void custom_withAnUnknownName_listsTheInstalledNames() {
        var launch = LaunchHttp.launchHttp(Path.of("."),
                                           settings(Map.of("http.security_mode", "custom", "http.authenticator", "absent")));

        assertRefused(launch, "installed: test-header");
    }

    @Test
    void custom_withTwoProvidersOfTheSameName_refusesTheLaunch_namingBoth() {
        var launch = LaunchHttp.launchHttp(Path.of("."),
                                           settings(Map.of("http.security_mode", "custom", "http.authenticator", "dup")),
                                           () -> Result.success(List.of(new First(), new Second())));

        assertRefused(launch, First.class.getName());
        launch.onFailure(cause -> assertThat(cause.message()).contains(Second.class.getName()));
    }

    @Test
    void custom_providersOfOtherNames_doNotCollide() {
        var launch = LaunchHttp.launchHttp(Path.of("."),
                                           settings(Map.of("http.security_mode", "custom", "http.authenticator", "dup")),
                                           () -> Result.success(List.of(new First(), new Other())));

        assertThat(launch.isSuccess()).isTrue();
    }

    @Test
    void custom_withoutAName_refusesTheLaunch() {
        assertRefused(LaunchHttp.launchHttp(Path.of("."), settings(Map.of("http.security_mode", "custom"))), "requires http.authenticator");
    }

    @Test
    void custom_withABlankName_isTreatedAsMissing() {
        assertRefused(LaunchHttp.launchHttp(Path.of("."), settings(Map.of("http.security_mode", "custom", "http.authenticator", " "))), "requires http.authenticator");
    }

    @Test
    void authenticatorName_beside_anotherMode_refusesTheLaunch() {
        assertRefused(LaunchHttp.launchHttp(Path.of("."), settings(Map.of("http.security_mode", "none", "http.authenticator", "test-header"))),
                      "http.authenticator is set but http.security_mode is not custom");
    }

    @Test
    void custom_providerFailure_refusesTheLaunch() {
        assertRefused(LaunchHttp.launchHttp(Path.of("."), settings(Map.of("http.security_mode", "custom", "http.authenticator", "test-header"))),
                      "authenticator.test-header.token is required");
    }

    private static HttpAuthenticator denyAll() {
        return (_, _) -> SecurityError.INVALID_API_KEY.result();
    }

    private static final class First implements HttpAuthenticatorFactory {
        @Override public String name() { return "dup"; }
        @Override public Result<HttpAuthenticator> authenticator(ConfigurationProvider settings) { return Result.success(denyAll()); }
    }

    private static final class Second implements HttpAuthenticatorFactory {
        @Override public String name() { return "dup"; }
        @Override public Result<HttpAuthenticator> authenticator(ConfigurationProvider settings) { return Result.success(denyAll()); }
    }

    private static final class Other implements HttpAuthenticatorFactory {
        @Override public String name() { return "other"; }
        @Override public Result<HttpAuthenticator> authenticator(ConfigurationProvider settings) { return Result.success(denyAll()); }
    }
}

package org.pragmatica.aether.http.security;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.RouteSecurityPolicy;
import org.pragmatica.aether.http.handler.security.SecurityContext;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeySecurityValidatorTest {
    private static final String VALID_KEY = "test-api-key-12345";
    private static final String INVALID_KEY = "invalid-key-67890";
    private static final Set<String> VALID_KEYS = Set.of(VALID_KEY, "another-valid-key");

    @Test
    void publicRoute_allowsAnonymousAccess() {
        var validator = SecurityValidator.apiKeyValidator(VALID_KEYS);
        var request = createRequest(Map.of());

        validator.validate(request, RouteSecurityPolicy.publicRoute())
                 .onFailureRun(Assertions::fail)
                 .onSuccess(context -> {
                     assertThat(context.isAuthenticated()).isFalse();
                     assertThat(context.principal().isAnonymous()).isTrue();
                 });
    }

    @Test
    void apiKeyRequired_succeedsWithValidKey() {
        var validator = SecurityValidator.apiKeyValidator(VALID_KEYS);
        var request = createRequest(Map.of("X-API-Key", List.of(VALID_KEY)));

        validator.validate(request, RouteSecurityPolicy.apiKeyRequired())
                 .onFailureRun(Assertions::fail)
                 .onSuccess(context -> {
                     assertThat(context.isAuthenticated()).isTrue();
                     assertThat(context.principal().isApiKey()).isTrue();
                     assertThat(context.principal().value()).contains(VALID_KEY);
                 });
    }

    @Test
    void apiKeyRequired_failsWithInvalidKey() {
        var validator = SecurityValidator.apiKeyValidator(VALID_KEYS);
        var request = createRequest(Map.of("X-API-Key", List.of(INVALID_KEY)));

        validator.validate(request, RouteSecurityPolicy.apiKeyRequired())
                 .onSuccessRun(Assertions::fail)
                 .onFailure(cause -> {
                     assertThat(cause).isInstanceOf(SecurityError.InvalidCredentials.class);
                     assertThat(cause.message()).contains("Invalid API key");
                 });
    }

    @Test
    void apiKeyRequired_failsWithMissingKey() {
        var validator = SecurityValidator.apiKeyValidator(VALID_KEYS);
        var request = createRequest(Map.of());

        validator.validate(request, RouteSecurityPolicy.apiKeyRequired())
                 .onSuccessRun(Assertions::fail)
                 .onFailure(cause -> {
                     assertThat(cause).isInstanceOf(SecurityError.MissingCredentials.class);
                     assertThat(cause.message()).contains("X-API-Key");
                 });
    }

    @Test
    void apiKeyRequired_caseInsensitiveHeaderLookup() {
        var validator = SecurityValidator.apiKeyValidator(VALID_KEYS);
        var request = createRequest(Map.of("x-api-key", List.of(VALID_KEY)));

        validator.validate(request, RouteSecurityPolicy.apiKeyRequired())
                 .onFailureRun(Assertions::fail)
                 .onSuccess(context -> {
                     assertThat(context.isAuthenticated()).isTrue();
                 });
    }

    @Test
    void noOpValidator_alwaysReturnsAnonymous() {
        var validator = SecurityValidator.noOpValidator();
        var request = createRequest(Map.of());

        validator.validate(request, RouteSecurityPolicy.apiKeyRequired())
                 .onFailureRun(Assertions::fail)
                 .onSuccess(context -> {
                     assertThat(context).isEqualTo(SecurityContext.anonymous());
                 });
    }

    private HttpRequestContext createRequest(Map<String, List<String>> headers) {
        return HttpRequestContext.httpRequestContext("/test",
                                                     "GET",
                                                     Map.of(),
                                                     headers,
                                                     new byte[0],
                                                     "test-request-id");
    }
}

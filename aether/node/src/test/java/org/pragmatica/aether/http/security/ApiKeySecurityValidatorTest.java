package org.pragmatica.aether.http.security;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.ApiKeyEntry;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.Role;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class ApiKeySecurityValidatorTest {
    private static final String VALID_KEY = "test-api-key-12345";
    private static final String INVALID_KEY = "invalid-key-67890";
    private static final Set<String> VALID_KEYS = Set.of(VALID_KEY, "another-valid-key");

    @Test
    void validate_allowsAnonymousAccess_forPublicRoute() {
        var validator = SecurityValidator.apiKeyValidator(VALID_KEYS);
        var request = createRequest(Map.of());

        validator.validate(request, SecurityPolicy.publicRoute())
                 .onFailureRun(() -> fail("Expected success"))
                 .onSuccess(context -> {
                     assertThat(context.isAuthenticated()).isFalse();
                     assertThat(context.principal().isAnonymous()).isTrue();
                 });
    }

    @Test
    void validate_succeeds_forValidApiKey() {
        var validator = SecurityValidator.apiKeyValidator(VALID_KEYS);
        var request = createRequest(Map.of("X-API-Key", List.of(VALID_KEY)));

        validator.validate(request, SecurityPolicy.apiKeyRequired())
                 .onFailureRun(() -> fail("Expected success"))
                 .onSuccess(context -> {
                     assertThat(context.isAuthenticated()).isTrue();
                     assertThat(context.principal().isApiKey()).isTrue();
                     assertThat(context.principal().value()).startsWith("api-key:key-");
                 });
    }

    @Test
    void validate_fails_forInvalidApiKey() {
        var validator = SecurityValidator.apiKeyValidator(VALID_KEYS);
        var request = createRequest(Map.of("X-API-Key", List.of(INVALID_KEY)));

        validator.validate(request, SecurityPolicy.apiKeyRequired())
                 .onSuccessRun(() -> fail("Expected failure"))
                 .onFailure(cause -> {
                     assertThat(cause).isInstanceOf(SecurityError.InvalidCredentials.class);
                     assertThat(cause.message()).contains("Invalid API key");
                 });
    }

    @Test
    void validate_fails_forMissingApiKey() {
        var validator = SecurityValidator.apiKeyValidator(VALID_KEYS);
        var request = createRequest(Map.of());

        validator.validate(request, SecurityPolicy.apiKeyRequired())
                 .onSuccessRun(() -> fail("Expected failure"))
                 .onFailure(cause -> {
                     assertThat(cause).isInstanceOf(SecurityError.MissingCredentials.class);
                     assertThat(cause.message()).contains("X-API-Key");
                 });
    }

    @Test
    void validate_succeeds_forCaseInsensitiveHeader() {
        var validator = SecurityValidator.apiKeyValidator(VALID_KEYS);
        var request = createRequest(Map.of("x-api-key", List.of(VALID_KEY)));

        validator.validate(request, SecurityPolicy.apiKeyRequired())
                 .onFailureRun(() -> fail("Expected success"))
                 .onSuccess(context -> {
                     assertThat(context.isAuthenticated()).isTrue();
                 });
    }

    @Test
    void validate_returnsSystemContext_forNoOpValidator() {
        var validator = SecurityValidator.noOpValidator();
        var request = createRequest(Map.of());

        validator.validate(request, SecurityPolicy.apiKeyRequired())
                 .onFailureRun(() -> fail("Expected success"))
                 .onSuccess(context -> {
                     assertThat(context.isAuthenticated()).isTrue();
                     assertThat(context.principal().isService()).isTrue();
                     assertThat(context.principal().value()).isEqualTo("service:system");
                     assertThat(context.hasRole(Role.ADMIN)).isTrue();
                     assertThat(context.hasRole(Role.SERVICE)).isTrue();
                 });
    }

    @Test
    void validate_succeeds_forNamedKeyEntry() {
        var entries = Map.of(
            VALID_KEY, ApiKeyEntry.apiKeyEntry("my-service", Set.of("admin", "service"))
        );
        var validator = SecurityValidator.apiKeyValidator(entries);
        var request = createRequest(Map.of("X-API-Key", List.of(VALID_KEY)));

        validator.validate(request, SecurityPolicy.apiKeyRequired())
                 .onFailureRun(() -> fail("Expected success"))
                 .onSuccess(context -> {
                     assertThat(context.isAuthenticated()).isTrue();
                     assertThat(context.principal().isApiKey()).isTrue();
                     assertThat(context.principal().value()).isEqualTo("api-key:my-service");
                     assertThat(context.hasRole(Role.ADMIN)).isTrue();
                     assertThat(context.hasRole(Role.SERVICE)).isTrue();
                 });
    }

    @Test
    void validate_sameKeyAlwaysProducesSameResult() {
        var validator = SecurityValidator.apiKeyValidator(VALID_KEYS);
        var request1 = createRequest(Map.of("X-API-Key", List.of(VALID_KEY)));
        var request2 = createRequest(Map.of("X-API-Key", List.of(VALID_KEY)));

        var result1 = validator.validate(request1, SecurityPolicy.apiKeyRequired());
        var result2 = validator.validate(request2, SecurityPolicy.apiKeyRequired());

        result1.onFailureRun(() -> fail("Expected success for request1"))
               .onSuccess(ctx1 ->
                   result2.onFailureRun(() -> fail("Expected success for request2"))
                          .onSuccess(ctx2 -> {
                              assertThat(ctx1.principal().value()).isEqualTo(ctx2.principal().value());
                              assertThat(ctx1.roles()).isEqualTo(ctx2.roles());
                          }));
    }

    @Test
    void validate_differentKeysProduceDifferentSecurityContexts() {
        var key1 = "first-key-value";
        var key2 = "second-key-value";
        var entries = Map.of(
            key1, ApiKeyEntry.apiKeyEntry("first-svc", Set.of("admin")),
            key2, ApiKeyEntry.apiKeyEntry("second-svc", Set.of("service"))
        );
        var validator = SecurityValidator.apiKeyValidator(entries);

        var request1 = createRequest(Map.of("X-API-Key", List.of(key1)));
        var request2 = createRequest(Map.of("X-API-Key", List.of(key2)));

        var result1 = validator.validate(request1, SecurityPolicy.apiKeyRequired());
        var result2 = validator.validate(request2, SecurityPolicy.apiKeyRequired());

        result1.onFailureRun(() -> fail("Expected success for key1"))
               .onSuccess(ctx1 ->
                   result2.onFailureRun(() -> fail("Expected success for key2"))
                          .onSuccess(ctx2 -> {
                              assertThat(ctx1.principal().value()).isNotEqualTo(ctx2.principal().value());
                              assertThat(ctx1.principal().value()).isEqualTo("api-key:first-svc");
                              assertThat(ctx2.principal().value()).isEqualTo("api-key:second-svc");
                          }));
    }

    @Test
    void validate_succeeds_forAuthenticatedPolicy_withValidApiKey() {
        var validator = SecurityValidator.apiKeyValidator(VALID_KEYS);
        var request = createRequest(Map.of("X-API-Key", List.of(VALID_KEY)));

        validator.validate(request, SecurityPolicy.authenticated())
                 .onFailureRun(() -> fail("Expected success"))
                 .onSuccess(context -> {
                     assertThat(context.isAuthenticated()).isTrue();
                     assertThat(context.principal().isApiKey()).isTrue();
                 });
    }

    @Test
    void validate_fails_forAuthenticatedPolicy_withMissingApiKey() {
        var validator = SecurityValidator.apiKeyValidator(VALID_KEYS);
        var request = createRequest(Map.of());

        validator.validate(request, SecurityPolicy.authenticated())
                 .onSuccessRun(() -> fail("Expected failure"))
                 .onFailure(cause -> assertThat(cause).isInstanceOf(SecurityError.MissingCredentials.class));
    }

    @Test
    void validate_succeeds_forRoleRequiredPolicy_withValidApiKey() {
        var validator = SecurityValidator.apiKeyValidator(VALID_KEYS);
        var request = createRequest(Map.of("X-API-Key", List.of(VALID_KEY)));

        validator.validate(request, SecurityPolicy.roleRequired("admin"))
                 .onFailureRun(() -> fail("Expected success"))
                 .onSuccess(context -> {
                     assertThat(context.isAuthenticated()).isTrue();
                     assertThat(context.principal().isApiKey()).isTrue();
                 });
    }

    @Test
    void validate_fails_forRoleRequiredPolicy_withMissingApiKey() {
        var validator = SecurityValidator.apiKeyValidator(VALID_KEYS);
        var request = createRequest(Map.of());

        validator.validate(request, SecurityPolicy.roleRequired("admin"))
                 .onSuccessRun(() -> fail("Expected failure"))
                 .onFailure(cause -> assertThat(cause).isInstanceOf(SecurityError.MissingCredentials.class));
    }

    @Test
    void validate_passesThrough_forBearerTokenPolicy() {
        var validator = SecurityValidator.apiKeyValidator(VALID_KEYS);
        var request = createRequest(Map.of());

        validator.validate(request, SecurityPolicy.bearerTokenRequired())
                 .onFailureRun(() -> fail("Expected success — wrong validator type passes through"))
                 .onSuccess(context -> assertThat(context.isAuthenticated()).isFalse());
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

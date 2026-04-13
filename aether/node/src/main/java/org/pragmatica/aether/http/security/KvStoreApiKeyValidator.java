package org.pragmatica.aether.http.security;

import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.AuthorizationRole;
import org.pragmatica.aether.http.handler.security.Role;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ApiKeyValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// API key validator that combines config-based keys with KV-Store-based keys.
///
/// On each request, hashes the presented key and checks:
/// 1. Config-based keys (static, from startup)
/// 2. KV-Store keys with ACTIVE status or REVOKED within grace period
@SuppressWarnings({"JBCT-SEQ-01", "JBCT-PAT-01"}) class KvStoreApiKeyValidator implements SecurityValidator {
    private static final Logger log = LoggerFactory.getLogger(KvStoreApiKeyValidator.class);

    private static final String API_KEY_HEADER = "X-API-Key";

    private static final String API_KEY_PREFIX = "api-key/";

    private final SecurityValidator configValidator;
    private final Supplier<KVStore<AetherKey, AetherValue>> kvStoreSupplier;

    KvStoreApiKeyValidator(SecurityValidator configValidator,
                           Supplier<KVStore<AetherKey, AetherValue>> kvStoreSupplier) {
        this.configValidator = configValidator;
        this.kvStoreSupplier = kvStoreSupplier;
    }

    @Override public Result<SecurityContext> validate(HttpRequestContext request, SecurityPolicy policy) {
        return switch (policy){
            case SecurityPolicy.Public() -> Result.success(SecurityContext.securityContext());
            case SecurityPolicy.BearerTokenRequired() -> Result.success(SecurityContext.securityContext());
            default -> validateApiKey(request);
        };
    }

    private Result<SecurityContext> validateApiKey(HttpRequestContext request) {
        var configResult = configValidator.validate(request, SecurityPolicy.apiKeyRequired());
        if (configResult.isSuccess()) {return configResult;}
        return extractApiKey(request.headers()).toResult(SecurityError.MISSING_API_KEY).flatMap(this::checkKvStoreKey);
    }

    private Result<SecurityContext> checkKvStoreKey(String apiKey) {
        var candidateHash = hashKey(apiKey);
        var kvStore = kvStoreSupplier.get();
        var snapshot = kvStore.snapshot();
        for (var entry : snapshot.entrySet()) {
            if (!entry.getKey().asString()
                             .startsWith(API_KEY_PREFIX)) {continue;}
            if (! (entry.getValue() instanceof ApiKeyValue keyValue)) {continue;}
            if (!keyValue.isValidForAuth()) {continue;}
            if (MessageDigest.isEqual(candidateHash.getBytes(StandardCharsets.UTF_8),
                                      keyValue.keyHash().getBytes(StandardCharsets.UTF_8))) {return buildAdminContext(keyValue.keyId());}
        }
        return SecurityError.INVALID_API_KEY.result();
    }

    private static Result<SecurityContext> buildAdminContext(String keyId) {
        return SecurityContext.securityContext("api-key:" + keyId, Set.of(Role.ADMIN), AuthorizationRole.ADMIN);
    }

    private Option<String> extractApiKey(Map<String, List<String>> headers) {
        return extractCaseSensitive(headers).orElse(() -> extractCaseInsensitive(headers));
    }

    private static Option<String> extractCaseSensitive(Map<String, List<String>> headers) {
        return Option.option(headers.get(API_KEY_HEADER)).filter(values -> !values.isEmpty())
                            .map(List::getFirst);
    }

    private static Option<String> extractCaseInsensitive(Map<String, List<String>> headers) {
        var value = headers.entrySet().stream()
                                    .filter(e -> API_KEY_HEADER.equalsIgnoreCase(e.getKey()))
                                    .map(Map.Entry::getValue)
                                    .filter(values -> values != null && !values.isEmpty())
                                    .map(List::getFirst)
                                    .findFirst();
        return Option.from(value);
    }

    @SuppressWarnings({"JBCT-UTIL-01", "JBCT-EX-01"}) static String hashKey(String key) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hash = digest.digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }
}

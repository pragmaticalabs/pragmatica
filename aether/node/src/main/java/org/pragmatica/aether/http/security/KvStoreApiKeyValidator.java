// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.AuthorizationRole;
import org.pragmatica.aether.http.handler.security.Role;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ApiKeyKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ApiKeyValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-PAT-01"})
class KvStoreApiKeyValidator implements SecurityValidator {
    private static final Logger log = LoggerFactory.getLogger(KvStoreApiKeyValidator.class);
    private static final String API_KEY_HEADER = "X-API-Key";

    private final SecurityValidator configValidator;
    private final Supplier<KVStore<AetherKey, AetherValue>> kvStoreSupplier;

    KvStoreApiKeyValidator(SecurityValidator configValidator,
                           Supplier<KVStore<AetherKey, AetherValue>> kvStoreSupplier) {
        this.configValidator = configValidator;
        this.kvStoreSupplier = kvStoreSupplier;
    }

    /// Exhaustive over the sealed hierarchy on purpose: no `default` arm, mirroring
    /// [ApiKeySecurityValidator#validate]. The `default -> validateApiKey(request)` this replaces
    /// routed `Unspecified` and `unused()` -- the two states that mean "nobody decided" -- into the
    /// KV credential check, so a caller holding any valid cluster key was SERVED a route whose
    /// policy had never been resolved. `BearerTokenRequired` was worse: it returned SUCCESS with an
    /// anonymous context and no credential inspected at all, which is the same total-bypass shape
    /// #866 review G1 removed from the two sibling validators and left behind here. Adding a policy
    /// state must be a COMPILE error, as it already is there.
    @Override
    public Result<SecurityContext> validate(HttpRequestContext request, SecurityPolicy policy) {
        return switch (policy) {
            case SecurityPolicy.Public() -> Result.success(SecurityContext.securityContext());
            case SecurityPolicy.ApiKeyRequired() -> validateApiKey(request);
            case SecurityPolicy.Authenticated() -> validateApiKey(request);
            case SecurityPolicy.RoleRequired _ -> validateApiKey(request);
            case SecurityPolicy.BearerTokenRequired() -> SecurityError.UNENFORCEABLE_POLICY.result();
            case SecurityPolicy.Unspecified() -> SecurityError.UNRESOLVED_POLICY.result();
            case SecurityPolicy.unused() -> SecurityError.UNRESOLVED_POLICY.result();
        };
    }

    /// A request carrying no `X-API-Key` is REFUSED, unconditionally (#908).
    ///
    /// It used to be refused only when this validator could already see a credential somewhere --
    /// a `hasConfiguredCredentials()` predicate, DELETED by this fix along with its two overrides
    /// and its declaration on [SecurityValidator], true when the node's TOML declared api-keys or
    /// the KV store already held one -- and otherwise handed back
    /// `SecurityContext.securityContext()`, whose `authorizationRole` is `VIEWER`. During the boot
    /// window between node start and the leader registering the bootstrap admin key in KV, a node
    /// running `security_mode = "api-key"` with no keys in its own config has neither source, so
    /// every anonymous management-API GET reaching the port in that window was SERVED. Precisely:
    /// that context reports `isAuthenticated() == false`, but `RoleEnforcer` gates on
    /// `authorizationRole` ALONE and never consults `isAuthenticated()`, so a VIEWER role on an
    /// anonymous principal clears every management read route. The validator was granting BECAUSE
    /// it could not check, which is #888's shape and #573's before it.
    ///
    /// The operator's "serve this plane without credentials" escape hatch is NOT here and is
    /// untouched: it is `security_mode = "none"`, which `ManagementServerImpl#handleRequest` honors
    /// by never consulting a validator at all (`AppHttpServer` has the same gate in its own
    /// `handleRequestInScope`; the two are different methods in different classes). A missing
    /// credential arriving HERE has already passed a node that asked for one.
    private Result<SecurityContext> validateApiKey(HttpRequestContext request) {
        var configResult = configValidator.validate(request, SecurityPolicy.apiKeyRequired());

        if (configResult.isSuccess()) {
            return configResult;
        }

        return extractApiKey(request.headers()).toResult(SecurityError.MISSING_API_KEY)
                            .flatMap(this::checkKvStoreKey);
    }

    private Result<SecurityContext> checkKvStoreKey(String apiKey) {
        var candidateHash = hashKey(apiKey);
        var kvStore = kvStoreSupplier.get();

        if (kvStore == null) {
            return SecurityError.INVALID_API_KEY.result();
        }

        var candidateHashBytes = candidateHash.getBytes(StandardCharsets.UTF_8);
        var match = new AtomicReference<ApiKeyValue>();

        kvStore.forEach(ApiKeyKey.class,
                        ApiKeyValue.class,
                        (_, keyValue) -> {
                            if (match.get() != null) {
                            return;
                        }

                            if (!keyValue.isValidForAuth()) {
                            return;
                        }

                            if (MessageDigest.isEqual(candidateHashBytes,
                                                      keyValue.keyHash().getBytes(StandardCharsets.UTF_8))) {
                            match.set(keyValue);
                        }
                        });
        var matched = match.get();

        return matched == null
               ? SecurityError.INVALID_API_KEY.result()
               : buildContext(matched);
    }

    /// #1024 — the stored `keyId` is passed BARE. The `securityContext(String, Set, AuthorizationRole)`
    /// factory is already typed to an api-key subject: it runs the name through
    /// `Principal.principal(name, PrincipalType.API_KEY)`, which applies the `api-key:` prefix itself.
    /// Prepending it here too produced `api-key:api-key:ak_09e4c3ad` on `GET /api/v1/whoami` — the
    /// prefix came from the FACTORY, not from the keyId, so the doubling was this call site's alone.
    /// [ApiKeySecurityValidator#toSecurityContext] passes its bare `entry.name()` through the same
    /// factory and has always been correct; this now matches it.
    private static Result<SecurityContext> buildContext(ApiKeyValue keyValue) {
        var role = parseAuthorizationRole(keyValue.authorizationRole());

        return SecurityContext.securityContext(keyValue.keyId(), Set.of(Role.ADMIN), role);
    }

    // RET-06: `raw` is a stored ApiKeyValue field (null on legacy keys); the null/blank coalesce to a
    // default is parse-don't-validate of persisted input.
    @SuppressWarnings("JBCT-RET-06")
    private static AuthorizationRole parseAuthorizationRole(String raw) {
        if (raw == null || raw.isBlank()) {
            return AuthorizationRole.VIEWER;
        }

        return switch (raw.toUpperCase()) {
            case "ADMIN" -> AuthorizationRole.ADMIN;
            case "OPERATOR" -> AuthorizationRole.OPERATOR;
            case "VIEWER" -> AuthorizationRole.VIEWER;
            default -> {
                log.warn("Unknown authorization role '{}' on stored API key; defaulting to VIEWER", raw);
                yield AuthorizationRole.VIEWER;
            }
        };
    }

    private Option<String> extractApiKey(Map<String, List<String>> headers) {
        return extractCaseSensitive(headers).orElse(() -> extractCaseInsensitive(headers));
    }

    private static Option<String> extractCaseSensitive(Map<String, List<String>> headers) {
        return Option.option(headers.get(API_KEY_HEADER))
                     .filter(values -> !values.isEmpty())
                     .map(List::getFirst);
    }

    private static Option<String> extractCaseInsensitive(Map<String, List<String>> headers) {
        var value = headers.entrySet()
                           .stream()
                           .filter(e -> API_KEY_HEADER.equalsIgnoreCase(e.getKey()))
                           .map(Map.Entry::getValue)
                           .filter(values -> values != null && !values.isEmpty())
                           .map(List::getFirst)
                           .findFirst();

        return Option.from(value);
    }

    @SuppressWarnings({"JBCT-UTIL-01", "JBCT-EX-01"})
    static String hashKey(String key) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hash = digest.digest(key.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }
}

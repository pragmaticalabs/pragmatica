// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.security;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.AuthorizationRole;
import org.pragmatica.aether.http.handler.security.Role;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ApiKeyKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ApiKeyValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// #1024 — the PRINCIPAL STRING the KV-backed api-key path renders, pinned end-to-end through the
/// real [KvStoreApiKeyValidator] against a real [KVStore].
///
/// Deliberately entered at the validator rather than at [org.pragmatica.aether.http.handler.security.Principal].
/// `StatusRoutesWhoamiTest` already covers `/whoami`'s rendering, but it HAND-BUILDS its `Principal`
/// via `Principal.principal(name, API_KEY)` — so it states what a correctly-prefixed principal looks
/// like and cannot observe a validator that prefixes twice. The doubling (`api-key:api-key:ak_...`,
/// observed live 2026-09-11) lived entirely in `buildContext`, between the stored `keyId` and the
/// factory, which is the span this test covers and that one does not.
///
/// The assertion is exact equality, not `startsWith`: the defect's shape is an EXTRA correct-looking
/// prefix, which every `startsWith("api-key:")` check in the codebase — including
/// [org.pragmatica.aether.http.handler.security.Principal#isApiKey] — accepts.
class KvStoreApiKeyValidatorPrincipalTest {
    private static final String KEY_ID = "ak_09e4c3ad";
    private static final String PLAINTEXT = "aether_bootstrap_plaintext_key";

    private KVStore<AetherKey, AetherValue> kvStore;

    @BeforeEach
    void setUp() {
        kvStore = new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
    }

    @Nested
    class WhenAKvStoredKeyAuthenticates {
        @Test
        void validate_principalCarriesTheApiKeyPrefixExactlyOnce() {
            registerKey(AuthorizationRole.ADMIN);

            validator().validate(request(PLAINTEXT), new SecurityPolicy.RoleRequired(Role.ADMIN.value()))
                       .onFailure(cause -> Assertions.fail("the registered key must authenticate: " + cause.message()))
                       .onSuccess(context -> assertThat(context.principal().value())
                                       .as("the principal an operator reads back from /whoami and an audit trail keys on")
                                       .isEqualTo("api-key:" + KEY_ID));
        }

        /// Names the regression directly, so a reintroduction reports the defect rather than a bare
        /// string mismatch. Redundant with the equality above BY CONSTRUCTION — that is the point:
        /// the two cannot disagree, so this one never becomes a vacuous second assertion.
        @Test
        void validate_principalIsNotDoublePrefixed() {
            registerKey(AuthorizationRole.ADMIN);

            validator().validate(request(PLAINTEXT), new SecurityPolicy.RoleRequired(Role.ADMIN.value()))
                       .onFailure(cause -> Assertions.fail("the registered key must authenticate: " + cause.message()))
                       .onSuccess(context -> assertThat(context.principal().value())
                                       .as("buildContext must not re-apply the prefix the factory already applies")
                                       .doesNotContain("api-key:api-key:"));
        }

        /// The prefix is a property of the credential KIND, not of the key's authorization role, so a
        /// non-ADMIN key renders identically. Guards against a fix applied on only one role branch.
        @Test
        void validate_viewerRoleKey_rendersTheSamePrincipalShape() {
            registerKey(AuthorizationRole.VIEWER);

            validator().validate(request(PLAINTEXT), SecurityPolicy.apiKeyRequired())
                       .onFailure(cause -> Assertions.fail("the registered key must authenticate: " + cause.message()))
                       .onSuccess(context -> assertThat(context.principal().value()).isEqualTo("api-key:" + KEY_ID));
        }
    }

    private void registerKey(AuthorizationRole authorizationRole) {
        var keyValue = ApiKeyValue.apiKeyValue(KEY_ID,
                                               KvStoreApiKeyValidator.hashKey(PLAINTEXT),
                                               0L,
                                               authorizationRole.name());

        kvStore.process(kvStore.createBatch(List.of(putKey(keyValue))));
    }

    @SuppressWarnings("unchecked")
    private static KVCommand<AetherKey> putKey(ApiKeyValue keyValue) {
        return (KVCommand<AetherKey>) (KVCommand<?>) new KVCommand.Put<>(ApiKeyKey.apiKeyKey(KEY_ID), keyValue);
    }

    /// The real management-plane pairing, matching `BootstrapAdminKeyValidatorAcceptanceTest`: the
    /// deny-unless-public config validator delegating to the KV store, as `AetherNode` wires it when
    /// no keys are configured in TOML.
    private KvStoreApiKeyValidator validator() {
        return new KvStoreApiKeyValidator(SecurityValidator.denyUnlessPublicValidator(), () -> kvStore);
    }

    private static HttpRequestContext request(String apiKey) {
        return HttpRequestContext.httpRequestContext("/api/v1/whoami",
                                                     "GET",
                                                     Map.<String, List<String>> of(),
                                                     Map.of("X-API-Key", List.of(apiKey)),
                                                     "req-1024");
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }
}

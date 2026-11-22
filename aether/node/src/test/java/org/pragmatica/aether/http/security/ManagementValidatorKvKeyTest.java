// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SecurityMode;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.AuthorizationRole;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ApiKeyKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ApiKeyValue;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// #3545 N2: the validator `SecurityValidator.managementValidator` builds for an ON config, exactly as production
/// builds it (no test-built KV wrapper, no mock KVStore), accepts a key held only in the cluster KV store. Red if the ON
/// arm drops its `kvStoreAwareValidator` wrap: KV-held and bootstrap keys would be refused (fail closed).
class ManagementValidatorKvKeyTest {
    private static final String KV_KEY_ID = "kv-held-admin";
    private static final String KV_KEY = "kv-held-admin-key-value";

    private KVStore<AetherKey, AetherValue> kvStore;

    @BeforeEach
    void setUp() {
        kvStore = new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
    }

    @Test
    void onArm_acceptsAKeyHeldOnlyInTheKvStore() {
        registerKvKey(KV_KEY_ID, KV_KEY, "ADMIN");

        var management = SecurityValidator.managementValidator(onConfig(), () -> kvStore);

        assertThat(management.enabled()).as("control: an api-key config builds the ON arm").isTrue();
        management.validator()
                  .validate(request(KV_KEY), SecurityPolicy.apiKeyRequired())
                  .onFailure(cause -> fail("a KV-held key must be accepted by the ON arm: " + cause.message()))
                  .onSuccess(authenticated -> assertThat(authenticated.context().authorizationRole())
                      .isEqualTo(AuthorizationRole.ADMIN));
    }

    @Test
    void onArm_refusesAKeyHeldNowhere() {
        registerKvKey(KV_KEY_ID, KV_KEY, "ADMIN");

        var management = SecurityValidator.managementValidator(onConfig(), () -> kvStore);

        management.validator()
                  .validate(request("not-" + KV_KEY), SecurityPolicy.apiKeyRequired())
                  .onSuccess(_ -> fail("control: a key held nowhere must be refused"));
    }

    private static AppHttpConfig onConfig() {
        return AppHttpConfig.appHttpConfig(true,
                                           8080,
                                           Map.of(),
                                           1024,
                                           SecurityMode.API_KEY,
                                           Option.none(),
                                           HttpProtocol.H1)
                            .unwrap();
    }

    private void registerKvKey(String keyId, String rawKey, String role) {
        AetherKey key = ApiKeyKey.apiKeyKey(keyId);
        AetherValue value = ApiKeyValue.apiKeyValue(keyId, sha256Hex(rawKey), 0L, role);

        kvStore.process(kvStore.createBatch(List.of(new Put<>(key, value))));
    }

    private static HttpRequestContext request(String apiKey) {
        return HttpRequestContext.httpRequestContext("/api/v1/nodes/status",
                                                     "GET",
                                                     Map.of(),
                                                     Map.of("X-API-Key", List.of(apiKey)),
                                                     new byte[0],
                                                     "mgmt");
    }

    @SuppressWarnings("JBCT-EX-01")
    private static String sha256Hex(String value) {
        try {
            return HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256")
                                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }
}

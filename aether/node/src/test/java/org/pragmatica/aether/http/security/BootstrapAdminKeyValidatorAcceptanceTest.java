// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.security;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.Role;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.node.BootstrapAdminKeyLeg;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.security.ClusterSecretDerivation;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// #980 — closes the loop the bootstrap defect ran through: the CLI derives a key locally from the
/// cluster secret and presents it as `X-API-Key`; the node must accept it as ADMIN.
///
/// Deliberately end-to-end across the seam rather than a hash comparison. `BootstrapAdminKeyLegTest`
/// already asserts the committed hash equals `hashKey(plaintext)` — but that pins the leg against
/// ITS OWN hashing, so a change of digest or encoding on both sides would keep it green while every
/// real request 401'd. Here the key is derived by [ClusterSecretDerivation] (the CLI's own entry
/// point), registered through the real leg, and validated by the real
/// [KvStoreApiKeyValidator] — the three implementations that must agree, none of them standing in
/// for another.
class BootstrapAdminKeyValidatorAcceptanceTest {
    private static final String SECRET = "acceptance-cluster-secret";

    private KVStore<AetherKey, AetherValue> kvStore;

    @BeforeEach
    void setUp() {
        kvStore = new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
    }

    @Test
    void validator_acceptsTheKeyTheBootstrapCliDerives_asAdmin() {
        registerBootstrapAdminKey();
        var cliDerivedKey = ClusterSecretDerivation.bootstrapAdminKey(SECRET).unwrap();

        validator().validate(request(cliDerivedKey), new SecurityPolicy.RoleRequired(Role.ADMIN.value()))
                   .onFailure(cause -> Assertions.fail("the CLI-derived key must authenticate against the "
                                                       + "cluster-registered hash: " + cause.message()))
                   .onSuccess(context -> assertThat(context.roles()).as("the bootstrap key must carry ADMIN")
                                                .contains(Role.ADMIN));
    }

    /// Separation, at the layer that decides access rather than at the derivation. A key derived from
    /// a different cluster's secret must be refused, not merely be a different string.
    @Test
    void validator_refusesAKeyDerivedFromAnotherClustersSecret() {
        registerBootstrapAdminKey();
        var foreignKey = ClusterSecretDerivation.bootstrapAdminKey(SECRET + "-other").unwrap();

        var result = validator().validate(request(foreignKey), new SecurityPolicy.RoleRequired(Role.ADMIN.value()));

        assertThat(result.isFailure()).as("another cluster's derived key must not authenticate here").isTrue();
    }

    private void registerBootstrapAdminKey() {
        BootstrapAdminKeyLeg.bootstrapAdminKeyLeg(() -> kvStore,
                                                  () -> true,
                                                  this::apply,
                                                  Option.some(SECRET))
                            .get()
                            .await()
                            .onFailure(cause -> Assertions.fail("registration must succeed: " + cause.message()));
    }

    /// The real management-plane pairing: the deny-unless-public config validator delegating to the
    /// KV store, exactly as `AetherNode` wires it when no keys are configured in TOML — which is the
    /// situation every cloud-bootstrapped cluster is in.
    private KvStoreApiKeyValidator validator() {
        return new KvStoreApiKeyValidator(SecurityValidator.denyUnlessPublicValidator(), () -> kvStore);
    }

    private Promise<List<Object>> apply(List<KVCommand<AetherKey>> commands) {
        kvStore.process(kvStore.createBatch(commands));

        return Promise.success(List.of());
    }

    private static HttpRequestContext request(String apiKey) {
        return HttpRequestContext.httpRequestContext("/api/v1/health",
                                                     "GET",
                                                     Map.<String, List<String>> of(),
                                                     Map.of("X-API-Key", List.of(apiKey)),
                                                     "req-980");
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

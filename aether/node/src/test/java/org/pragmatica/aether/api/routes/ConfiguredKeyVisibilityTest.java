// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.List;
import java.util.Map;
import java.util.Set;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.ApiKeyEntry;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ApiKeyKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ApiKeyValue;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Promise;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/// A credential the node ACCEPTS but no operator tool can SEE is the gap this covers.
///
/// Keys declared in a node's config file (or `AETHER_API_KEYS`) authenticate against every route —
/// `KvStoreApiKeyValidator` consults the config validator first — yet `GET /api/v1/cluster/keys`
/// reported only cluster-held keys, so such a credential was invisible to the tooling an operator
/// audits with. These tests pin what the endpoint now returns, and, just as importantly, what
/// revoking one of those records does: it is REFUSED, with a message naming the file as the
/// authority, because reporting success for a revocation that did not happen would leave an
/// operator believing a leaked credential was dead.
class ConfiguredKeyVisibilityTest {
    private static final String CONFIG_KEY_SECRET = "operator-declared-secret-value";
    private static final String CONFIG_KEY_NAME = "ops-preprovisioned";
    private static final String CONFIG_KEY_ID = ApiKeyRoutes.CONFIG_KEY_ID_PREFIX + CONFIG_KEY_NAME;
    private static final String CLUSTER_KEY_ID = "bootstrap-admin";

    private KVStore<AetherKey, AetherValue> kvStore;
    private ManageableNode node;
    private ApiKeyRoutes routes;

    @BeforeEach
    void setUp() {
        kvStore = new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
        node = mock(ManageableNode.class);
        when(node.kvStore()).thenReturn(kvStore);
    }

    /// `ApiKeyRoutes` arms a sweep on the process-wide `SharedScheduler` (#642); without this every
    /// instance this class builds keeps sweeping through a dead mock.
    @AfterEach
    void tearDown() {
        if (routes != null) {
            routes.stop();
        }
    }

    private ApiKeyRoutes routesWith(Map<String, ApiKeyEntry> configuredKeys) {
        routes = ApiKeyRoutes.apiKeyRoutes(() -> node, () -> configuredKeys);

        return routes;
    }

    private static Map<String, ApiKeyEntry> oneConfiguredAdminKey() {
        return Map.of(CONFIG_KEY_SECRET,
                      ApiKeyEntry.apiKeyEntry(CONFIG_KEY_NAME, Set.of("service"), "ADMIN"));
    }

    @Nested
    class Enumeration {
        @Test
        void listKeys_reportsAConfigDeclaredKey_markedWithItsSource() {
            var listed = await(routesWith(oneConfiguredAdminKey()).handleListKeys());

            assertThat(listed).hasSize(1);
            assertThat(listed.getFirst().keyId()).isEqualTo(CONFIG_KEY_ID);
            assertThat(listed.getFirst().source()).isEqualTo(ApiKeyRoutes.SOURCE_CONFIG);
            assertThat(listed.getFirst().status()).isEqualTo("ACTIVE");
            assertThat(listed.getFirst().authorizationRole()).isEqualTo("ADMIN");
        }

        /// Control: cluster-held keys are still listed, and the two sources are distinguishable in
        /// the same payload. A listing that reported everything as `config` would satisfy the test
        /// above and break every client.
        @Test
        void listKeys_reportsBothSources_distinguishably() {
            registerClusterKey(CLUSTER_KEY_ID, "ADMIN");

            var listed = await(routesWith(oneConfiguredAdminKey()).handleListKeys());

            assertThat(listed).hasSize(2);
            assertThat(listed).anyMatch(info -> info.keyId().equals(CLUSTER_KEY_ID)
                                                && info.source().equals(ApiKeyRoutes.SOURCE_CLUSTER));
            assertThat(listed).anyMatch(info -> info.keyId().equals(CONFIG_KEY_ID)
                                                && info.source().equals(ApiKeyRoutes.SOURCE_CONFIG));
        }

        /// Control: the pre-existing behaviour is untouched when nothing is configured — which is
        /// what the published image now boots with.
        @Test
        void listKeys_reportsOnlyClusterKeys_whenNothingIsConfigured() {
            registerClusterKey(CLUSTER_KEY_ID, "ADMIN");

            var listed = await(routesWith(Map.of()).handleListKeys());

            assertThat(listed).hasSize(1);
            assertThat(listed.getFirst().source()).isEqualTo(ApiKeyRoutes.SOURCE_CLUSTER);
        }

        /// The map key in `AppHttpConfig.apiKeys()` IS the secret. Enumerating credentials must not
        /// turn an ADMIN endpoint into a credential-disclosure endpoint.
        @Test
        void listKeys_neverEchoesTheSecret() {
            var listed = await(routesWith(oneConfiguredAdminKey()).handleListKeys());

            assertThat(listed.toString()).doesNotContain(CONFIG_KEY_SECRET);
        }
    }

    @Nested
    class Revocation {
        @Test
        void revoke_refusesAConfigDeclaredKey_namingTheFileAsAuthority() {
            routesWith(oneConfiguredAdminKey()).handleRevokeKey(CONFIG_KEY_ID, revokeRequest())
                                               .await()
                                               .onSuccess(_ -> fail("revoking a config-declared key reported success"))
                                               .onFailure(cause -> {
                                                   assertThat(cause)
                                                       .isInstanceOf(ApiKeyRoutes.ConfigDeclaredKeyError.class);
                                                   assertThat(cause.message()).contains("node configuration");
                                                   assertThat(cause.message()).contains("restart the node");
                                               });
        }

        /// Control: revocation of a cluster-held key still succeeds through the same method, so the
        /// refusal above is specific to config-declared records and not a broken revoke path.
        @Test
        void revoke_stillSucceeds_forAClusterHeldKey() {
            registerClusterKey(CLUSTER_KEY_ID, "ADMIN");
            when(node.<Object> apply(anyList())).thenReturn(Promise.success(List.of()));

            routesWith(oneConfiguredAdminKey()).handleRevokeKey(CLUSTER_KEY_ID, revokeRequest())
                                               .await()
                                               .onFailure(cause -> fail("cluster key revocation was refused: "
                                                                        + cause.message()))
                                               .onSuccess(response -> assertThat(response.toString())
                                                   .contains(CLUSTER_KEY_ID));
        }

        /// Control: an id that matches nothing still reports NOT FOUND. The config refusal must not
        /// have swallowed that path — the two mean different things to an operator.
        @Test
        void revoke_stillReportsNotFound_forAnUnknownKey() {
            routesWith(oneConfiguredAdminKey()).handleRevokeKey("no-such-key", revokeRequest())
                                               .await()
                                               .onSuccess(_ -> fail("unknown key reported success"))
                                               .onFailure(cause -> assertThat(cause)
                                                   .isInstanceOf(ApiKeyRoutes.KeyNotFoundError.class));
        }

        /// A `config:`-prefixed id that no configured key actually backs is NOT a config key. Without
        /// this, the prefix alone would let a caller mask any missing key behind the config refusal.
        @Test
        void revoke_reportsNotFound_forAConfigPrefixedIdThatIsNotDeclared() {
            routesWith(Map.of()).handleRevokeKey(ApiKeyRoutes.CONFIG_KEY_ID_PREFIX + "never-declared",
                                                 revokeRequest())
                                .await()
                                .onSuccess(_ -> fail("undeclared config-prefixed id reported success"))
                                .onFailure(cause -> assertThat(cause)
                                    .isInstanceOf(ApiKeyRoutes.KeyNotFoundError.class));
        }
    }

    private static ApiKeyRoutes.RevokeKeyRequest revokeRequest() {
        return new ApiKeyRoutes.RevokeKeyRequest(true, 0L, "test");
    }

    private static <T> T await(Promise<T> promise) {
        return promise.await()
                      .onFailure(cause -> fail("listing failed: " + cause.message()))
                      .unwrap();
    }

    private void registerClusterKey(String keyId, String role) {
        AetherKey key = ApiKeyKey.apiKeyKey(keyId);
        AetherValue value = ApiKeyValue.apiKeyValue(keyId, "hash-of-" + keyId, 0L, role);

        kvStore.process(kvStore.createBatch(List.of(new Put<>(key, value))));
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

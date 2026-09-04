// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.Test;

import org.pragmatica.aether.api.ManagementApiResponses.ScaleClusterResponse;
import org.pragmatica.aether.api.ManagementApiResponses.ScaleRequest;
import org.pragmatica.aether.config.cluster.ClusterConfigError;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopologyEntry;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;

/// #335: `POST /api/cluster/scale` against a cluster with no stored config (e.g. the freshly
/// bootstrapped cluster left behind by a `docker compose down -v` volume wipe) used to answer HTTP
/// 500 `{"detail":"No cluster configuration stored"}` — the bare, statusless `ConfigNotFoundError`
/// [ClusterConfigRoutes] uses for the read routes, reached through [ClusterConfigRoutes#lookupClusterConfig].
///
/// Bootstrapping a config here — the #290 formation-bootstrap class of fix — would need the cluster
/// name, semver version, distribution strategy, zones, and deployment settings a `ClusterConfigValue`
/// requires. A [ScaleRequest] carries only source/role/count/expectedVersion: there is no honest way
/// to synthesize the rest, so the route now refuses with a typed 409 naming the actual recovery
/// (`aether cluster bootstrap`) instead of guessing or 500ing. The stored-config path is pinned
/// unchanged alongside it.
class ClusterConfigRoutesScaleNoConfigTest {
    @Test
    void handleScale_noStoredConfig_refusesWithTypedConflict_namingBootstrapRecovery() {
        var result = scale(new TestKVStore(), new ScaleRequest("hetzner", "core", 5, 0));

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> {
            assertThat(cause).isInstanceOf(ClusterConfigError.NoConfigToScale.class);
            assertThat(cause).isInstanceOf(HttpStatusAware.class);
            assertThat(((HttpStatusAware) cause).httpStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(cause.message()).contains("aether cluster bootstrap",
                                                 "--source hetzner",
                                                 "--role core",
                                                 "--count 5");
        });
    }

    /// `--source` is optional on the real CLI command (inferred when exactly one source declares the
    /// role) — the recovery command must not suggest a flag the operator never supplied.
    @Test
    void handleScale_noStoredConfig_absentSource_omitsSourceFlagFromRecoveryCommand() {
        var result = scale(new TestKVStore(), new ScaleRequest("", "worker", 2, 0));

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause.message()).doesNotContain("--source")
                                                              .contains("--role worker", "--count 2"));
    }

    /// The guard must not touch the path where a config genuinely is stored — scaling still works
    /// exactly as before.
    @Test
    void handleScale_storedConfig_scalesNormally_unaffectedByTheNoConfigGuard() {
        var store = storeWith(topology(new TopologyEntry("eu", "core", 3)));

        var result = scale(store, new ScaleRequest("eu", "core", 5, 1));

        assertThat(result.isSuccess()).isTrue();
        var response = result.unwrap();
        assertThat(response.previousCount()).isEqualTo(3);
        assertThat(response.newCount()).isEqualTo(5);
    }

    private static Result<ScaleClusterResponse> scale(TestKVStore store, ScaleRequest request) {
        return ClusterConfigRoutes.clusterConfigRoutes(() -> nodeWith(store))
                                  .handleScale(request)
                                  .await();
    }

    private static ClusterConfigValue topology(TopologyEntry... entries) {
        return ClusterConfigValue.clusterConfigValue("toml", "prod", "1.0.0", List.of(entries), 3, 9, "hetzner", 1);
    }

    private static TestKVStore storeWith(ClusterConfigValue committed) {
        var store = new TestKVStore();

        store.seed(ClusterConfigKey.CURRENT, committed);

        return store;
    }

    private static ManageableNode nodeWith(TestKVStore store) {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, args) -> dispatch(store, method, args));
    }

    private static Object dispatch(TestKVStore store, Method method, Object[] args) {
        return switch (method.getName()) {
            case "kvStore" -> store;
            case "apply" -> applyBatch(store, args);
            default -> throw new UnsupportedOperationException("Not implemented in test proxy: " + method.getName());
        };
    }

    @SuppressWarnings("unchecked")
    private static Promise<List<Object>> applyBatch(TestKVStore store, Object[] args) {
        ((List<KVCommand<AetherKey>>) args[0]).forEach(command -> routeCommand(store, command));

        return Promise.success(List.of());
    }

    private static void routeCommand(TestKVStore store, KVCommand<AetherKey> command) {
        if (command instanceof KVCommand.Put<AetherKey, ?> put && put.value() instanceof AetherValue value) {
            store.applyPut(put.key(), value);
        }
    }

    /// Unconditional write, unlike [ClusterConfigRoutesApplyTest]'s successor-fence model — the
    /// RFC-0018 fence itself is already pinned there; this harness only needs a store that round-trips
    /// a `Put`.
    private static final class TestKVStore extends KVStore<AetherKey, AetherValue> {
        private final Map<AetherKey, AetherValue> storage = new HashMap<>();

        private TestKVStore() {
            super(null, null, null);
        }

        void seed(AetherKey key, AetherValue value) {
            storage.put(key, value);
        }

        void applyPut(AetherKey key, AetherValue value) {
            storage.put(key, value);
        }

        @Override
        public Map<AetherKey, AetherValue> snapshot() {
            return new HashMap<>(storage);
        }

        @Override
        public Option<AetherValue> get(AetherKey key) {
            return Option.option(storage.get(key));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <KK, VV> void forEach(Class<KK> keyClass, Class<VV> valueClass, BiConsumer<KK, VV> consumer) {
            storage.forEach((key, value) -> {
                if (keyClass.isInstance(key) && valueClass.isInstance(value)) {
                    consumer.accept((KK) key, (VV) value);
                }
            });
        }
    }
}

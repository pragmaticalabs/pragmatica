// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.Test;

import org.pragmatica.aether.api.ManagementApiResponses.UpgradeRequest;
import org.pragmatica.aether.config.cluster.ClusterConfigError;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;

/// #837: after a volume wipe leaves no stored cluster config, `GET /api/v1/cluster/config`,
/// `GET /api/v1/cluster/status`, and `POST /api/v1/cluster/upgrade` all answered HTTP 500 — the
/// two GETs via the bare, statusless `ConfigNotFoundError` [ClusterConfigRoutes] used for
/// [ClusterConfigRoutes#lookupClusterConfig], and upgrade because [ClusterConfigRoutes#handleUpgrade]
/// routed through the same promise-failing lookup instead of branching on absence the way
/// [ClusterConfigRoutes#handleScale] (#335/#835) does.
///
/// The reads now answer 404 — absence of a resource on a GET, not server failure. Upgrade answers
/// 409 naming the real recovery (`aether cluster bootstrap`), mirroring #835's `NoConfigToScale`:
/// an [UpgradeRequest] carries only a target version, not the cluster name, topology, or deployment
/// settings a config requires, so there is no honest way to synthesize one.
class ClusterConfigRoutesNoConfigTest {
    @Test
    void buildConfigResponse_noStoredConfig_refusesWithTypedNotFound() {
        var result = configResponse(new TestKVStore());

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> {
            assertThat(cause).isInstanceOf(HttpStatusAware.class);
            assertThat(((HttpStatusAware) cause).httpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(cause.message()).contains("No cluster configuration stored");
        });
    }

    @Test
    void buildStatusResponse_noStoredConfig_refusesWithTypedNotFound() {
        var result = statusResponse(new TestKVStore());

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> {
            assertThat(cause).isInstanceOf(HttpStatusAware.class);
            assertThat(((HttpStatusAware) cause).httpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(cause.message()).contains("No cluster configuration stored");
        });
    }

    @Test
    void handleUpgrade_noStoredConfig_refusesWithTypedConflict_namingBootstrapRecovery() {
        var result = upgrade(new TestKVStore(), new UpgradeRequest("1.2.3"));

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> {
            assertThat(cause).isInstanceOf(ClusterConfigError.NoConfigToUpgrade.class);
            assertThat(cause).isInstanceOf(HttpStatusAware.class);
            assertThat(((HttpStatusAware) cause).httpStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(cause.message()).contains("aether cluster bootstrap", "1.2.3");
        });
    }

    private static Result<?> configResponse(TestKVStore store) {
        return ClusterConfigRoutes.clusterConfigRoutes(() -> nodeWith(store))
                                  .buildConfigResponse()
                                  .await();
    }

    private static Result<?> statusResponse(TestKVStore store) {
        return ClusterConfigRoutes.clusterConfigRoutes(() -> nodeWith(store))
                                  .buildStatusResponse()
                                  .await();
    }

    private static Result<?> upgrade(TestKVStore store, UpgradeRequest request) {
        return ClusterConfigRoutes.clusterConfigRoutes(() -> nodeWith(store))
                                  .handleUpgrade(request)
                                  .await();
    }

    private static ManageableNode nodeWith(TestKVStore store) {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, args) -> dispatch(store, method));
    }

    private static Object dispatch(TestKVStore store, Method method) {
        return switch (method.getName()) {
            case "kvStore" -> store;
            default -> throw new UnsupportedOperationException("Not implemented in test proxy: " + method.getName());
        };
    }

    /// Empty store only — these tests exercise the absent-config path, so nothing is ever seeded.
    private static final class TestKVStore extends KVStore<AetherKey, AetherValue> {
        private final Map<AetherKey, AetherValue> storage = new HashMap<>();

        private TestKVStore() {
            super(null, null, null);
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
